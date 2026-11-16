package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.models.YouTubeLocale
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.LibraryRequest
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal class YouTubeLibrary(
    private val tube: InnerTube,
    private val client: YouTubeGuestClient,
) {
    suspend fun page(request: LibraryRequest): LibraryPage {
        if (!tube.hasSapCookieAuth()) return LibraryPage()
        val categories = if (request.likedSongsOnly) listOf("VLLM") else CATEGORIES
        val cursor = decode(request.continuation, request.likedSongsOnly, categories.size)
        val profile = client.get()
        tube.locale = YouTubeLocale(gl = request.country, hl = request.language)
        val snapshot = tube.sessionSnapshot()
        val result =
            try {
                val response =
                    tube.browse(
                        profile,
                        browseId = categories[cursor.section].takeIf { cursor.token == null },
                        continuation = cursor.token,
                        setLogin = true,
                    )
                check(response.status.isSuccess()) { "YouTube Music library failed with HTTP ${response.status.value}" }
                YouTubeCatalog.library(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LibraryPage(complete = false)
            }
        if (snapshot != tube.sessionSnapshot()) throw CancellationException("YouTube Music session changed")
        val next =
            result.continuation?.let { Cursor(cursor.section, it) }
                ?: (cursor.section + 1).takeIf { it < categories.size }?.let { Cursor(it, null) }
        return result.copy(
            items =
                if (request.likedSongsOnly) {
                    result.items.map { item ->
                        item.copy(track = item.track?.let { it.copy(extras = it.extras + ("likeStatus" to "LIKE")) })
                    }
                } else {
                    result.items
                },
            continuation = next?.let { encode(it, request.likedSongsOnly) },
        )
    }

    private data class Cursor(
        val section: Int,
        val token: String?,
    )

    private fun decode(
        value: String?,
        likedSongsOnly: Boolean,
        sectionCount: Int,
    ): Cursor {
        if (value == null) return Cursor(0, null)
        val prefix = if (likedSongsOnly) LIKES_PREFIX else PREFIX
        require(value.startsWith(prefix) && value.length <= 16_384)
        val root = Json.parseToJsonElement(value.removePrefix(prefix)) as? JsonObject ?: error("Invalid library continuation")
        val section = (root["section"] as? JsonPrimitive)?.intOrNull ?: error("Missing library section")
        val token = (root["token"] as? JsonPrimitive)?.contentOrNull
        require(section in 0 until sectionCount && (token == null || token.isNotBlank()))
        return Cursor(section, token)
    }

    private fun encode(
        cursor: Cursor,
        likedSongsOnly: Boolean,
    ): String =
        (if (likedSongsOnly) LIKES_PREFIX else PREFIX) +
            buildJsonObject {
                put("section", cursor.section)
                cursor.token?.let { put("token", it) }
            }

    private companion object {
        const val PREFIX = "ytm-library:"
        const val LIKES_PREFIX = "ytm-likes:"
        val CATEGORIES =
            listOf(
                "FEmusic_liked_videos",
                "FEmusic_liked_albums",
                "FEmusic_library_corpus_artists",
                "FEmusic_liked_playlists",
            )
    }
}
