package dev.metrofuse.youtube

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrVideoVariant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

// Only JSON crosses the plugin boundary. InnerTubeX classes belong to this plugin's loader.
internal fun encodeSabrBootstrap(value: SabrBootstrap): String =
    buildJsonObject {
        put("videoId", value.videoId)
        put("serverAbrStreamingUrl", value.serverAbrStreamingUrl)
        put("videoPlaybackUstreamerConfig", Base64.encode(value.videoPlaybackUstreamerConfig))
        put("clientName", value.clientName)
        put("clientVersion", value.clientVersion)
        put("audioFormat", sabrFormat(value.audioFormat))
        put("discardVideoFormat", sabrFormat(value.discardVideoFormat))
        put("discardVideoHeight", value.discardVideoHeight)
        value.selectedVideoFormat?.let { put("selectedVideoFormat", sabrFormat(it)) }
        value.selectedVideoWidth?.takeIf { it > 0 }?.let { put("selectedVideoWidth", it) }
        value.selectedVideoHeight?.takeIf { it > 0 }?.let { put("selectedVideoHeight", it) }
        value.selectedVideoContentLengthBytes?.takeIf { it > 0 }?.let { put("selectedVideoContentLengthBytes", it) }
        value.selectedVideoMimeType?.let { put("selectedVideoMimeType", it) }
        value.selectedVideoBitrate?.takeIf { it >= 0 }?.let { put("selectedVideoBitrate", it) }
        if (value.videoVariants.isNotEmpty()) {
            put(
                "videoVariants",
                buildJsonArray {
                    value.videoVariants.forEach { variant -> add(sabrVariant(variant)) }
                },
            )
        }
        put("durationMs", value.durationMs)
        value.contentLengthBytes?.takeIf { it > 0 }?.let { put("contentLengthBytes", it) }
        put("mimeType", value.mimeType)
        value.audioTrackId?.let { put("audioTrackId", it) }
        put("isDrc", value.isDrc)
        value.poToken?.let { put("poToken", Base64.encode(it)) }
        value.requestUserAgent?.let { put("requestUserAgent", it) }
        value.requestOrigin?.let { put("requestOrigin", it) }
        value.tokenVisitorData?.let { put("tokenVisitorData", it) }
    }.toString()

private fun sabrFormat(value: SabrFormatId) =
    buildJsonObject {
        put("itag", value.itag)
        put("lastModified", value.lastModified)
        value.xtags?.let { put("xtags", it) }
    }

private fun sabrVariant(value: SabrVideoVariant) =
    buildJsonObject {
        put("format", sabrFormat(value.format))
        value.width?.takeIf { it > 0 }?.let { put("width", it) }
        put("height", value.height)
        put("bitrate", value.bitrate)
        put("mimeType", value.mimeType)
        value.contentLengthBytes?.takeIf { it > 0 }?.let { put("contentLengthBytes", it) }
    }
