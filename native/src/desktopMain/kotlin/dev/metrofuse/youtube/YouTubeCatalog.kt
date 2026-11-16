package dev.metrofuse.youtube

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.RecommendationResult
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Adapted from MetroList's YouTubeItemParser and YouTubeSearchOperations (GPL-3.0).
internal object YouTubeCatalog {
    private val itemRenderers =
        setOf(
            "musicResponsiveListItemRenderer",
            "musicTwoRowItemRenderer",
            "musicMultiRowListItemRenderer",
            "musicCardShelfRenderer",
        )
    private val headerRenderers =
        setOf("musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
    private val shelfNames =
        setOf(
            "musicCarouselShelfRenderer",
            "musicShelfRenderer",
            "musicImmersiveCarouselShelfRenderer",
            "gridRenderer",
            "musicGridShelfRenderer",
            "musicCardShelfRenderer",
        )
    private val shelfHeaderNames =
        setOf(
            "musicCarouselShelfBasicHeaderRenderer",
            "musicCarouselShelfHeaderRenderer",
            "musicImmersiveCarouselShelfBasicHeaderRenderer",
            "musicImmersiveCarouselShelfHeaderRenderer",
            "gridHeaderRenderer",
            "musicGridShelfBasicHeaderRenderer",
        )

    fun home(root: JsonObject): List<HomeShelf> {
        var remaining = 1000
        return homeRoots(root)
            .flatMap(::shelves)
            .mapNotNull { shelf ->
                if (remaining == 0) return@mapNotNull null
                val header = shelf["header"] ?: shelf
                if (renderers(header, setOf("browseEndpoint")).any { it.string("browseId")?.startsWith("FEmusic_library") == true }) {
                    return@mapNotNull null
                }
                val title =
                    text(shelf["title"]) ?: renderers(header, shelfHeaderNames)
                        .firstNotNullOfOrNull { text(it["title"]) } ?: return@mapNotNull null
                val items =
                    (sequenceOf(shelf) + renderers(shelf["contents"] ?: shelf["items"], itemRenderers))
                        .mapNotNull(::homeItem)
                        .distinctBy { it.track?.id ?: it.album?.id ?: it.artist?.id ?: it.playlist?.id }
                        .take(minOf(100, remaining))
                        .toList()
                items.takeIf { it.isNotEmpty() }?.let {
                    remaining -= it.size
                    HomeShelf(
                        title,
                        it,
                        if (shelf.string("numItemsPerColumn")?.toIntOrNull()?.let { it > 1 } ==
                            true
                        ) {
                            HomeLayout.ROWS_GRID
                        } else {
                            HomeLayout.CARDS
                        },
                    )
                }
            }.take(50)
            .toList()
    }

    private fun homeItem(row: JsonObject): HomeItem? =
        collection(row)?.let {
            when (it.kind) {
                Kind.ALBUM -> HomeItem(album = it.album())
                Kind.ARTIST -> HomeItem(artist = it.artist())
                Kind.PLAYLIST -> HomeItem(playlist = it.playlist())
            }
        } ?: track(row)?.let { HomeItem(track = it) }

    fun library(root: JsonObject): LibraryPage {
        check(root["contents"] != null || root["continuationContents"] != null || responseActions(root).any()) {
            "Missing library contents"
        }
        check(root.obj("responseContext").obj("mainAppWebResponseContext").string("loggedOut") != "true") {
            "YouTube Music library session expired"
        }
        val items =
            homeRoots(root)
                .flatMap { renderers(it, itemRenderers) }
                .mapNotNull(::homeItem)
                .distinctBy {
                    it.track?.let { "track:${it.id}" } ?: it.album?.let { "album:${it.id}" } ?: it.artist?.let { "artist:${it.id}" }
                        ?: "playlist:${it.playlist?.id}"
                }.toList()
        require(items.size <= 1000)
        val tokens =
            homeRoots(root)
                .flatMap { renderers(it, libraryContainers) }
                .mapNotNull(::continuation)
                .toList() +
                responseActions(root).mapNotNull { inlineContinuation(it["continuationItems"]) }.toList()
        val distinct = tokens.distinct()
        return LibraryPage(items, distinct.singleOrNull(), complete = distinct.size <= 1)
    }

    private val libraryContainers =
        setOf(
            "musicShelfRenderer",
            "musicShelfContinuation",
            "musicPlaylistShelfRenderer",
            "musicPlaylistShelfContinuation",
            "gridRenderer",
            "gridContinuation",
            "sectionListRenderer",
            "sectionListContinuation",
        )

    fun homeContinuation(root: JsonObject): String? {
        homeRoots(root).flatMap { renderers(it, setOf("sectionListRenderer", "sectionListContinuation")) }.forEach { section ->
            continuation(section)?.let { return it }
        }
        return responseActions(root).firstNotNullOfOrNull { inlineContinuation(it["continuationItems"]) }
    }

    private fun homeRoots(root: JsonObject): Sequence<JsonElement> =
        sequenceOf(root["contents"], root["continuationContents"]).filterNotNull() +
            responseActions(root).mapNotNull { it["continuationItems"] }

    private fun responseActions(root: JsonObject): Sequence<JsonObject> =
        sequenceOf(root["onResponseReceivedActions"], root["onResponseReceivedEndpoints"], root["actions"])
            .flatMap { renderers(it, setOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")) }

    private fun shelves(root: JsonElement): Sequence<JsonObject> =
        sequence {
            val pending = ArrayDeque<JsonElement>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                when (val current = pending.removeLast()) {
                    is JsonObject -> {
                        current.entries.toList().asReversed().forEach { (name, value) ->
                            if (name in shelfNames && value is JsonObject) yield(value) else pending.add(value)
                        }
                    }

                    is JsonArray -> {
                        current.asReversed().forEach(pending::addLast)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

    fun radio(root: JsonObject): RecommendationResult {
        val panel =
            root.obj("continuationContents").obj("playlistPanelContinuation")
                ?: renderers(root["contents"], setOf("musicQueueRenderer")).firstOrNull().obj("content").obj("playlistPanelRenderer")
                ?: return RecommendationResult(emptyList())
        val tracks =
            (panel["contents"] as? JsonArray)
                .orEmpty()
                .mapNotNull { item ->
                    val row = item as? JsonObject ?: return@mapNotNull null
                    val renderer =
                        row.obj("playlistPanelVideoRenderer")
                            ?: row.obj("playlistPanelVideoWrapperRenderer").obj("primaryRenderer").obj("playlistPanelVideoRenderer")
                            ?: return@mapNotNull null
                    val id = renderer.string("videoId")?.takeIf(VIDEO_ID::matches) ?: return@mapNotNull null
                    val name = text(renderer["title"]) ?: return@mapNotNull null
                    val metadata = runs(renderer["longBylineText"]).ifEmpty { runs(renderer["shortBylineText"]) }
                    val album = metadata.firstOrNull { kind(endpoint(it).obj("browseEndpoint")) == Kind.ALBUM }
                    Track(
                        id,
                        name,
                        credit(metadata).ifBlank { text(renderer["shortBylineText"]).orEmpty() },
                        album.string("text").orEmpty(),
                        durationMillis(text(renderer["lengthText"])) ?: 0,
                        artwork(renderer),
                        trackExtras(id, album, renderer),
                    )
                }.distinctBy(Track::id)
                .take(100)
        return RecommendationResult(tracks, continuation(panel))
    }

    private fun continuation(container: JsonObject): String? =
        (container["continuations"] as? JsonArray).orEmpty().firstNotNullOfOrNull { entry ->
            val row = entry as? JsonObject
            (row.obj("nextRadioContinuationData") ?: row.obj("nextContinuationData"))
                .string("continuation")
                ?.takeIf { it.isNotBlank() && it.length <= 16_384 }
        } ?: inlineContinuation(container["contents"])

    private fun inlineContinuation(contents: JsonElement?): String? =
        (contents as? JsonArray).orEmpty().firstNotNullOfOrNull { entry ->
            (entry as? JsonObject)
                .obj("continuationItemRenderer")
                .obj("continuationEndpoint")
                .obj("continuationCommand")
                .string("token")
                ?.takeIf { it.isNotBlank() && it.length <= 16_384 }
        }

    fun search(
        root: JsonObject,
        limit: Int,
    ): SearchResult {
        val rows = renderers(root["contents"], itemRenderers).toList()
        val tracks = rows.mapNotNull(::track).distinctBy(Track::id).take(limit)
        val collections = rows.mapNotNull(::collection).distinctBy(Collection::id)
        return SearchResult(
            tracks = tracks,
            albums = collections.filter { it.kind == Kind.ALBUM }.take(limit).map(Collection::album),
            artists = collections.filter { it.kind == Kind.ARTIST }.take(limit).map(Collection::artist),
            playlists = collections.filter { it.kind == Kind.PLAYLIST }.take(limit).map(Collection::playlist),
        )
    }

    fun album(
        root: JsonObject,
        id: String,
    ): CatalogAlbum? = page(root, id, Kind.ALBUM)?.album()

    fun artist(
        root: JsonObject,
        id: String,
    ): CatalogArtist? = page(root, id, Kind.ARTIST)?.artist()

    fun playlist(
        root: JsonObject,
        id: String,
    ): CatalogPlaylist? = page(root, id, Kind.PLAYLIST)?.playlist()

    private fun page(
        root: JsonObject,
        id: String,
        kind: Kind,
    ): Collection? {
        val header =
            sequenceOf(root["header"], root["contents"])
                .flatMap { renderers(it, headerRenderers) }
                .firstOrNull { text(it["title"]) != null } ?: return null
        val title = text(header["title"]) ?: return null
        val rows = renderers(root["contents"], itemRenderers).toList()
        return Collection(
            id,
            title,
            kind,
            credit(runs(header["straplineTextOne"]) + runs(header["subtitle"])),
            artwork(header),
            rows.mapNotNull(::track).let { if (kind == Kind.PLAYLIST) it else it.distinctBy(Track::id) },
            if (kind ==
                Kind.ARTIST
            ) {
                rows
                    .mapNotNull(::collection)
                    .filter { it.kind == Kind.ALBUM }
                    .distinctBy(Collection::id)
                    .map(Collection::album)
            } else {
                emptyList()
            },
        )
    }

    private fun track(row: JsonObject): Track? {
        val watch = endpoint(row).obj("watchEndpoint") ?: endpoint(row).obj("watchPlaylistEndpoint")
        val videoId =
            row.obj("playlistItemData").string("videoId") ?: watch.string("videoId")
                ?: renderers(row["overlay"], setOf("watchEndpoint")).firstOrNull().string("videoId")
                ?: column(row, "flexColumns", 0)?.let(::runs)?.firstNotNullOfOrNull { endpoint(it).obj("watchEndpoint").string("videoId") }
                ?: return null
        if (!VIDEO_ID.matches(videoId) || collection(row) != null) return null
        val title = title(row) ?: return null
        val metadata = metadata(row)
        val album = metadata.firstOrNull { kind(endpoint(it).obj("browseEndpoint")) == Kind.ALBUM }
        return Track(
            id = videoId,
            title = title,
            artist = credit(metadata),
            album = album.string("text").orEmpty(),
            duration =
                (sequenceOf(text(column(row, "fixedColumns", 0))) + metadata.asSequence().map { it.string("text") })
                    .mapNotNull(::durationMillis)
                    .firstOrNull() ?: 0,
            artwork = artwork(row),
            extras = trackExtras(videoId, album, row),
        )
    }

    private fun trackExtras(
        id: String,
        album: JsonObject?,
        row: JsonObject,
    ): Map<String, String> =
        buildMap {
            put("youtube", id)
            renderers(row["menu"], setOf("likeButtonRenderer"))
                .firstOrNull { it.obj("target").string("videoId").let { target -> target == null || target == id } }
                ?.string("likeStatus")
                ?.takeIf { it in setOf("LIKE", "DISLIKE", "INDIFFERENT") }
                ?.let { put("likeStatus", it) }
            album?.let { endpoint(it).obj("browseEndpoint").string("browseId") }?.let { put("albumId", it) }
            val type =
                row.string("musicVideoType") ?: endpoint(row)
                    .obj("watchEndpoint")
                    .obj("watchEndpointMusicSupportedConfigs")
                    .obj("watchEndpointMusicConfig")
                    .string("musicVideoType")
            type?.let { put("musicVideoType", it) }
            if (renderers(row["badges"], setOf("musicInlineBadgeRenderer"))
                    .any { it.obj("icon").string("iconType") == "MUSIC_EXPLICIT_BADGE" }
            ) {
                put("explicit", "true")
            }
        }

    private fun collection(row: JsonObject): Collection? {
        val navigation = endpoint(row)
        val browse = navigation.obj("browseEndpoint")
        val watch = navigation.obj("watchPlaylistEndpoint") ?: navigation.obj("watchEndpoint")?.takeIf { it.string("videoId") == null }
        val id = browse.string("browseId") ?: watch.string("playlistId") ?: return null
        if (id.startsWith("FEmusic_library")) return null
        val kind = kind(browse) ?: watch.string("playlistId")?.let { Kind.PLAYLIST } ?: return null
        return Collection(id, title(row) ?: return null, kind, credit(metadata(row)), artwork(row))
    }

    private fun title(row: JsonObject) = text(row["title"]) ?: text(column(row, "flexColumns", 0)) ?: text(row["secondTitle"])

    private fun metadata(row: JsonObject) =
        sequenceOf(row["subtitle"], row["secondSubtitle"], column(row, "flexColumns", 1))
            .map(::runs)
            .firstOrNull(List<JsonObject>::isNotEmpty)
            .orEmpty()

    private fun credit(runs: List<JsonObject>): String =
        runs
            .mapNotNull { run ->
                run.string("text")?.trim()?.takeIf { kind(endpoint(run).obj("browseEndpoint")) == Kind.ARTIST }
            }.distinct()
            .joinToString(", ")

    private fun artwork(row: JsonObject): String =
        (
            sequenceOf(row.obj("thumbnail")) +
                sequenceOf(row["thumbnail"], row["thumbnailRenderer"], row["foregroundThumbnail"])
                    .flatMap { renderers(it, setOf("musicThumbnailRenderer", "croppedSquareThumbnailRenderer", "thumbnail")) }
        ).filterNotNull()
            .flatMap { ((it["thumbnails"] as? JsonArray).orEmpty()).asSequence() }
            .mapNotNull { (it as? JsonObject).string("url") }
            .lastOrNull { it.startsWith("https://") }
            .orEmpty()

    private fun durationMillis(value: String?): Long? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size !in 2..3 || parts.any { !it.matches(Regex("[0-9]{1,3}")) }) return null
        val values = parts.map(String::toLong)
        if (values.drop(1).any { it >= 60 }) return null
        return values.fold(0L) { seconds, part -> seconds * 60 + part } * 1000
    }

    private fun endpoint(row: JsonObject): JsonObject? =
        row.obj("navigationEndpoint") ?: row.obj("onTap") ?: row.obj("playNavigationEndpoint")

    private fun column(
        row: JsonObject,
        columns: String,
        index: Int,
    ): JsonElement? =
        ((row[columns] as? JsonArray)?.getOrNull(index) as? JsonObject)
            ?.obj(
                if (columns ==
                    "flexColumns"
                ) {
                    "musicResponsiveListItemFlexColumnRenderer"
                } else {
                    "musicResponsiveListItemFixedColumnRenderer"
                },
            )?.get("text")

    private fun rendersPlaylist(id: String) = id.startsWith("VL") || id.startsWith("PL") || id.startsWith("OLAK")

    private fun kind(browse: JsonObject?): Kind? {
        val type = browse.obj("browseEndpointContextSupportedConfigs").obj("browseEndpointContextMusicConfig").string("pageType")
        val id = browse.string("browseId") ?: return null
        return when {
            type == "MUSIC_PAGE_TYPE_ALBUM" || id.startsWith("MPRE") -> Kind.ALBUM
            type == "MUSIC_PAGE_TYPE_ARTIST" || id.startsWith("UC") -> Kind.ARTIST
            type == "MUSIC_PAGE_TYPE_PLAYLIST" || rendersPlaylist(id) -> Kind.PLAYLIST
            else -> null
        }
    }

    internal fun text(element: JsonElement?): String? =
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element.string("simpleText") ?: runs(element).mapNotNull { it.string("text") }.joinToString("")
            else -> null
        }?.trim()?.takeIf(String::isNotBlank)

    private fun runs(element: JsonElement?): List<JsonObject> =
        (((element as? JsonObject)?.get("runs")) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    internal fun renderers(
        root: JsonElement?,
        names: Set<String>,
    ): Sequence<JsonObject> =
        sequence {
            val pending = ArrayDeque<JsonElement>()
            root?.let(pending::addLast)
            while (pending.isNotEmpty()) {
                when (val current = pending.removeLast()) {
                    is JsonObject -> {
                        current.forEach { (name, value) -> if (name in names && value is JsonObject) yield(value) }
                        current.values
                            .toList()
                            .asReversed()
                            .forEach(pending::addLast)
                    }

                    is JsonArray -> {
                        current.asReversed().forEach(pending::addLast)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private enum class Kind { ALBUM, ARTIST, PLAYLIST }

    private data class Collection(
        val id: String,
        val title: String,
        val kind: Kind,
        val credit: String,
        val artwork: String,
        val tracks: List<Track> = emptyList(),
        val albums: List<CatalogAlbum> = emptyList(),
    ) {
        fun album() = CatalogAlbum(id, title, credit, artwork, tracks)

        fun artist() = CatalogArtist(id, title, artwork, tracks, albums)

        fun playlist() = CatalogPlaylist(id, title, credit, artwork, tracks)
    }
}

internal val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
