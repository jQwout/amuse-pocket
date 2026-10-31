package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
//
// Rendering of Driven-UI cards: the RenderDrivenUiCard dispatcher, the lesson card's light theme with its
// palette, and the small pieces every card shares (title row, divider, success color). The *CardView
// themselves live next door: WidgetCardViews.kt, LiveCardViews.kt, ImageCardView.kt, LessonCardViews.kt.
// Separated from DrivenUiEngine.kt, which kept the card model and JSON parsing.

import com.aiopenpocket.ui.chat.DrivenUiCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.flow.Flow

internal val CardSuccessGreen = Color(0xFF4CAF50)

// Dark theme: lesson cards are drawn in light silver with dark text (rather than a dark surfaceVariant,
// "dark on dark"). LessonCardTheme overrides the scheme's surface family for the card's subtree, so the
// existing onSurfaceVariant/outline/surface inside become "for a light card" on their own.
// The card's neutrals. They used to be cool lavender-greys (#DCDCE4/#CBCDD8/#2A2730/#6B6B75) — next to a
// lime-and-paper app that read as a violet card, and on the device it was the first thing the eye caught.
// Same job, warm stone instead.
private val LessonCardSilver = Color(0xFFE4E3D9)   // the card background (surfaceVariant)
private val LessonCardPanel = Color(0xFFD6D4C8)    // inner panels (surface)
private val LessonCardInk = Color(0xFF26251F)      // text (onSurface/onSurfaceVariant)
private val LessonCardOutline = Color(0xFF5C5A4E)  // captions and dividers (outline)
// Semantic quiz colors (fill/sort/match/scenario color "selected/wrong" through primary/error): the theme's
// own lime is made for a dark background and fades on light stone — we take dark saturated versions,
// readable on LessonCardSilver, with a light on-color for container uses. The accent used to be a dark
// PURPLE here, which is why quiz headings, the "Show an example" button and the "in this app" badge — all of
// which draw through `primary` — came out violet inside an otherwise lime app.
private val LessonCardPrimary = Color(0xFF3F5214)  // primary (selected/right) — deep olive
private val LessonCardOnPrimary = Color(0xFFF3F6E6)
private val LessonCardError = Color(0xFFB3261E)    // error (wrong) — dark red (the M3 light error)
private val LessonCardOnError = Color(0xFFFFFFFF)
private val LessonCardSecondary = Color(0xFF5A5B42) // secondary (rare accent icons)
// The lesson scenes are full of FilterChips, and a chip's SELECTED state draws through secondaryContainer.
// That role was left out of this override, so every picked chip inside a lesson card fell back to Material's
// purple — on a stone card, in a lime app. Same family as the rest of the card.
private val LessonCardSecondaryContainer = Color(0xFFCBD9A8)
private val LessonCardOnSecondaryContainer = Color(0xFF26330A)
private val LessonCardTertiary = Color(0xFF6E5118)   // the third accent (segmentation boxes, tags)
private val LessonCardOnTertiary = Color(0xFFFFFFFF)
private val LessonCardTertiaryContainer = Color(0xFFEEDDB4)
private val LessonCardOnTertiaryContainer = Color(0xFF33250A)

/** In the dark theme swaps the scheme's surface family for light silver in the lesson card's subtree. */
@Composable
private fun LessonCardTheme(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            surface = LessonCardPanel,
            surfaceVariant = LessonCardSilver,
            onSurface = LessonCardInk,
            onSurfaceVariant = LessonCardInk,
            outline = LessonCardOutline,
            primary = LessonCardPrimary,
            onPrimary = LessonCardOnPrimary,
            error = LessonCardError,
            onError = LessonCardOnError,
            secondary = LessonCardSecondary,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = LessonCardSecondaryContainer,
            onSecondaryContainer = LessonCardOnSecondaryContainer,
            tertiary = LessonCardTertiary,
            onTertiary = LessonCardOnTertiary,
            tertiaryContainer = LessonCardTertiaryContainer,
            onTertiaryContainer = LessonCardOnTertiaryContainer,
        ),
        content = content,
    )
}

