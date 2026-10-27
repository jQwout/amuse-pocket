package com.aiopenpocket.ui.chat.components

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.DrivenUiCard

/**
 * SAM's answer to a "segment" step: the photo that was sent, a frame round every match, and a caption saying
 * what the frames are. "Nothing found" is a result, not an error: the card shows the photo and says so.
 *
 * The frames are drawn from the numbers in the answer (see [scaleBox]); the photo underneath is stretched to the
 * same aspect ratio the picture had when it was sent, so a frame sits on exactly the pixels the model meant.
 */
@Composable
internal fun LessonSegmentResultCardView(card: DrivenUiCard.LessonSegmentResultCard) {
    val aspect = if (card.imageWidth > 0 && card.imageHeight > 0) card.imageWidth.toFloat() / card.imageHeight else 4f / 3f
    var photoMissing by remember(card.imageUrl) { mutableStateOf(false) }
    // Four colors that stay readable on a photo in any theme: the label chip carries its own filled background.
    val palette = listOf(
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary,
        MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary,
        MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
            // dp as plain floats: the frames are placed in dp, and the image fills the full width at this aspect.
            val viewWidth = maxWidth.value
            val viewHeight = viewWidth / aspect
            AsyncImage(
                model = card.imageUrl,
                contentDescription = tr("Фото с найденными объектами", "Photo with the objects that were found"),
                contentScale = ContentScale.FillBounds,
                onError = { photoMissing = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
            )
            card.boxes.forEachIndexed { index, item ->
                val (color, onColor) = palette[index % palette.size]
                val frame = scaleBox(item.box, viewWidth, viewHeight)
                Box(
                    modifier = Modifier
                        .offset(frame.left.dp, frame.top.dp)
                        .size(frame.width.dp, frame.height.dp)
                        .testTag("segment_frame_${item.id}")
                        .border(2.dp, color),
                )
                SegmentLabel(
                    text = segmentLabel(card.concept, item.box.confidence),
                    background = color,
                    content = onColor,
                    modifier = Modifier.offset(frame.left.dp, frame.top.dp),
                )
            }
        }
        if (photoMissing) {
            // The copy lives in the app's cache, which the system or the user may clear.
            Text(
                text = tr("Фото уже удалено из кэша приложения — остались только рамки.", "The photo is gone from the app's cache — only the frames are left."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(text = summary(card), style = MaterialTheme.typography.bodyMedium)
        if (card.boxes.isNotEmpty()) {
            Text(
                text = tr(
                    "Модель вернула текст с координатами углов — рамки нарисовало приложение.",
                    "The model returned the corner coordinates as text — the app drew the frames.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SegmentLabel(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private fun summary(card: DrivenUiCard.LessonSegmentResultCard): String = when {
    card.total == 0 -> tr(
        "«${card.concept}»: ничего не нашёл. Это нормальный ответ модели, а не ошибка — попробуй другое слово.",
        "No “${card.concept}” found. That is a normal answer, not an error — try another word.",
    )
    card.total > card.boxes.size -> tr(
        "Нашёл «${card.concept}»: ${card.total}. Показаны первые ${card.boxes.size}.",
        "Found “${card.concept}”: ${card.total}. Showing the first ${card.boxes.size}.",
    )
    else -> tr("Нашёл «${card.concept}»: ${card.total}.", "Found “${card.concept}”: ${card.total}.")
}
