package dev.metrofuse.youtube

import androidx.compose.runtime.Composable
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeLocale
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.LibraryProvider
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginField
import dev.metrofuse.api.LoginPlugin
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PagedHomePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginLoginState
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.RecommendationProvider
import dev.metrofuse.api.RecommendationRequest
import dev.metrofuse.api.RecommendationResult
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.SongVoteRequest
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.TrackVote
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

// The provider owns its network client and account session for each activation.
class YouTubeMusicPlugin :
    NativePlugin,
    LoginPlugin,
    NativeLoginUi,
    PagedHomePlugin {
    private var client: HttpClient? = null
    private var innerTube: InnerTube? = null
    private var cipherService: YouTubeCipherService? = null
    private var session: YouTubeSession? = null
    private var request: LoginRequest? = null
    private var accountScreen: WebViewLoginScreen? = null
    private val signedOut = MutableStateFlow(PluginLoginState())
    override val loginState: StateFlow<PluginLoginState> get() = session?.loginState ?: signedOut

    override suspend fun loginRequest() = request

    override suspend fun getHomePage(
        request: HomeRequest,
        continuation: String?,
    ) = checkNotNull(session).homePage(request, continuation)

    @Composable
    override fun Content(context: LoginUiContext) {
        accountScreen?.Content(context)
    }

    internal fun browserRequest() =
        WebLoginRequest(
            url = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com",
            cookieUrl = "https://music.youtube.com",
            cookieField = "cookie",
            requiredCookies = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"),
            allowedHosts =
                listOf(
                    "music.youtube.com",
                    "youtube.com",
                    "www.youtube.com",
                    "accounts.youtube.com",
                    "consent.youtube.com",
                    "accounts.google.com",
                    "accounts.google.co.uk",
                    "consent.google.com",
                    "myaccount.google.com",
                    "www.google.com",
                ),
        )

    override suspend fun login(credentials: LoginCredentials) = checkNotNull(session).login(credentials)

    override suspend fun logout() {
        session?.logout()
    }

    override suspend fun getHome(request: HomeRequest) = checkNotNull(session).home(request)

    override suspend fun start(context: PluginContext) {
        val http =
            HttpClient {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 8000
                    connectTimeoutMillis = 4000
                    socketTimeoutMillis = 8000
                }
            }
        client = http
        val tube = InnerTube(http)
        val locale = Locale.getDefault()
        tube.locale = YouTubeLocale(gl = locale.country, hl = locale.toLanguageTag())
        innerTube = tube
        val configs = RemotePlayerConfigStore(http, PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, configs)
        cipherService = cipher
        val extractor =
            InnerTubeExtractor(
                configParser = YtConfigParserImpl(http, tube, configs, cipherService = cipher),
                cipherService = cipher,
                innerTube = tube,
                fallbackStrategy = ContentAwareFallbackStrategy(),
            )
        val musicClient = YouTubeGuestClient(http)
        session = YouTubeSession(tube, musicClient)
        val strings = Json.parseToJsonElement(context.resources.readAsset("assets/login.json").toString(Charsets.UTF_8)) as JsonObject
        request =
            LoginRequest(
                fields = listOf(LoginField("cookie", requireNotNull((strings["label"] as? JsonPrimitive)?.contentOrNull))),
                instructions = requireNotNull((strings["instructions"] as? JsonPrimitive)?.contentOrNull),
            )

        fun label(key: String) = requireNotNull((strings[key] as? JsonPrimitive)?.contentOrNull)
        accountScreen =
            WebViewLoginScreen(
                WebViewLoginUi(
                    browserRequest(),
                    WebViewLabels(
                        title = label("title"),
                        hint = label("instructions"),
                        error = label("error"),
                        retry = label("retry"),
                        cancel = label("cancel"),
                        login = label("login"),
                        logout = label("logout"),
                    ),
                ),
            )
        val catalog = GuestCatalog(tube, musicClient)
        context.registerSearchProvider(catalog)
        context.registerCatalogProvider(catalog)
        context.registerMetadataSource(catalog)
        context.registerAudioSource(GuestAudio(extractor, context.settings))
    }

    override suspend fun stop() {
        session?.logout()
        session = null
        request = null
        accountScreen = null
        try {
            cipherService?.dispose()
        } finally {
            cipherService = null
            try {
                innerTube?.close()
            } finally {
                innerTube = null
                client?.close()
                client = null
            }
        }
    }
}

