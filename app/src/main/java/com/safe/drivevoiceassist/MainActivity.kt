package com.safe.drivevoiceassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // palette
    private val bg = Color.parseColor("#0B1220")
    private val card = Color.parseColor("#141D2F")
    private val cardLine = Color.parseColor("#1F2A40")
    private val textPrimary = Color.parseColor("#F1F5F9")
    private val textMuted = Color.parseColor("#8B97AD")
    private val accent = Color.parseColor("#0EA5E9")
    private val green = Color.parseColor("#22C55E")
    private val amber = Color.parseColor("#F59E0B")
    private val red = Color.parseColor("#EF4444")

    private lateinit var orb: TextView
    private lateinit var stateText: TextView
    private lateinit var heardText: TextView
    private lateinit var actionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var logText: TextView
    private lateinit var checkAccess: TextView
    private lateinit var checkMic: TextView
    private lateinit var checkContacts: TextView
    private lateinit var checkPhone: TextView

    private lateinit var langSpinner: Spinner
    private lateinit var confirmSwitch: Switch
    private lateinit var autoEmailSwitch: Switch
    private lateinit var ccInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText

    private val languages = listOf(
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "en-SG" to "English (Singapore)",
        "zh-CN" to "中文 (普通话)",
        "zh-TW" to "中文 (台灣)",
        "ms-MY" to "Bahasa Melayu"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }

        // ── header ──
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "🎙"
                textSize = 22f
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Drive Voice Assist"
                    textSize = 22f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Hands-free WhatsApp · WeChat · calls · email"
                    textSize = 12f
                    setTextColor(textMuted)
                })
            })
        }, lp(0, dp(14)))

        // ── status card ──
        val status = cardView()
        orb = TextView(this).apply {
            text = "🎤"
            textSize = 40f
            gravity = Gravity.CENTER
            background = circle(cardLine)
        }
        status.addView(orb, LinearLayout.LayoutParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        stateText = TextView(this).apply {
            text = "STANDBY"
            textSize = 15f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }
        status.addView(stateText)
        heardText = TextView(this).apply {
            text = "Say a command after starting driving mode"
            textSize = 18f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
        }
        status.addView(heardText)
        actionText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(accent)
            gravity = Gravity.CENTER
        }
        status.addView(actionText)
        root.addView(status, lp(0, dp(12)))

        // ── main button ──
        toggleButton = Button(this).apply {
            text = "START DRIVING MODE"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(green, dp(14))
            setPadding(dp(16), dp(20), dp(16), dp(20))
            isAllCaps = false
            setOnClickListener { toggleDriveMode() }
        }
        root.addView(toggleButton, lp(0, dp(18)))

        // ── setup checklist ──
        val setup = cardView()
        setup.addView(sectionTitle("SETUP"))
        checkAccess = checkRow("Accessibility service") { showPlayStoreDisclosureDialog() }
        checkMic = checkRow("Microphone permission") { requestPermissions() }
        checkContacts = checkRow("Contacts permission") { requestPermissions() }
        checkPhone = checkRow("Phone permission") { requestPermissions() }
        setup.addView(checkAccess); setup.addView(checkMic); setup.addView(checkContacts); setup.addView(checkPhone)
        setup.addView(TextView(this).apply {
            text = "If the accessibility toggle is greyed out: Settings → Apps → Drive Voice Assist → ⋮ → Allow restricted settings."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(setup, lp(0, dp(12)))

        // ── live log ──
        val logCard = cardView()
        logCard.addView(sectionTitle("ACTIVITY"))
        logText = TextView(this).apply {
            text = "No activity yet."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#C7D2E3"))
            setLineSpacing(0f, 1.15f)
        }
        logCard.addView(logText)
        root.addView(logCard, lp(0, dp(12)))

        // ── commands ──
        val cmd = cardView()
        cmd.addView(sectionTitle("WHAT YOU CAN SAY"))
        val examples = listOf(
            "WhatsApp Allan saying I'm on my way" to "sends it, asks you to confirm",
            "WhatsApp Allan" to "asks what the message is",
            "Tell Allan I'll be 10 minutes late" to "WhatsApp by default",
            "WeChat Allan saying …  ·  Text Allan …" to "WeChat / SMS",
            "Call Allan" to "fuzzy name match, asks if several",
            "Email Allan about the meeting tomorrow" to "drafts subject + body, opens Gmail",
            "Email Allan saying I will send the file tonight" to "your exact words",
            "Reply on my way" to "types and sends in the open chat",
            "Open WeChat / YouTube / Maps / any app" to "",
            "Navigate to KLCC" to "Google Maps",
            "Read screen · Scroll down · Go back · Home" to "",
            "Click [button text] · Send" to "",
            "Yes · No · Cancel · Stop driving mode" to ""
        )
        for ((say, does) in examples) {
            cmd.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(this@MainActivity).apply { text = "“$say”"; textSize = 14f; setTextColor(textPrimary) })
                if (does.isNotEmpty()) addView(TextView(this@MainActivity).apply { text = does; textSize = 12f; setTextColor(textMuted) })
            })
        }
        root.addView(cmd, lp(0, dp(12)))

        // ── settings ──
        val settings = cardView()
        settings.addView(sectionTitle("SETTINGS"))
        val prefs = Prefs.get(this)

        settings.addView(fieldLabel("Speech language"))
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.second })
            background = rounded(Color.parseColor("#E2E8F0"), dp(8))
            val idx = languages.indexOfFirst { it.first == prefs.getString("lang", "en-US") }
            setSelection(if (idx >= 0) idx else 0)
        }
        settings.addView(langSpinner, lp(0, dp(6)))

        confirmSwitch = switchRow("Ask \"yes\" before sending messages", prefs.getBoolean("confirm", true))
        settings.addView(confirmSwitch)
        autoEmailSwitch = switchRow("Send emails automatically (no review)", prefs.getBoolean("auto_email", false))
        settings.addView(autoEmailSwitch)

        settings.addView(fieldLabel("Country code for WhatsApp numbers (Malaysia = 60)"))
        ccInput = input("60", prefs.getString("cc", "60") ?: "60").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        settings.addView(ccInput)

        settings.addView(fieldLabel("Your name (signs your emails)"))
        nameInput = input("e.g. Allan", prefs.getString("user_name", "") ?: "")
        settings.addView(nameInput)

        settings.addView(fieldLabel("Claude API key (optional – AI-written emails)"))
        keyInput = input("sk-ant-…", prefs.getString("api_key", "") ?: "", password = true)
        settings.addView(keyInput)

        settings.addView(fieldLabel("AI model"))
        modelInput = input("claude-haiku-4-5", prefs.getString("model", "claude-haiku-4-5") ?: "claude-haiku-4-5")
        settings.addView(modelInput)

        settings.addView(Button(this).apply {
            text = "Save settings"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(accent, dp(10))
            setOnClickListener { savePrefs(); Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show() }
        }, lp(dp(14), 0))
        root.addView(settings, lp(0, dp(12)))

        root.addView(TextView(this).apply {
            text = "Drive Voice Assist v1.2 · Keep your eyes on the road."
            textSize = 11f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        scroll.addView(root)
        setContentView(scroll)
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        DriveAccessibilityService.logListener = { refreshAll() }
        DriveAccessibilityService.stateListener = { refreshAll() }
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        DriveAccessibilityService.logListener = null
        DriveAccessibilityService.stateListener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
    }

    // ───────────────────────── UI state ─────────────────────────

    private fun refreshAll() {
        val svc = DriveAccessibilityService.instance
        val active = DriveAccessibilityService.isDriveModeActive
        val st = DriveAccessibilityService.state

        val (label, color, icon) = when {
            svc == null -> Triple("ACCESSIBILITY OFF", red, "⚠")
            !active -> Triple("STANDBY", textMuted, "🎤")
            st == DriveAccessibilityService.State.LISTENING -> Triple("LISTENING", green, "🎤")
            st == DriveAccessibilityService.State.PROCESSING -> Triple("THINKING", amber, "💭")
            st == DriveAccessibilityService.State.SPEAKING -> Triple("SPEAKING", accent, "🔊")
            st == DriveAccessibilityService.State.ERROR -> Triple("ERROR", red, "⚠")
            else -> Triple("GETTING READY", amber, "⏳")
        }
        stateText.text = label
        stateText.setTextColor(color)
        orb.text = icon
        orb.background = circle(if (color == textMuted) cardLine else blend(color, bg, 0.35f))

        val heard = DriveAccessibilityService.lastHeard
        heardText.text = if (heard.isBlank()) {
            if (active) "Listening… say a command" else "Say a command after starting driving mode"
        } else "“$heard”"
        actionText.text = DriveAccessibilityService.lastAction

        if (active) {
            toggleButton.text = "STOP DRIVING MODE"
            toggleButton.background = rounded(red, dp(14))
        } else {
            toggleButton.text = "START DRIVING MODE"
            toggleButton.background = rounded(green, dp(14))
        }

        setCheck(checkAccess, "Accessibility service", svc != null)
        setCheck(checkMic, "Microphone permission", granted(Manifest.permission.RECORD_AUDIO))
        setCheck(checkContacts, "Contacts permission", granted(Manifest.permission.READ_CONTACTS))
        setCheck(checkPhone, "Phone permission", granted(Manifest.permission.CALL_PHONE))

        val lines = DriveAccessibilityService.logLines
        logText.text = if (lines.isEmpty()) "No activity yet." else lines.takeLast(9).joinToString("\n")
    }

    private fun granted(p: String) = ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setCheck(v: TextView, name: String, ok: Boolean) {
        v.text = (if (ok) "✅  " else "❌  ") + name + (if (ok) "" else "   — tap to fix")
        v.setTextColor(if (ok) textPrimary else amber)
    }

    // ───────────────────────── actions ─────────────────────────

    private fun savePrefs() {
        Prefs.get(this).edit()
            .putString("lang", languages[langSpinner.selectedItemPosition].first)
            .putBoolean("confirm", confirmSwitch.isChecked)
            .putBoolean("auto_email", autoEmailSwitch.isChecked)
            .putString("cc", ccInput.text.toString().trim().ifBlank { "60" })
            .putString("user_name", nameInput.text.toString().trim())
            .putString("api_key", keyInput.text.toString().trim())
            .putString("model", modelInput.text.toString().trim().ifBlank { "claude-haiku-4-5" })
            .apply()
        DriveAccessibilityService.instance?.applyPrefs()
    }

    private fun showPlayStoreDisclosureDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Disclosure")
            .setMessage(
                "Drive Voice Assist requires the Accessibility Service API to function.\n\n" +
                        "Why we need it:\n" +
                        "• To read screen buttons so you can say 'Click' hands-free while driving.\n" +
                        "• To scroll, and to type and send dictated messages in WhatsApp, WeChat and email on your behalf.\n\n" +
                        "Privacy Guarantee:\n" +
                        "We do NOT collect, store, or share any personal data, screen content, or voice recordings.\n\n" +
                        "If the toggle is greyed out: Settings → Apps → Drive Voice Assist → ⋮ → Allow restricted settings."
            )
            .setPositiveButton("Agree & Enable") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Decline", null)
            .show()
    }

    private fun toggleDriveMode() {
        val service = DriveAccessibilityService.instance
        if (service == null) { showPlayStoreDisclosureDialog(); return }
        if (!DriveAccessibilityService.isDriveModeActive) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return }
            savePrefs()
            try { ContextCompat.startForegroundService(this, Intent(this, ListeningService::class.java)) } catch (e: Exception) { }
            service.setDriveMode(true)
            if (DriveAccessibilityService.isDriveModeActive) service.speak("Driving mode active. I'm listening.")
        } else {
            service.setDriveMode(false)
            service.speak("Driving mode stopped.", false)
        }
        refreshAll()
    }

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.CALL_PHONE)
        perms.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { !granted(it) }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }

    // ───────────────────────── view helpers ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int, bottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val r = Color.red(c1) * ratio + Color.red(c2) * (1 - ratio)
        val g = Color.green(c1) * ratio + Color.green(c2) * (1 - ratio)
        val b = Color.blue(c1) * ratio + Color.blue(c2) * (1 - ratio)
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, dp(16))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textMuted)
        setPadding(0, 0, 0, dp(8))
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(textMuted)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun checkRow(name: String, onClick: () -> Unit) = TextView(this).apply {
        text = name
        textSize = 14f
        setTextColor(textPrimary)
        setPadding(0, dp(6), 0, dp(6))
        setOnClickListener { onClick() }
    }

    private fun switchRow(text: String, checked: Boolean) = Switch(this).apply {
        this.text = text
        isChecked = checked
        textSize = 14f
        setTextColor(textPrimary)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun input(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        setTextColor(textPrimary)
        setHintTextColor(textMuted)
        background = rounded(cardLine, dp(8))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}
