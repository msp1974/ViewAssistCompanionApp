package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.sendspin.SendspinArtwork
import com.msp1974.vacompanion.sendspin.SendspinColors
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.delay
import java.util.Locale

// A pause normally ends the Sendspin stream, so the player lingers a while to let it be resumed.
private const val PAUSED_LINGER_MS = 20_000L
private const val PROGRESS_TICK_MS = 500L
private const val COLOR_FADE_MS = 600

// The controls spread across the player's width, but not further apart than this.
private val CONTROLS_MAX_WIDTH = 520.dp

/**
 * Sizes for every part of the player, derived from the width it is given so the artwork, buttons,
 * text and spacing all grow together on a large screen rather than leaving a small player in a
 * wide card.
 */
private class PlayerMetrics(width: Dp) {
    val padding = (width * 0.03f).coerceIn(14.dp, 28.dp)
    val artwork = (width * 0.17f).coerceIn(80.dp, 168.dp)
    val artworkRadius = artwork * 0.14f
    val cardRadius = (width * 0.04f).coerceIn(24.dp, 36.dp)
    val playButton = (width * 0.075f).coerceIn(52.dp, 76.dp)
    val progressHeight = if (width < 420.dp) 4.dp else if (width < 640.dp) 5.dp else 6.dp
    val roomy = width >= 420.dp
    val large = width >= 640.dp
}

/** The colours the player is drawn in. */
private class PlayerColors(
    val container: Color,
    val content: Color,
    val secondary: Color,
    /** Fill of the play button, the progress bar and a switched-on shuffle/repeat. */
    val fill: Color,
    /** Content drawn on [fill]. */
    val onFill: Color,
    val track: Color,
)

/**
 * The player's colours: the track's palette from the Sendspin server when it sent one, for the
 * app's current light or dark theme, otherwise the app's own Material colours. The server
 * guarantees its background and foreground colours contrast, so everything on the card is drawn in
 * the foreground colour and nothing depends on the palette's other colours.
 */
@Composable
private fun playerColors(palette: SendspinColors): PlayerColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val background = (if (dark) palette.backgroundDark else palette.backgroundLight).takeIf { it != 0 }
    val foreground = (if (dark) palette.onDark else palette.onLight).takeIf { it != 0 }

    val target = if (background != null) {
        val container = Color(background)
        // Black or white if the server gave a background without a foreground.
        val content = foreground?.let { Color(it) }
            ?: if (container.luminance() > 0.5f) Color.Black else Color.White
        PlayerColors(
            container = container,
            content = content,
            secondary = content.copy(alpha = 0.72f),
            fill = content,
            onFill = container,
            track = content.copy(alpha = 0.24f),
        )
    } else {
        PlayerColors(
            container = scheme.surfaceContainerHigh,
            content = scheme.onSurface,
            secondary = scheme.onSurfaceVariant,
            fill = scheme.primary,
            onFill = scheme.onPrimary,
            track = scheme.surfaceContainerHighest,
        )
    }

    // The palette changes with every track, so fade to it rather than jumping.
    val spec = tween<Color>(COLOR_FADE_MS)
    return PlayerColors(
        container = animateColorAsState(target.container, spec, label = "container").value,
        content = animateColorAsState(target.content, spec, label = "content").value,
        secondary = animateColorAsState(target.secondary, spec, label = "secondary").value,
        fill = animateColorAsState(target.fill, spec, label = "fill").value,
        onFill = animateColorAsState(target.onFill, spec, label = "onFill").value,
        track = animateColorAsState(target.track, spec, label = "track").value,
    )
}

/**
 * Mini player for Sendspin audio: album art beside the track details, a progress line and the
 * playback controls (shuffle, previous, play/pause, next, repeat), in a Material 3 surface that
 * slides up while something is playing and away again once it has stopped. Fills the width it is
 * given, scaling its contents to suit, and takes its background from the track's colours when the
 * server provides them.
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
    val colors = playerColors(nowPlaying.colors)
    Surface(
        shape = RoundedCornerShape(metrics.cardRadius),
        color = colors.container,
        contentColor = colors.content,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(metrics.padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(nowPlaying.artworkUrl, metrics)
            Spacer(Modifier.width(metrics.padding))
            Column(modifier = Modifier.weight(1f)) {
                Details(nowPlaying, metrics, colors)
                if (nowPlaying.durationMs > 0) {
                    Spacer(Modifier.height(metrics.padding * 0.6f))
                    Progress(nowPlaying, metrics, colors)
                }
                Spacer(Modifier.height(metrics.padding * 0.5f))
                Controls(nowPlaying, metrics, colors, onCommand)
            }
        }
    }
}

@Composable
private fun Details(nowPlaying: SendspinNowPlaying, metrics: PlayerMetrics, colors: PlayerColors) {
    val typography = MaterialTheme.typography
    val titleStyle = when {
        metrics.large -> typography.headlineSmall
        metrics.roomy -> typography.titleLarge
        else -> typography.titleMedium
    }
    val artistStyle = when {
        metrics.large -> typography.titleMedium
        metrics.roomy -> typography.bodyLarge
        else -> typography.bodyMedium
    }
    val albumStyle = when {
        metrics.roomy -> typography.bodyMedium
        else -> typography.bodySmall
    }
    Column {
        Text(
            text = nowPlaying.title.ifBlank { "Unknown track" },
            style = titleStyle,
            color = colors.content,
            // Narrow players have little room, so let the title wrap rather than lose its end.
            maxLines = if (metrics.roomy) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (nowPlaying.artist.isNotBlank()) {
            Text(
                text = nowPlaying.artist,
                style = artistStyle,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (nowPlaying.album.isNotBlank()) {
            Text(
                text = nowPlaying.album,
                style = albumStyle,
                color = colors.secondary,
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
private fun Progress(nowPlaying: SendspinNowPlaying, metrics: PlayerMetrics, colors: PlayerColors) {
    val position by produceState(initialValue = nowPlaying.positionNow(), nowPlaying) {
        while (true) {
            value = nowPlaying.positionNow()
            delay(PROGRESS_TICK_MS)
        }
    }
    val labelStyle = MaterialTheme.typography.labelMedium
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = labelStyle, color = colors.secondary)
        LinearProgressIndicator(
            progress = { (position.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(metrics.progressHeight)
                .clip(CircleShape),
            color = colors.fill,
            trackColor = colors.track,
            drawStopIndicator = {},
        )
        Text(formatTime(nowPlaying.durationMs), style = labelStyle, color = colors.secondary)
    }
}

/**
 * Shuffle, previous, play/pause, next and repeat, spread evenly across the available width so there
 * is room between them. Shuffle and repeat are left out when the server offers neither.
 */
