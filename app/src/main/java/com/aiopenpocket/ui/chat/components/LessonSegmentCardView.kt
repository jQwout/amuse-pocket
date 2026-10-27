package com.aiopenpocket.ui.chat.components

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aiopenpocket.agent.core.llm.SegmentationConcept
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.settings.MetaKeyHint
import java.io.File

/** The folder in cacheDir where the camera writes the lesson photo. ChatViewModel deletes the file after the analysis. */
internal const val LESSON_PHOTO_DIR = "lesson_photos"

/**
 * The folder in cacheDir with the compressed copies of photos that went to SAM: the result cards show them.
 * Not exposed through the FileProvider — only our own process reads it.
 */
internal const val LESSON_SEGMENT_DIR = "lesson_segments"

/**
 * The "segment" lesson step: SAM 3.1 outlines what you name in a photo. The main button is one tap on the
 * bundled sample photo (if the step has one) — the same reasoning as the "video" step: the point of it shows
 * without a camera, permissions and light; your own photo is the second row.
 *
 * The step needs a Meta Model API key, and a judge or a fresh clone may have none, so the card says so plainly
 * and says where to get one. It is never a dead end: "Next" stays available outside the card, the step can be skipped.
 *
 * The camera writes into its own file in cacheDir through a FileProvider, not into the gallery, and the file is
 * deleted after the analysis. While SAM works, "Looking…" replaces the buttons: sending again costs another call.
 */
@Composable
internal fun LessonSegmentCardView(
    card: DrivenUiCard.LessonSegmentCard,
    analyzing: Boolean,
    onPhotoChosen: (concept: String, photo: Uri) -> Unit,
) {
    val context = LocalContext.current
    // rememberSaveable: the camera is a separate activity, and our process may be recreated while it is open.
    var concept by rememberSaveable { mutableStateOf(card.prompt) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMissing by rememberSaveable { mutableStateOf(false) }

    val problem = SegmentationConcept.problemOf(concept)
    // Buttons need a usable concept: a list or a command would cost a call and fail.
    val canRun = problem == null && !analyzing

    val capture = rememberLauncherForActivityResult(LessonPhotoCapture()) { saved ->
        val uri = pendingCapture?.let(Uri::parse)
        pendingCapture = null
        if (saved && uri != null) onPhotoChosen(concept, uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoChosen(concept, uri)
    }

    val launchCamera = {
        val uri = newPhotoCaptureUri(context)
        pendingCapture = uri.toString()
        try {
            capture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            cameraMissing = true
        }
    }
    val launchGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = card.instruction, style = MaterialTheme.typography.bodyLarge)
        if (card.sampleImage.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(ANDROID_ASSET_PREFIX + card.sampleImage).build(),
                contentDescription = tr("Фото-пример", "Sample photo"),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        OutlinedTextField(
            value = concept,
            onValueChange = { concept = it },
            label = { Text(tr("Что найти (по-английски)", "What to find (in English)")) },
            supportingText = {
                Text(
                    if (problem == null || concept.isBlank()) {
                        tr("Одно понятие: cup, yellow bus. Без запятых.", "One thing: cup, yellow bus. No commas.")
                    } else {
                        tr("Одно понятие, без запятых и команд.", "One thing only — no commas or commands.")
                    },
                )
            },
            isError = problem != null && concept.isNotBlank(),
            singleLine = true,
            enabled = !analyzing,
            modifier = Modifier.fillMaxWidth(),
        )
        // The key and the photo's destination, said on the card itself: a person without a key must not have to
        // guess why nothing happens, and what is in the frame leaves the phone.
        Text(
            text = tr(
                "Нужен ключ Meta Model API: Настройки → «КЛЮЧ API». Без него шаг можно пропустить — «Дальше» работает. Фото уходит в Meta целиком, так что личного в кадре не держи. Один запрос стоит около 0,0025 \$.",
                "Needs a Meta Model API key: Settings → API KEY. Without one you can skip the step — “Next” still works. The photo goes to Meta in full, so keep anything private out of the frame. One request costs about \$0.0025.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        MetaKeyHint()
        if (analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(tr("Ищу на фото…", "Looking in the photo…"), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (card.sampleImage.isNotBlank()) {
            Button(
                onClick = { onPhotoChosen(concept, Uri.parse(ANDROID_ASSET_PREFIX + card.sampleImage)) },
                enabled = canRun,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Показать на примере", "Show an example"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = launchCamera, enabled = canRun, modifier = Modifier.weight(1f)) {
                    Text(tr("Снять своё", "Take my own"))
                }
                OutlinedButton(onClick = launchGallery, enabled = canRun, modifier = Modifier.weight(1f)) {
                    Text(tr("Из галереи", "From gallery"))
                }
            }
        } else {
            Button(onClick = launchCamera, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Сфотографировать", "Take a photo"))
            }
            OutlinedButton(onClick = launchGallery, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Выбрать из галереи", "Choose from gallery"))
            }
        }
        if (cameraMissing && !analyzing) {
            Text(
                text = tr("Не нашёл приложение камеры — выбери фото из галереи.", "Couldn't find a camera app — pick a photo from your gallery."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The system camera, with write access to the lesson's own file spelled out for the camera app. */
private class LessonPhotoCapture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun newPhotoCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, LESSON_PHOTO_DIR).apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
