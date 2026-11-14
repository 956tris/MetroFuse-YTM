package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.models.YouTubeLocale
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.PluginLoginState
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

internal class YouTubeSession(
    private val tube: InnerTube,
    private val client: YouTubeGuestClient,
) {
    private val gate = Mutex()
    private val state = MutableStateFlow(PluginLoginState())
    val loginState = state.asStateFlow()

    suspend fun login(credentials: LoginCredentials): PluginLoginState =
        gate.withLock {
            val cookie = requireNotNull(credentials.values["cookie"]).trim()
            require(cookie.isNotBlank() && cookie.length <= 16384 && '\r' !in cookie && '\n' !in cookie)
            val candidate = tube.createIsolatedSession(includeAccount = false)
            try {
                candidate.cookie = cookie
                require(candidate.hasSapCookieAuth()) { "Authentication cookie missing" }
                val response = candidate.accountMenu(client.get())
                check(response.status.isSuccess()) { "Account verification failed" }
                val account =
                    requireNotNull(accountName(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES))) {
                        "Account verification failed"
                    }
                tube.replaceSession(
                    cookie = candidate.cookie,
                    visitorData = candidate.visitorData,
                    dataSyncId = candidate.dataSyncId,
                    authUser = candidate.authUser,
                    useLoginForBrowse = true,
                )
                PluginLoginState(loggedIn = true, accountName = account).also { state.value = it }
            } finally {
                candidate.close()
            }
        }

    suspend fun logout() =
        gate.withLock {
            tube.cookie = null
            tube.dataSyncId = null
            tube.visitorData = null
            tube.useLoginForBrowse = false
            state.value = PluginLoginState()
        }

    suspend fun home(request: HomeRequest): List<HomeShelf> {
        val profile = client.get()
        tube.locale = YouTubeLocale(gl = request.country, hl = request.language)
        val response = tube.browse(profile, browseId = "FEmusic_home", setLogin = loginState.value.loggedIn)
        check(response.status.isSuccess()) { "YouTube Music Home failed with HTTP ${response.status.value}" }
        return YouTubeCatalog.home(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES))
    }

    private fun accountName(root: JsonObject): String? =
        YouTubeCatalog
            .renderers(root, setOf("activeAccountHeaderRenderer"))
            .mapNotNull { YouTubeCatalog.text(it["accountName"]) }
            .firstOrNull()
}
