package com.aiopenpocket.ui.theme

// @trace classic-typography-a44c05

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.aiopenpocket.R

// Классическая типографика: засечный Lora для крупных заголовков (академический характер)
// и чистый системный гротеск для текста и подписей. Lora — переменный шрифт с полной
// поддержкой кириллицы; веса задаём через FontVariation (на API < 26 берётся базовое начертание).
@OptIn(ExperimentalTextApi::class)
private fun lora(weight: Int) =
  Font(
    R.font.lora,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
  )

private val Serif =
  FontFamily(
    lora(400),
    lora(500),
    lora(600),
    lora(700),
  )

private val Sans = FontFamily.Default

private val Base = Typography()

// Полная согласованная шкала Material 3: заголовки — Lora, тело и подписи — системный sans.
val Typography =
  Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = Serif),
    displayMedium = Base.displayMedium.copy(fontFamily = Serif),
    displaySmall = Base.displaySmall.copy(fontFamily = Serif),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    // Заголовки всех тиров — serif, чтобы шрифт не «плясал» между карточками (titleMedium/Small
    // используются как заголовки карточек рядом с titleLarge и заголовком свёрнутого тулбара).
    titleMedium = Base.titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Sans),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Sans),
    bodySmall = Base.bodySmall.copy(fontFamily = Sans),
    labelLarge = Base.labelLarge.copy(fontFamily = Sans),
    labelMedium = Base.labelMedium.copy(fontFamily = Sans),
    labelSmall = Base.labelSmall.copy(fontFamily = Sans),
  )
