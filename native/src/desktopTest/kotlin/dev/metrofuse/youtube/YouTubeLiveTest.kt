package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import dev.metrofuse.api.HomeRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeLiveTest {
    @OptIn(ExperimentalSabrApi::class)
    @Test
    fun guestSearchHomeAndFirstAudioBytes() =
        runBlocking {
            assumeTrue("Live service checks are opt-in", System.getenv("METROFUSE_LIVE_YTM") == "1")
            val http =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                explicitNulls = false
                            },
                        )
                    }
                    install(HttpTimeout) {
                        requestTimeoutMillis = 15000
                        connectTimeoutMillis = 8000
                        socketTimeoutMillis = 15000
                    }
                }
            val tube = InnerTube(http)
            val locale = Locale.getDefault()
            tube.locale = YouTubeLocale(hl = locale.toLanguageTag(), gl = locale.country)
            val configs = RemotePlayerConfigStore(http, PlayerConfigRepository.disabled())
            val cipher = YouTubeCipherService(http, configs)
            try {
                withTimeout(120000) {
                    val profile = YouTubeGuestClient(http)
                    val searchResponse = tube.search(profile.get(), query = "Adele Hello", setLogin = false)
                    assertTrue(searchResponse.status.isSuccess())
                    val results = YouTubeCatalog.search(searchResponse.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES), 30)
                    println("Live search returned ${results.tracks.size} tracks")
                    assertTrue(results.tracks.isNotEmpty())
                    val shelves = YouTubeSession(tube, profile).home(HomeRequest(locale.toLanguageTag(), locale.country))
                    println("Live Home returned ${shelves.size} shelves")
                    assertTrue(shelves.isNotEmpty())
                    val extractor =
                        InnerTubeExtractor(
                            configParser = YtConfigParserImpl(http, tube, configs, cipherService = cipher),
                            cipherService = cipher,
                            innerTube = tube,
                            fallbackStrategy = ContentAwareFallbackStrategy(),
                        )
                    val stream = assertNotNull(extractor.extract("YQHsXMglC9A", ContentHints().withStreamCapabilities(allowSabr = true)))
                    val sabr = stream.sabrBootstrap
                    if (sabr != null) {
                        val chunk = SabrAudioStream(http, sabr).chunks().first { !it.isInitialization && it.data.isNotEmpty() }
                        println("Live SABR audio returned ${chunk.data.size} media bytes")
                        assertTrue(chunk.data.isNotEmpty())
                    } else {
                        val response =
                            http.get(stream.audioUrl) {
                                stream.headers.forEach { (key, value) -> header(key, value) }
                                header(HttpHeaders.Range, "bytes=0-65535")
                            }
                        assertTrue(response.status.isSuccess())
                        val channel = response.bodyAsChannel()
                        try {
                            val count = channel.readAvailable(ByteArray(4096), 0, 4096)
                            println("Live direct audio returned $count bytes")
                            assertTrue(count > 0)
                        } finally {
                            channel.cancel(null)
                        }
                    }
                }
            } finally {
                cipher.dispose()
                tube.close()
                http.close()
            }
        }
}
