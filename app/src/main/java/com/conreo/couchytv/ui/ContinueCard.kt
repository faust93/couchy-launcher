package com.conreo.couchytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.ContinueEntry

/**
 * A continue-watching card: 16:9 like every other home-screen card (the
 * poster is center-cropped at decode time), so the row lines up with the app
 * rows and the magnetic scroll's pivot math stays exact.
 *
 * Shows the poster when one arrived, the source app's tile otherwise; the
 * bottom scrim carries title + "S2 · E5 · Source", and a thin bar shows
 * playback progress when the publisher sent a duration.
 */
@Composable
fun ContinueCard(
    entry: ContinueEntry,
    poster: ImageBitmap?,
    sourceLabel: String,
    accent: Color,
    cardWidth: Dp = 190.dp,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit,
) {
    val season = entry.season
    val episode = entry.episode
    val episodeText = when {
        !season.isNullOrBlank() && !episode.isNullOrBlank() ->
            stringResource(R.string.cw_season_ep, season, episode)
        !episode.isNullOrBlank() -> stringResource(R.string.cw_episode_only, episode)
        else -> null
    }
    val subtitle = listOfNotNull(episodeText, sourceLabel.ifBlank { null })
        .joinToString(" · ")

    CardSurface(
        accent = accent,
        tile = entry.tile,
        cardWidth = cardWidth,
        isMoving = false, // content cards never enter move mode
        modifier = modifier,
        onLaunch = onLaunch,
        onLongPress = onLongPress,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            if (poster != null) {
                Image(
                    bitmap = poster,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    // Own render layer: the focus scale animation transforms the
                    // cached layer instead of re-sampling the bitmap.
                    modifier = Modifier.fillMaxSize().graphicsLayer { },
                )
            } else {
                // No artwork (yet): the same sheen tile as banner-less app cards,
                // so an unloaded row still reads as one visual family.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.14f),
                                )
                            )
                        )
                )
                if (showLabel) {
                    Column(
                        Modifier.fillMaxSize().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (poster != null && showLabel) {
                // Readability scrim: transparent → dark, text sits inside it.
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Playback progress — drawn last so the scrim never covers it.
            val progress = entry.progress
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(accent)
                    )
                }
            }
        }
    }
}
