package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.features.music.MusicIdentifyUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The result card for "name that song".
 *
 * Two deliberate differences from the player's other overlays:
 *
 *  - **It does not dim the video and does not dismiss on an outside click.**
 *    Identification takes 7–10 s and the viewer is still watching; covering the
 *    picture to tell them a song's name would be a worse trade than the card
 *    itself. Only the close button (or [onDismiss] from the trigger) removes it.
 *  - **It never pauses playback.** The server is fetching the same stream the
 *    player is reading, so pausing would change which moment is sampled and
 *    could stall the extractor's read.
 *
 * The card is bottom-right so it sits clear of the transport controls and the
 * player's bottom-left metadata block.
 */
@Composable
internal fun MusicIdentifyOverlay(
    state: MusicIdentifyUiState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is MusicIdentifyUiState.Idle,
        enter = fadeIn() + slideInHorizontally { it / 4 },
        exit = fadeOut() + slideOutHorizontally { it / 4 },
        modifier = modifier,
    ) {
        // An empty Box does not intercept pointer events, so the video controls
        // under it stay live everywhere the card is not drawn.
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .widthIn(min = 280.dp, max = 380.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.86f),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (state) {
                        is MusicIdentifyUiState.Found ->
                            Artwork(state.match.artworkUrl, state.match.title)

                        MusicIdentifyUiState.Identifying ->
                            Box(
                                modifier = Modifier.size(52.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color.White.copy(alpha = 0.85f),
                                    strokeWidth = 2.dp,
                                )
                            }

                        else -> Spacer(Modifier.width(0.dp))
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        when (state) {
                            MusicIdentifyUiState.Identifying -> {
                                Text(
                                    text = stringResource(Res.string.music_identify_listening),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                            }

                            is MusicIdentifyUiState.Found -> {
                                Text(
                                    text = state.match.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val secondary = listOfNotNull(
                                    state.match.artist,
                                    state.match.album?.takeIf { it != state.match.artist },
                                ).joinToString(" • ")
                                if (secondary.isNotBlank()) {
                                    Text(
                                        text = secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.saved) {
                                        Text(
                                            text = stringResource(Res.string.music_identify_saved),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.6f),
                                        )
                                    } else {
                                        TextButton(
                                            onClick = onSave,
                                            enabled = !state.saving,
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    if (state.saving) {
                                                        Res.string.music_identify_saving
                                                    } else {
                                                        Res.string.music_identify_save
                                                    },
                                                ),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = Color.White,
                                            )
                                        }
                                    }
                                }
                            }

                            is MusicIdentifyUiState.Message -> {
                                Text(
                                    text = when (state.kind) {
                                        MusicIdentifyUiState.Message.Kind.NO_MATCH ->
                                            stringResource(Res.string.music_identify_no_match)
                                        MusicIdentifyUiState.Message.Kind.NO_STREAM ->
                                            stringResource(Res.string.music_identify_no_stream)
                                        MusicIdentifyUiState.Message.Kind.NO_CONTEXT ->
                                            stringResource(Res.string.music_identify_no_context)
                                        MusicIdentifyUiState.Message.Kind.RATE_LIMITED ->
                                            stringResource(Res.string.music_identify_rate_limited)
                                        MusicIdentifyUiState.Message.Kind.UNAVAILABLE ->
                                            stringResource(Res.string.music_identify_unavailable)
                                        MusicIdentifyUiState.Message.Kind.FAILED ->
                                            stringResource(Res.string.music_identify_failed)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }

                            MusicIdentifyUiState.Idle -> Unit
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.music_identify_close),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, title: String) {
    val shape = RoundedCornerShape(10.dp)
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.12f)),
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(shape),
        )
    }
}