internal class GuestCatalog(
    private val tube: InnerTube,
    private val guestClient: YouTubeGuestClient,
) : SearchProvider,
    CatalogProvider,
    LibraryProvider,
    RecommendationProvider,
    MetadataSource {
    override val id = "youtube-music.catalog"
    private val accountLibrary = YouTubeLibrary(tube, guestClient)

    override suspend fun library(request: LibraryRequest) = accountLibrary.page(request)

    override val supportsLikedSongs = true
    override val supportsSongVotes = true

    override suspend fun setSongVote(request: SongVoteRequest): Boolean {
        if (!tube.hasSapCookieAuth() || !VIDEO_ID.matches(request.trackId)) return false
        val snapshot = tube.sessionSnapshot()
        val profile = guestClient.get()
        val response =
            when (request.vote) {
                TrackVote.LIKE -> tube.likeVideo(profile, request.trackId, snapshot)
                TrackVote.DISLIKE -> tube.dislikeVideo(profile, request.trackId, snapshot)
                TrackVote.INDIFFERENT -> tube.unlikeVideo(profile, request.trackId, snapshot)
            }
        if (snapshot != tube.sessionSnapshot()) throw CancellationException("YouTube Music account changed")
        return response.status.isSuccess()
    }

    override suspend fun search(request: SearchRequest): SearchResult {
        val query = request.query.trim()
        if (query.isEmpty()) return SearchResult(emptyList())
        val response = tube.search(guestClient.get(), query = query, setLogin = tube.hasSapCookieAuth())
        check(response.status.isSuccess()) { "YouTube Music search failed with HTTP ${response.status.value}" }
        return YouTubeCatalog.search(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES), request.limit.coerceIn(1, 100))
    }

    override suspend fun album(id: String) = browse(id)?.let { YouTubeCatalog.album(it, id) }

    override suspend fun artist(id: String) = browse(id)?.let { YouTubeCatalog.artist(it, id) }

    override suspend fun playlist(id: String) = browse(if (id.startsWith("VL")) id else "VL$id")?.let { YouTubeCatalog.playlist(it, id) }

    override suspend fun recommendations(request: RecommendationRequest): RecommendationResult {
        val id = videoId(request.identity) ?: return RecommendationResult(emptyList())
        require((request.continuation?.length ?: 0) <= 16_384)
        val snapshot = tube.sessionSnapshot()
        val response =
            tube.next(
                guestClient.get(),
                videoId = if (request.continuation == null) id else null,
                playlistId = if (request.continuation == null) "RDAMVM$id" else null,
                continuation = request.continuation,
            )
        check(response.status.isSuccess()) { "YouTube Music recommendations failed with HTTP ${response.status.value}" }
        val result = YouTubeCatalog.radio(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES))
        check(snapshot == tube.sessionSnapshot()) { "YouTube Music session changed" }
        return result.copy(tracks = result.tracks.take(request.limit.coerceIn(1, 100)))
    }

    private suspend fun browse(id: String): JsonObject? {
        if (!BROWSE_ID.matches(id)) return null
        val response = tube.browse(guestClient.get(), browseId = id, setLogin = tube.hasSapCookieAuth())
        check(response.status.isSuccess()) { "YouTube Music browse failed with HTTP ${response.status.value}" }
        return response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)
    }

    override suspend fun resolve(request: MetadataRequest): MetadataContribution? {
        val id = videoId(request.identity) ?: return null
        val response = tube.player(guestClient.get(), id)
        check(response.status.isSuccess()) { "YouTube Music metadata failed with HTTP ${response.status.value}" }
        val details = response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)["videoDetails"] as? JsonObject ?: return null
        if ((details["videoId"] as? JsonPrimitive)?.contentOrNull != id) return null
        val fields =
            buildJsonObject {
                (details["title"] as? JsonPrimitive)?.let { put("title", it) }
                (details["author"] as? JsonPrimitive)?.let { put("artist", it) }
                (details["lengthSeconds"] as? JsonPrimitive)
                    ?.longOrNull
                    ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
                    ?.let { put("duration", it * 1000) }
            }
        return fields.takeIf { it.isNotEmpty() }?.let { MetadataContribution(request.identity, it, confidence = 1.0) }
    }
}

