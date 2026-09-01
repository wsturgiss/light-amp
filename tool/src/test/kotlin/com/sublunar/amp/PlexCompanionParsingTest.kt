package com.sublunar.amp

import com.sublunar.amp.data.plexTimelineFrom
import com.sublunar.amp.data.plexXmlElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Companion bodies are the only XML the tool itself parses, and the parser
 * is a hand-rolled attribute scan — exactly the sort of thing that quietly
 * breaks on an entity or a similar tag name, so it is pinned down here.
 */
class PlexCompanionParsingTest {

    @Test
    fun `clients list parses attributes and entities`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MediaContainer size="2">
              <Server name="Living Room &amp; Den" host="10.0.0.5" machineIdentifier="abc123"
                      product="Plex for Apple TV" protocolCapabilities="timeline,playback,navigation"/>
              <Server name="Controller only" machineIdentifier="def456" product="Plex Web"
                      protocolCapabilities="navigation"/>
            </MediaContainer>
        """.trimIndent()
        val servers = plexXmlElements(xml, "Server")
        assertEquals(2, servers.size)
        assertEquals("Living Room & Den", servers[0]["name"])
        assertEquals("abc123", servers[0]["machineIdentifier"])
        assertEquals("Plex for Apple TV", servers[0]["product"])
        assertEquals("navigation", servers[1]["protocolCapabilities"])
    }

    @Test
    fun `tag match does not bleed into longer names`() {
        val xml = """<Container><Time x="1"/><Timeline type="music" state="playing" time="5000"/></Container>"""
        assertEquals(1, plexXmlElements(xml, "Timeline").size)
        assertEquals(1, plexXmlElements(xml, "Time").size)
    }

    @Test
    fun `timeline picks the music entry`() {
        val body = """
            <MediaContainer commandID="4">
              <Timeline type="video" state="stopped" time="0"/>
              <Timeline type="music" state="playing" time="61500" duration="185000"
                        ratingKey="4711" volume="60"/>
              <Timeline type="photo" state="stopped"/>
            </MediaContainer>
        """.trimIndent()
        val t = plexTimelineFrom(body)!!
        assertEquals("playing", t.state)
        assertEquals(61500L, t.timeMs)
        assertEquals(185000L, t.durationMs)
        assertEquals("4711", t.ratingKey)
        assertEquals(60, t.volume)
    }

    @Test
    fun `timeline without volume or track stays honest`() {
        val body = """<MediaContainer><Timeline type="music" state="paused" time="9000"/></MediaContainer>"""
        val t = plexTimelineFrom(body)!!
        assertEquals("paused", t.state)
        assertNull(t.volume)
        assertNull(t.ratingKey)
        assertEquals(0L, t.durationMs)
    }

    @Test
    fun `no music timeline means null, not a guess`() {
        assertNull(plexTimelineFrom("""<MediaContainer><Timeline type="video" state="playing"/></MediaContainer>"""))
        assertNull(plexTimelineFrom(""))
    }
}
