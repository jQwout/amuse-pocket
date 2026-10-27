package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
//
// The image card: a lesson illustration or a picture in a chat, with the light backing for SVG diagrams
// and the "Draw it for me" button for lesson images.

import com.aiopenpocket.ui.chat.DrivenUiCard
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aiopenpocket.i18n.tr

// A light backing under lesson images. The SVG diagrams are drawn "for a light background" (gray captions
// #9aa0a6 + light text on colored fills). After the glass redesign the assistant bubble became
// transparent over the dark aurora — and transparent SVGs "disappeared". A fixed light background under
// the image brings back their readability in any theme (the theme is not applied to SVGs, see
// lesson-format.md).
private val LessonImageBacking = Color(0xFFF4F4F7)

/**
 * An image/diagram in a lesson step. The source is a bundled asset (tried FIRST so it works offline), with
 * a fallback to the network URL if there is no asset or it failed to load. SVG and raster are decoded by
 * the app's shared Coil singleton (SvgDecoder is plugged in in [com.aiopenpocket.AiOpenPocketApp.newImageLoader]).
 * This used to build ITS OWN ImageLoader per card instance — its cache died with the feed item, and when
 * the bubble came back into the viewport the image loaded again (an empty bubble → a jump).
 */
@Composable
fun ImageCardView(
    card: DrivenUiCard.ImageCard,
    personalizing: Boolean = false,
    // null — no button. Not a Boolean flag next to the callback: "show the button with no action" does not exist.
    onPersonalize: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val assetData = card.asset.takeIf { it.isNotBlank() }?.let { "file:///android_asset/$it" }
    val urlData = card.url.takeIf { it.isNotBlank() }
    // The asset first; on a load error we switch to the URL (once).
    var useFallback by remember(card) { mutableStateOf(assetData == null) }
    val data = if (!useFallback) (assetData ?: urlData) else urlData

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (data == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                Text(
                    text = tr("Нет изображения", "No image"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(data).crossfade(true).build(),
                contentDescription = card.caption.ifBlank { null },
                // A fixed aspect: the bubble's height is known BEFORE the image loads, so the feed does not "swell"
                // and jump when the image arrives (previously FillWidth without a height: the bubble was
                // born collapsed). Fit — schemes/diagrams are not cropped; letterboxing at the edges is
                // possible.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    // The light backing: transparent SVGs were drawn "for a light background", and after the glass
                    // redesign the bubble became transparent over the dark aurora — without it the diagram vanished.
                    .background(LessonImageBacking),
                onError = {
                    // The asset did not load — try the network URL if it is set and not tried yet.
                    if (!useFallback && assetData != null && urlData != null) useFallback = true
                },
            )
        }
        if (card.caption.isNotBlank()) {
            Text(
                text = card.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
        if (onPersonalize != null) {
            // Muse Image thinks for tens of seconds before drawing: without an explicit "Drawing…" repeated taps
            // would look like a broken button, and each would be one more paid generation.
            OutlinedButton(onClick = onPersonalize, enabled = !personalizing) {
                if (personalizing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Рисую под тебя…", "Drawing it for you…"))
                } else {
                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Нарисуй под меня", "Draw it for me"))
                }
            }
        }
    }
}
