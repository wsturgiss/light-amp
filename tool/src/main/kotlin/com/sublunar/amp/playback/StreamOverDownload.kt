package com.sublunar.amp.playback

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerAddress
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.qualityRank

/**
 * Whether a song already downloaded as [downloaded] is streamed from [source]
 * instead — only when that is better *and* cheap in every sense.
 *
 * Unmetered says the bytes are free, not that they will arrive. Hotel or a
 * friend's Wi-Fi is unmetered too, and there the server is at its public
 * address, sending the original out over a home upload: a FLAC that never
 * started while the MP3 sat on the phone. So the server has to be on this
 * network as well — see [ServerAddress.isOnLan].
 *
 * And the better format has to be one that was asked for. The Original that
 * stands in for a choice the server can't serve (see
 * [MusicSource.wifiFormatChosen]) is a substitute, not an upgrade over the
 * format the downloads were chosen in.
 */
internal fun streamsOverDownload(source: MusicSource, unmetered: Boolean, downloaded: StreamFormat): Boolean =
    unmetered &&
        source.wifiFormatChosen &&
        ServerAddress.isOnLan(source.baseUrl) &&
        source.wifiFormat.qualityRank > downloaded.qualityRank
