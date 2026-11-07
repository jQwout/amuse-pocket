package com.aiopenpocket

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.voice.call.CallBackground
import com.aiopenpocket.voice.call.CallControl
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.components.glass.StatusBarScrim
import com.aiopenpocket.ui.call.CallScreen
import com.aiopenpocket.ui.chat.ChatScreen
import com.aiopenpocket.ui.conversations.ConversationsListScreen
import com.aiopenpocket.ui.agents.AgentsScreen
import com.aiopenpocket.ui.agents.builder.AgentBuilderRoute
import com.aiopenpocket.ui.agents.builder.AgentSourceContext
import com.aiopenpocket.ui.agents.detail.AgentDetailRoute
import com.aiopenpocket.ui.forge.AgentUiRoute
import com.aiopenpocket.ui.home.HomeScreen
import com.aiopenpocket.ui.memory.MemoryGraphScreen
import com.aiopenpocket.ui.memory.MemoryPageScreen
import com.aiopenpocket.ui.onboarding.OnboardingScreen
import com.aiopenpocket.ui.plan.PlanDetailRoute
import com.aiopenpocket.ui.plan.PlanListRoute
import com.aiopenpocket.ui.roadmap.LibraryScreen
import com.aiopenpocket.ui.roadmap.RoadmapScreen
import com.aiopenpocket.ui.scheduledworks.ScheduledWorksScreen
import com.aiopenpocket.ui.profile.ProfileScreen
import com.aiopenpocket.ui.settings.SettingsScreen
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach

/**
 * Duration of the transition between screens. 260 ms is a deliberate compromise: shorter reads as a
 * blink and the animation loses its point, longer starts to feel like the app is lagging. The easing
 * is the standard Material one: a fast start, a soft stop.
 */
private const val NAV_ANIM_MS = 260

/** The specs are split by type: the slide animates IntOffset, the fade a Float; one spec cannot serve both. */
private val NAV_SLIDE = tween<androidx.compose.ui.unit.IntOffset>(NAV_ANIM_MS, easing = FastOutSlowInEasing)
private val NAV_FADE = tween<Float>(NAV_ANIM_MS, easing = FastOutSlowInEasing)

class MainActivity : ComponentActivity() {

    // Incremented every time the app is opened as a voice assistant —
    // a signal for the UI to open a new chat.
    private val openNewChatRequest = MutableStateFlow(0)

    // The conversation id from a tap on the "agent replied" notification — a signal for the UI to open that chat.
    private val openConversationRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The launch intent is handled only on a "clean" start. After a rotation, a system theme change or
        // process restoration getIntent() is still the same original intent, while the navigation stack
        // is already restored: handling it again added extra home and empty chat/new screens on top
        // (after an assistant call) or opened the chat from the notification a second time. A launch
        // from Recents repeats the old intent the same way — it is skipped too.
        // The notification permission is no longer asked here: a system dialog before onboarding,
        // with no explanation why, is an easy reason to tap "Don't allow". It is asked when it matters:
        // the notification_permission card the agent shows when a heartbeat runs into a refusal.
        if (savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) {
            handleLaunchIntent(intent)
        }

        enableEdgeToEdge()
        followCallForPip()

