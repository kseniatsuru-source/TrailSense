package com.mayushi.trailsense.domain

import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object TrackCodec {
    private const val VERSION = 4
    fun encode(points: List<TrackPoint>, forceVersion4: Boolean = false): String {
        val encodedVersion = if(forceVersion4 || points.any {it.cadencePerMinute != null}) VERSION else 3
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(encodedVersion); data.writeInt(points.size)
            points.forEach { p ->
                data.writeDouble(p.latitude); data.writeDouble(p.longitude)
                data.writeBoolean(p.elevationM != null); p.elevationM?.let(data::writeDouble)
                data.writeBoolean(p.timeEpochMs != null); p.timeEpochMs?.let(data::writeLong)
                data.writeBoolean(p.heartRateBpm != null); p.heartRateBpm?.let(data::writeInt)
                data.writeBoolean(p.segmentStart); data.writeBoolean(p.inferred)
                data.writeBoolean(p.watchDistanceM != null); p.watchDistanceM?.let(data::writeDouble)
                if(encodedVersion >= 4) {data.writeBoolean(p.cadencePerMinute != null); p.cadencePerMinute?.let(data::writeInt)}
            }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun decode(value: String): List<TrackPoint> = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { data ->
        val version = data.readInt()
        require(version in 1..VERSION) { "Неподдерживаемая версия трека" }
        val size = data.readInt().also { require(it in 0..2_000_000) }
        List(size) {
            val lat = data.readDouble(); val lon = data.readDouble()
            val ele = if (data.readBoolean()) data.readDouble() else null
            val time = if (data.readBoolean()) data.readLong() else null
            val hr = if (data.readBoolean()) data.readInt() else null
            TrackPoint(lat, lon, ele, time, hr, if (version >= 2) data.readBoolean() else false, if (version >= 2) data.readBoolean() else false, if (version >= 3 && data.readBoolean()) data.readDouble() else null, if(version >= 4 && data.readBoolean()) data.readInt() else null)
        }
    }
}
