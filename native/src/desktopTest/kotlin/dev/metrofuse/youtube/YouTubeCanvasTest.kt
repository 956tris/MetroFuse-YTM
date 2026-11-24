package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrVideoVariant
import dev.metrofuse.api.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class YouTubeCanvasTest {
    @Test
    fun resolvesCounterpartFromNextAndExtractsYoutubeVideoStreamWithoutAppleCalls() =
        runBlocking {
            val requestedHosts = mutableListOf<String>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        requestedHosts += request.url.host
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            return@MockEngine respond(
                                content = swJsBootstrap(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                        respond(
                            content =
                                """
                                {
                                  "contents": {
                                    "singleColumnMusicWatchNextResultsRenderer": {
                                      "tabbedRenderer": {
                                        "watchNextTabbedResultsRenderer": {
                                          "tabs": [{
                                            "tabRenderer": {
                                              "content": {
                                                "musicQueueRenderer": {
                                                  "content": {
                                                    "playlistPanelRenderer": {
                                                      "contents": [{
                                                        "playlistPanelVideoWrapperRenderer": {
                                                          "primaryRenderer": {
                                                            "playlistPanelVideoRenderer": {
                                                              "videoId": "atvSong0001"
                                                            }
                                                          },
                                                          "counterpart": [{
                                                            "counterpartRenderer": {
                                                              "playlistPanelVideoRenderer": {
                                                                "videoId": "omvVideo001",
                                                                "navigationEndpoint": {
                                                                  "watchEndpoint": {
                                                                    "watchEndpointMusicSupportedConfigs": {
                                                                      "watchEndpointMusicConfig": {
                                                                        "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                                                                      }
                                                                    }
                                                                  }
                                                                }
                                                              }
                                                            }
                                                          }]
                                                        }
                                                      }]
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }]
                                        }
                                      }
                                    }
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }
            val tube = InnerTube(http)
            val guestClient = YouTubeGuestClient(http)
            val extractor = CanvasFixtureExtractor("omvVideo001")
            val canvas = YouTubeCanvas(tube, guestClient, extractor)
            val track =
                Track(
                    id = "metrofuse:metrofuse.youtube-music:atvSong0001",
                    title = "Track Title",
                    artist = "Artist One",
                    album = "Album",
                    duration = 200_000L,
                    extras = mapOf("musicVideoType" to "MUSIC_VIDEO_TYPE_ATV"),
                )

            val resolved = assertNotNull(canvas.resolve(track, maxHeight = 720))
            assertEquals("omvVideo001", resolved.videoId)
            assertEquals("https://rr1.googlevideo.com/video.mp4", resolved.url)
            assertEquals("omvVideo001", extractor.lastVideoId)
            assertTrue(assertNotNull(extractor.lastHints).allowSabr)
            assertFalse(assertNotNull(extractor.lastHints).allowHls)
            assertEquals(720, assertNotNull(extractor.lastHints).maxVideoHeight)
            assertFalse(requestedHosts.any { "apple" in it.lowercase() })

            tube.close()
            http.close()
        }

    @Test
    fun extractsSabrVideoStreamForCanvasWhenDirectUrlAbsent() =
        runBlocking {
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            respond(
                                content = swJsBootstrap(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } else {
                            respond(
                                content = """{"contents":{}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }
            val tube = InnerTube(http)
            val extractor = CanvasFixtureExtractor("omvVideo002", directUrl = null, withSabr = true)
            val canvas = YouTubeCanvas(tube, YouTubeGuestClient(http), extractor)
            val track =
                Track(
                    id = "metrofuse:metrofuse.youtube-music:omvVideo002",
                    title = "Track Title",
                    artist = "Artist One",
                    album = "Album",
                    duration = 200_000L,
                    extras = mapOf("musicVideoType" to "MUSIC_VIDEO_TYPE_OMV"),
                )

            val resolved = assertNotNull(canvas.resolve(track, maxHeight = 480))
            assertEquals("omvVideo002", resolved.videoId)
            assertNull(resolved.url)
            val sabrJson = Json.parseToJsonElement(assertNotNull(resolved.sabr)).jsonObject
            assertEquals("omvVideo002", sabrJson["videoId"]?.jsonPrimitive?.content)
            assertEquals(480, resolved.height)
            assertEquals(200L, resolved.durationSeconds)
            assertEquals("video/webm", resolved.mimeType)

            tube.close()
            http.close()
        }

    @Test
    fun fallsBackToYoutubeMusicVideosSearchWhenNextHasNoCounterpart() =
        runBlocking {
            var searchCalled = false
            val http =
                HttpClient(
                    MockEngine { request ->
                        val payload =
                            when {
                                request.url.encodedPath.endsWith("sw.js_data") -> {
                                    swJsBootstrap()
                                }

                                request.url.encodedPath.endsWith("search") -> {
                                    searchCalled = true
                                    """
                                {
                                  "contents": {
                                    "tabbedSearchResultsRenderer": {
                                      "tabs": [{
                                        "tabRenderer": {
                                          "content": {
                                            "sectionListRenderer": {
                                              "contents": [{
                                                "musicShelfRenderer": {
                                                  "contents": [{
                                                    "musicResponsiveListItemRenderer": {
                                                      "flexColumns": [
                                                        {
                                                          "musicResponsiveListItemFlexColumnRenderer": {
                                                            "text": {
                                                              "runs": [{
                                                                "text": "Blinding Lights",
                                                                "navigationEndpoint": {
                                                                  "watchEndpoint": {
                                                                    "videoId": "searchOmv01",
                                                                    "watchEndpointMusicSupportedConfigs": {
                                                                      "watchEndpointMusicConfig": {
                                                                        "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                                                                      }
                                                                    }
                                                                  }
                                                                }
                                                              }]
                                                            }
                                                          }
                                                        },
                                                        {
                                                          "musicResponsiveListItemFlexColumnRenderer": {
                                                            "text": {
                                                              "runs": [{"text": "The Weeknd"}, {"text": "3:22"}]
                                                            }
                                                          }
                                                        }
                                                      ],
                                                      "playlistItemData": {"videoId": "searchOmv01"}
                                                    }
                                                  }]
                                                }
                                              }]
                                            }
                                          }
                                        }
                                      }]
                                    }
                                  }
                                }
                                """.trimIndent()
                                }

                                else -> {
                                    """{"contents":{}}"""
                                }
                            }
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }
            val tube = InnerTube(http)
            val canvas = YouTubeCanvas(tube, YouTubeGuestClient(http), CanvasFixtureExtractor("searchOmv01"))
            val track =
                Track(
                    id = "atvSong0002",
                    title = "Blinding Lights",
                    artist = "The Weeknd",
                    album = "",
                    duration = 200_000L,
                    extras = mapOf("musicVideoType" to "MUSIC_VIDEO_TYPE_ATV"),
                )

            assertEquals("searchOmv01", canvas.resolveMusicVideoId(track))
            assertFalse(!searchCalled)

            val mismatch = Track(id = "atvSong0003", title = "Completely Different Song", artist = "Nobody", album = "", duration = 200_000L)
            assertNull(canvas.resolveMusicVideoId(mismatch))

            tube.close()
            http.close()
        }

    private fun swJsBootstrap(): String {
        val tuple = List(17) { if (it == 15) "67" else if (it == 16) "\"1.20261112.01.00\"" else "null" }
        return tuple.joinToString(",", prefix = "[", postfix = "]")
    }

    private class CanvasFixtureExtractor(
        private val expectedId: String,
        private val directUrl: String? = "https://rr1.googlevideo.com/video.mp4",
        private val withSabr: Boolean = false,
    ) : StreamExtractor {
        var lastVideoId: String? = null
        var lastHints: ContentHints? = null

        override suspend fun prewarm() {}

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            lastVideoId = videoId
            lastHints = hints
            if (videoId != expectedId) return null
            val bootstrap =
                if (withSabr) {
                    SabrBootstrap(
                        videoId = videoId,
                        serverAbrStreamingUrl = "https://rr1.googlevideo.com/sabr",
                        videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3),
                        clientName = 67,
                        clientVersion = "1.20261112.01.00",
                        audioFormat = SabrFormatId(251, 123L),
                        discardVideoFormat = SabrFormatId(160, 456L),
                        discardVideoHeight = 144,
                        selectedVideoFormat = SabrFormatId(244, 789L),
                        selectedVideoWidth = 854,
                        selectedVideoHeight = 480,
                        selectedVideoContentLengthBytes = 4_000_000L,
                        selectedVideoMimeType = "video/webm",
                        selectedVideoBitrate = 900_000,
                        durationMs = 200_000L,
                        contentLengthBytes = 123L,
                        mimeType = "audio/webm",
                        videoVariants = listOf(SabrVideoVariant(SabrFormatId(244, 789L), 854, 480, 900_000, "video/webm", 4_000_000L)),
                    )
                } else {
                    null
                }
            return ExtractedStream(
                videoId = videoId,
                audioUrl = "https://rr1.googlevideo.com/audio.m4a",
                headers = mapOf("User-Agent" to "fixture"),
                loudnessDb = null,
                expiresAt = Instant.fromEpochMilliseconds(4102444800000L),
                contentLengthBytes = 123L,
                itag = 140,
                mimeType = "audio/mp4",
                codecs = "mp4a",
                bitrate = 128000,
                sampleRate = 44100,
                clientName = "WEB_REMIX",
                profileId = "WEB_REMIX",
                requireBoundedRange = true,
                rangeChunkSizeBytes = 65536,
                videoUrl = directUrl,
                videoWidth = if (directUrl != null) 1280 else null,
                videoHeight = if (directUrl != null) 720 else null,
                videoMimeType = if (directUrl != null) "video/mp4" else null,
                videoCodecs = if (directUrl != null) "avc1" else null,
                videoBitrate = if (directUrl != null) 2_000_000 else null,
                videoItag = if (directUrl != null) 22 else null,
                videoContentLengthBytes = if (directUrl != null) 5_000_000L else null,
                sabrVideoBootstrap = bootstrap,
            )
        }
    }
}
