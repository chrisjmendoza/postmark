package com.plusorminustwo.postmark.ui.contact

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.plusorminustwo.postmark.ui.thread.VideoPlayerDialog
import com.plusorminustwo.postmark.ui.thread.VideoThumbnailTile

/**
 * Full-screen gallery of EVERY image attachment exchanged in one thread, newest first —
 * opened by tapping the Photos section on [ContactDetailScreen]. Tapping a tile reuses
 * the same [ContactFullScreenViewer] the contact screen's own preview uses, rather than
 * standing up a second image-viewer implementation.
 *
 * @param onBack Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGalleryScreen(
    onBack: () -> Unit,
    viewModel: PhotoGalleryViewModel = hiltViewModel()
) {
    val photos by viewModel.photos.collectAsState()
    var fullScreenUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fullScreenUri?.let { uri ->
        ContactFullScreenViewer(uri = uri, onDismiss = { fullScreenUri = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (photos.isEmpty()) {
            EmptyGalleryMessage(padding = padding, text = "No photos yet")
        } else {
            MediaGalleryGrid(padding = padding) {
                items(photos, key = { "${it.messageId}_${it.attachmentIndex}" }) { attachment ->
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(attachment.uri))
                            // Grid tiles are ~1/3 screen width; 300px covers 2-3x density
                            // without decoding full-resolution bitmaps (cf. AttachmentThumbnail).
                            .size(300, 300)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { fullScreenUri = attachment.uri }
                    )
                }
            }
        }
    }
}

/**
 * Full-screen gallery of EVERY video attachment exchanged in one thread, newest first —
 * opened by tapping the Videos section on [ContactDetailScreen]. Tiles use the same
 * first-frame-still + play-badge treatment as the thread bubbles/grid ([VideoThumbnailTile]);
 * tapping one opens the existing [VideoPlayerDialog].
 *
 * @param onBack Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGalleryScreen(
    onBack: () -> Unit,
    viewModel: VideoGalleryViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsState()
    var playingUri by remember { mutableStateOf<String?>(null) }

    playingUri?.let { uri ->
        VideoPlayerDialog(uri = uri, onDismiss = { playingUri = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Videos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (videos.isEmpty()) {
            EmptyGalleryMessage(padding = padding, text = "No videos yet")
        } else {
            MediaGalleryGrid(padding = padding) {
                items(videos, key = { "${it.messageId}_${it.attachmentIndex}" }) { attachment ->
                    VideoThumbnailTile(
                        uri = attachment.uri,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { playingUri = attachment.uri }
                    )
                }
            }
        }
    }
}

/**
 * Shared 3-column grid scaffolding for [PhotoGalleryScreen] and [VideoGalleryScreen].
 *
 * [padding] is the Scaffold's own content padding (status bar below the TopAppBar, nav
 * bar at the bottom) passed straight through as [LazyVerticalGrid]'s `contentPadding`
 * (plus a flat 2dp so tiles don't touch the physical edges) — as contentPadding rather
 * than an outer `Modifier.padding`, the last row scrolls clear of the nav bar instead of
 * hiding behind it (cf. BlockedNumbersScreen).
 */
@Composable
private fun MediaGalleryGrid(
    padding: PaddingValues,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit
) {
    val layoutDirection = LocalLayoutDirection.current
    val gridPadding = PaddingValues(
        start = 2.dp + padding.calculateStartPadding(layoutDirection),
        top = 2.dp + padding.calculateTopPadding(),
        end = 2.dp + padding.calculateEndPadding(layoutDirection),
        bottom = 2.dp + padding.calculateBottomPadding()
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = gridPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

@Composable
private fun EmptyGalleryMessage(padding: PaddingValues, text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
