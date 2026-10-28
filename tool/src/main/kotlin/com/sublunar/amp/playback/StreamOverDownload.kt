package com.sublunar.amp.playback

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerAddress
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.qualityRank

/**
 * Whether a song downloaded as [downloaded] is streamed from [source] instead.
 *
 * True only when all of these hold:
 *  - the link is unmetered;
 *  - the source's address is on the local network ([ServerAddress.isOnLan]).
 *    Unmetered Wi-Fi elsewhere reaches the server at its public address, over
 *    the home upload, which can be too slow to stream an original;
 *  - the Wi-Fi format was chosen ([MusicSource.wifiFormatChosen]), not the RAW
 *    fallback for a format the server can't serve;
 *  - that format ranks above the download's.
 */
internal fun streamsOverDownload(source: MusicSource, unmetered: Boolean, downloaded: StreamFormat): Boolean =
    unmetered &&
        source.wifiFormatChosen &&
        ServerAddress.isOnLan(source.baseUrl) &&
        source.wifiFormat.qualityRank > downloaded.qualityRank