@Composable
private fun Controls(
    nowPlaying: SendspinNowPlaying,
    metrics: PlayerMetrics,
    colors: PlayerColors,
    onCommand: (String) -> Unit,
) {
    val commands = nowPlaying.commands
    val shuffleCommand = if (nowPlaying.shuffle) "unshuffle" else "shuffle"
    val repeatCommand = nextRepeatCommand(nowPlaying)
    val showShuffle = "shuffle" in commands || "unshuffle" in commands
    val showRepeat = commands.any { it.startsWith("repeat_") }
    val playToggle = if (nowPlaying.playing) "pause" else "play"

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Buttons shrink to fit when there is little room, so five of them never run out of width.
        val rowWidth = minOf(maxWidth, CONTROLS_MAX_WIDTH)
        val play = minOf(metrics.playButton, rowWidth * 0.2f)
        val skip = play * 0.78f
        val toggle = play * 0.68f

        Row(
            modifier = Modifier.width(rowWidth),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showShuffle) {
                ToggleButton(
                    icon = Icons.Rounded.Shuffle,
                    description = if (nowPlaying.shuffle) "Shuffle on" else "Shuffle off",
                    on = nowPlaying.shuffle,
                    enabled = shuffleCommand in commands,
                    size = toggle,
                    colors = colors,
                    onClick = { onCommand(shuffleCommand) },
                )
            }
            SkipButton(Icons.Rounded.SkipPrevious, "Previous track", "previous" in commands, skip, colors) {
                onCommand("previous")
            }
            FilledIconButton(
                onClick = { onCommand(playToggle) },
                enabled = playToggle in commands,
                modifier = Modifier.size(play),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.fill,
                    contentColor = colors.onFill,
                    disabledContainerColor = colors.fill.copy(alpha = 0.3f),
                    disabledContentColor = colors.onFill.copy(alpha = 0.6f),
                ),
            ) {
                Icon(
                    imageVector = if (nowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (nowPlaying.playing) "Pause" else "Play",
                    modifier = Modifier.size(play * 0.55f),
                )
            }
            SkipButton(Icons.Rounded.SkipNext, "Next track", "next" in commands, skip, colors) {
                onCommand("next")
            }
            if (showRepeat) {
                ToggleButton(
                    icon = if (nowPlaying.repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    description = when (nowPlaying.repeat) {
                        "one" -> "Repeat track"
                        "all" -> "Repeat all"
                        else -> "Repeat off"
                    },
                    on = nowPlaying.repeat != "off",
                    enabled = repeatCommand != null,
                    size = toggle,
                    colors = colors,
                    onClick = { repeatCommand?.let(onCommand) },
                )
            }
        }
    }
}

/** The repeat command that comes next in the off, all, one cycle, skipping modes the server
 *  doesn't offer; null if it offers none. */
private fun nextRepeatCommand(nowPlaying: SendspinNowPlaying): String? {
    val cycle = listOf("off", "all", "one")
    val start = cycle.indexOf(nowPlaying.repeat).coerceAtLeast(0)
    return (1..cycle.size)
        .map { cycle[(start + it) % cycle.size] }
        .map { "repeat_$it" }
        .firstOrNull { it in nowPlaying.commands }
}

@Composable
private fun SkipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    size: Dp,
    colors: PlayerColors,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colors.content,
            disabledContentColor = colors.content.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.6f))
    }
}

/** Shuffle and repeat: marked with a soft highlight and the accent colour while switched on. */
@Composable
private fun ToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    on: Boolean,
    enabled: Boolean,
    size: Dp,
    colors: PlayerColors,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (on) colors.fill.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (on) colors.fill else colors.secondary,
            disabledContentColor = colors.content.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.6f))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
