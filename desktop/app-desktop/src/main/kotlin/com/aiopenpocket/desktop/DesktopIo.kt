package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Открывает файл или папку в системном приложении. Тихо ничего не делает там, где AWT это не умеет.
 *
 * Папку создаём, если её нет. Каталог результатов заводится только при записи первого файла, а
 * кнопка «Открыть папку» заметнее всего как раз до первой генерации — и раньше она в этот момент
 * молча не делала ничего, что неотличимо от сломанной кнопки.
 */
fun openInSystem(path: File) {
  runCatching {
    if (!Desktop.isDesktopSupported()) return
    if (!path.exists() && path.extension.isEmpty()) path.mkdirs()
    if (path.exists()) Desktop.getDesktop().open(path)
  }
}

/**
 * Системный диалог выбора картинки. AWT-диалог вместо Compose-компонента: он родной для ОС и не
 * тянет зависимостей — на десктопе это важнее единообразия с мобильным UI.
 */
fun pickImageFile(): File? {
  val dialog = FileDialog(null as Frame?, "Выбери картинку", FileDialog.LOAD)
  dialog.isMultipleMode = false
  dialog.setFilenameFilter { _, name ->
    name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
  }
  dialog.isVisible = true
  val dir = dialog.directory ?: return null
  val name = dialog.file ?: return null
  return File(dir, name)
}

/**
 * Читает картинку с диска в [ImageBitmap]. null — если файл не картинка или битый.
 *
 * Блокирующая: чтение файла плюс декодирование. Звать только с IO-диспетчера — из композиции
 * это подвешивало бы окно на каждой карточке истории.
 */
fun loadImage(path: File): ImageBitmap? = runCatching {
  SkiaImage.makeFromEncoded(path.readBytes()).toComposeImageBitmap()
}.getOrNull()

/**
 * Стоимость в долларах. Переехала в ядро (`formatUsd`), когда туда же уехала строка сметы: формат
 * денег обязан совпадать в обоих местах, а у `:app-desktop` тестов нет. Здесь остался псевдоним,
 * чтобы не править десяток вызовов.
 */
fun formatCost(costUsd: Double?): String = com.aiopenpocket.genai.formatUsd(costUsd)
