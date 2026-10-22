package com.mayushi.trailsense.data

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Observe source revisions, not summary scalars: sample-only updates have the same summary. */
class HuaweiHeartRate(private val db: TrailDatabase) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(id: Long): Flow<List<Pair<Long,Int>>> = db.trailDao().observeHuaweiWorkout(id)
        .distinctUntilChanged().transformLatest {
            // Clear the previous generation before suspension; cancellation cannot retain old samples.
            emit(emptyList())
            emit(load(id))
        }.flowOn(Dispatchers.IO)

    companion object {
        fun withOriginalFallback(archived: List<Pair<Long,Int>>, activity: ActivityEntity): List<Pair<Long,Int>> = archived.ifEmpty {
            runCatching { com.mayushi.trailsense.domain.TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded) }
                .getOrDefault(emptyList()).mapNotNull { p ->
                    val time=p.timeEpochMs; val bpm=p.heartRateBpm
                    if(time!=null && bpm!=null) time to bpm else null
                }.sortedBy {it.first}
        }
    }
    suspend fun load(id: Long): List<Pair<Long,Int>> = db.withTransaction {
        val dao=db.trailDao()
        val source=dao.huaweiWorkoutsForActivity(id).singleOrNull()
            ?: return@withTransaction emptyList()
        val result=mutableListOf<Pair<Long,Int>>()
        fun append(j: JSONObject) {
            val samples=j.optJSONArray("heartRateSamples") ?: return
            for(i in 0 until samples.length()) samples.getJSONObject(i).let { s ->
                result+=s.getLong("timeMs") to s.getInt("bpm")
            }
        }
        append(JSONObject(requireNotNull(dao.loadTrack(source.payloadRef))))
        for(chunk in dao.huaweiRawChunks(id)) append(JSONObject(requireNotNull(dao.loadTrack(chunk.payloadRef))))
        result.sortedBy {it.first}
    }
}
