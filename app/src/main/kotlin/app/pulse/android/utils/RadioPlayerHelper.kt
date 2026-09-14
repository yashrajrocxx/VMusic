package app.pulse.android.utils

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.pulse.android.models.RadioStation
import app.pulse.android.service.PlayerService

fun RadioStation.asRadioMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId("radio:$id")
        .setUri(streamUrl.toUri())
        .setCustomCacheKey("radio:$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(if (frequency.isNotBlank()) "$city • $frequency" else "$city • $language")
                .setAlbumTitle("Live Radio")
                .setArtworkUri(logoUrl?.toUri())
                .build()
        )
        .build()
}

fun PlayerService.Binder.playRadio(station: RadioStation) {
    player.forcePlay(station.asRadioMediaItem())
}
