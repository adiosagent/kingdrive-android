package com.safe.drivevoiceassist

import java.util.Locale

sealed class Command {
    data class OpenApp(val appName: String) : Command()
    data class SendMessage(val app: String, val contactQuery: String, val message: String?) : Command()
    data class Call(val contactQuery: String) : Command()
    data class Email(val contactQuery: String?, val topic: String?, val body: String?) : Command()
    data class Navigate(val destination: String) : Command()
    data class Click(val target: String) : Command()
    data class Type(val text: String) : Command()
    data class Reply(val message: String) : Command()
    data class Unknown(val raw: String) : Command()
    object Send : Command()
    object ScrollDown : Command()
    object ScrollUp : Command()
    object Back : Command()
    object Home : Command()
    object ReadScreen : Command()
    object Yes : Command()
    object No : Command()
    object Stop : Command()
    object Help : Command()
}

/**
 * Turns free-form speech into a Command. Tolerant of filler words, many phrasings,
 * and speech-recognizer quirks ("whats app", "we chat", "e mail").
 */
object CommandParser {

    private val fillers = listOf(
        "hey king", "ok king", "okay king", "hey google", "hey siri", "king", "hey", "hi",
        "ok", "okay", "please", "can you", "could you", "would you", "will you",
        "help me to", "help me", "i want to", "i want you to", "i need to", "i need you to",
        "let's", "lets", "just", "now", "quickly"
    )

    private val yesWords = setOf(
        "yes", "yes please", "yep", "yup", "yeah", "ya", "yah", "confirm", "confirmed", "correct",
        "go ahead", "sure", "do it", "proceed", "right", "affirmative"
    )
    private val noWords = setOf(
        "no", "nope", "cancel", "cancel it", "never mind", "nevermind", "don't", "dont", "do not",
        "wrong", "abort", "forget it", "stop it", "no no", "negative", "cancel that"
    )

