package com.mayushi.trailsense.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class HuaweiImporter(private val db: TrailDatabase) {
    data class Candidate(val id: Long,val title: String,val startedAt: Long?)
    data class Row(val key: String,val kind: String,val sourceId: String,val hash: String,val title: String,val start: Long,
        val status: String,val candidates: List<Candidate>,val target: Long?,val existingIdentity: Boolean=false,val offset: String="+0000")
    data class Preview(val rows: List<Row>,val databaseToken: String,val fileHash: String) {
        fun count(status: String) = rows.count { it.status==status }
        val conflicts get() = rows.filter { it.status=="CONFLICT" }
    }
    private val dao get()=db.trailDao()
    private suspend fun token(): String = HuaweiPackage.sha(dao.huaweiActivityIndex().joinToString { "${it.id}:${it.updatedAt}:${it.huaweiFactsJson}" } +
        dao.huaweiSourceIdentities().sortedBy { it.kind+it.sourceId }.joinToString { "${it.kind}:${it.sourceId}:${it.sourceHash}:${it.activityId}" })
    private fun fileHash(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest -> file.inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }; digest.digest().joinToString("") { "%02x".format(it) } }
    suspend fun preview(file: File): Preview {
        val before=token(); val sources=dao.huaweiSourceIdentities().associateBy { "${it.kind}:${it.sourceId}" }; val activities=dao.huaweiActivityIndex()
        val rows=mutableListOf<Row>()
        HuaweiPackage.read(file) { item ->
            val key="${item.kind}:${item.id}"
            // Old installations may contain a binding to an already deleted recording.
            // Such a source is reimportable, never an unchanged live identity.
            val old=sources[key]?.takeIf { source -> item.kind!="workouts" || activities.any {it.id==source.activityId} }
            val near=if(item.kind=="workouts") activities.filter { a -> (a.importedStartedAt ?: a.startedAt)?.let { abs(it-item.start)<=120000 } == true } else emptyList()
            val exact=near.filter { a ->
                val m=a.originalMetricsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                val d=m?.optDouble("distanceM",a.distanceM) ?: a.distanceM
                // totalTime is only a matching discriminant, not assigned to elapsed/moving.
                val time=m?.optLong("elapsedSeconds",a.elapsedSeconds ?: -1) ?: a.elapsedSeconds ?: -1
                abs(d-item.json.getDouble("distanceM"))<0.01 && time>=0 && abs(time*1000-item.json.getLong("totalTimeMs"))<=1000
            }
            val occupied=sources.values.filter { it.kind=="workouts" && it.sourceId!=item.id }.mapNotNull { it.activityId }.toSet()
            val status=when {
                old!=null && old.sourceHash==item.hash -> "UNCHANGED"
                old!=null -> "CONFLICT"
                item.kind=="sleeps" -> "NEW_SLEEP"
                exact.size==1 && near.size==1 && exact.single().id !in occupied -> "ENRICH"
                near.isNotEmpty() -> "CONFLICT"
                else -> "NEW"
            }
            val candidates=if(old!=null) activities.filter { it.id==old.activityId } else near.filter { it.id !in occupied }
            rows+=Row(key,item.kind,item.id,item.hash,item.json.optString("title",if(item.kind=="sleeps") "Сон Huawei" else "Хайк Huawei"),item.start,status,
                candidates.map { Candidate(it.id,it.title,it.startedAt) },old?.activityId ?: exact.singleOrNull()?.id,old!=null,item.offset)
        }
        require(before==token()) { "База изменилась. Повтори предпросмотр." }
        val competingKeys=mutableSetOf<String>()
        val changedWorkouts=rows.filter {it.kind=="workouts" && it.status!="UNCHANGED"}
        changedWorkouts.sortedBy {it.start}.zipWithNext().forEach {(a,b) -> if(b.start-a.start<=120000) {competingKeys+=a.key;competingKeys+=b.key} }
        changedWorkouts.filter {it.target!=null}.groupBy {it.target}.values.filter {it.size>1}.flatten().forEach {competingKeys+=it.key}
        val resolvedRows=rows.map {row -> if(row.key in competingKeys && row.status in listOf("NEW","ENRICH")) row.copy(status="CONFLICT") else row }
        return Preview(resolvedRows,before,fileHash(file))
    }
    /** choices: SKIP, NEW, REPLACE (sleep only), or explicit activity ID; no unresolved default. */
    suspend fun import(file: File, preview: Preview, choices: Map<String,String>): Int = db.withTransaction {
        require(fileHash(file)==preview.fileHash && token()==preview.databaseToken) { "Данные изменились. Повтори предпросмотр." }
        preview.conflicts.forEach { row ->
            val decision=choices[row.key] ?: error("Разбери все спорные совпадения")
            val exists=dao.huaweiSourceIdentities().any { it.kind==row.kind && it.sourceId==row.sourceId &&
                (it.kind!="workouts" || it.activityId?.let { id -> dao.storedActivity(id) }!=null) }
            require(decision=="SKIP" || (decision=="NEW" && row.kind=="workouts" && !exists) ||
                (decision=="REPLACE" && row.kind=="sleeps") || row.candidates.any { it.id.toString()==decision }) { "Недопустимое решение конфликта" }
        }
        val rows=preview.rows.associateBy { it.key }; val claimed=mutableSetOf<Long>(); var count=0
        val newTargets=mutableMapOf<String,Long>(); val targets=mutableMapOf<String,Long>()
        fun wanted(id: String): Boolean = rows["workouts:$id"]?.let {it.status!="UNCHANGED" && choices[it.key]!="SKIP"} == true
        for(row in preview.rows.filter {it.kind=="workouts" && wanted(it.sourceId)}) dao.deleteHuaweiRawChunks(HuaweiPackage.sha(row.sourceId)+":%")
        HuaweiTrackSpool(requireNotNull(file.parentFile)).use { spool ->
        HuaweiPackage.read(file, chunk = {kind,j ->
            val id=j.getString("sourceId")
            if(wanted(id)) {
                if(kind=="trackChunks") spool.accept(j) else {
                    val ordinal=j.getInt("ordinal")
                    dao.putHuaweiSource(HuaweiSourceEntity("rawChunks",HuaweiPackage.sha(id)+":"+ordinal,HuaweiPackage.sha(HuaweiPackage.canonical(j)),null,
                        requireNotNull(dao.storeTrack(j.toString())),"","+0000",ordinal.toLong(),0,null,null,false))
                }
            }
        }) { item ->
            val row=requireNotNull(rows["${item.kind}:${item.id}"]); require(row.hash==item.hash)
            val decision=choices[row.key]
            if(row.status!="UNCHANGED" && decision!="SKIP") {
                var target=if(row.status=="ENRICH") row.target else decision?.toLongOrNull()
                if(item.kind=="workouts") {
                    if(target==null) {
                        val title=item.json.optString("title","Хайк Huawei").ifBlank { "Хайк Huawei" }
                        val route=dao.insertRoute(RouteEntity(title=title,sourceLabel="Huawei archive"))
                        target=dao.insertActivity(ActivityEntity(routeId=route,title=title,sourceFileName="Huawei JSON",sourceFormat="HUAWEI_ARCHIVE",trackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(emptyList()),
                            startedAt=item.start,importedStartedAt=item.start,distanceM=item.json.getDouble("distanceM"),elevationGainM=item.json.getDouble("gainDm")/10,elevationLossM=item.json.getDouble("lossDm")/10))
                        newTargets[item.id]=target
                    }
                    require(claimed.add(target)) { "Несколько тренировок выбраны для одной записи. Разбери их отдельно." }
                    targets[item.id]=target
                    val current=requireNotNull(dao.storedActivity(target))
                    val summary=item.summary()
                    // Keep questionnaire, track corrections, original metrics, dates, links and notes field-exact.
                    dao.updateStoredActivity(current.copy(huaweiFactsJson=summary.toString(),updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1)))
                    dao.invalidateAiForActivity(target)
                }
                val sleep=if(item.kind=="sleeps") HuaweiPackage.sleep(item) else null
                // Use the existing immutable chunk store for both raw source and samples, never a giant row.
                val payload=dao.storeTrack(item.json.toString())!!
                dao.putHuaweiSource(HuaweiSourceEntity(item.kind,item.id,item.hash,target,payload,item.device,item.offset,item.start,item.end,sleep?.wakeDay,sleep?.durationMs,sleep?.nap ?: false))
                count++
            }
        }
        // Final geometry links commit only after complete validation; root order is irrelevant.
        for((id,target) in targets) {
            val ref=spool.store(id,dao)
            if(ref!=null) {
                val current=requireNotNull(dao.storedActivity(target))
                val facts=JSONObject(current.huaweiFactsJson).put("archiveTrackRef",ref)
                dao.updateStoredActivity(if(id in newTargets) current.copy(trackEncoded=ref,originalTrackEncoded=ref,huaweiFactsJson=facts.toString()) else current.copy(huaweiFactsJson=facts.toString()))
                if(id in newTargets) current.routeId?.let { routeId -> val route=requireNotNull(dao.storedRoute(routeId));dao.updateStoredRoute(route.copy(trackEncoded=ref)) }
            }
        }
        for((id,target) in targets) dao.bindHuaweiRawChunks(HuaweiPackage.sha(id)+":%",target)
        }
        val sources=dao.huaweiRecordingAndSleepSources()
        val sleeps=sources.filter { it.kind=="sleeps" }.map { HuaweiPackage.Sleep(it.sourceId,it.deviceId,it.sourceOffset,it.startTimeMs,it.endTimeMs,it.wakeEpochDay!!,it.durationMs,it.nap,it.sourceHash) }
        for(source in sources.filter { it.kind=="workouts" && it.activityId!=null }) {
            val a=dao.storedActivity(source.activityId!!) ?: continue
            val facts=JSONObject(a.huaweiFactsJson.ifBlank { "{}" })
            val day=java.time.Instant.ofEpochMilli(source.startTimeMs).atOffset(java.time.ZoneOffset.of(source.sourceOffset)).toLocalDate().toEpochDay()
            val sleep=HuaweiPackage.matchingSleep(source.startTimeMs,day,sleeps)
            val old=facts.optJSONObject("sleep")?.toString()
            if(sleep!=null) facts.put("sleep",JSONObject().put("sourceId",sleep.id).put("durationMs",sleep.durationMs).put("basis","COMPLETE_NAMED_STAGES_EXCLUDING_WAKE").put("wakeEpochDay",sleep.wakeDay).put("sourceHash",sleep.hash)) else facts.remove("sleep")
            if(old!=facts.optJSONObject("sleep")?.toString()) {
                dao.updateStoredActivity(a.copy(huaweiFactsJson=facts.toString(),updatedAt=maxOf(System.currentTimeMillis(),a.updatedAt+1)))
                dao.invalidateAiForActivity(a.id)
            }
        }
        count
    }
}
