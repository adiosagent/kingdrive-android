package com.safe.drivevoiceassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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

    private lateinit var toggleButton: Button
    private lateinit var statusIndicator: TextView
    private lateinit var logText: TextView
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

        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F172A")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        layout.addView(TextView(this).apply {
            text = "DRIVE VOICE ASSIST"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        })
        statusIndicator = TextView(this).apply {
            text = "Status: Standby"
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
        }
        layout.addView(statusIndicator)

        layout.addView(Button(this).apply {
            text = "STEP 1: ENABLE ACCESSIBILITY"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { showPlayStoreDisclosureDialog() }
        }, matchWrap(0, dp(8)))

        toggleButton = Button(this).apply {
            text = "STEP 2: START DRIVING MODE"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(22), dp(16), dp(22))
            setOnClickListener { toggleDriveMode() }
        }
        layout.addView(toggleButton, matchWrap(0, dp(16)))

        layout.addView(label("LIVE LOG"))
        logText = TextView(this).apply {
            text = "Waiting…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(120)
        }
        layout.addView(logText)

        layout.addView(label("VOICE COMMANDS"))
        layout.addView(TextView(this).apply {
            text = "• \"WhatsApp Allan saying I'm on my way\"\n" +
                    "• \"WhatsApp Allan\" → asks for the message\n" +
                    "• \"Tell Allan I'll be 10 minutes late\"\n" +
                    "• \"WeChat Allan saying …\"  /  \"Text Allan …\"\n" +
                    "• \"Call Allan\"\n" +
                    "• \"Email Allan about the meeting tomorrow\"\n" +
                    "• \"Email Allan saying I will send the file tonight\"\n" +
                    "• \"Reply on my way\"  (in an open chat)\n" +
                    "• \"Open WeChat\" / \"Open YouTube\" / any app name\n" +
                    "• \"Navigate to KLCC\"\n" +
                    "• \"Read screen\" / \"Scroll down\" / \"Go back\" / \"Home\"\n" +
                    "• \"Click [button text]\" / \"Send\"\n" +
                    "• \"Yes\" / \"No\" / \"Cancel\"  /  \"Stop driving mode\""
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })

        layout.addView(label("SETTINGS"))
        val prefs = Prefs.get(this)

        layout.addView(label("Speech language", 13f))
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.second })
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            val idx = languages.indexOfFirst { it.first == prefs.getString("lang", "en-US") }
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(langSpinner)

        confirmSwitch = Switch(this).apply {
            text = "Ask \"yes\" before sending messages"
            isChecked = prefs.getBoolean("confirm", true)
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
        }
        layout.addView(confirmSwitch)

        autoEmailSwitch = Switch(this).apply {
            text = "Send emails automatically (no review)"
            isChecked = prefs.getBoolean("auto_email", false)
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }
        layout.addView(autoEmailSwitch)

        layout.addView(label("Country code for WhatsApp numbers (Malaysia = 60)", 13f))
        ccInput = input("60", prefs.getString("cc", "60") ?: "60").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        layout.addView(ccInput)

        layout.addView(label("Your name (used to sign emails)", 13f))
        nameInput = input("e.g. Allan", prefs.getString("user_name", "") ?: "")
        layout.addView(nameInput)

        layout.addView(label("Claude API key (optional – AI-written emails)", 13f))
        keyInput = input("sk-ant-…", prefs.getString("api_key", "") ?: "", password = true)
        layout.addView(keyInput)

        layout.addView(label("AI model", 13f))
        modelInput = input("claude-haiku-4-5", prefs.getString("model", "claude-haiku-4-5") ?: "claude-haiku-4-5")
        layout.addView(modelInput)

        layout.addView(Button(this).apply {
            text = "SAVE SETTINGS"
            setBackgroundColor(Color.parseColor("#0EA5E9"))
            setTextColor(Color.WHITE)
            setOnClickListener { savePrefs(); Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show() }
        }, matchWrap(dp(12), 0))

        scrollView.addView(layout)
        setContentView(scrollView)
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        refreshLog()
        DriveAccessibilityService.logListener = { refreshLog() }
    }

    override fun onPause() {
        super.onPause()
        DriveAccessibilityService.logListener = null
    }

    private fun refreshLog() {
        val lines = DriveAccessibilityService.logLines
        logText.text = if (lines.isEmpty()) "Waiting…" else lines.takeLast(10).joinToString("\n")
        updateUI()
    }

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
            .setNegativeButton("Decline", null)
            .show()
    }

    private fun toggleDriveMode() {
        val service = DriveAccessibilityService.instance
        if (service == null) { showPlayStoreDisclosureDialog(); return }
        if (!DriveAccessibilityService.isDriveModeActive) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(); return
            }
            savePrefs()
            try { ContextCompat.startForegroundService(this, Intent(this, ListeningService::class.java)) } catch (e: Exception) { }
            service.setDriveMode(true)
            service.speak("Driving mode active. I'm listening.")
        } else {
            service.setDriveMode(false)
            service.speak("Driving mode stopped.", false)
        }
        updateUI()
    }

    private fun updateUI() {
        if (DriveAccessibilityService.instance == null) {
            statusIndicator.text = "Status: Accessibility not enabled"
            statusIndicator.setTextColor(Color.parseColor("#F87171"))
        } else if (DriveAccessibilityService.isDriveModeActive) {
            statusIndicator.text = "Status: LISTENING"
            statusIndicator.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            statusIndicator.text = "Status: Standby (accessibility on)"
            statusIndicator.setTextColor(Color.parseColor("#94A3B8"))
        }
        if (DriveAccessibilityService.isDriveModeActive) {
            toggleButton.text = "STOP DRIVING MODE"
            toggleButton.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            toggleButton.text = "STEP 2: START DRIVING MODE"
            toggleButton.setBackgroundColor(Color.parseColor("#16A34A"))
        }
    }

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.CALL_PHONE)
        perms.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }

    // ───────── small view helpers ─────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int, bottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }

    private fun label(text: String, size: Float = 12f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#94A3B8"))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun input(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748B"))
        setBackgroundColor(Color.parseColor("#1E293B"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}