internal class GuestAudio(
    private val extractor: StreamExtractor,
    private val settings: PluginSettings,
) : AudioSource {
    override val id = "youtube-music.audio"
    override val capabilities =
        AudioCapabilities(setOf(StreamTransport.DIRECT, StreamTransport.RANGE_HTTP, StreamTransport.HLS, StreamTransport.SABR_UMP))

    override suspend fun resolve(request: AudioQuery): AudioResult {
        val id = videoId(request.identity) ?: return AudioResult(error = ProviderError("IDENTITY_REQUIRED"))
        if ((settings.values.value["audioEnabled"] as? JsonPrimitive)?.booleanOrNull == false) {
            return AudioResult(error = ProviderError("AUDIO_DISABLED"))
        }
        if (request.quality.lossless) return AudioResult(error = ProviderError("LOSSLESS_UNAVAILABLE"))
        val hints = ContentHints().withStreamCapabilities(allowHls = true, allowSabr = true, allowBoundedRange = true)
        val quality = AudioQuality.valueOf(request.quality.preset.name)
        val stream =
            try {
                val selected = extractor.extract(id, hints, audioQuality = quality)
                if (selected?.sabrBootstrap?.videoId?.let { it != id } == true) {
                    val fallbackHints = hints.withStreamCapabilities(allowHls = true, allowSabr = false, allowBoundedRange = true)
                    val fallback = extractor.extract(id, fallbackHints, audioQuality = quality)
                    if (fallback == null || fallback.sabrBootstrap != null || fallback.videoId != id) {
                        return AudioResult(error = ProviderError("SABR_MEDIA_IDENTITY_MISMATCH"))
                    }
                    fallback
                } else {
                    selected
                }
            } catch (e: StreamResolveException) {
                return AudioResult(error = ProviderError("YOUTUBE_${e.reason.name}"))
            } ?: return AudioResult(error = ProviderError("STREAM_UNAVAILABLE"))
        if (request.quality.maxBitrate?.let { maximum -> stream.bitrate?.let { it > maximum } != false } == true) {
            return AudioResult(error = ProviderError("BITRATE_UNAVAILABLE"))
        }
        val transport =
            when {
                stream.sabrBootstrap != null -> StreamTransport.SABR_UMP

                stream.mimeType?.contains("mpegurl", ignoreCase = true) == true ||
                    stream.audioUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> StreamTransport.HLS

                stream.requireBoundedRange || stream.useRangeChunks -> StreamTransport.RANGE_HTTP

                else -> StreamTransport.DIRECT
            }
        return AudioResult(
            stream =
                StreamDescriptor(
                    transport = transport,
                    locator = stream.sabrBootstrap?.serverAbrStreamingUrl ?: stream.audioUrl,
                    mimeType = stream.mimeType,
                    codec = stream.codecs,
                    bitrate = stream.bitrate,
                    expiresAtMillis = stream.expiresAt?.toEpochMilliseconds(),
                    headers = stream.headers,
                    sampleRate = stream.sampleRate,
                    contentLength = stream.contentLengthBytes?.takeIf { it > 0 },
                    durationSeconds = stream.mediaMetadata?.durationSeconds,
                    attributes = stream.sabrBootstrap?.let { mapOf("metrofuse.sabr.v1" to encodeSabrBootstrap(it)) }.orEmpty(),
                ),
        )
    }
}

internal fun videoId(identity: TrackIdentity): String? {
    val owner = identity.identifiers["pluginOwner"] ?: identity.identifiers["plugin"]
    val candidate =
        identity.identifiers["youtube"] ?: when (owner) {
            null, "metrofuse.youtube-music" -> identity.canonicalId
            else -> return null
        }
    return candidate.takeIf(VIDEO_ID::matches)
}

internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
private val BROWSE_ID = Regex("[A-Za-z0-9_-]{1,240}")
