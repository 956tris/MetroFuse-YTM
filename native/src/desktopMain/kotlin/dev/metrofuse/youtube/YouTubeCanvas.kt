package dev.metrofuse.youtube

// YouTube Music Video Canvas counterpart and matching logic adapted from Metrolist
// (https://github.com/MetrolistGroup/Metrolist), GPL-3.0:
//   shared/src/commonMain/kotlin/com/metrolist/shared/api/musicvideo/MusicVideoCounterpart.kt
//   shared/src/commonMain/kotlin/com/metrolist/shared/api/musicvideo/MusicVideoMatcher.kt
//   shared/src/commonMain/kotlin/com/metrolist/shared/api/artwork/AnimatedCoverSelection.kt
// Copyright (C) Metrolist contributors. Modified for the MetroFuse plugin API.

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import dev.metrofuse.api.Track
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class YouTubeCanvasVideo(
    val url: String? = null,
    val sabr: String? = null,
    val videoId: String,
    val mimeType: String? = null,
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val itag: Int? = null,
    val contentLengthBytes: Long? = null,
    val durationSeconds: Long? = null,
    val expiresAtMillis: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal class YouTubeCanvas(
    private val tube: InnerTube,
    private val client: YouTubeGuestClient,
    private val extractor: StreamExtractor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<List<Any?>, Pair<String?, Long>>(32, 0.75f, true)

    /** Resolves a YouTube Music Video counterpart ID for [track], or returns [track]'s ID if already a music video. */
    suspend fun resolveMusicVideoId(track: Track): String? {
        val sourceId = trackVideoId(track)
        if (sourceId != null && isVideoMusicType(track.extras["musicVideoType"])) {
            return sourceId
        }
        val key = listOf(sourceId, track.title, track.artist, track.duration)
        return mutex.withLock {
            cache[key]?.takeIf { it.second > now() }?.let { return@withLock it.first }
            val result =
                try {
                    withTimeoutOrNull(15_000) {
                        lookupCounterpart(sourceId) ?: searchMusicVideo(track, excludeId = sourceId)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            cache[key] = result to (now() + if (result == null) 60_000L else 300_000L)
            while (cache.size > 32) cache.remove(cache.keys.first())
            result
        }
    }

    /** Returns a direct HTTPS or SABR YouTube Music video stream suitable for looping Canvas clips. */
    suspend fun resolve(
        track: Track,
        maxHeight: Int = 720,
        allowSabr: Boolean = true,
    ): YouTubeCanvasVideo? {
        val videoId = resolveMusicVideoId(track) ?: return null
        val hints =
            ContentHints()
                .copy(wantVideo = true, maxVideoHeight = maxHeight.coerceIn(144, 1080))
                .withStreamCapabilities(allowHls = false, allowSabr = allowSabr, allowBoundedRange = true)
        val stream =
            try {
                val selected = extractor.extract(videoId, hints)
                if (selected?.sabrVideoBootstrap?.videoId?.let { it != videoId } == true) {
                    val fallbackHints = hints.withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
                    val fallback = extractor.extract(videoId, fallbackHints)
                    if (fallback == null || fallback.sabrVideoBootstrap != null || fallback.videoId != videoId) null else fallback
                } else {
                    selected
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: StreamResolveException) {
                null
            } ?: return null
        if (stream.videoId != videoId) return null
        val sabrVideo = stream.sabrVideoBootstrap?.takeIf { allowSabr && it.selectedVideoFormat != null && it.videoId == videoId }
        val url = stream.videoUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        if (sabrVideo == null && url == null) return null
        return YouTubeCanvasVideo(
            url = url,
            sabr = sabrVideo?.let(::encodeSabrBootstrap),
            videoId = videoId,
            mimeType = stream.videoMimeType ?: sabrVideo?.selectedVideoMimeType,
            codecs = stream.videoCodecs,
            width = stream.videoWidth ?: sabrVideo?.selectedVideoWidth,
            height = stream.videoHeight ?: sabrVideo?.selectedVideoHeight,
            bitrate = stream.videoBitrate ?: sabrVideo?.selectedVideoBitrate,
            itag = stream.videoItag ?: sabrVideo?.selectedVideoFormat?.itag,
            contentLengthBytes =
                (stream.videoContentLengthBytes ?: sabrVideo?.selectedVideoContentLengthBytes)?.takeIf { it > 0 },
            durationSeconds =
                stream.mediaMetadata?.durationSeconds
                    ?: sabrVideo?.durationMs?.takeIf { it > 0L }?.div(1000L)
                    ?: track.duration.takeIf { it > 0 }?.div(1000L),
            expiresAtMillis = stream.expiresAt?.toEpochMilliseconds(),
            headers = stream.headers,
        )
    }

    fun clear() {
        cache.clear()
    }

    private suspend fun lookupCounterpart(sourceId: String?): String? {
        sourceId ?: return null
        suspend fun fetchCounterpart(playlistId: String?): String? {
            val response = tube.next(client.get(), videoId = sourceId, playlistId = playlistId)
            if (!response.status.isSuccess()) return null
            val root = response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)
            return parseCounterpartVideoId(root, sourceId)
        }
        return fetchCounterpart("RDAMVM$sourceId") ?: fetchCounterpart(null)
    }

    private suspend fun searchMusicVideo(
        track: Track,
        excludeId: String?,
    ): String? {
        if (track.title.isBlank()) return null
        val query = listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" ")
        val response =
            tube.search(
                client = client.get(),
                query = query,
                params = YTM_VIDEOS_SEARCH_PARAMS,
                setLogin = tube.hasSapCookieAuth(),
            )
        if (!response.status.isSuccess()) return null
        val root = response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)
        val candidates = YouTubeCatalog.search(root, limit = 15).tracks
        return selectBestCandidate(track, candidates, excludeId)
    }

    companion object {
        internal const val YTM_VIDEOS_SEARCH_PARAMS = "EgWKAQIQAWoKEAMQBBAJEAoQBQ%3D%3D"

        internal fun parseCounterpartVideoId(
            response: JsonObject,
            sourceVideoId: String,
        ): String? {
            for (wrapper in findObjects(response, "playlistPanelVideoWrapperRenderer")) {
                val primaryId = rendererVideoId(wrapper, "primaryRenderer") ?: continue
                val counterparts = wrapper["counterpart"] as? JsonArray ?: continue
                for (entry in counterparts) {
                    val counterpart = entry as? JsonObject ?: continue
                    val counterpartId = rendererVideoId(counterpart, "counterpartRenderer") ?: continue
                    val forward = sourceVideoId == primaryId
                    if (!forward && sourceVideoId != counterpartId) continue
                    val targetId = if (forward) counterpartId else primaryId
                    if (targetId == sourceVideoId || !VIDEO_ID.matches(targetId)) continue
                    val targetType = if (forward) findStringValue(counterpart, "musicVideoType") else null
                    if (targetType == null || isVideoMusicType(targetType)) {
                        return targetId
                    }
                }
            }
            return null
        }

        internal fun isVideoMusicType(raw: String?): Boolean {
            val upper = raw?.uppercase(Locale.ROOT)?.trim().orEmpty()
            if (upper.isBlank()) return false
            val normalized = upper.removePrefix("MUSIC_VIDEO_TYPE_")
            if ("ATV" in normalized || "PODCAST" in normalized || "PRIVATELY_OWNED" in normalized) return false
            return "OMV" in normalized ||
                "OFFICIAL_SOURCE" in normalized ||
                "UGC" in normalized ||
                "USER_GENERATED" in normalized ||
                "LIVE" in normalized ||
                normalized == "VIDEO" ||
                "VIDEO" in normalized
        }

        internal fun selectBestCandidate(
            track: Track,
            candidates: List<Track>,
            excludeId: String? = null,
        ): String? =
            candidates
                .asSequence()
                .mapNotNull { candidate ->
                    val id = trackVideoId(candidate) ?: return@mapNotNull null
                    if (id == excludeId) return@mapNotNull null
                    val type = candidate.extras["musicVideoType"]
                    if (type != null && !isVideoMusicType(type)) return@mapNotNull null
                    val score = scoreCandidate(track, candidate) ?: return@mapNotNull null
                    id to score
                }.maxByOrNull { it.second }
                ?.first

        private fun scoreCandidate(
            target: Track,
            candidate: Track,
        ): Double? {
            val titleSim = tokenSimilarity(target.title, candidate.title)
            if (titleSim < 0.6) return null
            if (target.artist.isNotBlank() && candidate.artist.isNotBlank()) {
                val targetArtists = tokens(target.artist)
                val candidateArtists = tokens(candidate.artist)
                if (targetArtists.isNotEmpty() && candidateArtists.isNotEmpty() && targetArtists.intersect(candidateArtists).isEmpty()) {
                    return null
                }
            }
            if (target.duration > 0L && candidate.duration > 0L && abs(target.duration - candidate.duration) > 90_000L) {
                return null
            }
            val officialBonus =
                if (candidate.extras["musicVideoType"]?.uppercase(Locale.ROOT)?.let { "OMV" in it || "OFFICIAL_SOURCE" in it } == true) {
                    0.5
                } else {
                    0.0
                }
            return titleSim + officialBonus
        }

        private fun tokens(value: String?): Set<String> =
            Normalizer
                .normalize(value.orEmpty(), Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() && it !in NOISE_WORDS }
                .toSet()

        private fun tokenSimilarity(
            left: String?,
            right: String?,
        ): Double {
            val a = tokens(left)
            val b = tokens(right)
            return if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / maxOf(a.size, b.size)
        }

        private val NOISE_WORDS =
            setOf("feat", "ft", "featuring", "official", "video", "music", "audio", "lyric", "lyrics", "hd", "4k")

        private fun rendererVideoId(
            container: JsonObject,
            key: String,
        ): String? =
            ((container[key] as? JsonObject)?.get("playlistPanelVideoRenderer") as? JsonObject)
                ?.get("videoId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf(VIDEO_ID::matches)

        private fun findObjects(
            element: JsonElement,
            key: String,
        ): Sequence<JsonObject> =
            sequence {
                when (element) {
                    is JsonObject -> {
                        (element[key] as? JsonObject)?.let { yield(it) }
                        element.values.forEach { yieldAll(findObjects(it, key)) }
                    }

                    is JsonArray -> {
                        element.forEach { yieldAll(findObjects(it, key)) }
                    }

                    else -> {
                        return@sequence
                    }
                }
            }

        private fun findStringValue(
            element: JsonElement,
            key: String,
        ): String? =
            when (element) {
                is JsonObject -> {
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: element.values.firstNotNullOfOrNull { findStringValue(it, key) }
                }

                is JsonArray -> {
                    element.firstNotNullOfOrNull { findStringValue(it, key) }
                }

                else -> {
                    null
                }
            }
    }
}
