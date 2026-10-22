package com.mayushi.trailsense

import android.content.Context
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.domain.RepeatRouteDynamics
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Legacy manual data retained for non-destructive upgrade; no manual group UI. */
class RepeatRoutePreferences(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("repeat_routes_037",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",false)
        set(value) {prefs.edit().putBoolean("enabled",value).apply()}
    fun excluded():Set<Long> = prefs.getStringSet("excluded_038",emptySet()).orEmpty().mapNotNull{it.toLongOrNull()}.toSet()
    fun exclude(id:Long) {prefs.edit().putStringSet("excluded_038",(excluded()+id).map{it.toString()}.toSet()).apply()}
    fun groups():List<RepeatRouteDynamics.Group> = runCatching {
        val array=JSONArray(prefs.getString("groups","[]") ?: "[]")
        val groups=(0 until array.length()).map {i ->
            val obj=array.getJSONObject(i);val members=obj.getJSONArray("members")
            RepeatRouteDynamics.Group(obj.getString("id"),obj.getString("title"),obj.getString("sourceFormat"),(0 until members.length()).map {n ->
                val m=members.getJSONObject(n)
                RepeatRouteDynamics.Member(m.getLong("activityId"),m.optBoolean("sameAscentConfirmed",false),m.optBoolean("recordingComplete",false),
                    if(m.has("startIndex")) m.getInt("startIndex") else null,if(m.has("endIndex")) m.getInt("endIndex") else null,
                    if(m.has("trackFingerprint")) m.getString("trackFingerprint") else null)
            })
        };validate(groups);groups
    }.getOrDefault(emptyList())
    fun saveGroups(groups:List<RepeatRouteDynamics.Group>) {
        validate(groups)
        val array=JSONArray()
        groups.forEach {g ->
            val members=JSONArray()
            g.members.forEach {m -> members.put(JSONObject().put("activityId",m.activityId).put("sameAscentConfirmed",m.sameAscentConfirmed).put("recordingComplete",m.recordingComplete)
                .put("startIndex",m.startIndex).put("endIndex",m.endIndex).put("trackFingerprint",m.trackFingerprint))}
            array.put(JSONObject().put("id",g.id).put("title",g.title).put("sourceFormat",g.sourceFormat).put("members",members))
        }
        prefs.edit().putString("groups",array.toString()).apply()
    }
    private fun validate(groups:List<RepeatRouteDynamics.Group>) {
        require(groups.map{it.id}.distinct().size==groups.size)
        require(groups.all {g -> g.id.isNotBlank() && g.title.isNotBlank() && g.sourceFormat.isNotBlank() &&
            g.members.all {it.activityId>0 && (!it.recordingComplete || it.sameAscentConfirmed)} &&
            g.members.map{Triple(it.activityId,it.startIndex,it.endIndex)}.distinct().size==g.members.size})
    }
}
internal fun recordingDuration(seconds:Long):String = "%d:%02d:%02d".format(seconds/3600,seconds%3600/60,seconds%60)
internal fun recordingDelta(seconds:Long):String = (if(seconds<0) "−" else "+")+recordingDuration(kotlin.math.abs(seconds))
internal fun recordingDate(activity:ActivityEntity):String {
    val date=RepeatRouteDynamics.date(activity)?.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")) ?: "дата неизвестна"
    val time=RepeatRouteDynamics.timestamp(activity)?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} ?: "время старта неизвестно"
    return "$date · $time"
}
