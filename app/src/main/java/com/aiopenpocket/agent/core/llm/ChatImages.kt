package com.aiopenpocket.agent.core.llm

// @trace photos-in-chat-bca88e

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.aiopenpocket.agent.core.lesson.prepareSegmentPhoto
import java.io.File
import java.util.UUID

/**
 * Photos attached to chat messages: copied into the app's own files and sent to the model as a data URL.
 *
 * A copy, not the picker's content URI: that URI stops resolving after the picker's grant ends, and the
 * photo must still show in the chat (and still reach the model on "Retry") later. The copy is the same
 * upright, downscaled JPEG the SAM lesson sends (prepareSegmentPhoto): a phone photo is several MB, and a
 * model reads a dish or a label just as well downscaled.
 */
object ChatImages {

    private const val DIR = "chat_images"

    /** Copies the photo at [uri] into the app's files. Null when it cannot be read. Blocking: call off Main. */
    fun import(context: Context, uri: Uri): String? = runCatching {
        prepareSegmentPhoto(
            open = { context.contentResolver.openInputStream(uri) },
            outputDir = File(context.filesDir, DIR),
            name = "${UUID.randomUUID()}.jpg",
        )?.file?.absolutePath
    }.getOrNull()

    /** The photo at [path] as a `data:image/jpeg;base64,…` URL for `image_url`, or null if the file is gone. */
    fun dataUrl(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
}
