package com.aiopenpocket.ui.onboarding.components

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53
// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.settings.ApiKeyField
import com.aiopenpocket.ui.settings.MetaKeyHint
import com.aiopenpocket.ui.theme.MyApplicationTheme

/**
 * The onboarding step where the person enters their own Meta Model API key.
 *
 * It sits BEFORE the first step that calls a model (the "about me" parsing): no key is bundled into any
 * build, so without one that very next step would have nothing to talk to. The field and the "where to
 * get a key" hint are the Settings ones ([ApiKeyField], [MetaKeyHint]) — the same masking, the same
 * quickstart link — so the two places cannot drift apart. "Skip" is a real way through: the key can be added
 * later in Settings, and every model action then says so instead of failing.
 */
@Composable
fun MetaKeyStep(
  key: String,
  onKeyChange: (String) -> Unit,
  onSkip: () -> Unit,
  modifier: Modifier = Modifier,
  /** Paddings for the onboarding's floating glass islands, applied inside the scroll — see [AboutMeStep]. */
  contentPadding: PaddingValues = PaddingValues(),
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(
      text = tr("Твой ключ Meta", "Your Meta key"),
      style = MaterialTheme.typography.headlineSmall,
      // An explicit color, as in AboutMeStep: the ambient one is near-black outside the root Surface.
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      text = tr(
        "Агент работает на моделях Meta и использует твой собственный ключ Meta Model API. " +
          "Он хранится только на этом телефоне, в зашифрованном виде, и уходит только в Meta. " +
          "Ключ можно добавить и позже в Настройках.",
        "The agent runs on Meta's models and uses your own Meta Model API key. " +
          "It stays on this phone, encrypted, and goes only to Meta. " +
          "You can also add it later in Settings.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
    )

    ApiKeyField(
      label = tr("Ключ Meta Model API", "Meta Model API key"),
      value = key,
      onValueChange = onKeyChange,
      modifier = Modifier.testTag("onboarding_meta_key"),
    )
    MetaKeyHint(modifier = Modifier.padding(top = 8.dp))

    Spacer(modifier = Modifier.height(16.dp))
    TextButton(
      onClick = onSkip,
      modifier = Modifier.testTag("onboarding_key_skip"),
    ) {
      Text(tr("Пропустить", "Skip"))
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun MetaKeyStepPreview() {
  MyApplicationTheme {
    MetaKeyStep(key = "", onKeyChange = {}, onSkip = {})
  }
}
