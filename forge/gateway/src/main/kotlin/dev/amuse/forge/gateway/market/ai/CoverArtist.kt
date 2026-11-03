package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.gateway.ai.GeneratedImage
import dev.amuse.forge.gateway.ai.MetaModels

// @trace market-ai-8f3b21

/**
 * A Muse Image cover for each listing, in one house style so the market reads as one shop. The
 * listing's own words only fill the subject line: they are flattened and cut, and the style asks for
 * no text in the picture (a cover with a stranger's slogan baked in is a cover nobody moderated).
 */
class CoverArtist(private val models: MetaModels) {

    suspend fun draw(title: String, summary: String): GeneratedImage = models.image(prompt(title, summary))

    fun prompt(title: String, summary: String): String =
        "$STYLE Subject: an object or scene that stands for \"${flat(title, 60)}\" - ${flat(summary, 160)}."

    private fun flat(text: String, max: Int) = text.replace(Regex("[\\s\"]+"), " ").trim().take(max)

    private companion object {
        const val STYLE =
            "Flat editorial illustration for an app store card. Soft pastel palette on a warm off-white background, " +
                "one clear central subject, gentle shadows, generous empty space. " +
                "No text, no letters, no numbers, no logos, no people's faces."
    }
}