    private val bodySeps = listOf(
        " saying that ", " saying ", " say that ", " say ", " telling ", " that says ", " that ",
        " to say ", " with the message ", " the message ", " message "
    )
    private val topicSeps = listOf(" about ", " regarding ", " on the topic of ", " concerning ")

    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).trim()
        s = s.replace(Regex("[^\\p{L}\\p{N}@'+\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace("whats app", "whatsapp")
            .replace("what's app", "whatsapp")
            .replace("what sap", "whatsapp")
            .replace("what's up message", "whatsapp message")
            .replace("we chat", "wechat")
            .replace("e mail", "email")
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in fillers) {
                if (s == f) { s = ""; changed = true; break }
                if (s.startsWith("$f ")) { s = s.substring(f.length + 1).trim(); changed = true; break }
            }
        }
        return s
    }

    fun parse(raw: String): Command {
        val s = normalize(raw)
        if (s.isBlank()) return Command.Unknown(raw)
        if (s in yesWords) return Command.Yes
        if (s in noWords) return Command.No

        if (Regex("^(stop|end|exit|quit|turn off|disable) (driving mode|drive mode|listening|voice assist|voice mode|the assistant)$").matches(s)) return Command.Stop
        if (Regex("^(help|what can you do|what can i say|commands|list commands|show commands)$").matches(s)) return Command.Help
        if (Regex("^(go )?(back|previous|return)$").matches(s)) return Command.Back
        if (Regex("^(go )?(home|close|close app|close this|close it|exit|exit app|minimize)$").matches(s)) return Command.Home
        if (Regex("^(scroll|swipe|page) ?(down|lower|next)( .*)?$").matches(s) || s == "next page") return Command.ScrollDown
        if (Regex("^(scroll|swipe|page) ?(up|top|previous)( .*)?$").matches(s)) return Command.ScrollUp
        if (Regex("^(read|read screen|read this|read the screen|read it|read message|read messages|read the message|read the messages|read last message|read chat|what does it say|what did they say)$").matches(s)) return Command.ReadScreen
        if (Regex("^(send|send it|send now|send message|send the message|press send|tap send|click send|hit send)$").matches(s)) return Command.Send

        Regex("^(?:navigate|navigation|directions?|take me|drive|bring me|go) to (.+)$").find(s)?.let {
            return Command.Navigate(it.groupValues[1].trim())
        }
        Regex("^(?:call|phone|dial|ring|make a call to|give a call to|call to)\\s+(.+)$").find(s)?.let {
            return Command.Call(stripLead(it.groupValues[1], "to", "my", "contact"))
        }

        // Email: "email allan about the meeting", "write an email to allan saying ...", "email"
        Regex("^(?:write|send|compose|draft|create)?\\s*(?:an? |the )?(?:email|mail)\\s*(?:to\\s+)?(.*)$").find(s)?.let { m ->
            return parseEmail(m.groupValues[1].trim())
        }

        // "whatsapp allan saying ...", "message allan ...", "send a text to allan ..."
        Regex("^(?:send (?:an? |the )?)?(whatsapp|wechat|sms|text message|text|message|msg|dm)\\s+(?:message\\s+)?(?:to\\s+)?(.+)$").find(s)?.let { m ->
            val app = when (m.groupValues[1]) { "whatsapp" -> "whatsapp"; "wechat" -> "wechat"; "sms", "text", "text message" -> "sms"; else -> "default" }
            return buildSend(app, m.groupValues[2])
        }
        // "send allan a message saying hi" / "send allan a whatsapp that ..."
        Regex("^send\\s+(.+?)\\s+(?:a |an |the )?(whatsapp|message|text|sms|wechat)(?:\\s+(?:saying|say|that|to say)\\s+(.+))?$").find(s)?.let { m ->
            val app = when (m.groupValues[2]) { "whatsapp" -> "whatsapp"; "wechat" -> "wechat"; "text", "sms" -> "sms"; else -> "default" }
            val msg = m.groupValues[3].takeIf { it.isNotBlank() }?.let { cleanMessage(it) }
            return Command.SendMessage(app, stripLead(m.groupValues[1], "to", "my", "contact"), msg)
        }

        Regex("^tell\\s+(.+)$").find(s)?.let { return buildSend("default", it.groupValues[1]) }

        Regex("^(?:reply|respond|answer)\\s+(?:with\\s+|saying\\s+|that\\s+)?(.+)$").find(s)?.let {
            return Command.Reply(cleanMessage(it.groupValues[1]))
        }
        Regex("^(?:type|write|enter|input|dictate)\\s+(.+)$").find(s)?.let {
            return Command.Type(cleanMessage(it.groupValues[1]))
        }
        Regex("^(?:click|tap|press|select|choose|hit)\\s+(?:on\\s+|the\\s+)?(.+)$").find(s)?.let {
            return Command.Click(it.groupValues[1].trim())
        }
        Regex("^(?:open|launch|start|run|switch to|show)\\s+(?:the\\s+|my\\s+)?(.+)$").find(s)?.let {
            return Command.OpenApp(it.groupValues[1].removeSuffix(" app").trim())
        }
        if (s in setOf("whatsapp", "wechat", "gmail", "maps", "youtube", "spotify", "waze", "telegram")) return Command.OpenApp(s)

        return Command.Unknown(raw)
    }

    private fun buildSend(appIn: String, restIn: String): Command {
        var app = appIn
        var rest = " ${restIn.trim()} "
        val appHints = listOf(
            " on whatsapp " to "whatsapp", " via whatsapp " to "whatsapp", " by whatsapp " to "whatsapp", " using whatsapp " to "whatsapp", " in whatsapp " to "whatsapp",
            " on wechat " to "wechat", " via wechat " to "wechat", " by wechat " to "wechat", " using wechat " to "wechat", " in wechat " to "wechat",
            " by sms " to "sms", " via sms " to "sms", " by text " to "sms"
        )
        for ((kw, a) in appHints) {
            if (rest.contains(kw)) { app = a; rest = rest.replace(kw, " ") }
        }
        rest = rest.replace(Regex("\\s+"), " ").trim()
        val (name, msg) = splitNameMessage(rest)
        return Command.SendMessage(app, stripLead(name, "to", "my", "contact"), msg)
    }

    private fun splitNameMessage(rest: String): Pair<String, String?> {
        val padded = " $rest "
        var cut = -1
        var sepLen = 0
        for (sep in bodySeps) {
            val i = padded.indexOf(sep)
            if (i >= 0 && (cut < 0 || i < cut)) { cut = i; sepLen = sep.length }
        }
        if (cut < 0) return rest to null
        val before = padded.substring(0, cut).trim()
        val after = padded.substring(cut + sepLen).trim()
        return before to cleanMessage(after)
    }

    private fun parseEmail(rest: String): Command {
        if (rest.isBlank()) return Command.Email(null, null, null)
        val r = stripLead(rest, "to", "my", "contact")
        val padded = " $r "
        var cut = -1
        var sepLen = 0
        var isBody = false
        for (sep in bodySeps) {
            val i = padded.indexOf(sep)
            if (i >= 0 && (cut < 0 || i < cut)) { cut = i; sepLen = sep.length; isBody = true }
        }
        for (sep in topicSeps) {
            val i = padded.indexOf(sep)
            if (i >= 0 && (cut < 0 || i < cut)) { cut = i; sepLen = sep.length; isBody = false }
        }
        if (cut < 0) return Command.Email(r, null, null)
        val before = padded.substring(0, cut).trim()
        val after = padded.substring(cut + sepLen).trim()
        val name = before.ifBlank { null }
        return if (isBody) Command.Email(name, null, cleanMessage(after)) else Command.Email(name, after, null)
    }

    private fun stripLead(s: String, vararg words: String): String {
        var r = s.trim()
        var changed = true
        while (changed) {
            changed = false
            for (w in words) {
                if (r.startsWith("$w ")) { r = r.substring(w.length + 1).trim(); changed = true }
            }
        }
        return r
    }

    /** Light cleanup for dictated text: spoken punctuation, capital first letter. */
    fun cleanMessage(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        t = t.replace(Regex("\\s+(full stop|period)\\b", RegexOption.IGNORE_CASE), ".")
            .replace(Regex("\\s+comma\\b", RegexOption.IGNORE_CASE), ",")
            .replace(Regex("\\s+question mark\\b", RegexOption.IGNORE_CASE), "?")
            .replace(Regex("\\s+exclamation (mark|point)\\b", RegexOption.IGNORE_CASE), "!")
            .replace(Regex("\\s+new line\\b", RegexOption.IGNORE_CASE), "\n")
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    /** "one" / "first" / "number two" → zero-based index. */
    fun ordinal(raw: String): Int? {
        val s = normalize(raw).removePrefix("number ").removePrefix("the ").trim()
        return when (s) {
            "1", "one", "first", "first one" -> 0
            "2", "two", "second", "second one", "to", "too" -> 1
            "3", "three", "third", "third one" -> 2
            else -> null
        }
    }
}
