package com.sublunar.amp.playback

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StreamOverDownloadTest {

    private fun plex(baseUrl: String, wifi: StreamFormat? = null) = MusicSource(
        id = "p",
        kind = SourceKind.PLEX,
        name = "Plex",
        baseUrl = baseUrl,
        streamFormatId = (wifi ?: StreamFormat.DEFAULT).id,
    )

    private val home = "https://192-168-200-124.0123abcd.plex.direct:32400"
    private val away = "https://216-196-143-224.0123abcd.plex.direct:25324"

    @Test
    fun `an Original chosen on the home network passes over an MP3 download`() {
        assertTrue(streamsOverDownload(plex(home, StreamFormat.RAW), unmetered = true, downloaded = StreamFormat.MP3))
    }

    /** 10-27: non-home Wi-Fi, server at its public address; the FLAC stream never started. */
    @Test
    fun `unmetered Wi-Fi away from the server keeps the download`() {
        assertFalse(streamsOverDownload(plex(away, StreamFormat.RAW), unmetered = true, downloaded = StreamFormat.MP3))
    }

    @Test
    fun `the RAW fallback for an unservable Opus is not an upgrade`() {
        val source = plex(home)
        assertTrue(source.wifiFormat == StreamFormat.RAW, "Plex can't serve Opus, so it reads as RAW")
        assertFalse(streamsOverDownload(source, unmetered = true, downloaded = StreamFormat.MP3))
    }

    @Test
    fun `metered keeps the download, whatever was chosen`() {
        assertFalse(streamsOverDownload(plex(home, StreamFormat.RAW), unmetered = false, downloaded = StreamFormat.MP3))
    }

    @Test
    fun `a download as good as the stream keeps the download`() {
        assertFalse(streamsOverDownload(plex(home, StreamFormat.MP3), unmetered = true, downloaded = StreamFormat.MP3))
        assertFalse(streamsOverDownload(plex(home, StreamFormat.RAW), unmetered = true, downloaded = StreamFormat.RAW))
    }
}
