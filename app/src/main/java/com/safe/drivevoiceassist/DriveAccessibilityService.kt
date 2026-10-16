package com.safe.drivevoiceassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateFormat
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class DriveAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        var instance: DriveAccessibilityService? = null
        @Volatile var isDriveModeActive = false
        val logLines = ArrayList<String>()
        var logListener: ((String) -> Unit)? = null
        var stateListener: ((State) -> Unit)? = null
        @Volatile var state: State = State.STANDBY
        @Volatile var lastHeard: String = ""
        @Volatile var lastAction: String = ""
        @Volatile var engineName: String = "system"
        private const val TTS_ID = "kd_tts"

        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_WHATSAPP_BIZ = "com.whatsapp.w4b"
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_GMAIL = "com.google.android.gm"
        const val PKG_MAPS = "com.google.android.apps.maps"
    }

    enum class State { STANDBY, STARTING, LISTENING, PROCESSING, SPEAKING, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isListening = false
    private var isSpeaking = false
    private var listenAfterSpeech = true
    private var errorStreak = 0
    private var hardErrors = 0
    private var engineMode = 0          // 0 = system default, 1 = on-device, 2 = Google app
    private var engineSwitches = 0
    private lateinit var contacts: ContactResolver

    private sealed class Pending {
        class Confirm(val action: () -> Unit) : Pending()
        class Choose(val candidates: List<ContactResolver.Contact>, val onChosen: (ContactResolver.Contact) -> Unit) : Pending()
        class AwaitText(val onText: (String) -> Unit) : Pending()
    }
    private var pending: Pending? = null

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        contacts = ContactResolver(this)
        tts = TextToSpeech(this, this)
        setupRecognizer()
        log("Service connected")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            applyPrefs()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) { finishedSpeaking() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finishedSpeaking() }
                override fun onError(utteranceId: String?, errorCode: Int) { finishedSpeaking() }
            })
            speak("Drive Voice Assist connected.", false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isDriveModeActive = false
        recognizer?.destroy()
        tts?.shutdown()
        instance = null
    }

    private var langVariant = 0

    /** Google's recogniser prefers BCP-47 tags like cmn-Hans-CN; other engines want zh-CN. */
    private fun recognizerLangTag(pref: String): String {
        val variants = when (pref) {
            "zh-CN" -> listOf("cmn-Hans-CN", "zh-CN", "zh")
            "zh-TW" -> listOf("cmn-Hant-TW", "zh-TW", "zh")
            "ms-MY" -> listOf("ms-MY", "ms")
            "en-SG" -> listOf("en-SG", "en-US")
            else -> listOf(pref)
        }
        return variants[langVariant % variants.size]
    }

    private fun zh(): Boolean = Prefs.language(this).startsWith("zh")
    fun t(en: String, zhText: String): String = if (zh()) zhText else en

    fun applyPrefs() {
        val lang = Prefs.language(this)
        val tag = recognizerLangTag(lang)
        if (::recognizerIntent.isInitialized) {
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        }
        log("Language: $lang (tag $tag)")
        val locale = when {
            lang.startsWith("zh-TW") -> Locale.TRADITIONAL_CHINESE
            lang.startsWith("zh") -> Locale.SIMPLIFIED_CHINESE
            lang.startsWith("ms") -> Locale("ms", "MY")
            lang.startsWith("en-GB") -> Locale.UK
            else -> Locale.US
        }
        tts?.language = locale
    }

    fun setDriveMode(on: Boolean) {
        isDriveModeActive = on
        pending = null
        if (on) {
            applyPrefs()
            errorStreak = 0
            hardErrors = 0
            engineSwitches = 0
            Thread { contacts.all() }.start()
            log("Driving mode ON (engine: $engineName)")
            setState(State.STARTING)
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                speak("Speech recognition is not available on this phone. Please install or enable the Google app.", false)
                setState(State.ERROR)
                return
            }
            startListening()
        } else {
            log("Driving mode OFF")
            setState(State.STANDBY)
            handler.post { recognizer?.cancel(); isListening = false }
            try { stopService(Intent(this, ListeningService::class.java)) } catch (e: Exception) { }
        }
    }

    // ───────────────────────── logging / speech ─────────────────────────

    private fun setState(st: State) {
        state = st
        handler.post { stateListener?.invoke(st) }
    }

    private fun action(msg: String) {
        lastAction = msg
        log("✔ $msg")
    }

    fun log(msg: String) {
        handler.post {
            val line = "${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}  $msg"
            logLines.add(line)
            if (logLines.size > 80) logLines.removeAt(0)
            logListener?.invoke(line)
        }
    }

    fun speak(text: String, listenAfter: Boolean = true) {
        log("▶ $text")
        val t = tts
        if (t == null || !ttsReady) { if (listenAfter) scheduleListen(500); return }
        handler.post {
            recognizer?.cancel()
            isListening = false
            isSpeaking = true
            listenAfterSpeech = listenAfter
            if (isDriveModeActive) setState(State.SPEAKING)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_ID)
        }
    }

    private fun finishedSpeaking() {
        handler.post {
            isSpeaking = false
            if (isDriveModeActive) setState(State.STARTING) else setState(State.STANDBY)
            if (listenAfterSpeech) scheduleListen(300)
        }
    }

    // ───────────────────────── speech recognition ─────────────────────────

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        10 -> "TOO_MANY_REQUESTS"
        11 -> "SERVER_DISCONNECTED"
        12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"
        else -> "ERROR_$code"
    }

    /** Builds a recogniser for the current engineMode, falling back to the system default. */
    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            when (engineMode) {
                1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    engineName = "on-device"
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    engineName = "system"
                    SpeechRecognizer.createSpeechRecognizer(this)
                }
                2 -> {
                    val google = ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                    )
                    engineName = "google"
                    SpeechRecognizer.createSpeechRecognizer(this, google)
                }
                else -> {
                    engineName = "system"
                    SpeechRecognizer.createSpeechRecognizer(this)
                }
            }
        } catch (e: Exception) {
            log("⚠ cannot create recogniser (${e.message})")
            engineName = "system"
            try { SpeechRecognizer.createSpeechRecognizer(this) } catch (e2: Exception) { null }
        }
    }

    private fun switchEngine() {
        engineMode = (engineMode + 1) % 3
        engineSwitches++
        hardErrors = 0
        log("↻ switching speech engine → mode $engineMode")
        setupRecognizer()
        if (engineSwitches == 1) speak("Switching speech engine.")
        if (engineSwitches >= 4) {
            isDriveModeActive = false
            setState(State.ERROR)
            speak("Speech recognition keeps failing on this phone. Please check that the Google app is installed and updated, and that the microphone permission is allowed.", false)
        }
    }

    private fun setupRecognizer() {
        handler.post {
            try { recognizer?.destroy() } catch (e: Exception) { }
            recognizer = createRecognizer()
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLangTag(Prefs.language(this@DriveAccessibilityService)))
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognizerLangTag(Prefs.language(this@DriveAccessibilityService)))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; setState(State.LISTENING) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setState(State.PROCESSING) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    isListening = false
                    if (!isDriveModeActive || isSpeaking) return
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            isDriveModeActive = false
                            setState(State.ERROR)
                            log("⚠ NO_PERMISSION")
                            speak("Microphone permission is missing. Please allow it in the app.", false)
                            return
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            errorStreak = 0
                            hardErrors = 0
                            setState(State.STARTING)
                            scheduleListen(250)
                            return
                        }
                    }
                    log("⚠ ${errorName(error)} (engine: $engineName)")
                    if (error == 12 || error == 13) {
                        langVariant++
                        applyPrefs()
                        if (langVariant % 3 == 0) { switchEngine(); if (!isDriveModeActive) return }
                        setState(State.STARTING)
                        scheduleListen(600)
                        return
                    }
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        try { recognizer?.cancel() } catch (e: Exception) { }
                    }
                    errorStreak++
                    hardErrors++
                    if (hardErrors >= 3) { switchEngine(); if (!isDriveModeActive) return }
                    setState(State.STARTING)
                    scheduleListen(minOf(400L + errorStreak * 400L, 3000L))
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    errorStreak = 0
                    hardErrors = 0
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull { it.isNotBlank() }
                    if (text == null) { setState(State.STARTING); scheduleListen(250); return }
                    setState(State.PROCESSING)
                    handleUtterance(text.trim())
                    if (!isSpeaking) scheduleListen(400)
                }
            })
        }
    }

    fun startListening() {
        if (!isDriveModeActive) return
        handler.post {
            if (isListening || isSpeaking) return@post
            val r = recognizer ?: return@post
            try {
                isListening = true
                setState(State.STARTING)
                r.startListening(recognizerIntent)
            } catch (e: Exception) {
                isListening = false
                log("⚠ startListening failed: ${e.message}")
                scheduleListen(1500)
            }
        }
    }

    fun stopListening() {
        handler.post { recognizer?.cancel(); isListening = false }
    }

    private fun scheduleListen(delayMs: Long) {
        handler.postDelayed({ startListening() }, delayMs)
    }

    // ───────────────────────── command handling ─────────────────────────

    private fun handleUtterance(raw: String) {
        lastHeard = raw
        log("🎤 $raw")
        val p = pending
        if (p != null && handlePending(p, raw)) return
        execute(CommandParser.parse(raw))
    }

    private fun handlePending(p: Pending, raw: String): Boolean {
        val cmd = CommandParser.parse(raw)
        if (cmd is Command.No) { pending = null; speak(t("Cancelled.", "已取消。")); return true }
        return when (p) {
            is Pending.Confirm -> {
                if (cmd is Command.Yes || cmd is Command.Send) { pending = null; p.action(); true }
                else { pending = null; false }
            }
            is Pending.AwaitText -> {
                pending = null
                p.onText(CommandParser.cleanMessage(raw))
                true
            }
            is Pending.Choose -> {
                val idx = CommandParser.ordinal(raw)
                val q = ContactResolver.norm(raw)
                val chosen = if (idx != null && idx in p.candidates.indices) p.candidates[idx]
                else p.candidates.firstOrNull { q.isNotBlank() && ContactResolver.norm(it.name).contains(q) }
                if (chosen != null) { pending = null; p.onChosen(chosen); true }
                else { speak(t("Say one, two or three, or say cancel.", "请说一、二或三，或者说取消。")); true }
            }
        }
    }

    private fun execute(cmd: Command) {
        when (cmd) {
            is Command.Yes -> speak(t("Nothing to confirm.", "没有需要确认的。"))
            is Command.No -> speak(t("Okay.", "好的。"))
            is Command.Stop -> { speak(t("Driving mode stopped.", "驾驶模式已停止。"), false); setDriveMode(false) }
            is Command.Help -> speak(t("Try: WhatsApp Allan saying I'm on my way. Call Allan. Email Allan about the meeting. Open WeChat. Navigate to KLCC. Scroll down. Go back. Read screen. Reply, then your message. Or say send.", "你可以说：微信给小明说我在路上。打电话给小明。写邮件给小明关于会议。打开微信。导航到双子塔。向下滑。返回。朗读屏幕。回复，然后说内容。或者说发送。"))
            is Command.Back -> { performGlobalAction(GLOBAL_ACTION_BACK); scheduleListen(400) }
            is Command.Home -> { performGlobalAction(GLOBAL_ACTION_HOME); speak(t("Closed.", "已关闭。")) }
            is Command.ScrollDown -> { scroll(true); scheduleListen(600) }
            is Command.ScrollUp -> { scroll(false); scheduleListen(600) }
            is Command.ReadScreen -> readScreen()
            is Command.Send -> if (clickSendButton()) speak(t("Sent.", "已发送。")) else speak(t("I can't find a send button on this screen.", "在这个屏幕上找不到发送按钮。"))
            is Command.OpenApp -> openApp(cmd.appName)
            is Command.Navigate -> navigate(cmd.destination)
            is Command.Call -> callContact(cmd.contactQuery)
            is Command.SendMessage -> startSendMessage(cmd.app, cmd.contactQuery, cmd.message)
            is Command.Reply -> replyInCurrentChat(cmd.message)
            is Command.Type -> if (typeIntoFocused(cmd.text)) speak(t("Typed. Say send to send it.", "已输入。说发送来发出去。")) else speak(t("No text box found.", "找不到输入框。"))
            is Command.Click -> clickText(cmd.target)
            is Command.Email -> startEmail(cmd)
            is Command.Unknown -> { action("Not understood"); speak(t("Sorry, I didn't get that. Say help for commands.", "抱歉，我没听懂。说帮助查看指令。")) }
        }
    }

    // ───────────────────────── apps & navigation ─────────────────────────

    private val appAliases = mapOf(
        "whatsapp" to listOf(PKG_WHATSAPP, PKG_WHATSAPP_BIZ),
        "whatsapp business" to listOf(PKG_WHATSAPP_BIZ),
        "wechat" to listOf(PKG_WECHAT),
        "gmail" to listOf(PKG_GMAIL), "email" to listOf(PKG_GMAIL), "mail" to listOf(PKG_GMAIL),
        "maps" to listOf(PKG_MAPS), "google maps" to listOf(PKG_MAPS), "map" to listOf(PKG_MAPS),
        "waze" to listOf("com.waze"),
        "youtube" to listOf("com.google.android.youtube"),
        "spotify" to listOf("com.spotify.music"),
        "telegram" to listOf("org.telegram.messenger"),
        "messages" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
        "phone" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "dialer" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "camera" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera"),
        "chrome" to listOf("com.android.chrome"), "browser" to listOf("com.android.chrome"),
        "facebook" to listOf("com.facebook.katana"),
        "instagram" to listOf("com.instagram.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "grab" to listOf("com.grabtaxi.passenger"),
        "settings" to listOf("com.android.settings"),
        "calendar" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
        "mt5" to listOf("net.metaquotes.metatrader5"), "metatrader" to listOf("net.metaquotes.metatrader5"),
        "mt4" to listOf("net.metaquotes.metatrader4")
    )

    private fun isInstalled(pkg: String) = packageManager.getLaunchIntentForPackage(pkg) != null

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    private fun resolveApp(name: String): Pair<String, String>? {
        val q = name.lowercase(Locale.ROOT).trim()
        appAliases[q]?.firstOrNull { isInstalled(it) }?.let { return it to name }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var best: Pair<String, String>? = null
        var bestScore = 0
        try {
            for (ri in packageManager.queryIntentActivities(main, 0)) {
                val label = ri.loadLabel(packageManager).toString()
                val l = label.lowercase(Locale.ROOT)
                val score = when {
                    l == q -> 100
                    l.startsWith(q) -> 80
                    l.contains(q) -> 60
                    ContactResolver.similarity(l, q) >= 0.8 -> 50
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; best = ri.activityInfo.packageName to label }
            }
        } catch (e: Exception) { }
        return best
    }

    private fun openApp(name: String) {
        val app = resolveApp(name)
        if (app == null || !launch(app.first)) { speak(t("I couldn't find an app called $name.", "找不到叫 $name 的应用。")); return }
        action("Open ${app.second}"); speak(t("Opening ${app.second}.", "正在打开 ${app.second}。"))
    }

    private fun navigate(dest: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
                .setPackage(PKG_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled(PKG_MAPS)) { startActivity(i); speak(t("Navigating to $dest.", "正在导航到 $dest。")); return }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speak("Searching for $dest.")
        } catch (e: Exception) {
            speak("No maps app found.")
        }
    }

    // ───────────────────────── calls ─────────────────────────

    private fun callContact(query: String) {
        val res = contacts.resolveWithRemainder(query)
        val cands = res.candidates.filter { it.phones.isNotEmpty() }
        when {
            cands.isEmpty() -> speak(t("I couldn't find $query in your contacts.", "在联系人里找不到 $query。"))
            res.ambiguous && cands.size > 1 -> askChoose(cands) { dial(it) }
            else -> dial(cands.first())
        }
    }

    private fun dial(c: ContactResolver.Contact) {
        try {
            action("Call ${c.name}"); speak(t("Calling ${c.name}.", "正在拨打 ${c.name}。"))
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.phones.first()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            speak("I'm not allowed to make calls. Please grant the phone permission.")
        }
    }

    private fun askChoose(cands: List<ContactResolver.Contact>, onChosen: (ContactResolver.Contact) -> Unit) {
        val top = cands.take(3)
        pending = Pending.Choose(top, onChosen)
        val names = top.mapIndexed { i, c -> "${i + 1}, ${c.name}" }.joinToString(". ")
        speak(t("I found ${top.size} contacts. $names. Which one?", "找到 ${top.size} 个联系人。$names。请说第几个？"))
    }

    // ───────────────────────── messaging ─────────────────────────

    private fun appLabel(app: String) = when (app) { "wechat" -> "WeChat"; "sms" -> "SMS"; else -> "WhatsApp" }

    private fun startSendMessage(app: String, query: String, message: String?) {
        if (query.isBlank()) { speak(t("Who should I message?", "要发给谁？")); return }
        val res = contacts.resolveWithRemainder(query)
        if (res.candidates.isEmpty()) { speak(t("I couldn't find a contact named $query.", "找不到叫 $query 的联系人。")); return }
        val msg = message ?: res.remainder.takeIf { it.isNotBlank() }?.let { CommandParser.cleanMessage(it) }
        val resolvedApp = if (app == "default") {
            if (isInstalled(PKG_WHATSAPP) || isInstalled(PKG_WHATSAPP_BIZ)) "whatsapp" else "sms"
        } else app
        if (res.ambiguous) askChoose(res.candidates) { c -> continueMessage(resolvedApp, c, msg) }
        else continueMessage(resolvedApp, res.candidates.first(), msg)
    }

    private fun continueMessage(app: String, c: ContactResolver.Contact, msg: String?) {
        if (msg == null) {
            pending = Pending.AwaitText { t -> continueMessage(app, c, t) }
            speak(t("What's the message for ${c.firstName()}?", "要给 ${c.firstName()} 发什么内容？"))
            return
        }
        if (Prefs.confirm(this)) {
            pending = Pending.Confirm { doSend(app, c, msg) }
            speak(t("${appLabel(app)} to ${c.name}: $msg. Say yes to send.", "用${appLabel(app)}发给 ${c.name}：$msg。说好来发送。"))
        } else doSend(app, c, msg)
    }

    private fun doSend(app: String, c: ContactResolver.Contact, msg: String) {
        when (app) {
            "wechat" -> sendWeChat(c, msg)
            "sms" -> sendSms(c, msg)
            else -> sendWhatsApp(c, msg)
        }
    }

    private fun sendWhatsApp(c: ContactResolver.Contact, msg: String) {
        val pkg = listOf(PKG_WHATSAPP, PKG_WHATSAPP_BIZ).firstOrNull { isInstalled(it) }
        if (pkg == null) { speak(t("WhatsApp is not installed.", "没有安装 WhatsApp。")); return }
        val phone = c.phones.firstOrNull()
        if (phone == null) { speak("${c.name} has no phone number."); return }
        val intl = ContactResolver.toInternational(phone, Prefs.countryCode(this))
        try {
            val uri = Uri.parse("https://wa.me/$intl?text=" + Uri.encode(msg))
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { speak("Could not open WhatsApp."); return }
        log("WhatsApp → ${c.name} ($intl)")
        speak(t("Opening WhatsApp.", "正在打开 WhatsApp。"), false)
        retry(12, 700,
            { nodeByViewId("$pkg:id/send")?.let { clickNode(it) } ?: false },
            { action("WhatsApp sent to ${c.name}"); speak(t("Sent to ${c.name}.", "已发送给 ${c.name}。")) },
            { speak(t("The message is ready in WhatsApp. Say send when you see it.", "信息已经在 WhatsApp 里准备好了。看到后说发送。")) })
    }

    private fun sendSms(c: ContactResolver.Contact, msg: String) {
        val phone = c.phones.firstOrNull()
        if (phone == null) { speak("${c.name} has no phone number."); return }
        try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))
                .putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) { speak("No SMS app found."); return }
        speak("Opening messages.", false)
        retry(8, 700, { clickSendButton() }, { speak("SMS sent to ${c.name}.") }, { speak("SMS is ready. Say send.") })
    }

    private fun sendWeChat(c: ContactResolver.Contact, msg: String) {
        if (!isInstalled(PKG_WECHAT)) { speak(t("WeChat is not installed.", "没有安装微信。")); return }
        val name = c.name
        launch(PKG_WECHAT)
        speak(t("Opening WeChat.", "正在打开微信。"), false)
        handler.postDelayed({
            val search = nodeByDesc("Search", "搜索") ?: nodeByText("Search", "搜索")
            if (search == null || !clickNode(search)) { speak("I can't find WeChat search. Open the chat yourself and say reply."); return@postDelayed }
            handler.postDelayed({
                val box = firstEditable()
                if (box == null || !setTextOn(box, name)) { speak("Couldn't type the contact name in WeChat."); return@postDelayed }
                handler.postDelayed({
                    val hit = findNode(root()) { n ->
                        n.isVisibleToUser && !n.isEditable && (n.text?.toString()?.contains(name, true) == true)
                    }
                    if (hit == null || !clickNode(hit)) { speak(t("I couldn't find $name in WeChat.", "在微信里找不到 $name。")); return@postDelayed }
                    handler.postDelayed({
                        val input = firstEditable()
                        if (input == null || !setTextOn(input, msg)) { speak("Couldn't type the message in WeChat."); return@postDelayed }
                        retry(6, 500,
                            { (nodeByText("Send", "发送") ?: nodeByDesc("Send", "发送"))?.let { clickNode(it) } ?: false },
                            { action("WeChat sent to $name"); speak(t("Sent to $name on WeChat.", "已在微信发送给 $name。")) },
                            { speak(t("Message typed in WeChat. Say send.", "信息已输入微信。说发送。")) })
                    }, 1500)
                }, 1800)
            }, 900)
        }, 2200)
    }

    private fun replyInCurrentChat(msg: String) {
        if (!typeIntoFocused(msg)) { speak("No chat box found. Open a chat first."); return }
        val go = {
            retry(4, 400, { clickSendButton() }, { speak("Sent.") }, { speak("Typed, but I couldn't find send. Say send.") })
        }
        if (Prefs.confirm(this)) {
            pending = Pending.Confirm { go() }
            speak(t("Reply: $msg. Say yes to send.", "回复：$msg。说好来发送。"))
        } else go()
    }

    // ───────────────────────── email ─────────────────────────

    private fun startEmail(cmd: Command.Email) {
        val q = cmd.contactQuery
        if (q.isNullOrBlank()) { composeEmail(null, cmd.topic, cmd.body); return }
        val res = contacts.resolveWithRemainder(q)
        if (res.candidates.isEmpty()) { speak("I couldn't find $q in your contacts."); return }
        val body = cmd.body ?: res.remainder.takeIf { it.isNotBlank() }?.let { CommandParser.cleanMessage(it) }
        if (res.ambiguous) askChoose(res.candidates) { composeEmail(it, cmd.topic, body) }
        else composeEmail(res.candidates.first(), cmd.topic, body)
    }

    private fun composeEmail(c: ContactResolver.Contact?, topic: String?, body: String?) {
        if (topic == null && body == null) {
            pending = Pending.AwaitText { t -> composeEmail(c, null, t) }
            speak(if (c == null) t("What should the email say?", "邮件要写什么？") else t("What should the email to ${c.firstName()} say?", "给 ${c.firstName()} 的邮件要写什么？"))
            return
        }
        val name = c?.name ?: ""
        val to = c?.emails?.firstOrNull()
        val warn = if (c != null && to == null) "${c.name} has no email address saved, so you'll need to add one. " else ""
        val sender = Prefs.userName(this)
        val key = Prefs.apiKey(this)
        if (key.isNotBlank()) {
            speak(t("Drafting the email.", "正在撰写邮件。"), false)
            AiDrafter.draftEmail(key, Prefs.model(this), name, topic, body, sender) { result ->
                handler.post {
                    if (result != null) openComposer(to, result.first, result.second, warn)
                    else {
                        val d = AiDrafter.templateEmail(name, topic, body, sender)
                        openComposer(to, d.first, d.second, warn + "AI drafting failed, using a simple draft. ")
                    }
                }
            }
        } else {
            val d = AiDrafter.templateEmail(name, topic, body, sender)
            openComposer(to, d.first, d.second, warn)
        }
    }

    private fun openComposer(to: String?, subject: String, text: String, note: String) {
        val uri = Uri.parse("mailto:" + (to ?: "") + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(text))
        val i = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled(PKG_GMAIL)) i.setPackage(PKG_GMAIL)
        try { startActivity(i) } catch (e: Exception) { speak("No email app found."); return }
        if (Prefs.autoSendEmail(this) && to != null) {
            speak("${note}Sending the email.", false)
            retry(10, 700, { clickSendButton() }, { speak("Email sent. Subject: $subject.") }, { speak("Email is ready. Say send to send it.") })
        } else {
            speak(t("${note}Email ready. Subject: $subject. Check it and say send.", "${note}邮件已准备好。主题：$subject。检查后说发送。"))
        }
    }

    // ───────────────────────── screen helpers ─────────────────────────

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun findNode(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val f = findNode(node.getChild(i), pred)
            if (f != null) return f
        }
        return null
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, pred: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null) return
        if (pred(node)) out.add(node)
        for (i in 0 until node.childCount) collectNodes(node.getChild(i), out, pred)
    }

    private fun nodeByViewId(id: String): AccessibilityNodeInfo? =
        try { root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() } catch (e: Exception) { null }

    private fun nodeByDesc(vararg descs: String): AccessibilityNodeInfo? = findNode(root()) { n ->
        val d = n.contentDescription?.toString()?.trim()
        d != null && n.isVisibleToUser && descs.any { it.equals(d, true) }
    }

    private fun nodeByText(vararg texts: String): AccessibilityNodeInfo? = findNode(root()) { n ->
        val t = n.text?.toString()?.trim()
        t != null && n.isVisibleToUser && texts.any { it.equals(t, true) }
    }

    private fun firstEditable(): AccessibilityNodeInfo? = findNode(root()) { it.isEditable && it.isVisibleToUser }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        if (node != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) return tap(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun typeIntoFocused(text: String): Boolean {
        val focused = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = if (focused != null && focused.isEditable) focused else firstEditable()
        return target != null && setTextOn(target, text)
    }

    private fun clickSendButton(): Boolean {
        val ids = listOf(
            "$PKG_WHATSAPP:id/send", "$PKG_WHATSAPP_BIZ:id/send", "$PKG_GMAIL:id/send",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.samsung.android.messaging:id/send_button"
        )
        for (id in ids) nodeByViewId(id)?.let { if (clickNode(it)) return true }
        nodeByDesc("Send", "发送", "Send message")?.let { if (clickNode(it)) return true }
        nodeByText("Send", "发送")?.let { if (clickNode(it)) return true }
        return false
    }

    private fun clickText(target: String) {
        val r = root()
        if (r == null) { speak("Nothing on screen."); return }
        val direct = try { r.findAccessibilityNodeInfosByText(target) } catch (e: Exception) { null }
        direct?.forEach { if (clickNode(it)) { speak("Clicked $target."); return } }
        val fuzzy = findNode(r) { n ->
            n.isVisibleToUser && ((n.text?.toString()?.contains(target, true) == true) || (n.contentDescription?.toString()?.contains(target, true) == true))
        }
        if (fuzzy != null && clickNode(fuzzy)) { speak("Clicked $target."); return }
        speak("I can't find $target on the screen.")
    }

    private fun readScreen() {
        val out = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root(), out) { n ->
            val t = n.text?.toString()?.trim()
            !t.isNullOrEmpty() && t.length > 1 && n.isVisibleToUser && !n.isEditable
        }
        val texts = out.mapNotNull { it.text?.toString()?.trim() }.distinct()
        if (texts.isEmpty()) { speak("Nothing to read."); return }
        speak(texts.takeLast(12).joinToString(". ").take(700))
    }

    private fun scroll(down: Boolean) {
        val scrollable = findNode(root()) { it.isScrollable && it.isVisibleToUser }
        if (scrollable != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) return
        }
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val startY = if (down) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
        val endY = if (down) dm.heightPixels * 0.25f else dm.heightPixels * 0.75f
        val path = Path().apply { moveTo(midX, startY); lineTo(midX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun retry(attempts: Int, delayMs: Long, action: () -> Boolean, onSuccess: () -> Unit, onFail: () -> Unit) {
        handler.postDelayed({
            val ok = try { action() } catch (e: Exception) { false }
            when {
                ok -> onSuccess()
                attempts <= 1 -> onFail()
                else -> retry(attempts - 1, delayMs, action, onSuccess, onFail)
            }
        }, delayMs)
    }
}
