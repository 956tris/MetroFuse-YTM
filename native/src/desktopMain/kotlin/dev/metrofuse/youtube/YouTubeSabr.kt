package dev.metrofuse.youtube

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
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
        put("durationMs", value.durationMs)
        value.contentLengthBytes?.let { put("contentLengthBytes", it) }
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
