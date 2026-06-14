package com.aiopenpocket.ui.onboarding.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val genderOptions = listOf(
  "Не указывать",
  "Мужской",
  "Женский",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileForm(
  name: String,
  age: String,
  gender: String,
  preferredAddress: String,
  extraInfo: String,
  onNameChange: (String) -> Unit,
  onAgeChange: (String) -> Unit,
  onGenderChange: (String) -> Unit,
  onPreferredAddressChange: (String) -> Unit,
  onExtraInfoChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var genderExpanded by remember { mutableStateOf(false) }
  val displayGender = when (gender.lowercase()) {
    "мужской" -> "Мужской"
    "женский" -> "Женский"
    else -> genderOptions.first()
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = onNameChange,
      label = { Text("Имя") },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("profile_name"),
      singleLine = true,
    )
    OutlinedTextField(
      value = age,
      onValueChange = onAgeChange,
      label = { Text("Возраст") },
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .testTag("profile_age"),
      singleLine = true,
    )
    ExposedDropdownMenuBox(
      expanded = genderExpanded,
      onExpandedChange = { genderExpanded = it },
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp),
    ) {
      OutlinedTextField(
        value = displayGender,
        onValueChange = {},
        readOnly = true,
        label = { Text("Пол") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
        modifier = Modifier
          .menuAnchor()
          .fillMaxWidth()
          .testTag("profile_gender"),
      )
      ExposedDropdownMenu(
        expanded = genderExpanded,
        onDismissRequest = { genderExpanded = false },
      ) {
        genderOptions.forEach { option ->
          DropdownMenuItem(
            text = { Text(option) },
            onClick = {
              onGenderChange(if (option == genderOptions.first()) "" else option.lowercase())
              genderExpanded = false
            },
          )
        }
      }
    }
    OutlinedTextField(
      value = preferredAddress,
      onValueChange = onPreferredAddressChange,
      label = { Text("Как обращаться") },
      placeholder = { Text("Саша, ты, и т.д.") },
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .testTag("profile_preferred_address"),
      singleLine = true,
    )
    OutlinedTextField(
      value = extraInfo,
      onValueChange = onExtraInfoChange,
      label = { Text("Дополнительно") },
      placeholder = { Text("Чем занимаешься, предпочтения...") },
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp, bottom = 16.dp)
        .testTag("profile_extra_info"),
      minLines = 3,
      maxLines = 5,
    )
  }
}
