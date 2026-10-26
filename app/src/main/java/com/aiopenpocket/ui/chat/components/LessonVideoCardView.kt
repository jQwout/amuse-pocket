package com.aiopenpocket.ui.chat.components

// @trace video-na-vhod-v-uroke-muse-spark-08ccdb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.DrivenUiCard
import java.io.File

/** Папка в cacheDir, куда камера пишет ролик для урока. Её же чистит ChatViewModel после разбора. */
internal const val LESSON_VIDEO_DIR = "lesson_videos"

/** Так Coil и наш [bundledAssetPath] узнают файл из assets APK (ролик-пример и его превью). */
private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"

/**
 * Шаг урока "video": отдать Muse Spark ролик-пример из assets (если у шага он есть — это главная
 * кнопка), снять свой камерой или выбрать из галереи.
 *
 * Камера пишет в свой файл в cacheDir через FileProvider, а не в галерею: снятое для урока не
 * должно оседать в фотоплёнке, и после разбора его удаляют. Пока модель смотрит, вместо кнопок —
 * «Смотрю видео…»: разбор идёт десятки секунд, и повторная отправка стоила бы ещё одного вызова.
 */
@Composable
internal fun LessonVideoCardView(
    card: DrivenUiCard.LessonVideoCard,
    analyzing: Boolean,
    onVideoChosen: (Uri) -> Unit,
) {
    val context = LocalContext.current
    // rememberSaveable: камера — отдельная активность, и наш процесс может пересоздаться, пока она открыта.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMissing by rememberSaveable { mutableStateOf(false) }
    val capture = rememberLauncherForActivityResult(ShortVideoCapture()) { saved ->
        val uri = pendingCapture?.let(Uri::parse)
        pendingCapture = null
        if (saved && uri != null) onVideoChosen(uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onVideoChosen(uri)
    }

    val launchCamera = {
        val uri = newCaptureUri(context)
        pendingCapture = uri.toString()
        try {
            capture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            cameraMissing = true
        }
    }
    val launchGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = card.instruction, style = MaterialTheme.typography.bodyLarge)
        if (card.samplePreview.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(ANDROID_ASSET_PREFIX + card.samplePreview).build(),
                contentDescription = tr("Ролик-пример", "Sample clip"),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        if (analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(tr("Смотрю видео…", "Watching the video…"), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (card.sampleVideo.isNotBlank()) {
            // Главное действие — один тап по готовому ролику: съёмка на телефоне — это камера,
            // разрешения и свет, а смысл шага виден и без неё. Своё видео — вторым рядом.
            Button(
                onClick = { onVideoChosen(Uri.parse(ANDROID_ASSET_PREFIX + card.sampleVideo)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Показать на примере", "Show an example"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = launchCamera, modifier = Modifier.weight(1f)) { Text(tr("Снять своё", "Film my own")) }
                OutlinedButton(onClick = launchGallery, modifier = Modifier.weight(1f)) { Text(tr("Из галереи", "From gallery")) }
            }
        } else {
            Button(onClick = launchCamera, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Снять видео", "Record a video"))
            }
            OutlinedButton(onClick = launchGallery, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Выбрать из галереи", "Choose from gallery"))
            }
        }
        if (cameraMissing && !analyzing) {
            Text(
                text = tr("Не нашёл приложение камеры — выбери видео из галереи.", "Couldn't find a camera app — pick a video from your gallery."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Путь ассета внутри APK, если [this] указывает на ролик-пример урока, иначе null. */
internal fun Uri.bundledAssetPath(): String? =
    toString().takeIf { it.startsWith(ANDROID_ASSET_PREFIX) }?.removePrefix(ANDROID_ASSET_PREFIX)

/**
 * Системная камера с ограничениями: до 10 секунд и в низком качестве. Модели этого хватает, а
 * ролик остаётся лёгким — его целиком везут провайдеру в base64.
 */
private class ShortVideoCapture : ActivityResultContracts.CaptureVideo() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 10)
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, LESSON_VIDEO_DIR).apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.mp4")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