        val appGraph = (application as AiOpenPocketApp).appGraph

        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
            ) {
                val themeMode by remember { appGraph.themeRepository.mode }.collectAsState()
                MyApplicationTheme(mode = themeMode) {
                    // @trace fonovye-hody-agenta-dde42d
                    // Notifications are asked for right at the start: background replies and reminders need them.
                    com.aiopenpocket.agent.core.notification.AskNotificationsOnStart()
                    val userPreferencesRepository = remember { appGraph.userPreferencesRepository }
                    val onboardingCompleted by userPreferencesRepository.onboardingCompleted
                        .collectAsState(initial = null)
                    // onEach before the state: AppLanguage.code changes before language becomes non-null — the first
                    // frame after the placeholder is already drawn in the right language, without a flicker.
                    val language by remember {
                        userPreferencesRepository.language.onEach { AppLanguage.code = it }
                    }.collectAsState(initial = null)

                    when (if (language == null) null else onboardingCompleted) {
                        null -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        else -> {
                            val navController = rememberNavController()
                            val newChatToken by openNewChatRequest.collectAsState()
                            // The start screen is chosen right away from whether onboarding is done — otherwise
                            // NavHost first draws "onboarding", and only then LaunchedEffect moves to
                            // "home", which makes onboarding flash for a user who has already completed it.
                            // By this point onboardingCompleted is no longer null (we are in the else branch).
                            val startDestination = if (onboardingCompleted == true) "home" else "onboarding"
                            // A safety net in case onboarding completes after the start
                            // (false → true): go to home, removing onboarding from the stack.
                            LaunchedEffect(onboardingCompleted) {
                                if (onboardingCompleted == true &&
                                    navController.currentDestination?.route == "onboarding"
                                ) {
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            }
                            // Open a call when the voice assistant is invoked.
                            // Onboarding must be completed — otherwise the chat is not available.
                            LaunchedEffect(newChatToken, onboardingCompleted) {
                                if (newChatToken > 0 && onboardingCompleted == true) {
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    // @trace voice-call-mode-54cb49
                                    // The owner, 3 Nov 2026: the assistant button opens a call, as in the Claude app,
                                    // not a chat that hears one question. Hanging up leaves the chat with the call in it.
                                    navController.navigate("call/new") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            // A tap on the "agent replied" notification: open that very conversation.
                            val openConversationId by openConversationRequest.collectAsState()
                            LaunchedEffect(openConversationId, onboardingCompleted) {
                                val convId = openConversationId
                                if (convId != null && onboardingCompleted == true) {
                                    // The id was already checked (a UUID) in handleLaunchIntent; encode is in case
                                    // the id format is ever extended: a "/" in the route broke navigation.
                                    navController.navigate("chat/${Uri.encode(convId)}") { launchSingleTop = true }
                                    openConversationRequest.value = null
                                }
                            }
                            // Switching bottom bar tabs: keep the stack flat —
                            // "home" as the base, the current tab on top, with state saved.
                            val navigateToTab: (String) -> Unit = { route ->
                                navController.navigate(route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            // Settings and Profile link to each other (the card, the gear). launchSingleTop only
                            // dedups against the TOP entry, so a plain navigate would stack
                            // Settings -> Profile -> Settings -> Profile ... one entry per round trip. If the target is
                            // already in the stack, go back to it instead; navigate only when it is not.
                            val openOrReturnTo: (String) -> Unit = { route ->
                                if (!navController.popBackStack(route, inclusive = false)) {
                                    navController.navigate(route) { launchSingleTop = true }
                                }
                            }
                            // The fade under the clock — ONE for the whole app, on top of NavHost: screens do not
                            // know about it and cannot forget to add it. It intercepts nothing (it is
                            // just a background), so lying on top is safe.
                            Box(modifier = Modifier.fillMaxSize()) {
                            // Screen transitions — once for the whole NavHost, not per route: otherwise
                            // 18 destinations would have to be kept in sync by hand, and sooner or
                            // later one would drift. The motion is simple and not "cartoonish": the new
                            // screen slides in with a small offset and fades in, the old one leaves
                            // symmetrically; back is mirrored so the direction reads.
                            // The offset is small (1/8 of the width): a full-screen slide on every
                            // transition quickly gets tiresome and subjectively slows the app down.
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                enterTransition = {
                                    slideInHorizontally(NAV_SLIDE) { it / 8 } + fadeIn(NAV_FADE)
                                },
                                exitTransition = {
                                    slideOutHorizontally(NAV_SLIDE) { -it / 8 } + fadeOut(NAV_FADE)
                                },
                                popEnterTransition = {
                                    slideInHorizontally(NAV_SLIDE) { -it / 8 } + fadeIn(NAV_FADE)
                                },
                                popExitTransition = {
                                    slideOutHorizontally(NAV_SLIDE) { it / 8 } + fadeOut(NAV_FADE)
                                },
                            ) {
                                composable("onboarding") {
                                    // Navigation after completion is driven by the onboardingCompleted observer
                                    // (the LaunchedEffect above) — a single source.
                                    OnboardingScreen()
                                }
                                composable("onboarding_edit") {
                                    // Lesson 1 = onboarding: re-entering from the map to edit the settings.
                                    OnboardingScreen(onClose = { navController.popBackStack() })
                                }
                                composable("home") {
                                    HomeScreen(
                                        onOpenChat = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                        onNavigateToConversations = {
                                            navController.navigate("conversations")
                                        },
                                        onNavigateToLibrary = {
                                            navController.navigate("library")
                                        },
                                        onNavigateToRoadmap = { navigateToTab("roadmap") },
                                        onNavigateToSettings = { navigateToTab("settings") },
                                        // The round avatar: the person's own data, not the key/model settings.
                                        onNavigateToProfile = { navController.navigate("profile") },
                                        onNavigateToAgents = { navController.navigate("agents") },
                                    )
                                }
                                composable("agents") {
                                    AgentsScreen(
                                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                                        // "Create agent" opens a new chat with the hidden Builder agent; the step form
                                        // stays reachable through the draft/edit routes below.
                                        onOpenBuilderChat = { id -> navController.navigate("chat/$id") },
                                        // A draft opens straight in the builder: it has no chat yet.
                                        onEditDraft = { id -> navController.navigate("agent_builder/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                        // The other way to create: the step form, no model calls while filling it in.
                                        onOpenBuilderForm = { navController.navigate("agent_builder") },
                                    )
                                }
                                // @trace cloud-forged-screens-40d702
                                // The agent's own forged interface, full screen; the chat is one tap away from it.
                                composable("agent_ui/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    AgentUiRoute(
                                        agentId = agentId,
                                        onOpenChat = { id -> navController.navigate("chat/$id") },
                                        onOpenAgentDetail = { id -> navController.navigate("agent/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                // @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce
                                // A preset agent's native app (Tutor flashcards, Budget, Task list).
                                composable("agent_app/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    com.aiopenpocket.ui.agents.apps.AgentAppRoute(
                                        agentId = agentId,
                                        onOpenChat = { id -> navController.navigate("chat/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable("agent/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    AgentDetailRoute(
                                        agentId = agentId,
                                        onOpenChat = { id -> navController.navigate("chat/$id") },
                                        onOpenUi = { id -> navController.navigate("agent_ui/$id") },
                                        onOpenApp = { id -> navController.navigate("agent_app/$id") },
                                        onNavigateToBuilder = { id -> navController.navigate("agent_builder/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenPlan = { planId -> navController.navigate("plan/$planId") },
                                        onOpenPlanList = { id -> navController.navigate("plans/$id") },
                                    )
                                }
                                composable("plans/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    PlanListRoute(
                                        agentId = agentId,
                                        onOpenPlan = { planId -> navController.navigate("plan/$planId") },
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable("plan/{planId}") { backStackEntry ->
                                    val planId = backStackEntry.arguments?.getString("planId") ?: ""
                                    PlanDetailRoute(
                                        planId = planId,
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable(
                                    route = "agent_builder?source={source}",
                                    arguments = listOf(
                                        // The Academy capstone (Task 23): ChatScreen's onOpenAgentBuilder navigates here
                                        // with source=ACADEMY_CAPSTONE. All other entries (the "Create agent" FAB etc.)
                                        // navigate to bare "agent_builder" — the source takes defaultValue = MANUAL.
                                        navArgument("source") {
                                            type = NavType.StringType
                                            defaultValue = AgentSourceContext.MANUAL.name
                                        },
                                    ),
                                ) { backStackEntry ->
                                    val sourceContext = backStackEntry.arguments?.getString("source")
                                        ?.let { runCatching { AgentSourceContext.valueOf(it) }.getOrNull() }
                                        ?: AgentSourceContext.MANUAL
                                    AgentBuilderRoute(
                                        agentId = null,
                                        sourceContext = sourceContext,
                                        onExit = { navController.popBackStack() },
                                        onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                                        // A published agent goes straight to its chat; the builder is closed
                                        // so that "Back" from the chat does not return to the finished wizard.
                                        onOpenChat = { id ->
                                            navController.popBackStack()
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable(
                                    route = "agent_builder/{agentId}?review={review}",
                                    arguments = listOf(
                                        // The builder chat navigates to "agent_builder/{id}?review=true" once the draft
                                        // validates, to open the one-page review instead of step 1. Every other entry
                                        // (edit a draft, duplicate) keeps the plain route and the wizard.
                                        navArgument("review") {
                                            type = NavType.BoolType
                                            defaultValue = false
                                        },
                                    ),
                                ) { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId")
                                    val openReview = backStackEntry.arguments?.getBoolean("review") ?: false
                                    AgentBuilderRoute(
                                        agentId = agentId,
                                        openReview = openReview,
                                        onExit = { navController.popBackStack() },
                                        onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                                        onOpenChat = { id ->
                                            navController.popBackStack()
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable("roadmap") {
                                    RoadmapScreen(
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                        onReopenOnboarding = {
                                            navController.navigate("onboarding_edit")
                                        },
                                        onNavigateToHome = { navigateToTab("home") },
                                        onNavigateToSettings = { navigateToTab("settings") },
                                    )
                                }
                                composable("library") {
                                    LibraryScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                    )
                                }
                                composable("conversations") {
                                    ConversationsListScreen(
                                        onConversationClick = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                        onNavigateBack = { navController.popBackStack() },
                                        onNavigateToGraph = {
                                            navController.navigate("memory_graph") {
                                                launchSingleTop = true
                                            }
                                        },
                                    )
                                }
                                composable("memory_graph") {
                                    MemoryGraphScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenChat = { id ->
                                            navController.navigate("memory_page/$id")
                                        },
                                    )
                                }
                                composable("memory_page/{conversationId}") { backStackEntry ->
                                    val conversationId =
                                        backStackEntry.arguments?.getString("conversationId") ?: ""
                                    MemoryPageScreen(
                                        conversationId = conversationId,
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenChat = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onNavigateToScheduledWorks = { navController.navigate("scheduled_works") },
                                        onNavigateToHome = { navigateToTab("home") },
                                        onNavigateToRoadmap = { navigateToTab("roadmap") },
                                        onOpenProfile = { openOrReturnTo("profile") },
                                    )
                                }
                                // @trace profile-screen-7354a7
                                composable("profile") {
                                    ProfileScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        // Pushed on top of Profile (not a tab switch): "Back" returns here. If Settings
                                        // is already beneath (Settings -> Profile -> gear), return to it rather than stack
                                        // a second copy.
                                        onOpenSettings = { openOrReturnTo("settings") },
                                        onOpenMemoryGraph = {
                                            navController.navigate("memory_graph") { launchSingleTop = true }
                                        },
                                    )
                                }
                                composable("scheduled_works") {
                                    ScheduledWorksScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable(
                                    route = "chat/{conversationId}?voice={voice}",
                                    arguments = listOf(
                                        // @trace voice-assistant-mode-a81a99
                                        // Only the assistant button sets it; every other "chat/<id>" keeps false.
                                        navArgument("voice") {
                                            type = NavType.BoolType
                                            defaultValue = false
                                        },
                                    ),
                                ) { backStackEntry ->
                                    val conversationId =
                                        backStackEntry.arguments?.getString("conversationId") ?: "new"
                                    ChatScreen(
                                        conversationId = if (conversationId == "new") "" else conversationId,
                                        voiceAssistant = backStackEntry.arguments?.getBoolean("voice") ?: false,
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenAgentUi = { id -> navController.navigate("agent_ui/$id") },
                                        onOpenAgentApp = { id -> navController.navigate("agent_app/$id") },
                                        onOpenAgentDetail = { agentId ->
                                            navController.navigate("agent/$agentId")
                                        },
                                        // The capstone's "Open the builder": a new builder chat.
                                        onOpenBuilderChat = { id -> navController.navigate("chat/$id") },
                                        // The "Review your agent" card at the end of a builder chat.
                                        onOpenAgentReview = { id ->
                                            navController.navigate("agent_builder/$id?review=true")
                                        },
                                        onOpenPlanDetail = { planId ->
                                            navController.navigate("plan/$planId")
                                        },
                                        // Pushed on top of the chat, not a tab switch: "Back" from Settings returns
                                        // to this very chat, where "Retry" re-sends the message once the key is in.
                                        onOpenSettings = {
                                            navController.navigate("settings") { launchSingleTop = true }
                                        },
                                        onStartCall = { id ->
                                            navController.navigate("call/${id.ifBlank { "new" }}") { launchSingleTop = true }
                                        },
                                    )
                                }
                                // @trace voice-call-mode-54cb49
                                composable("call/{conversationId}") { backStackEntry ->
                                    val conversationId =
                                        backStackEntry.arguments?.getString("conversationId") ?: "new"
                                    CallScreen(
                                        conversationId = if (conversationId == "new") "" else conversationId,
                                        onHangUp = { convId -> navController.leaveCall(convId) },
                                    )
                                }
                            }

                            // A call draws its own backdrop up to the top edge; the scrim's band of the page
                            // colour sat over it like a stray toolbar (owner, 4 Nov 2026).
                            val route = navController.currentBackStackEntryAsState().value?.destination?.route
                            if (route?.startsWith("call/") != true) {
                                StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    // ---- a call in a picture-in-picture window ----
    // @trace background-voice-calls-a-picture-in-picture-window-and-a-call-that-goes-on-off-screen-3ab748

    private val supportsPip: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Keeps the window's picture-in-picture settings in step with the call: on Android 12+ leaving a call
     * shrinks it by itself (auto-enter), not while the call itself opened the camera or a dialog, and the
     * mini window's buttons show mute or unmute as it stands. A call hung up from the mini window closes the
     * window too, instead of leaving the chat in it. Applied synchronously from CallBackground's setters.
     */
    private val refreshCallPip: () -> Unit = {
        if (supportsPip) {
            runCatching { setPictureInPictureParams(callPipParams(callMayShrink())) }
            if (!CallBackground.onCall.value && isInPictureInPictureMode) moveTaskToBack(false)
        }
    }

    private fun callMayShrink(): Boolean = CallBackground.onCall.value && !CallBackground.holdPip.value

    private fun followCallForPip() {
        CallBackground.pipHost = refreshCallPip
        refreshCallPip()
    }

    override fun onDestroy() {
        if (CallBackground.pipHost === refreshCallPip) CallBackground.pipHost = null
        super.onDestroy()
    }

    /** Before Android 12 the window cannot shrink by itself: Home (the user leaving) asks for it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (supportsPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && callMayShrink()) {
            runCatching { enterPictureInPictureMode(callPipParams(onCall = true)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        CallBackground.setInPip(isInPictureInPictureMode)
    }

    private fun callPipParams(onCall: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4))
        if (onCall) {
            val status = CallBackground.status.value
            val micOff = status.muted || status.paused
            val micLabel = if (micOff) tr("Включить микрофон", "Unmute") else tr("Без звука", "Mute")
            val hangUp = tr("Завершить", "Hang up")
            builder.setActions(
                listOf(
                    RemoteAction(
                        Icon.createWithResource(this, if (micOff) R.drawable.ic_call_mic else R.drawable.ic_call_mic_off),
                        micLabel,
                        micLabel,
                        CallBackground.controlIntent(this, CallControl.MUTE),
                    ),
                    RemoteAction(
                        Icon.createWithResource(this, R.drawable.ic_call_end),
                        hangUp,
                        hangUp,
                        CallBackground.controlIntent(this, CallControl.HANG_UP),
                    ),
                ),
            )
        } else {
            builder.setActions(emptyList())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(onCall)
        return builder.build()
    }

    /**
     * Turns the launch intent into a one-off UI signal and "consumes" it: the action and the
     * conversation id are erased so the same intent object means nothing if it is read again.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_OPEN_NEW_CHAT, Intent.ACTION_ASSIST -> openNewChatRequest.value += 1
            ACTION_OPEN_CONVERSATION ->
                validConversationIdOrNull(intent.getStringExtra(EXTRA_CONVERSATION_ID))
                    ?.let { openConversationRequest.value = it }
            else -> return
        }
        intent.action = null
        intent.removeExtra(EXTRA_CONVERSATION_ID)
    }

    companion object {
        /** Open a new chat (the app was invoked as a voice assistant). */
        const val ACTION_OPEN_NEW_CHAT = "com.aiopenpocket.action.OPEN_NEW_CHAT"

        /** A tap on the "agent replied" notification — open a specific conversation. */
        const val ACTION_OPEN_CONVERSATION = "com.aiopenpocket.action.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        private val CONVERSATION_ID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * The activity is exported (launcher/assistant), so an intent with ACTION_OPEN_CONVERSATION can
         * come from any app. Our conversation ids are UUIDs only (ConversationRepository), everything
         * else is dropped: an id with a "/" used to throw right in the navigation LaunchedEffect.
         */
        internal fun validConversationIdOrNull(raw: String?): String? =
            raw?.trim()?.takeIf { CONVERSATION_ID_REGEX.matches(it) }
    }
}

/**
 * Leaves the call for the chat that holds it. A call started from that chat simply closes; one started by
 * the assistant button, or from a new chat that had no conversation yet, opens the conversation it created
 * in place of what was under it, so "Back" from there does not land on an empty chat.
 */
// @trace voice-call-mode-54cb49
private fun NavController.leaveCall(conversationId: String?) {
    popBackStack()
    if (conversationId == null) return
    val top = currentBackStackEntry
    val topIsChat = top?.destination?.route?.startsWith("chat/") == true
    if (topIsChat && top?.arguments?.getString("conversationId") == conversationId) return
    if (topIsChat) popBackStack()
    navigate("chat/$conversationId") { launchSingleTop = true }
}
