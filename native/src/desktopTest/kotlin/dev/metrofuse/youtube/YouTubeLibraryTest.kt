package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import dev.metrofuse.api.LibraryRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeLibraryTest {
    private fun fixture(name: String) = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name.json")).readText()).jsonObject

    private val empty = """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
    private val bootstrap = """[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,67,"1.20261114.00.00"]"""

    @Test
    fun libraryParserReadsEveryObjectKindAndContinuedSongs() {
        val page = YouTubeCatalog.library(fixture("youtube-library"))
        assertEquals("library-next", page.continuation)
        assertTrue(page.complete)
        assertEquals(4, page.items.size)
        assertEquals(
            "abcdefghijk",
            page.items
                .single { it.track != null }
                .track
                ?.id,
        )
        assertEquals(
            "MPREfixture",
            page.items
                .single { it.album != null }
                .album
                ?.id,
        )
        assertEquals(
            "UCfixture",
            page.items
                .single { it.artist != null }
                .artist
                ?.id,
        )
        assertEquals(
            "VLPLfixture",
            page.items
                .single { it.playlist != null }
                .playlist
                ?.id,
        )
        val next = YouTubeCatalog.library(fixture("youtube-library-continuation"))
        assertEquals(
            "lmnopqrstuv",
            next.items
                .single()
                .track
                ?.id,
        )
        assertNull(next.continuation)
        assertFailsWith<IllegalStateException> { YouTubeCatalog.library(JsonObject(emptyMap())) }
    }

    @Test
    fun nativeLibraryLoadsAuthenticatedCategoriesAndContinuationWithTheRequestedLocale() =
        runBlocking<Unit> {
            val bodies = mutableListOf<JsonObject>()
            val tokens = mutableListOf<String?>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            respond(bootstrap)
                        } else {
                            assertTrue(request.headers[HttpHeaders.Cookie]?.split(';')?.any { it.trim() == "SAPISID=fixture" } == true)
                            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true)
                            val body = Json.parseToJsonElement(request.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                            bodies += body
                            val token = request.url.parameters["continuation"] ?: body["continuation"]?.jsonPrimitive?.contentOrNull
                            tokens += token
                            val content =
                                when {
                                    token != null -> {
                                        fixture("youtube-library-continuation").toString()
                                    }

                                    body["browseId"]?.jsonPrimitive?.content == "FEmusic_liked_videos" -> {
                                        fixture(
                                            "youtube-library",
                                        ).toString()
                                    }

                                    else -> {
                                        empty
                                    }
                                }
                            respond(content, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(http)
            try {
                val catalog = GuestCatalog(tube, YouTubeGuestClient(http))
                val request = LibraryRequest("pt-BR", "BR")
                assertTrue(catalog.library(request).items.isEmpty())
                assertTrue(bodies.isEmpty())
                tube.cookie = "SAPISID=fixture"
                tube.useLoginForBrowse = true
                var page = catalog.library(request)
                val items = page.items.toMutableList()
                while (page.continuation != null) {
                    page = catalog.library(request.copy(continuation = page.continuation))
                    assertTrue(page.complete)
                    items += page.items
                }
                assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), items.mapNotNull { it.track?.id })
                assertEquals(
                    listOf("FEmusic_liked_videos", "FEmusic_liked_albums", "FEmusic_library_corpus_artists", "FEmusic_liked_playlists"),
                    bodies.mapNotNull { it["browseId"]?.jsonPrimitive?.contentOrNull },
                )
                assertEquals("library-next", tokens[1])
                assertNull(bodies[1]["browseId"]?.jsonPrimitive?.contentOrNull)
                bodies.forEach { body ->
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                    assertEquals("pt-BR", client["hl"]?.jsonPrimitive?.content)
                    assertEquals("BR", client["gl"]?.jsonPrimitive?.content)
                }
                assertFailsWith<IllegalArgumentException> { catalog.library(request.copy(continuation = "invalid")) }
            } finally {
                tube.close()
                http.close()
            }
        }

    @Test
    fun failedCategoryIsIncompleteAndAChangedAccountCannotReturnOldItems() =
        runBlocking<Unit> {
            var changeAccount = false
            lateinit var tube: InnerTube
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("sw.js_data")) {
                            respond(bootstrap)
                        } else {
                            if (changeAccount) tube.cookie = "SAPISID=another"
                            respond(
                                if (changeAccount) fixture("youtube-library").toString() else "{}",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            tube =
                InnerTube(http).apply {
                    cookie = "SAPISID=fixture"
                    useLoginForBrowse = true
                }
            try {
                val library = YouTubeLibrary(tube, YouTubeGuestClient(http))
                val request = LibraryRequest("pt-BR", "BR")
                val failed = library.page(request)
                assertFalse(failed.complete)
                assertNotNull(failed.continuation)
                changeAccount = true
                assertFailsWith<CancellationException> { library.page(request) }
            } finally {
                tube.close()
                http.close()
            }
        }
}
