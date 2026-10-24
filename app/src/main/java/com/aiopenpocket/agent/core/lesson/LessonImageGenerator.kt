package com.aiopenpocket.agent.core.lesson

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import android.app.Application
import android.util.Log
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Картинка для шага урока по его [LessonStep.imagePrompt]: из кеша на диске, а если её там нет —
 * из Muse Image.
 *
 * Кеш — файлы в `filesDir/lesson_images`, имя — хеш модели и полного промпта. Повторный проход
 * урока, переоткрытие чата и прокрутка назад картинку не перерисовывают: каждая генерация стоит
 * денег и десятков секунд. `filesDir`, а не `cacheDir`: система чистит `cacheDir` когда захочет, и
 * урок посреди прохождения потерял бы уже оплаченные картинки.
 *
 * Один промпт рисуется не больше одного раза одновременно: предзагрузка урока и показ шага могут
 * попросить одну и ту же картинку почти одновременно, и без блокировки по ключу это были бы две
 * оплаченные генерации одного кадра.
 */
@SingleIn(AppScope::class)
@Inject
class LessonImageGenerator(
  private val application: Application,
  private val client: ImageGenClient,
) {

  private val locks = ConcurrentHashMap<String, Mutex>()

  private val dir: File get() = File(application.filesDir, DIR_NAME)

  /** Файл с картинкой или `null`, если нарисовать не вышло (нет ключа, сети, отказ провайдера). */
  suspend fun imageFor(prompt: String): File? = withContext(Dispatchers.IO) {
    // Ключ читается из EncryptedSharedPreferences, а первое обращение к ним поднимает Keystore —
    // поэтому весь путь на IO, а не на потоке вызывающего (это может быть viewModelScope).
    val fullPrompt = styled(prompt)
    val name = cacheName(fullPrompt)
    file(name).takeIf { it.isUsable() }?.let { return@withContext it }
    locks.getOrPut(name) { Mutex() }.withLock {
      // Пока ждали блокировку, картинку мог дорисовать другой вызов.
      file(name).takeIf { it.isUsable() }?.let { return@withLock it }
      val image = client.generate(fullPrompt) ?: return@withLock null
      write(name, image.bytes)
    }
  }

  private fun styled(prompt: String): String = "${prompt.trim()}\n\n$LESSON_IMAGE_STYLE"

  private fun cacheName(fullPrompt: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("${client.model}\n$fullPrompt".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
  }

  private fun file(name: String) = File(dir, "$name$EXTENSION")

  private fun File.isUsable() = isFile && length() > 0

  /**
   * Запись через временный файл и переименование: читатель (Coil при показе карточки) видит либо
   * старое состояние, либо картинку целиком, но не обрубок. Сбой записи — `null`: картинка уже
   * оплачена, но урок без неё всё равно пойдёт дальше.
   */
  private fun write(name: String, bytes: ByteArray): File? = try {
    dir.mkdirs()
    val target = file(name)
    val tmp = File(dir, "$name.tmp")
    tmp.writeBytes(bytes)
    if (tmp.renameTo(target)) target else null.also { tmp.delete() }
  } catch (e: IOException) {
    Log.w(TAG, "Failed to cache lesson image: ${e.message}")
    null
  }

  private companion object {
    const val TAG = "LessonImageGenerator"
    const val DIR_NAME = "lesson_images"
    // Расширение условное: тип картинки (png/webp/jpeg) Coil определяет по содержимому файла.
    const val EXTENSION = ".img"
  }
}

/**
 * Общий стиль для всех картинок уроков. Без него каждый шаг выглядел бы как из другой книжки.
 * «Без текста» — потому что подпись у карточки своя, а буквы, особенно кириллицу, модели рисуют
 * криво. Горизонтальный кадр — под карточку 16:9: параметра формы у `meta/muse-image` нет.
 *
 * Ту же строку дословно дописывает скрипт предгенерации `tools/lesson-images/pregenerate.py` —
 * иначе картинки из сборки и нарисованные на телефоне были бы в разном стиле. Расхождение ловит
 * LessonImageStyleSyncTest.
 */
internal const val LESSON_IMAGE_STYLE = "Flat, clean editorial illustration for a mobile lesson about AI. " +
  "Light background, soft colors, wide 16:9 landscape composition. " +
  "No text, letters or numbers anywhere in the image."
