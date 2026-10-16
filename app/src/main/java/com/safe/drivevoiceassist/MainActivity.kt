package com.safe.drivevoiceassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusIndicator: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 64)
        }

        val titleText = TextView(this).apply {
            text = "DRIVE VOICE ASSIST"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        statusIndicator = TextView(this).apply {
            text = "Status: Standby"
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val guideText = TextView(this).apply {
            text = "Voice Commands While Driving:\n\n" +
                    "• 'Open WeChat' / 'Open WhatsApp'\n" +
                    "• 'Open Gmail' / 'Close' / 'Go back'\n" +
                    "• 'Scroll down' / 'Scroll up'\n" +
                    "• 'Click [Name or Button]'\n" +
                    "• 'Send message [Your text]'\n" +
                    "• 'Write email saying [Your text]'\n" +
                    "• 'Call [Contact Name]'"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(40, 40, 40, 40)
        }

        val accessibilityBtn = Button(this).apply {
            text = "STEP 1: ENABLE ACCESSIBILITY"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 48, 0, 32) }
            layoutParams = params
            setOnClickListener { showPlayStoreDisclosureDialog() }
        }

        toggleButton = Button(this).apply {
            text = "STEP 2: START DRIVING MODE"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            setPadding(40, 64, 40, 64)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
            setOnClickListener { toggleDriveMode() }
        }

        layout.addView(titleText)
        layout.addView(statusIndicator)
        layout.addView(guideText)
        layout.addView(accessibilityBtn)
        layout.addView(toggleButton)
        scrollView.addView(layout)
        setContentView(scrollView)

        requestPermissions()
    }

    // Mandatory Google Play Store Prominent Disclosure Dialog
    private fun showPlayStoreDisclosureDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Disclosure")
            .setMessage("Drive Voice Assist requires the Accessibility Service API to function.\n\n" +
                    "Why we need it:\n" +
                    "• To read screen buttons so you can say 'Click' hands-free while driving.\n" +
                    "• To scroll up/down and enter dictated text into messaging apps on your behalf.\n\n" +
                    "Privacy Guarantee:\n" +
                    "We do NOT collect, store, or share any personal data, screen content, or voice recordings.")
            .setPositiveButton("Agree & Enable") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Decline", null)
            .show()
    }

    private fun toggleDriveMode() {
        val service = DriveAccessibilityService.instance
        if (service == null) {
            showPlayStoreDisclosureDialog()
            return
        }

        DriveAccessibilityService.isDriveModeActive = !DriveAccessibilityService.isDriveModeActive
        updateUI()

        if (DriveAccessibilityService.isDriveModeActive) {
            service.speak("Driving mode active. Listening for commands.")
            service.startListening()
        } else {
            service.speak("Driving mode stopped.")
            service.stopListening()
        }
    }

    private fun updateUI() {
        if (DriveAccessibilityService.isDriveModeActive) {
            statusIndicator.text = "Status: LISTENING CONTINUOUSLY"
            statusIndicator.setTextColor(Color.parseColor("#4ADE80"))
            toggleButton.text = "STOP DRIVING MODE"
            toggleButton.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            statusIndicator.text = "Status: Standby"
            statusIndicator.setTextColor(Color.parseColor("#94A3B8"))
            toggleButton.text = "STEP 2: START DRIVING MODE"
            toggleButton.setBackgroundColor(Color.parseColor("#16A34A"))
        }
    }

    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 101)
        }
    }
}
