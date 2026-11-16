package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.RecommendationRequest
import dev.metrofuse.api.TrackIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeRecommendationsTest {
    private fun fixture(name: String) = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name.json")).readText()).jsonObject

    private fun panel(): JsonObject =
        fixture("youtube-radio")["contents"]!!
            .jsonObject["singleColumnMusicWatchNextResultsRenderer"]!!
            .jsonObject["tabbedRenderer"]!!
            .jsonObject["watchNextTabbedResultsRenderer"]!!
            .jsonObject["tabs"]!!
            .let { it as JsonArray }
            .first()
            .jsonObject["tabRenderer"]!!
            .jsonObject["content"]!!
            .jsonObject["musicQueueRenderer"]!!
            .jsonObject["content"]!!
            .jsonObject["playlistPanelRenderer"]!!
            .jsonObject

    private fun continuedRadio() =
        buildJsonObject {
            put("continuationContents", buildJsonObject { put("playlistPanelContinuation", panel()) })
        }

    @Test
    fun radioParserReadsInitialAndContinuationPanelsWithoutCounterpartOrUnrelatedTracks() {
        for (root in listOf(fixture("youtube-radio"), continuedRadio())) {
            val result = YouTubeCatalog.radio(root)
            assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), result.tracks.map { it.id })
            assertEquals("radio-next", result.continuation)
            val related = result.tracks.last()
            assertEquals(241_000L, related.duration)
            assertEquals("Fixture artist", related.artist)
            assertEquals("Fixture album", related.album)
            assertEquals("https://example.org/related.jpg", related.artwork)
            assertEquals("MPREfixture", related.extras["albumId"])
            assertEquals("MUSIC_VIDEO_TYPE_ATV", related.extras["musicVideoType"])
            assertEquals("true", related.extras["explicit"])
        }
    }

    @Test
    fun nativeRecommendationsRequestRadioAndContinuationUsingTheCurrentAccount() =
        runBlocking {
            val bodies = mutableListOf<JsonObject>()
            val cookies = mutableListOf<String?>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            respond(BOOTSTRAP)
                        } else {
                            assertTrue(request.url.encodedPath.endsWith("next"))
                            val body = Json.parseToJsonElement(request.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                            bodies += body
                            cookies += request.headers[HttpHeaders.Cookie]
                            respond(
                                (
                                    if (body["continuation"]?.jsonPrimitive?.contentOrNull !=
                                        null
                                    ) {
                                        continuedRadio()
                                    } else {
                                        fixture("youtube-radio")
                                    }
                                ).toString(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(http)
            try {
                val catalog = GuestCatalog(tube, YouTubeGuestClient(http))
                assertTrue(
                    catalog
                        .recommendations(
                            RecommendationRequest(TrackIdentity("foreign", mapOf("pluginOwner" to "other"))),
                        ).tracks
                        .isEmpty(),
                )
                val seed = TrackIdentity("abcdefghijk", mapOf("pluginOwner" to "metrofuse.youtube-music"))
                val first = catalog.recommendations(RecommendationRequest(seed))
                assertEquals("abcdefghijk", bodies.first()["videoId"]?.jsonPrimitive?.contentOrNull)
                assertEquals("RDAMVMabcdefghijk", bodies.first()["playlistId"]?.jsonPrimitive?.contentOrNull)
                assertNull(cookies.first())
                tube.cookie = "SAPISID=fixture"
                tube.useLoginForBrowse = true
                catalog.recommendations(RecommendationRequest(seed, continuation = first.continuation))
                assertEquals("radio-next", bodies.last()["continuation"]?.jsonPrimitive?.contentOrNull)
                assertNull(bodies.last()["videoId"]?.jsonPrimitive?.contentOrNull)
                assertTrue(cookies.last()?.split(';')?.any { it.trim() == "SAPISID=fixture" } == true)
            } finally {
                tube.close()
                http.close()
            }
        }

    @Test
    fun homeParsesLaterAlbumsMixesArtistsAndCardsAndKeepsLibraryShelvesOut() {
        val initial = fixture("youtube-home")
        assertEquals(listOf("Listen again", "Quick picks"), YouTubeCatalog.home(initial).map { it.title })
        assertEquals("home-next", YouTubeCatalog.homeContinuation(initial))
        val later = fixture("youtube-home-continuation")
        val shelves = YouTubeCatalog.home(later)
        assertEquals(listOf("New releases", "Mixes for you", "Artists for you", "Featured recommendation"), shelves.map { it.title })
        assertEquals(
            "MPREfixture",
            shelves[0]
                .items
                .single()
                .album!!
                .id,
        )
        assertEquals(
            "RDCLAKfixture",
            shelves[1]
                .items
                .single()
                .playlist!!
                .id,
        )
        assertEquals(
            "https://example.org/mix.jpg",
            shelves[1]
                .items
                .single()
                .playlist!!
                .artwork,
        )
        assertEquals(
            "UCfixture",
            shelves[2]
                .items
                .single()
                .artist!!
                .id,
        )
        assertEquals(
            "wxyz0123456",
            shelves[3]
                .items
                .single()
                .track!!
                .id,
        )
        assertNull(YouTubeCatalog.homeContinuation(later))
    }

    @Test
    fun nativeHomeContinuationUsesTheRequestedLocaleAndDoesNotRestartTheFeed() =
        runBlocking {
            val bodies = mutableListOf<JsonObject>()
            val tokens = mutableListOf<String?>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            respond(BOOTSTRAP)
                        } else {
                            val body = Json.parseToJsonElement(request.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                            bodies += body
                            val token = request.url.parameters["continuation"] ?: body["continuation"]?.jsonPrimitive?.contentOrNull
                            tokens += token
                            respond(
                                fixture(if (token == null) "youtube-home" else "youtube-home-continuation").toString(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(http)
            try {
                val session = YouTubeSession(tube, YouTubeGuestClient(http))
                val locale = HomeRequest("pt-BR", "BR")
                val first = session.homePage(locale)
                val next = session.homePage(locale, first.continuation)
                assertEquals(4, next.shelves.size)
                assertEquals("home-next", tokens.last())
                assertNull(bodies.last()["browseId"]?.jsonPrimitive?.contentOrNull)
                for (body in bodies) {
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                    assertEquals("pt-BR", client["hl"]?.jsonPrimitive?.contentOrNull)
                    assertEquals("BR", client["gl"]?.jsonPrimitive?.contentOrNull)
                }
            } finally {
                tube.close()
                http.close()
            }
        }

    companion object {
        private const val BOOTSTRAP = "[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,67,\"1.20261114.00.00\"]"
    }
}
