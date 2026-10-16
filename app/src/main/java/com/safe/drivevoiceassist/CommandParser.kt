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
    data class SetLanguage(val lang: String) : Command()
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
        if (hasCjk(raw)) parseChinese(raw)?.let { return it }
        val s = normalize(raw)
        if (s.isBlank()) return Command.Unknown(raw)
        if (s in yesWords) return Command.Yes
        if (s in noWords) return Command.No

        if (Regex("^(?:switch|change) (?:to |language to )?(?:chinese|mandarin|中文)$").matches(s) || s == "chinese mode" || s == "中文") return Command.SetLanguage("zh-CN")
        if (Regex("^(?:switch|change) (?:to |language to )?english$").matches(s) || s == "english mode") return Command.SetLanguage("en-US")
        if (Regex("^(stop|end|exit|quit|turn off|disable) (driving mode|drive mode|listening|voice assist|voice mode|the assistant)$").matches(s)) return Command.Stop
        if (Regex("^(help|what can you do|what can i say|commands|list commands|show commands)$").matches(s)) return Command.Help
        if (Regex("^(go )?(back|previous|return)$").matches(s)) return Command.Back
        if (Regex("^(?:go |return )?(?:home|close|exit|quit|minimize|kill|leave|dismiss)(?: (?:the |this |that |current )?(?:app|application|apps|it|this|screen|window|page|.+))?$").matches(s)) return Command.Home
        if (Regex("^(?:back|return|go) (?:to )?(?:the )?home(?: screen)?$").matches(s)) return Command.Home
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
            "1", "one", "first", "first one", "一", "第一", "第一个", "一号" -> 0
            "2", "two", "second", "second one", "to", "too", "二", "两", "第二", "第二个", "二号" -> 1
            "3", "three", "third", "third one", "三", "第三", "第三个", "三号" -> 2
            else -> null
        }
    }

    // ───────────────────────── 中文 ─────────────────────────

    private val cjk = Regex("[\\u4e00-\\u9fff]")
    fun hasCjk(s: String): Boolean = cjk.containsMatchIn(s)

    private val zhFillers = listOf("请你", "请", "麻烦你", "麻烦", "帮我", "帮忙", "嘿", "你好", "hey king", "king", "现在", "快点", "可以", "能不能", "帮我把")
    private val zhYes = setOf("是", "是的", "好", "好的", "对", "对的", "确定", "确认", "可以", "行", "没问题", "发吧", "发", "ok", "okay", "嗯")
    private val zhNo = setOf("不", "不要", "不用", "取消", "算了", "错了", "不对", "不是", "别", "别发", "停")
    private val zhBodySeps = listOf("说的是", "内容是", "内容为", "告诉他", "告诉她", "跟他说", "跟她说", "说", "讲", "内容", "：", ":")
    private val zhTopicSeps = listOf("关于", "有关", "主题是", "主题")
    private val zhApps = mapOf(
        "微信" to "wechat", "地图" to "maps", "谷歌地图" to "maps", "导航" to "maps", "油管" to "youtube", "视频" to "youtube",
        "电话" to "phone", "拨号" to "phone", "相机" to "camera", "照相机" to "camera", "设置" to "settings", "浏览器" to "chrome",
        "日历" to "calendar", "短信" to "messages", "信息" to "messages", "脸书" to "facebook", "抖音" to "tiktok",
        "邮件" to "gmail", "邮箱" to "gmail", "电邮" to "gmail", "音乐" to "spotify", "电报" to "telegram", "脸书" to "facebook", "ig" to "instagram"
    )

    private fun normalizeZh(raw: String): String {
        var s = raw.trim().lowercase(Locale.ROOT)
        s = s.replace(Regex("[，。！？、：；,.!?;\"'“”‘’]"), " ")
        s = s.replace("whats app", "whatsapp").replace("what's app", "whatsapp").replace("微 信", "微信")
        // drop spaces between Chinese characters (recognisers sometimes insert them)
        s = s.replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in zhFillers) {
                if (s == f) { s = ""; changed = true; break }
                if (s.startsWith(f)) { s = s.substring(f.length).trim(); changed = true; break }
            }
        }
        return s
    }

    private fun zhSplit(rest: String, seps: List<String>): Pair<String, String?> {
        var cut = -1; var len = 0
        for (sep in seps) {
            val i = rest.indexOf(sep)
            if (i >= 0 && (cut < 0 || i < cut)) { cut = i; len = sep.length }
        }
        if (cut < 0) return rest.trim() to null
        return rest.substring(0, cut).trim() to cleanMessage(rest.substring(cut + len))
    }

    private fun zhStrip(s: String, vararg lead: String): String {
        var r = s.trim()
        var changed = true
        while (changed) {
            changed = false
            for (w in lead) if (r.startsWith(w)) { r = r.substring(w.length).trim(); changed = true }
        }
        return r
    }

    fun parseChinese(raw: String): Command? {
        val s = normalizeZh(raw)
        if (s.isBlank()) return null
        if (s in zhYes) return Command.Yes
        if (s in zhNo) return Command.No
        if (Regex("^(切换|换|改|用|转)(成|到|为)?(英文|英语|english)(模式)?$").matches(s) || s == "english" || s == "英文模式") return Command.SetLanguage("en-US")
        if (Regex("^(切换|换|改|用|转)(成|到|为)?(中文|华语|普通话)(模式)?$").matches(s) || s == "中文模式" || s == "中文") return Command.SetLanguage("zh-CN")
        if (Regex("^(停止|关闭|退出|结束|关掉)(驾驶模式|驾驶|监听|语音助手|语音模式|语音)$").matches(s)) return Command.Stop
        if (Regex("^(帮助|你能做什么|有什么命令|有什么指令|指令|命令)$").matches(s)) return Command.Help
        if (Regex("^(返回|后退|上一页|退回|回去)$").matches(s)) return Command.Back
        if (Regex("^(回到?主页|回到?主屏幕|主页|桌面|回到桌面|回主画面)$").matches(s)) return Command.Home
        if (Regex("^(关闭|关掉|关|退出|最小化|结束)(.*)$").matches(s)) return Command.Home
        if (Regex("^(向下|往下|下)(滑|滚|翻|滚动|滑动|翻页)(.*)$").matches(s) || s == "下一页") return Command.ScrollDown
        if (Regex("^(向上|往上|上)(滑|滚|翻|滚动|滑动|翻页)(.*)$").matches(s) || s == "上一页") return Command.ScrollUp
        if (Regex("^(朗读|读|念)(屏幕|一下|出来|信息|消息|这个|内容|一读|屏|一遍|给我听)?$").matches(s)) return Command.ReadScreen
        if (Regex("^(发送|发出去|送出|发出|传送)$").matches(s)) return Command.Send

        Regex("^(?:导航到|导航去|导航|带我去|开车去|去)(.+)$").find(s)?.let { return Command.Navigate(it.groupValues[1].trim()) }
        Regex("^(?:打电话给|打给|拨打给|拨打|呼叫|打电话|致电|拨给|拨)(.+)$").find(s)?.let { return Command.Call(zhStrip(it.groupValues[1], "给")) }

        Regex("^(?:写邮件给|发邮件给|发电邮给|写电邮给|邮件给|电邮给|写邮件|发邮件|写电邮|发电邮|邮件|电邮|email|发email给|写email给)(.*)$").find(s)?.let { m ->
            val rest = zhStrip(m.groupValues[1], "给")
            if (rest.isBlank()) return Command.Email(null, null, null)
            val padded = rest
            var cut = -1; var len = 0; var isBody = false
            for (sep in zhBodySeps) { val i = padded.indexOf(sep); if (i >= 0 && (cut < 0 || i < cut)) { cut = i; len = sep.length; isBody = true } }
            for (sep in zhTopicSeps) { val i = padded.indexOf(sep); if (i >= 0 && (cut < 0 || i < cut)) { cut = i; len = sep.length; isBody = false } }
            if (cut < 0) return Command.Email(rest, null, null)
            val name = padded.substring(0, cut).trim().ifBlank { null }
            val after = padded.substring(cut + len).trim()
            return if (isBody) Command.Email(name, null, cleanMessage(after)) else Command.Email(name, after, null)
        }

        // messaging: 发微信给X说Y / 微信X说Y / whatsapp X 说 Y / 发短信给X / 发信息给X说Y / 告诉X Y / 跟X说Y
        Regex("^跟(.+?)说(.+)$").find(s)?.let { return Command.SendMessage("default", it.groupValues[1].trim(), cleanMessage(it.groupValues[2])) }
        Regex("^(?:用|通过|发|传|发送)?(?:微信|whatsapp|短信|简讯|信息|消息|讯息|whatsapp信息|微信信息|微信消息)(?:信息|消息)?(?:给|发给|发送给)?(.+)$").find(s)?.let { m ->
            val app = when {
                s.startsWith("用微信") || s.startsWith("微信") || s.startsWith("发微信") || s.startsWith("通过微信") || s.startsWith("传微信") -> "wechat"
                s.contains("whatsapp") -> "whatsapp"
                s.contains("短信") || s.contains("简讯") -> "sms"
                else -> "default"
            }
            var rest = m.groupValues[1].trim()
            var appOverride = app
            for ((kw, a) in listOf("用微信" to "wechat", "通过微信" to "wechat", "在微信" to "wechat", "用whatsapp" to "whatsapp", "通过whatsapp" to "whatsapp", "在whatsapp" to "whatsapp", "用短信" to "sms")) {
                if (rest.contains(kw)) { appOverride = a; rest = rest.replace(kw, "") }
            }
            val (name, msg) = zhSplit(zhStrip(rest, "给"), zhBodySeps)
            return Command.SendMessage(appOverride, name, msg)
        }
        Regex("^告诉(.+)$").find(s)?.let { m ->
            val (name, msg) = zhSplit(m.groupValues[1], zhBodySeps)
            return Command.SendMessage("default", name, msg)
        }

        Regex("^(?:回复|回答|回)(.+)$").find(s)?.let { return Command.Reply(cleanMessage(zhStrip(it.groupValues[1], "说", "他", "她"))) }
        Regex("^(?:输入|打字|键入)(.+)$").find(s)?.let { return Command.Type(cleanMessage(it.groupValues[1])) }
        Regex("^(?:点击|点一下|点|按|按下|选择|选)(.+)$").find(s)?.let { return Command.Click(it.groupValues[1].trim()) }
        Regex("^(?:打开|开启|启动|进入|开|切换到)(.+)$").find(s)?.let { m ->
            val name = m.groupValues[1].trim().removeSuffix("应用").removeSuffix("app").trim()
            return Command.OpenApp(zhApps[name] ?: name)
        }
        if (s == "微信" || s == "whatsapp") return Command.OpenApp(zhApps[s] ?: s)
        return Command.Unknown(raw)
    }
}
