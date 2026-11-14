package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.LoginCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeSessionTest {
    @Test
    fun accountVerificationCommitsOnlyAValidSessionAndLogoutClearsIt() =
        runBlocking {
            var accountResponse = ACCOUNT
            var receivedCookie: String? = null
            var receivedAuthorization: String? = null
            val http =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("sw.js_data") -> {
                                respond(bootstrap())
                            }

                            request.url.encodedPath.endsWith("account_menu") -> {
                                receivedCookie = request.headers[HttpHeaders.Cookie]
                                receivedAuthorization = request.headers[HttpHeaders.Authorization]
                                respond(accountResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            }

                            else -> {
                                error("Unexpected request path ${request.url.encodedPath}")
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(http)
            tube.visitorData = "fixture-visitor"
            val session = YouTubeSession(tube, YouTubeGuestClient(http))
            try {
                assertFailsWith<IllegalArgumentException> { session.login(LoginCredentials(mapOf("cookie" to "SID=unverified"))) }
                assertNull(tube.cookie)
                assertFalse(session.loginState.value.loggedIn)
                val verified = session.login(LoginCredentials(mapOf("cookie" to "SAPISID=fixture-secret; SID=fixture")))
                assertTrue(verified.loggedIn)
                assertEquals("Fixture account", verified.accountName)
                assertTrue(assertNotNull(receivedCookie).contains("SAPISID=fixture-secret"))
                assertTrue(assertNotNull(receivedAuthorization).contains("SAPISIDHASH"))
                val previous = tube.cookie
                accountResponse = "{}"
                assertFailsWith<IllegalArgumentException> { session.login(LoginCredentials(mapOf("cookie" to "SAPISID=invalid"))) }
                assertEquals(previous, tube.cookie)
                assertEquals(verified, session.loginState.value)
                session.logout()
                assertFalse(session.loginState.value.loggedIn)
                assertFalse(tube.hasSapCookieAuth())
                assertNull(tube.cookie)
                assertNull(tube.dataSyncId)
                assertNull(tube.visitorData)
            } finally {
                tube.close()
                http.close()
            }
        }

    @Test
    fun homeUsesTheRequestedLocaleAndKeepsTheGuestSessionUnauthenticated() =
        runBlocking {
            var sentBody = ""
            val http =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("sw.js_data") -> {
                                respond(bootstrap())
                            }

                            request.url.encodedPath.endsWith("browse") -> {
                                sentBody = request.body.toByteArray().toString(Charsets.UTF_8)
                                assertNull(request.headers[HttpHeaders.Cookie])
                                assertNull(request.headers[HttpHeaders.Authorization])
                                respond(HOME, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            }

                            else -> {
                                error("Unexpected request path ${request.url.encodedPath}")
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(http)
            tube.visitorData = "fixture-visitor"
            try {
                val shelves = YouTubeSession(tube, YouTubeGuestClient(http)).home(HomeRequest("fr", "FR"))
                assertEquals("fr", tube.locale.hl)
                assertEquals("FR", tube.locale.gl)
                assertTrue(sentBody.contains("FEmusic_home"))
                assertTrue(sentBody.contains("\"hl\":\"fr\""))
                assertEquals("Recommendations", shelves.single().title)
                assertEquals(
                    "abcdefghijk",
                    shelves
                        .single()
                        .items
                        .single()
                        .track
                        ?.id,
                )
            } finally {
                tube.close()
                http.close()
            }
        }

    private fun bootstrap(): String {
        val tuple = MutableList<JsonElement>(17) { JsonNull }
        tuple[15] = JsonPrimitive(67)
        tuple[16] = JsonPrimitive("1.20261112.01.00")
        return JsonArray(tuple).toString()
    }

    companion object {
        private val ACCOUNT =
            """
            {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
              "header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Fixture account"}]}}}
            }}}}]}
            """.trimIndent()
        private val HOME =
            """
            {"contents":{"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Recommendations"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Fixture song"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}
              }}]
            }}}
            """.trimIndent()
    }
}
