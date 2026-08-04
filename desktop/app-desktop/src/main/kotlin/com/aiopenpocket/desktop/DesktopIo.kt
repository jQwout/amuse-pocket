package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

/** Открывает файл или папку в системном приложении. Тихо ничего не делает там, где AWT это не умеет. */
fun openInSystem(path: File) {
  runCatching {
    if (!Desktop.isDesktopSupported()) return
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
 * Стоимость в долларах. Генерации бывают дешевле цента, поэтому округление до двух знаков
 * показывало бы «$0.00» — четыре знака честнее.
 *
 * Locale.ROOT: иначе на русской системе выходит «$0,0042» — точка и запятая вперемешку
 * в зависимости от машины.
 */
fun formatCost(costUsd: Double?): String = costUsd?.let { "$" + "%.4f".format(Locale.ROOT, it) } ?: "—"
