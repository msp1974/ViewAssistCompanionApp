package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.sendspin.SendspinArtwork
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.delay

// A pause normally ends the Sendspin stream, so the player lingers a while to let it be resumed.
private const val PAUSED_LINGER_MS = 20_000L
private const val PROGRESS_TICK_MS = 500L

/**
 * Sizes for every part of the player, derived from the width it is given so the artwork, buttons,
 * text and spacing all grow together on a large screen rather than leaving a small player in a
 * wide card.
 */
private class PlayerMetrics(width: Dp) {
    val padding = (width * 0.035f).coerceIn(10.dp, 24.dp)
    val artwork = (width * 0.2f).coerceIn(56.dp, 140.dp)
    val artworkRadius = artwork * 0.2f
    val cardRadius = (width * 0.06f).coerceIn(20.dp, 32.dp)
    val playButton = (width * 0.14f).coerceIn(44.dp, 88.dp)
    val skipButton = playButton * 0.8f
    val playIcon = playButton * 0.58f
    val skipIcon = skipButton * 0.6f
    val progressHeight = if (width < 360.dp) 3.dp else if (width < 520.dp) 4.dp else 6.dp
    val roomy = width >= 360.dp
    val large = width >= 520.dp
}

/**
 * Mini player for Sendspin audio: album art, then the track details (title, artist, and album when
 * known), then previous / play-pause / next controls, with a progress line along the bottom. Fills
 * the width it is given, scaling its contents to suit, in a Material 3 surface that slides up
 * while something is playing and away again once it has stopped.
 */
@Composable
fun NowPlayingOverlay(
    nowPlaying: SendspinNowPlaying,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(nowPlaying.active) }
    LaunchedEffect(nowPlaying.active) {
        if (nowPlaying.active) {
            visible = true
        } else {
            delay(PAUSED_LINGER_MS)
            visible = false
        }
    }

    // Keep the last track on screen while the player animates away, and while paused after the
    // server has already dropped its metadata.
    var shown by remember { mutableStateOf(nowPlaying) }
    shown = if (nowPlaying.hasContent) nowPlaying else shown.copy(active = false, playing = false)

    AnimatedVisibility(
        visible = visible && shown.hasContent,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        BoxWithConstraints {
            MiniPlayer(shown, PlayerMetrics(maxWidth), onCommand)
        }
    }
}

@Composable
private fun MiniPlayer(
    nowPlaying: SendspinNowPlaying,
    metrics: PlayerMetrics,
    onCommand: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(metrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(metrics.padding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(nowPlaying.artworkUrl, metrics)
                Spacer(Modifier.width(metrics.padding))
                Details(nowPlaying, metrics, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(metrics.padding / 2))
                Controls(nowPlaying, metrics, onCommand)
            }
            if (nowPlaying.durationMs > 0) {
                Spacer(Modifier.height(metrics.padding))
                Progress(nowPlaying, metrics)
            }
        }
    }
}

@Composable
private fun Details(nowPlaying: SendspinNowPlaying, metrics: PlayerMetrics, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    val titleStyle = when {
        metrics.large -> typography.titleLarge
        metrics.roomy -> typography.titleMedium
        else -> typography.titleSmall
    }
    val artistStyle = when {
        metrics.large -> typography.bodyLarge
        metrics.roomy -> typography.bodyMedium
        else -> typography.bodySmall
    }
    val albumStyle = when {
        metrics.large -> typography.bodyMedium
        metrics.roomy -> typography.bodySmall
        else -> typography.labelSmall
    }
    Column(modifier = modifier) {
        Text(
            text = nowPlaying.title.ifBlank { "Unknown track" },
            style = titleStyle,
            // Narrow players have little room, so let the title wrap rather than lose its end.
            maxLines = if (metrics.roomy) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (nowPlaying.artist.isNotBlank()) {
            Text(
                text = nowPlaying.artist,
                style = artistStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (nowPlaying.album.isNotBlank()) {
            Text(
                text = nowPlaying.album,
                style = albumStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Artwork(url: String?, metrics: PlayerMetrics) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = url?.let { SendspinArtwork.load(it) }
    }
    Box(
        modifier = Modifier
            .size(metrics.artwork)
            .clip(RoundedCornerShape(metrics.artworkRadius)),
        contentAlignment = Alignment.Center,
    ) {
        val art = bitmap
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // No artwork (yet): a tonal placeholder rather than an empty hole.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.matchParentSize(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(metrics.artwork * 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(nowPlaying: SendspinNowPlaying, metrics: PlayerMetrics) {
    val position by produceState(initialValue = nowPlaying.positionNow(), nowPlaying) {
        while (true) {
            value = nowPlaying.positionNow()
            delay(PROGRESS_TICK_MS)
        }
    }
    LinearProgressIndicator(
        progress = { (position.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.progressHeight)
            .clip(CircleShape),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
    )
}

@Composable
private fun Controls(nowPlaying: SendspinNowPlaying, metrics: PlayerMetrics, onCommand: (String) -> Unit) {
    // Only offer what the server will actually honour right now.
    val toggle = if (nowPlaying.playing) "pause" else "play"
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onCommand("previous") },
            enabled = "previous" in nowPlaying.commands,
            modifier = Modifier.size(metrics.skipButton),
        ) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "Previous track",
                modifier = Modifier.size(metrics.skipIcon),
            )
        }
        FilledIconButton(
            onClick = { onCommand(toggle) },
            enabled = toggle in nowPlaying.commands,
            modifier = Modifier.size(metrics.playButton),
        ) {
            Icon(
                imageVector = if (nowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (nowPlaying.playing) "Pause" else "Play",
                modifier = Modifier.size(metrics.playIcon),
            )
        }
        IconButton(
            onClick = { onCommand("next") },
            enabled = "next" in nowPlaying.commands,
            modifier = Modifier.size(metrics.skipButton),
        ) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Next track",
                modifier = Modifier.size(metrics.skipIcon),
            )
        }
    }
}
