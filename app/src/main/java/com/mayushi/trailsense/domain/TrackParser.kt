package com.mayushi.trailsense.domain

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

object TrackParser {
    private const val PROLOG_SCAN_LIMIT = 64 * 1024
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private val SECURITY_FEATURES = listOf(
        DISALLOW_DOCTYPE to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    fun parse(input: InputStream, fileName: String? = null): ParsedTrack =
        parse(input, fileName, SAXParserFactory.newInstance())

    internal fun parse(input: InputStream, fileName: String?, factory: SAXParserFactory): ParsedTrack {
        val handler = Handler()
        try {
            val safeInput = rejectUnsafeProlog(input)
            factory.isNamespaceAware = true
            SECURITY_FEATURES.forEach { (name, value) -> setFeatureIfSupported(factory, name, value) }
            factory.newSAXParser().xmlReader.apply {
                SECURITY_FEATURES.forEach { (name, value) -> setFeatureIfSupported(this, name, value) }
                entityResolver = EntityResolver { _, _ ->
                    throw SAXException("DTD и внешние XML-сущности не поддерживаются")
                }
                contentHandler = handler
                errorHandler = handler
                parse(InputSource(safeInput))
            }
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Не удалось прочитать файл ${fileName ?: "тренировки"}. Проверь, что это корректный GPX или TCX.",
                error,
            )
        }
        require(handler.format != TrackFormat.TCX || handler.activityCount <= 1) {
            "Файл $fileName содержит несколько тренировок. Экспортируй один хайк отдельным TCX."
        }
        require(handler.points.size >= 2) { "В файле $fileName не найден пригодный GPS-трек" }
        return ParsedTrack(
            name = handler.name,
            points = handler.points,
            sourceFormat = handler.format ?: inferFormat(fileName),
            summary = handler.summary(),
            startedAtEpochMs = handler.startedAtEpochMs(),
        )
    }

