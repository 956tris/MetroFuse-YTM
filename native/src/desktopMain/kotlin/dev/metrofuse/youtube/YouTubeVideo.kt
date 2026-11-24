package dev.metrofuse.youtube

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `playback.video/v1`: the host calls this when the player's video button is used. The host keeps the audio resolved by
 * [GuestAudio] and shows the direct HTTP or SABR video stream (up to 4K 2160p) returned here.
 */
internal class GuestVideo(
    private val extractor: StreamExtractor,
    private val settings: PluginSettings,
    private val maxHeight: Int = 1080,
    private val maxHeight4k: Int = 2160,
) : JsonService {
    override suspend fun call(request: JsonObject): JsonObject {
        if ((settings.values.value["videoEnabled"] as? JsonPrimitive)?.booleanOrNull == false) return JsonObject(emptyMap())
        val track = request["track"]?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement<Track>(it) }.getOrNull() }
        val id = track?.let { trackVideoId(it) } ?: return JsonObject(emptyMap())
        val cap = if ((settings.values.value["video4k"] as? JsonPrimitive)?.booleanOrNull != false) maxHeight4k else maxHeight
        val requestedMaxHeight = (request["maxHeight"] as? JsonPrimitive)?.intOrNull?.coerceIn(144, maxHeight4k)
        val effectiveMaxHeight = minOf(requestedMaxHeight ?: cap, cap)
        val allowSabr = (request["allowSabr"] as? JsonPrimitive)?.booleanOrNull ?: true
        val allowHls = (request["allowHls"] as? JsonPrimitive)?.booleanOrNull ?: false
        val hints =
            ContentHints()
                .copy(wantVideo = true, maxVideoHeight = effectiveMaxHeight)
                .withStreamCapabilities(allowHls = allowHls, allowSabr = allowSabr, allowBoundedRange = true)
        val stream =
            try {
                val selected = extractor.extract(id, hints)
                if (selected?.sabrVideoBootstrap?.videoId?.let { it != id } == true) {
                    val fallbackHints = hints.withStreamCapabilities(allowHls = allowHls, allowSabr = false, allowBoundedRange = true)
                    val fallback = extractor.extract(id, fallbackHints)
                    if (fallback == null || fallback.sabrVideoBootstrap != null || fallback.videoId != id) return JsonObject(emptyMap())
                    fallback
                } else {
                    selected
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StreamResolveException) {
                null
            } ?: return JsonObject(emptyMap())
        if (stream.videoId != id) return JsonObject(emptyMap())
        val sabrVideo = stream.sabrVideoBootstrap?.takeIf { allowSabr && it.selectedVideoFormat != null && it.videoId == id }
        val directUrl = stream.videoUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        if (sabrVideo == null && directUrl == null) return JsonObject(emptyMap())
        val heights =
            stream.availableVideoHeights
                .ifEmpty { sabrVideo?.videoVariants?.map { it.height }.orEmpty() }
                .filter { it in 144..maxHeight4k }
                .distinct()
                .sortedDescending()
        return buildJsonObject {
            directUrl?.let { put("url", it) }
            sabrVideo?.let { put("sabr", encodeSabrBootstrap(it)) }
            if (stream.headers.isNotEmpty()) put("headers", JsonObject(stream.headers.mapValues { JsonPrimitive(it.value) }))
            (stream.videoMimeType ?: sabrVideo?.selectedVideoMimeType)?.let { put("mimeType", it) }
            stream.videoCodecs?.let { put("codecs", it) }
            (stream.videoWidth ?: sabrVideo?.selectedVideoWidth)?.let { put("width", it) }
            (stream.videoHeight ?: sabrVideo?.selectedVideoHeight)?.let { put("height", it) }
            (stream.videoBitrate ?: sabrVideo?.selectedVideoBitrate)?.let { put("bitrate", it) }
            (stream.videoItag ?: sabrVideo?.selectedVideoFormat?.itag)?.let { put("itag", it) }
            (stream.videoContentLengthBytes ?: sabrVideo?.selectedVideoContentLengthBytes)?.takeIf { it > 0 }?.let {
                put("contentLengthBytes", it)
            }
            stream.expiresAt?.let { put("expiresAtMillis", it.toEpochMilliseconds()) }
            if (heights.isNotEmpty()) {
                put(
                    "availableHeights",
                    buildJsonArray {
                        heights.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        }
    }
}

internal fun trackVideoId(track: Track): String? =
    (track.extras["youtube"] ?: track.id).takeIf(VIDEO_ID::matches)
