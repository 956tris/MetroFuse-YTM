package dev.metrofuse.youtube

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrVideoVariant
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class YouTubeVideoTest {
    private val track = Track("abcdefghijk", "Title", "Artist", "Album")

    @Test
    fun returns4kSabrVideoWithVariantsAndAvailableHeights() =
        runBlocking {
            val extractor = VideoFixtureExtractor()
            extractor.stream = extractor.stream?.copy(sabrVideoBootstrap = sabrVideoBootstrap("abcdefghijk"))
            val service = GuestVideo(extractor, VideoSettings())
            val response = service.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) })

            assertEquals(2160, extractor.hints?.maxVideoHeight)
            assertTrue(extractor.hints?.allowSabr == true)
            assertEquals("https://fixture.googlevideo.com/video.webm", response["url"]?.jsonPrimitive?.content)
            assertEquals(3840, response["width"]?.jsonPrimitive?.intOrNull)
            assertEquals(2160, response["height"]?.jsonPrimitive?.intOrNull)
            assertEquals(
                listOf(2160, 1440, 1080),
                response["availableHeights"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull },
            )
            val sabr = Json.parseToJsonElement(assertNotNull(response["sabr"]?.jsonPrimitive?.content)).jsonObject
            assertEquals("abcdefghijk", sabr["videoId"]?.jsonPrimitive?.content)
            assertEquals(313, sabr["selectedVideoFormat"]?.jsonObject?.get("itag")?.jsonPrimitive?.intOrNull)
            assertEquals(2160, sabr["selectedVideoHeight"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, sabr["videoVariants"]?.jsonArray?.size)
        }

    @Test
    fun respectsVideo4kSettingAndRequestedMaxHeight() =
        runBlocking {
            val settings = VideoSettings()
            settings.set("video4k", JsonPrimitive(false))
            val extractor = VideoFixtureExtractor()
            val service = GuestVideo(extractor, settings)

            service.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) })
            assertEquals(1080, extractor.hints?.maxVideoHeight)

            settings.set("video4k", JsonPrimitive(true))
            service.call(
                buildJsonObject {
                    put("track", Json.encodeToJsonElement(track))
                    put("maxHeight", 720)
                },
            )
            assertEquals(720, extractor.hints?.maxVideoHeight)
        }

    @Test
    fun mismatchedSabrIdentityRetriesWithoutSabrAndDisabledSettingReturnsEmpty() =
        runBlocking {
            val settings = VideoSettings()
            val extractor = VideoFixtureExtractor()
            val direct = assertNotNull(extractor.stream)
            extractor.stream = direct.copy(sabrVideoBootstrap = sabrVideoBootstrap("otherVideo1"))
            extractor.fallbackStream = direct
            val service = GuestVideo(extractor, settings)

            val response = service.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) })
            assertEquals(2, extractor.calls)
            assertFalse(assertNotNull(extractor.hints).allowSabr)
            assertNull(response["sabr"])
            assertEquals("https://fixture.googlevideo.com/video.webm", response["url"]?.jsonPrimitive?.content)

            settings.set("videoEnabled", JsonPrimitive(false))
            assertEquals(0, service.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) }).size)
        }

    @Test
    fun resolvesCounterpartVideoIdForAudioTracksWhenCustomResolverProvided() =
        runBlocking {
            val extractor = VideoFixtureExtractor()
            extractor.stream = extractor.stream?.copy(videoId = "omvTarget01")
            val service = GuestVideo(extractor, VideoSettings(), resolveVideoId = { "omvTarget01" })
            val response = service.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) })

            assertEquals("omvTarget01", extractor.lastVideoId)
            assertEquals("https://fixture.googlevideo.com/video.webm", response["url"]?.jsonPrimitive?.content)
        }

    private fun sabrVideoBootstrap(videoId: String) =
        SabrBootstrap(
            videoId = videoId,
            serverAbrStreamingUrl = "https://fixture.googlevideo.com/sabr",
            videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3),
            clientName = 67,
            clientVersion = "fixture",
            audioFormat = SabrFormatId(251, 123L),
            discardVideoFormat = SabrFormatId(160, 456L),
            discardVideoHeight = 144,
            selectedVideoFormat = SabrFormatId(313, 789L),
            selectedVideoWidth = 3840,
            selectedVideoHeight = 2160,
            selectedVideoContentLengthBytes = 987654321L,
            selectedVideoMimeType = "video/webm",
            selectedVideoBitrate = 18_000_000,
            durationMs = 180000L,
            contentLengthBytes = 123456L,
            mimeType = "audio/webm",
            videoVariants =
                listOf(
                    SabrVideoVariant(SabrFormatId(313, 789L), 3840, 2160, 18_000_000, "video/webm", 987654321L),
                    SabrVideoVariant(SabrFormatId(248, 789L), 1920, 1080, 4_000_000, "video/webm", 234567890L),
                ),
        )

    private class VideoSettings : PluginSettings {
        override val values =
            MutableStateFlow<Map<String, JsonElement>>(
                mapOf("videoEnabled" to JsonPrimitive(true), "video4k" to JsonPrimitive(true)),
            )

        override suspend fun set(
            key: String,
            value: JsonElement,
        ) {
            values.value = values.value + (key to value)
        }
    }

    private class VideoFixtureExtractor : StreamExtractor {
        var calls = 0
        var lastVideoId: String? = null
        var hints: ContentHints? = null
        var stream: ExtractedStream? =
            ExtractedStream(
                videoId = "abcdefghijk",
                audioUrl = "https://fixture.googlevideo.com/audio.m4a",
                headers = mapOf("User-Agent" to "fixture"),
                loudnessDb = null,
                expiresAt = Instant.fromEpochMilliseconds(4102444800000L),
                contentLengthBytes = 123456L,
                itag = 140,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = 128000,
                sampleRate = 44100,
                clientName = "WEB_REMIX",
                profileId = "WEB_REMIX",
                requireBoundedRange = true,
                rangeChunkSizeBytes = 65536,
                videoUrl = "https://fixture.googlevideo.com/video.webm",
                videoWidth = 3840,
                videoHeight = 2160,
                videoMimeType = "video/webm",
                videoCodecs = "vp9",
                videoBitrate = 18_000_000,
                videoItag = 313,
                videoContentLengthBytes = 987654321L,
                availableVideoHeights = listOf(2160, 1440, 1080),
            )
        var fallbackStream: ExtractedStream? = null

        override suspend fun prewarm() {}

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            calls++
            this.lastVideoId = videoId
            this.hints = hints
            return if (calls > 1 && !hints.allowSabr) fallbackStream else stream
        }
    }
}
