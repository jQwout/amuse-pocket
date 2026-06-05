package com.example.ui.chat

import com.example.data.database.ChatMessage
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Drawer & Attachment state
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AP",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "AI Open Pocket",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            state.capturedCookie?.let { cookie ->
                                Text(
                                    text = "Сессия активна",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Section: History of chat messages
                    Text(
                        text = "История запросов",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )

                    val userMessages = state.messages.filter { it.sender == "user" }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (userMessages.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "История пока пуста",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(userMessages) { msg ->
                                    val displayContent = if (msg.content.length > 30) "${msg.content.take(30)}..." else msg.content
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                val indexInMessages = state.messages.indexOfFirst { it.id == msg.id }
                                                if (indexInMessages != -1) {
                                                    scope.launch {
                                                        drawerState.close()
                                                        listState.animateScrollToItem(indexInMessages)
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Chat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = displayContent,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Control actions panel inside drawer as requested
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            label = { Text("Очистить историю") },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    viewModel.handleIntent(ChatIntent.ClearHistory)
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.testTag("drawer_clear_history")
                        )

                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            label = { Text("Настроить API-ключ LM") },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    showSettingsDialog = true
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.testTag("drawer_settings_api_key")
                        )

                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Logout,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            label = { Text("Выйти из системы") },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    viewModel.handleIntent(ChatIntent.Logout)
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("drawer_logout")
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    drawerState.open()
                                }
                            },
                            modifier = Modifier.testTag("drawer_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Открыть меню",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "AI Open Pocket",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            state.capturedCookie?.let { cookie ->
                                val displayCookie = if (cookie.length > 25) "${cookie.take(25)}..." else cookie
                                Text(
                                    text = "Сессия: $displayCookie",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.testTag("top_bar_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = "Настройка ключа",
                                tint = if (state.customApiKey.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            modifier = modifier.fillMaxSize().testTag("chat_screen")
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Chat Content Messages Flow
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (state.messages.isEmpty()) {
                        // Empty state representation
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Sparkle",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Добро пожаловать в AI Open Pocket!",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Сессия успешно получена. Отправьте текстовое сообщение ИИ (NVIDIA NIM) ниже. Вы также можете сымитировать вложенные файлы (фото, документы)!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            items(state.messages) { message ->
                                ChatBubbleItem(message = message)
                            }
                        }
                    }
                }

                // Real-time Upload/Loader active loader indicator and cancel card
                if (state.isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                            .testTag("loading_and_cancel_banner"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("ai_loading_spinner"),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI Open Pocket думает...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Выполняется запрос к NVIDIA NIM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Cancel button
                        Button(
                            onClick = { viewModel.handleIntent(ChatIntent.CancelRequest) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("cancel_request_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Отмена",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отмена", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Render attachment list indicators above bar
                if (state.attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.attachments.forEachIndexed { index, attachment ->
                            Row(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (attachment.mimeType.contains("image")) Icons.Default.Image else Icons.Default.AttachFile,
                                    contentDescription = "Тип файла",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Удалить",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.handleIntent(ChatIntent.RemoveAttachment(index)) },
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Input panel (text panel + clear + send + hide keyboard) with dynamic imePadding to handle keyboard cleanly
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding()
                    .padding(16.dp)
            ) {
                // Main modern Capsule Card Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(28.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Line 1: Borderless Text Input Field
                    TextField(
                        value = state.inputText,
                        onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
                        placeholder = {
                            Text(
                                text = "Ask me anything...",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("message_input_field"),
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Line 2: Actions Row with Plus sign next to Send Button on the right, and Red back-undo Clear Button on the left
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left side: Clean crimson red "Undo-arrow" clear button when text is entered
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (state.inputText.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.handleIntent(ChatIntent.ClearInput) },
                                    modifier = Modifier
                                        .testTag("clear_text_button")
                                        .size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Undo,
                                        contentDescription = "Очистить ввод",
                                        tint = Color(0xFFEF5350), // Beautiful soft crimson red accent
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Right side: Attachment trigger (+) and Send button side by side and close together
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Attachment trigger button (the Plus sign as a clean, shadeless icon)
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                modifier = Modifier
                                    .testTag("attachment_trigger")
                                    .size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Прикрепить файл",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Send Message Button designed as circular with gradient matching the original visual style
                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    viewModel.handleIntent(ChatIntent.SendMessage)
                                },
                                enabled = !state.isLoading && (state.inputText.trim().isNotEmpty() || state.attachments.isNotEmpty()),
                                modifier = Modifier
                                    .testTag("send_message_button")
                                    .size(42.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = if (state.inputText.trim().isNotEmpty() || state.attachments.isNotEmpty()) {
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.secondary
                                                )
                                            } else {
                                                listOf(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                )
                                            }
                                        ),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить",
                                    tint = if (state.inputText.trim().isNotEmpty() || state.attachments.isNotEmpty()) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Mock attachment selector menu dialog
        if (showAttachmentMenu) {
            AlertDialog(
                onDismissRequest = { showAttachmentMenu = false },
                title = { Text("Прикрепить вложение") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Выберите тип файла для имитации вложения в запросе:")
                        
                        ListItem(
                            headlineContent = { Text("Снимок_Камеры.jpg") },
                            supportingContent = { Text("Изображение (JPEG)") },
                            leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = "Камера", tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier
                                .clickable {
                                    viewModel.handleIntent(ChatIntent.AttachMockFile("Снимок_Камеры.jpg", "image/jpeg"))
                                    showAttachmentMenu = false
                                }
                                .fillMaxWidth()
                        )
                        
                        ListItem(
                            headlineContent = { Text("Договор_Проекта.pdf") },
                            supportingContent = { Text("Документ (PDF)") },
                            leadingContent = { Icon(Icons.Default.Description, contentDescription = "Документ", tint = MaterialTheme.colorScheme.secondary) },
                            modifier = Modifier
                                .clickable {
                                    viewModel.handleIntent(ChatIntent.AttachMockFile("Договор_Проекта.pdf", "application/pdf"))
                                    showAttachmentMenu = false
                                }
                                .fillMaxWidth()
                        )

                        ListItem(
                            headlineContent = { Text("Скриншот_Ошибка.png") },
                            supportingContent = { Text("Изображение (PNG)") },
                            leadingContent = { Icon(Icons.Default.Image, contentDescription = "Картинка", tint = MaterialTheme.colorScheme.tertiary) },
                            modifier = Modifier
                                .clickable {
                                    viewModel.handleIntent(ChatIntent.AttachMockFile("Скриншот_Ошибка.png", "image/png"))
                                    showAttachmentMenu = false
                                }
                                .fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAttachmentMenu = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        // Custom API Key Settings Dialog
        if (showSettingsDialog) {
            var tempKey by remember { mutableStateOf(state.customApiKey) }
            var isKeyVisible by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Настройка API-ключа",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Если у вас нет настроенных переменных окружения, вы можете ввести свой NVIDIA API-ключ вручную здесь для прямого соединения с Llama-3.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            label = { Text("Личный API-ключ (NVIDIA)") },
                            placeholder = { Text("nvapi-...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("api_key_input_field"),
                            visualTransformation = if (isKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                    Icon(
                                        imageVector = if (isKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = "Переключить видимость"
                                    )
                                }
                            }
                        )

                        Text(
                            text = "Выберите активную персональность (характер) ИИ:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        var expanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedCard(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth().testTag("persona_picker_card")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = when (state.selectedPersonaId) {
                                                "silent_butler" -> "Тихий дворецкий"
                                                "attentive_secretary" -> "Внимательный секретарь"
                                                "sharp_partner" -> "Резкий напарник ⭐️ (Дефолт)"
                                                "egalitarian_partner" -> "Партнёр-эгалитарист"
                                                "friendly_companion" -> "Дружелюбный компаньон"
                                                "ironic_assistant" -> "Ироничный ассистент"
                                                "strict_coach" -> "Строгий коуч"
                                                else -> state.selectedPersonaId
                                            },
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = when (state.selectedPersonaId) {
                                                "silent_butler" -> "Минимальный, утилитарный тон"
                                                "attentive_secretary" -> "Вежливый, уточняющий тон"
                                                "sharp_partner" -> "Деловой, проактивный топ"
                                                "egalitarian_partner" -> "Ровный, уважительный"
                                                "friendly_companion" -> "Теплый, общительный"
                                                "ironic_assistant" -> "Сухой юмор"
                                                "strict_coach" -> "Прямой, мотивирующий"
                                                else -> ""
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }

                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                state.personaList.forEach { pId ->
                                    val name = when (pId) {
                                        "silent_butler" -> "Тихий дворецкий"
                                        "attentive_secretary" -> "Внимательный секретарь"
                                        "sharp_partner" -> "Резкий напарник ⭐️"
                                        "egalitarian_partner" -> "Партнёр-эгалитарист"
                                        "friendly_companion" -> "Дружелюбный компаньон"
                                        "ironic_assistant" -> "Ироничный ассистент"
                                        "strict_coach" -> "Строгий коуч"
                                        else -> pId
                                    }
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            viewModel.handleIntent(ChatIntent.ChangePersona(pId))
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Ключ и настройки сохраняются локально на вашем устройстве Android в безопасном хранилище приложения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.handleIntent(ChatIntent.SaveApiKey(tempKey.trim()))
                            showSettingsDialog = false
                        },
                        modifier = Modifier.testTag("save_api_key_button")
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSettingsDialog = false }
                    ) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}
}

@Composable
fun ChatBubbleItem(message: ChatMessage) {
    val isUser = message.sender == "user"
    val isSystem = message.sender == "system"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = when {
            isUser -> Alignment.CenterEnd
            isSystem -> Alignment.Center
            else -> Alignment.CenterStart
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isSystem) 1.0f else 0.85f),
            horizontalAlignment = when {
                isUser -> Alignment.End
                isSystem -> Alignment.CenterHorizontally
                else -> Alignment.Start
            }
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = when {
                            isUser -> MaterialTheme.colorScheme.primaryContainer
                            isSystem -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = when {
                            isUser -> RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
                            isSystem -> RoundedCornerShape(8.dp)
                            else -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // Render attachment tag inside chat if any
                    if (message.attachmentName != null) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (message.attachmentMimeType?.contains("image") == true) Icons.Default.Image else Icons.Default.AttachFile,
                                contentDescription = "Вложение",
                                modifier = Modifier.size(12.dp),
                                tint = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = message.attachmentName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Render actual text message or Driven UI Card
                    val isJsonCard = remember(message.content) {
                        !isUser && !isSystem && message.content.trim().startsWith("{") && message.content.trim().endsWith("}") && message.content.contains("\"template\"")
                    }

                    if (isJsonCard) {
                        RenderDrivenUiCard(
                            spec = message.content.trim()
                        )
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                isSystem -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            
            // Footer status time tag
            Text(
                text = if (isSystem) "Оповещение системы" else if (isUser) "Пользователь • Отправлено" else "LLM ИИ Помощник",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}
