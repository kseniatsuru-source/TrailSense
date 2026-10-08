package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.SAXNotRecognizedException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import java.time.Instant

class TrackParserTest {
    @Test fun `tcx recorded point time wins over inconsistent export headers`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase><Activities><Activity>
          <Id>2026-06-02T18:03:20.000Z</Id><Lap StartTime="2026-06-02T18:04:00.000Z"><Track>
          <Trackpoint><Time>2026-06-02T18:05:00.000Z</Time><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position></Trackpoint>
          <Trackpoint><Time>2026-06-02T18:06:00.000Z</Time><Position><LatitudeDegrees>49.2</LatitudeDegrees><LongitudeDegrees>-123.2</LongitudeDegrees></Position></Trackpoint>
          </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""

        val track = TrackParser.parse(xml.byteInputStream(), "20260602Mountain hike.tcx")

        assertEquals(Instant.parse("2026-06-02T18:05:00Z").toEpochMilli(), track.startedAtEpochMs)
    }

    @Test fun `tcx hike date falls back to lap start when activity id is absent`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase><Activities><Activity>
          <Lap StartTime="2026-06-02T18:03:20.000Z"><Track>
          <Trackpoint><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position></Trackpoint>
          <Trackpoint><Position><LatitudeDegrees>49.2</LatitudeDegrees><LongitudeDegrees>-123.2</LongitudeDegrees></Position></Trackpoint>
          </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""

        val track = TrackParser.parse(xml.byteInputStream(), "hike.tcx")

        assertEquals(Instant.parse("2026-06-02T18:03:20Z").toEpochMilli(), track.startedAtEpochMs)
    }

    @Test fun `nested id inside activity is never mistaken for the activity id`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase><Activities><Activity>
          <Creator><Id>2026-01-01T00:00:00Z</Id></Creator>
          <Id>2026-06-02T18:03:20Z</Id><Lap><Track>
          <Trackpoint><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position></Trackpoint>
          <Trackpoint><Position><LatitudeDegrees>49.2</LatitudeDegrees><LongitudeDegrees>-123.2</LongitudeDegrees></Position></Trackpoint>
          </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""

        val track = TrackParser.parse(xml.byteInputStream(), "hike.tcx")

        assertEquals(Instant.parse("2026-06-02T18:03:20Z").toEpochMilli(), track.startedAtEpochMs)
    }

    @Test fun `gpx date always comes from track points even with tcx-like extensions`() {
        val xml = """<?xml version="1.0"?><gpx><metadata><extensions>
          <Activity><Id>2026-01-01T00:00:00Z</Id></Activity>
          <Activity><Id>2026-01-02T00:00:00Z</Id></Activity>
          </extensions></metadata><trk><trkseg>
          <trkpt lat="49.1" lon="-123.1"><time>2026-06-02T18:03:20Z</time></trkpt>
          <trkpt lat="49.2" lon="-123.2"><time>2026-06-02T18:04:20Z</time></trkpt>
          </trkseg></trk></gpx>"""

        val track = TrackParser.parse(xml.byteInputStream(), "hike.gpx")

        assertEquals(Instant.parse("2026-06-02T18:03:20Z").toEpochMilli(), track.startedAtEpochMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tcx containing multiple activities is rejected instead of assigning the wrong date`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase><Activities>
          <Activity><Id>2026-06-01T10:00:00Z</Id><Lap><Track><Trackpoint><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position></Trackpoint></Track></Lap></Activity>
          <Activity><Id>2026-06-02T10:00:00Z</Id><Lap><Track><Trackpoint><Position><LatitudeDegrees>49.2</LatitudeDegrees><LongitudeDegrees>-123.2</LongitudeDegrees></Position></Trackpoint></Track></Lap></Activity>
          </Activities></TrainingCenterDatabase>"""

        TrackParser.parse(xml.byteInputStream(), "many.tcx")
    }

    @Test fun `parses gpx timestamps elevation and heart rate extension`() {
        val xml = """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Test Hill</name><trkseg>
          <trkpt lat="49.1" lon="-123.1"><ele>12</ele><time>2026-08-01T10:00:00Z</time><extensions><hr>100</hr></extensions></trkpt>
          <trkpt lat="49.2" lon="-123.2"><ele>25</ele><time>2026-08-01T10:10:00Z</time><extensions><hr>120</hr></extensions></trkpt>
        </trkseg></trk></gpx>"""
        val track = TrackParser.parse(xml.byteInputStream(), "route.gpx")
        assertEquals(TrackFormat.GPX, track.sourceFormat)
        assertEquals("Test Hill", track.name)
        assertEquals(2, track.points.size)
        assertEquals(100, track.points.first().heartRateBpm)
        assertEquals(Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), track.startedAtEpochMs)
    }

    @Test fun `parses tcx position heart rate and Huawei lap summary`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"><Activities><Activity><Lap>
          <TotalTimeSeconds>32826.0</TotalTimeSeconds><DistanceMeters>15980.0</DistanceMeters><CumulativeClimb>1312.0</CumulativeClimb><CumulativeDecrease>1310.8</CumulativeDecrease><Track>
          <Trackpoint><Time>2026-08-01T10:00:00Z</Time><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position><AltitudeMeters>12</AltitudeMeters><HeartRateBpm><Value>101</Value></HeartRateBpm></Trackpoint>
          <Trackpoint><Time>2026-08-01T10:01:00Z</Time><Position><LatitudeDegrees>49.101</LatitudeDegrees><LongitudeDegrees>-123.101</LongitudeDegrees></Position><AltitudeMeters>20</AltitudeMeters><HeartRateBpm><Value>121</Value></HeartRateBpm></Trackpoint>
        </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""
        val track = TrackParser.parse(xml.byteInputStream(), "activity.tcx")
        assertEquals(TrackFormat.TCX, track.sourceFormat)
        assertEquals(2, track.points.size)
        assertEquals(121, track.points.last().heartRateBpm)
        assertEquals(15_980.0, track.summary?.distanceM)
        assertEquals(1_312.0, track.summary?.elevationGainM)
        assertEquals(1_310.8, track.summary?.elevationLossM)
        assertEquals(32_826L, track.summary?.elapsedSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects xml external entity declarations`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM="file:///etc/passwd">]><gpx><trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>"""
        TrackParser.parse(xml.byteInputStream(), "evil.gpx")
    }

    @Test fun `parses tcx when android does not recognize disallow doctype feature`() {
        val xml = """<?xml version="1.0"?><TrainingCenterDatabase><Activities><Activity><Lap><Track>
          <Trackpoint><Position><LatitudeDegrees>49.1</LatitudeDegrees><LongitudeDegrees>-123.1</LongitudeDegrees></Position></Trackpoint>
          <Trackpoint><Position><LatitudeDegrees>49.2</LatitudeDegrees><LongitudeDegrees>-123.2</LongitudeDegrees></Position></Trackpoint>
        </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""

        val track = TrackParser.parse(xml.byteInputStream(), "huawei.tcx", AndroidLikeSaxParserFactory())

        assertEquals(TrackFormat.TCX, track.sourceFormat)
        assertEquals(2, track.points.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `still rejects doctype when android does not recognize disallow feature`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM="file:///etc/passwd">]><gpx><trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>"""
        TrackParser.parse(xml.byteInputStream(), "evil.gpx", AndroidLikeSaxParserFactory())
    }

    private class AndroidLikeSaxParserFactory : SAXParserFactory() {
        private val delegate = SAXParserFactory.newInstance()

        override fun newSAXParser(): SAXParser {
            delegate.isNamespaceAware = isNamespaceAware
            delegate.isValidating = isValidating
            return delegate.newSAXParser()
        }

        override fun setFeature(name: String, value: Boolean) {
            if (name == "http://apache.org/xml/features/disallow-doctype-decl") {
                throw SAXNotRecognizedException(name)
            }
            delegate.setFeature(name, value)
        }

        override fun getFeature(name: String): Boolean = delegate.getFeature(name)
    }
}
