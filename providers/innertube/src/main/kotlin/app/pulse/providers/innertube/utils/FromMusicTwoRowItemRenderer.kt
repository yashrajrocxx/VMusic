package app.pulse.providers.innertube.utils

import app.pulse.providers.innertube.Innertube
import app.pulse.providers.innertube.models.MusicTwoRowItemRenderer
import app.pulse.providers.innertube.models.NavigationEndpoint

fun Innertube.AlbumItem.Companion.from(renderer: MusicTwoRowItemRenderer) = Innertube.AlbumItem(
    info = renderer
        .title
        ?.runs
        ?.firstOrNull()
        ?.let(Innertube::Info),
    authors = null,
    year = renderer
        .subtitle
        ?.runs
        ?.lastOrNull()
        ?.text,
    thumbnail = renderer
        .thumbnailRenderer
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.firstOrNull()
).takeIf { it.info?.endpoint?.browseId != null }

fun Innertube.ArtistItem.Companion.from(renderer: MusicTwoRowItemRenderer) = Innertube.ArtistItem(
    info = renderer
        .title
        ?.runs
        ?.firstOrNull()
        ?.let(Innertube::Info),
    subscribersCountText = renderer
        .subtitle
        ?.runs
        ?.firstOrNull()
        ?.text,
    thumbnail = renderer
        .thumbnailRenderer
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.firstOrNull()
).takeIf { it.info?.endpoint?.browseId != null }

fun Innertube.PlaylistItem.Companion.from(renderer: MusicTwoRowItemRenderer) =
    Innertube.PlaylistItem(
        info = renderer
            .title
            ?.runs
            ?.firstOrNull()
            ?.let(Innertube::Info),
        channel = renderer
            .subtitle
            ?.runs
            ?.getOrNull(2)
            ?.let(Innertube::Info),
        songCount = renderer
            .subtitle
            ?.runs
            ?.getOrNull(4)
            ?.text
            ?.split(' ')
            ?.firstOrNull()
            ?.toIntOrNull(),
        thumbnail = renderer
            .thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.firstOrNull()
    ).takeIf { it.info?.endpoint?.browseId != null }

/**
 * Mood-page featured playlists (e.g. Bollywood Hitlist): the subtitle holds
 * artist names, not channel/count metadata, so those stay null instead of
 * parsing junk. Takes the largest thumbnail.
 */
fun Innertube.PlaylistItem.Companion.fromMoodPlaylist(renderer: MusicTwoRowItemRenderer) =
    Innertube.PlaylistItem(
        info = renderer
            .title
            ?.runs
            ?.firstOrNull()
            ?.let(Innertube::Info),
        channel = @Suppress("FilterIsInstanceResultIsAlwaysEmpty") renderer
            .subtitle
            ?.runs
            ?.map { Innertube.Info(name = it.text, endpoint = it.navigationEndpoint?.endpoint) }
            ?.filterIsInstance<Innertube.Info<NavigationEndpoint.Endpoint.Browse>>()
            ?.firstOrNull(),
        songCount = null,
        thumbnail = renderer
            .thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.lastOrNull()
    ).takeIf { it.info?.endpoint?.browseId != null }

/**
 * Watch-endpoint two-row cards (the "New music videos" shelf) as playable
 * songs. The canonical endpoint lives on the renderer itself; the title run
 * carries it on some layouts, so both are tried.
 */
fun Innertube.SongItem.Companion.from(renderer: MusicTwoRowItemRenderer) =
    Innertube.SongItem(
        info = (renderer.navigationEndpoint?.endpoint
            ?: renderer.title?.runs?.firstOrNull()?.navigationEndpoint?.endpoint)
            ?.let { endpoint ->
                if (endpoint is NavigationEndpoint.Endpoint.Watch) Innertube.Info(
                    name = renderer.title?.runs?.firstOrNull()?.text,
                    endpoint = endpoint
                ) else null
            },
        authors = @Suppress("FilterIsInstanceResultIsAlwaysEmpty") renderer
            .subtitle
            ?.runs
            ?.map { Innertube.Info(name = it.text, endpoint = it.navigationEndpoint?.endpoint) }
            ?.filterIsInstance<Innertube.Info<NavigationEndpoint.Endpoint.Browse>>()
            ?.takeIf(List<Any>::isNotEmpty),
        album = null,
        durationText = renderer
            .subtitle
            ?.runs
            ?.lastOrNull()
            ?.text
            ?.takeIf { text -> text.contains(':') && text.all { it.isDigit() || it == ':' } },
        explicit = false,
        thumbnail = renderer
            .thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.lastOrNull()
    ).takeIf { it.info?.endpoint?.videoId != null }