    private fun setFeatureIfSupported(factory: SAXParserFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: SAXNotRecognizedException) {
            // Android's bundled SAX parser does not expose every Apache/Xerces feature.
        } catch (_: SAXNotSupportedException) {
            // The stream guard and EntityResolver below still block DTD/external entities.
        }
    }

    private fun setFeatureIfSupported(reader: XMLReader, name: String, value: Boolean) {
        try {
            reader.setFeature(name, value)
        } catch (_: SAXNotRecognizedException) {
            // See the factory overload above.
        } catch (_: SAXNotSupportedException) {
            // See the factory overload above.
        }
    }

    private fun rejectUnsafeProlog(input: InputStream): InputStream {
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
        buffered.mark(PROLOG_SCAN_LIMIT + 1)
        val prefix = ByteArray(PROLOG_SCAN_LIMIT)
        var count = 0
        while (count < prefix.size) {
            val read = buffered.read(prefix, count, prefix.size - count)
            if (read <= 0) break
            count += read
        }
        buffered.reset()
        val normalized = String(prefix, 0, count, Charsets.UTF_8)
            .replace("\u0000", "")
            .uppercase(Locale.ROOT)
        require("<!DOCTYPE" !in normalized && "<!ENTITY" !in normalized) {
            "DTD и XML-сущности не поддерживаются"
        }
        return buffered
    }

    private fun inferFormat(fileName: String?): TrackFormat =
        if (fileName?.lowercase()?.endsWith(".tcx") == true) TrackFormat.TCX else TrackFormat.GPX

    private class Handler : DefaultHandler() {
        val points = mutableListOf<TrackPoint>()
        var format: TrackFormat? = null
        var name: String? = null
        var activityCount: Int = 0
            private set
        private var text = StringBuilder()
        private var inPoint = false
        private var currentLat: Double? = null
        private var currentLon: Double? = null
        private var currentEle: Double? = null
        private var currentTime: Long? = null
        private var currentHr: Int? = null
        private var currentDistance: Double? = null
        private var inTrackName = false
        private var summaryDistanceM: Double? = null
        private var summaryElevationGainM: Double? = null
        private var summaryElevationLossM: Double? = null
        private var summaryElapsedSeconds: Long? = null
        private val elementStack = ArrayDeque<String>()
        private var activityStartedAt: Long? = null
        private var lapStartedAt: Long? = null
        private var firstPointTime: Long? = null
        private var nextSegmentStart = false

        fun summary(): TrackSummary? {
            if (summaryDistanceM == null && summaryElevationGainM == null && summaryElevationLossM == null && summaryElapsedSeconds == null) return null
            return TrackSummary(
                distanceM = summaryDistanceM,
                elevationGainM = summaryElevationGainM,
                elevationLossM = summaryElevationLossM,
                elapsedSeconds = summaryElapsedSeconds,
            )
        }

        // Huawei export headers are not authoritative when a recorded point time exists.
        // Only undated TCX recordings fall back to Lap/Id; never use the filename/import time.
        fun startedAtEpochMs(): Long? = when (format) {
            TrackFormat.TCX -> firstPointTime ?: lapStartedAt ?: activityStartedAt
            else -> firstPointTime
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val tag = (localName?.takeIf { it.isNotBlank() } ?: qName ?: "").substringAfter(':').lowercase()
            val parentTag = elementStack.lastOrNull()
            elementStack.addLast(tag)
            text = StringBuilder()
            when (tag) {
                "gpx" -> format = TrackFormat.GPX
                "trainingcenterdatabase" -> format = TrackFormat.TCX
                "activity" -> activityCount++
                "trkseg", "track", "rte" -> nextSegmentStart = points.isNotEmpty()
                "lap" -> if (parentTag == "activity" && lapStartedAt == null) {
                    lapStartedAt = attributes.getValue("StartTime")?.let(::parseTime)
                }
                "trkpt", "rtept" -> {
                    inPoint = true
                    currentLat = attributes.getValue("lat")?.toDoubleOrNull()
                    currentLon = attributes.getValue("lon")?.toDoubleOrNull()
                }
                "trackpoint" -> { inPoint = true; currentLat = null; currentLon = null }
                "name" -> if (name == null && !inPoint) inTrackName = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = (localName?.takeIf { it.isNotBlank() } ?: qName ?: "").substringAfter(':').lowercase()
            val parentTag = elementStack.elementAtOrNull(elementStack.size - 2)
            val value = text.toString().trim()
            if (!inPoint) when (tag) {
                "id" -> if (parentTag == "activity" && activityStartedAt == null) activityStartedAt = parseTime(value)
                "totaltimeseconds" -> if (parentTag == "lap") {
                    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()?.let {
                        summaryElapsedSeconds = (summaryElapsedSeconds ?: 0L) + it
                    }
                }
                "distancemeters" -> if (parentTag == "lap") {
                    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
                        summaryDistanceM = (summaryDistanceM ?: 0.0) + it
                    }
                }
                "cumulativeclimb" -> summaryElevationGainM = value.toDoubleOrNull()
                "cumulativedecrease" -> summaryElevationLossM = value.toDoubleOrNull()
            }
            if (inPoint) when (tag) {
                "latitudedegrees" -> currentLat = value.toDoubleOrNull()
                "distancemeters" -> currentDistance = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                "longitudedegrees" -> currentLon = value.toDoubleOrNull()
                "ele", "altitudemeters" -> currentEle = value.toDoubleOrNull()
                "time" -> {
                    currentTime = parseTime(value)
                    // Date is accepted only when the GPS point below is accepted.
                }
                "value", "hr" -> if (currentHr == null) currentHr = value.toIntOrNull()
            }
            if (tag == "name" && inTrackName && value.isNotBlank()) { name = value; inTrackName = false }
            if (tag in setOf("trkpt", "rtept", "trackpoint")) {
                val lat = currentLat; val lon = currentLon
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    if (firstPointTime == null) firstPointTime = currentTime
                    points += TrackPoint(lat, lon, currentEle, currentTime, currentHr, segmentStart = nextSegmentStart, watchDistanceM = currentDistance)
                    nextSegmentStart = false
                }
                inPoint = false; currentLat = null; currentLon = null; currentEle = null; currentTime = null; currentHr = null
                currentDistance = null
            }
            if (elementStack.lastOrNull() == tag) elementStack.removeLast()
            text = StringBuilder()
        }

        private fun parseTime(value: String): Long? = try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { null }
    }
}
