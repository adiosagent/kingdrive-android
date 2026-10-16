package com.safe.drivevoiceassist

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Drafts emails: a plain template by default, or Claude when an API key is configured. */
object AiDrafter {

    fun templateEmail(name: String, topic: String?, body: String?, sender: String): Pair<String, String> {
        val first = name.split(" ").firstOrNull { it.isNotBlank() } ?: ""
        val greet = if (first.isBlank()) "Hi," else "Hi $first,"
        val sign = if (sender.isBlank()) "Best regards" else "Best regards,\n$sender"
        return if (!body.isNullOrBlank()) {
            val subject = body.split(" ").take(6).joinToString(" ").cap()
            subject to "$greet\n\n${body.cap()}\n\n$sign"
        } else {
            val t = (topic ?: "a quick update").trim()
            t.cap() to "$greet\n\nI'm currently on the road and wanted to reach out regarding $t. I'll follow up with the full details as soon as I'm available.\n\n$sign"
        }
    }

    fun draftEmail(
        apiKey: String, model: String, name: String, topic: String?, body: String?, sender: String,
        callback: (Pair<String, String>?) -> Unit
    ) {
        Thread {
            try {
                val who = if (name.isBlank()) "the recipient" else name
                val from = if (sender.isBlank()) "me" else sender
                val instruction = buildString {
                    append("Write an email from $from to $who. ")
                    if (!body.isNullOrBlank()) append("The sender dictated this while driving; clean it up but keep the meaning: \"$body\". ")
                    else append("Topic: ${topic ?: "a quick update"}. ")
                    append("Keep it under 120 words, friendly and professional.")
                }
                val payload = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 400)
                    .put("system", "You write short, polite emails on behalf of a driver dictating by voice. Reply with 'Subject: <subject>' on the first line, then a blank line, then the email body. No commentary.")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", instruction)))

                val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) { callback(null); return@Thread }
                val text = conn.inputStream.bufferedReader().readText()
                val content = JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text").trim()
                val lines = content.lines()
                val subject = lines.firstOrNull { it.startsWith("Subject:", true) }?.substringAfter(":")?.trim()
                    ?: (topic ?: "Message").cap()
                val bodyText = lines.dropWhile { it.startsWith("Subject:", true) || it.isBlank() }.joinToString("\n").trim()
                callback(subject to bodyText)
            } catch (e: Exception) {
                callback(null)
            }
        }.start()
    }

    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}