@Composable
fun RenderDrivenUiCard(
    // A ready card rather than a JSON spec: the bubble has already parsed the spec deciding interactive/markdown
    // — a second parse of the same JSON on the main thread at the item's first composition was redundant.
    card: DrivenUiCard,
    onFormSubmitted: (String) -> Unit = {},
    onChoiceSelected: (String) -> Unit = {},
    onLessonComplete: () -> Unit = {},
    onLessonQuizComplete: () -> Unit = {},
    /** The quiz's saved answers (messages.cardState) and where to write them back. */
    cardState: String? = null,
    onCardStateChanged: (String?) -> Unit = {},
    // The Academy capstone (Task 23): a tap on the CTA of the lesson_complete card. Zero-arg — which
    // AgentSourceContext it means is decided by the caller (ChatScreen), not by the card engine.
    onOpenAgentBuilder: () -> Unit = {},
    /** The agent builder chat's "Review your agent" card: opens the review screen of this draft. */
    onOpenAgentReview: (String) -> Unit = {},
    onAction: (String, Boolean) -> Unit = { _, _ -> },
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = { kotlinx.coroutines.flow.flowOf(null) },
    onCancelHeartbeat: (String) -> Unit = {},
    /** true → a lesson card: in the dark theme it is drawn in light silver with dark text. */
    lessonCard: Boolean = false,
    /** Image prompts being redrawn "for me" right now — their button shows "Drawing…". */
    personalizingImages: Set<String> = emptySet(),
    onPersonalizeImage: (DrivenUiCard.ImageCard) -> Unit = {},
    /** The tasks of "video" steps whose clips the model is watching right now. */
    analyzingVideos: Set<String> = emptySet(),
    onLessonVideo: (videoPrompt: String, video: android.net.Uri) -> Unit = { _, _ -> },
    /** The default prompts of "segment" steps whose photos SAM is working on right now. */
    segmentingPrompts: Set<String> = emptySet(),
    onLessonSegment: (cardPrompt: String, concept: String, photo: android.net.Uri) -> Unit = { _, _, _ -> },
) {
    // The gate is only lessonCard, not isSystemInDarkTheme(): the app's theme is chosen in Settings
    // (Light/Dark/System, see Theme.kt), so the system flag can disagree with what is on screen — and in a
    // forced dark theme a lesson card would stay dark on the dark aurora (exactly the unreadability this
    // code fixes). A lesson card is drawn light in any theme.
    val useLightCard = lessonCard
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (useLightCard) LessonCardSilver
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .padding(16.dp)
    ) {
        LessonCardTheme(useLightCard) {
        // The surface content color is explicit — as Surface(color = surfaceVariant) would do: texts without an
        // explicit color (the reveal/fill answer, the concept definition, sort/match items) do not take the
        // ~black default.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        when (card) {
            is DrivenUiCard.TodoList -> TodoListCardView(card)
            is DrivenUiCard.WeatherCard -> WeatherCardView(card)
            is DrivenUiCard.TimerCard -> TimerCardView(card)
            is DrivenUiCard.TableCard -> TableCardView(card)
            is DrivenUiCard.ChartBarCard -> ChartBarCardView(card)
            is DrivenUiCard.FormCard -> FormCardView(card, onFormSubmitted)
            is DrivenUiCard.ChoiceCard -> ChoiceCardView(card, onChoiceSelected)
            is DrivenUiCard.MultiChoiceCard -> MultiChoiceCardView(card, cardState, onCardStateChanged, onChoiceSelected)
            is DrivenUiCard.AgentReviewCard -> AgentReviewCardView(card, onOpenAgentReview)
            is DrivenUiCard.LessonQuizCard -> LessonQuizCardView(card, onLessonQuizComplete, cardState, onCardStateChanged)
            is DrivenUiCard.LessonCompleteCard -> LessonCompleteCardView(card, onLessonComplete, onOpenAgentBuilder)
            is DrivenUiCard.SwitchCard -> SwitchCardView(card, onAction)
            is DrivenUiCard.NotificationPermissionCard -> NotificationPermissionCardView(card)
            is DrivenUiCard.ReminderCard -> ReminderCardView(card, observeHeartbeat, onCancelHeartbeat)
            // The "Draw it for me" button — only for lesson images with a prompt: an image the agent drew in a
            // normal chat has nothing to be redrawn "for the lesson".
            is DrivenUiCard.ImageCard -> ImageCardView(
                card = card,
                personalizing = card.prompt in personalizingImages,
                onPersonalize = if (lessonCard && card.prompt.isNotBlank()) {
                    { onPersonalizeImage(card) }
                } else {
                    null
                },
            )
            is DrivenUiCard.LessonAnimationCard -> LessonAnimationCardView(card)
            is DrivenUiCard.LessonVideoCard -> LessonVideoCardView(
                card = card,
                analyzing = card.prompt in analyzingVideos,
                onVideoChosen = { uri -> onLessonVideo(card.prompt, uri) },
            )
            is DrivenUiCard.LessonSegmentCard -> LessonSegmentCardView(
                card = card,
                analyzing = card.prompt in segmentingPrompts,
                onPhotoChosen = { concept, photo -> onLessonSegment(card.prompt, concept, photo) },
            )
            is DrivenUiCard.LessonSegmentResultCard -> LessonSegmentResultCardView(card)
            is DrivenUiCard.RevealCard -> RevealCardView(card)
            is DrivenUiCard.ConceptCard -> ConceptCardView(card)
            is DrivenUiCard.ExampleCard -> ExampleCardView(card)
            is DrivenUiCard.FillCard -> FillCardView(card, onLessonQuizComplete)
            is DrivenUiCard.SortCard -> SortCardView(card, onLessonQuizComplete)
            is DrivenUiCard.MatchCard -> MatchCardView(card, onLessonQuizComplete)
            is DrivenUiCard.ScenarioCard -> ScenarioCardView(card, onLessonQuizComplete)
            is DrivenUiCard.ErrorCard -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = tr("Ошибка", "Error"),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = card.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        }
        }
    }
}

@Composable
internal fun CardTitleRow(icon: ImageVector, title: String, iconTint: Color? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
}
