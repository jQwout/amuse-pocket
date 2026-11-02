package dev.amuse.forge.gateway.ai

import dev.amuse.forge.gateway.TaskRunner

/** Scripted Meta models: answers are queued per call, every prompt is kept for assertions. */
class FakeMetaModels(
    private val answer: (system: String, user: String) -> String = { _, _ -> "{}" },
    private val picture: GeneratedImage = GeneratedImage("PNGBYTES".toByteArray(), "image/png"),
    private val failImages: Boolean = false,
) : MetaModels {
    override val chatModel = "muse-spark-test"
    val chats = mutableListOf<Pair<String, String>>()
    val imagePrompts = mutableListOf<String>()

    override suspend fun complete(system: String, user: String, effort: String?): String {
        chats += system to user
        return answer(system, user)
    }

    override suspend fun image(prompt: String): GeneratedImage {
        imagePrompts += prompt
        if (failImages) throw MetaCallException(500, "image model down")
        return picture
    }
}

/** Collects background work so a test decides when it runs. */
class QueuedTaskRunner : TaskRunner {
    private val tasks = ArrayDeque<suspend () -> Unit>()

    override fun submit(name: String, task: suspend () -> Unit) {
        tasks.addLast(task)
    }

    val pending: Int get() = tasks.size

    suspend fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }
}
