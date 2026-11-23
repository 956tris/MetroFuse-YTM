package dev.metrofuse.youtube

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class YouTubeAudioTest {
    private val query = AudioQuery(TrackIdentity("abcdefghijk"), "desktop")

    @Test
    fun streamMappingPreservesTransportExpiryHeadersAndSelectionIntent() =
        runBlocking {
            val settings = Settings()
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, settings)
            val stream = assertNotNull(source.resolve(query.copy(quality = QualityPreference(preset = QualityPreset.MP4))).stream)
            assertEquals(AudioQuality.MP4, extractor.quality)
            assertTrue(assertNotNull(extractor.hints).allowSabr)
            assertTrue(assertNotNull(extractor.hints).allowHls)
            assertEquals(StreamTransport.RANGE_HTTP, stream.transport)
            assertEquals("audio/mp4", stream.mimeType)
            assertEquals("mp4a.40.2", stream.codec)
            assertEquals(128000, stream.bitrate)
            assertEquals(44100, stream.sampleRate)
            assertEquals(123456L, stream.contentLength)
            assertEquals(4102444800000L, stream.expiresAtMillis)
            assertEquals(mapOf("User-Agent" to "fixture"), stream.headers)

            extractor.stream = extractor.stream?.copy(audioUrl = "https://fixture.googlevideo.com/audio.m3u8", mimeType = null)
            assertEquals(StreamTransport.HLS, source.resolve(query).stream?.transport)
        }

    @Test
    fun disabledLosslessForeignIdentityAndBitrateLimitsProduceStructuredErrors() =
        runBlocking {
            val settings = Settings()
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, settings)
            assertEquals(
                "IDENTITY_REQUIRED",
                source
                    .resolve(
                        query.copy(
                            identity =
                                TrackIdentity(
                                    "abcdefghijk",
                                    mapOf("pluginOwner" to "other.provider"),
                                ),
                        ),
                    ).error
                    ?.code,
            )
            settings.set("audioEnabled", JsonPrimitive(false))
            assertEquals("AUDIO_DISABLED", source.resolve(query).error?.code)
            settings.set("audioEnabled", JsonPrimitive(true))
            assertEquals("LOSSLESS_UNAVAILABLE", source.resolve(query.copy(quality = QualityPreference(lossless = true))).error?.code)
            assertEquals(0, extractor.calls)
            assertEquals("BITRATE_UNAVAILABLE", source.resolve(query.copy(quality = QualityPreference(maxBitrate = 96000))).error?.code)
        }

    @Test
    fun missingStreamsFailAndCancellationPropagates() =
        runBlocking<Unit> {
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, Settings())
            extractor.stream = null
            assertEquals("STREAM_UNAVAILABLE", source.resolve(query).error?.code)
            extractor.cancel = true
            assertFailsWith<CancellationException> { source.resolve(query) }
        }

    @Test
    fun sabrSessionCrossesTheBoundaryAsJsonAndKeepsTheSelectedAudioFormat() =
        runBlocking {
            val extractor = FixtureExtractor()
            extractor.stream = extractor.stream?.copy(sabrBootstrap = bootstrap())
            val stream = assertNotNull(GuestAudio(extractor, Settings()).resolve(query).stream)
            assertEquals(StreamTransport.SABR_UMP, stream.transport)
            assertEquals("https://fixture.googlevideo.com/sabr", stream.locator)
            val payload = Json.parseToJsonElement(assertNotNull(stream.attributes["metrofuse.sabr.v1"])).jsonObject
            assertEquals("abcdefghijk", payload["videoId"]?.jsonPrimitive?.content)
            assertEquals(
                "251",
                payload["audioFormat"]
                    ?.jsonObject
                    ?.get("itag")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals("AQID", payload["videoPlaybackUstreamerConfig"]?.jsonPrimitive?.content)
            assertEquals("BAUG", payload["poToken"]?.jsonPrimitive?.content)
        }

    @Test
    fun sabrOmitsNonpositiveContentLengths() =
        runBlocking {
            val extractor = FixtureExtractor()
            for (length in listOf(0L, -1L)) {
                extractor.stream = extractor.stream?.copy(contentLengthBytes = length, sabrBootstrap = bootstrap().copy(contentLengthBytes = length))
                val stream = assertNotNull(GuestAudio(extractor, Settings()).resolve(query).stream)
                assertEquals(StreamTransport.SABR_UMP, stream.transport)
                assertNull(stream.contentLength)
                val payload = Json.parseToJsonElement(assertNotNull(stream.attributes["metrofuse.sabr.v1"])).jsonObject
                assertNull(payload["contentLengthBytes"])
            }
        }

    @Test
    fun mismatchedSabrMediaRetriesWithoutSabrAndRejectsUnusableFallback() =
        runBlocking {
            val extractor = FixtureExtractor()
            val direct = assertNotNull(extractor.stream)
            extractor.stream = direct.copy(sabrBootstrap = bootstrap().copy(videoId = "otherVideo1"))
            extractor.fallbackStream = direct
            val source = GuestAudio(extractor, Settings())
            assertEquals(StreamTransport.RANGE_HTTP, source.resolve(query).stream?.transport)
            assertEquals(2, extractor.calls)
            assertFalse(assertNotNull(extractor.hints).allowSabr)

            extractor.fallbackStream = null
            assertEquals("SABR_MEDIA_IDENTITY_MISMATCH", source.resolve(query).error?.code)
        }

    private fun bootstrap() =
        SabrBootstrap(
            videoId = "abcdefghijk",
            serverAbrStreamingUrl = "https://fixture.googlevideo.com/sabr",
            videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3),
            clientName = 67,
            clientVersion = "fixture",
            audioFormat = SabrFormatId(251, 123L),
            discardVideoFormat = SabrFormatId(160, 456L),
            discardVideoHeight = 144,
            durationMs = 180000L,
            contentLengthBytes = 123456L,
            mimeType = "audio/webm",
            poToken = byteArrayOf(4, 5, 6),
        )

    private class Settings : PluginSettings {
        override val values = MutableStateFlow<Map<String, JsonElement>>(mapOf("audioEnabled" to JsonPrimitive(true)))

        override suspend fun set(
            key: String,
            value: JsonElement,
        ) {
            values.value = values.value + (key to value)
        }
    }

    private class FixtureExtractor : StreamExtractor {
        var calls = 0
        var cancel = false
        var quality: AudioQuality? = null
        var hints: ContentHints? = null
        var fallbackStream: ExtractedStream? = null
        var stream: ExtractedStream? =
            ExtractedStream(
                videoId = "abcdefghijk",
                audioUrl = "https://fixture.googlevideo.com/audio.mp4",
                headers = mapOf("User-Agent" to "fixture"),
                loudnessDb = null,
                expiresAt = Instant.fromEpochMilliseconds(4102444800000),
                contentLengthBytes = 123456,
                itag = 140,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = 128000,
                sampleRate = 44100,
                clientName = "fixture",
                profileId = "fixture",
                requireBoundedRange = true,
                rangeChunkSizeBytes = 65536,
            )

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            calls++
            this.hints = hints
            quality = audioQuality
            if (cancel) throw CancellationException("Fixture cancellation")
            return if (hints.allowSabr) stream else fallbackStream
        }
    }
}
