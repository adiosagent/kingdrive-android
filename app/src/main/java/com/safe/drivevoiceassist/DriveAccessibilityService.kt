package com.safe.drivevoiceassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class DriveAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false

    companion object {
        var instance: DriveAccessibilityService? = null
        var isDriveModeActive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        speak("Drive Voice Assist connected.")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DriveAssistTTS")
    }

    private fun setupSpeechRecognizer() {
        handler.post {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }

                override fun onError(error: Int) {
                    isListening = false
                    if (isDriveModeActive) {
                        handler.postDelayed({ startListening() }, 900)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0].lowercase(Locale.getDefault())
                        processVoiceCommand(command)
                    }
                    if (isDriveModeActive) {
                        handler.postDelayed({ startListening() }, 1000)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun startListening() {
        if (!isDriveModeActive) return
        handler.post {
            if (!isListening) {
                try {
                    speechRecognizer?.startListening(recognizerIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun stopListening() {
        handler.post {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    private fun processVoiceCommand(command: String) {
        when {
            command.contains("open wechat") -> openApp("com.tencent.mm", "WeChat")
            command.contains("open whatsapp") -> openApp("com.whatsapp", "WhatsApp")
            command.contains("open gmail") || command.contains("open email") -> openApp("com.google.android.gm", "Gmail")

            command.contains("close") || command.contains("go home") -> {
                speak("Closing app")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            command.contains("go back") -> {
                speak("Going back")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            command.contains("scroll down") -> scrollScreen(true)
            command.contains("scroll up") -> scrollScreen(false)

            command.startsWith("click ") || command.startsWith("tap ") -> {
                val target = command.removePrefix("click ").removePrefix("tap ").trim()
                clickTextOnScreen(target)
            }

            command.startsWith("type ") || command.startsWith("send message ") -> {
                val msg = command.removePrefix("type ").removePrefix("send message ").trim()
                typeAndSendMessage(msg)
            }

            command.contains("write email") || command.contains("send email") -> {
                val body = command.substringAfter("saying", "I am currently driving and will reply shortly.")
                composeEmail(body.trim())
            }

            command.startsWith("call ") || command.startsWith("help me call ") -> {
                val contactName = command.removePrefix("help me ").removePrefix("call ").trim()
                callContactByName(contactName)
            }
        }
    }

    private fun openApp(packageName: String, appName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak("Opening $appName")
        } else {
            speak("$appName is not installed.")
        }
    }

    private fun scrollScreen(scrollDown: Boolean) {
        val dm = resources.displayMetrics
        val midX = (dm.widthPixels / 2).toFloat()
        val startY = if (scrollDown) (dm.heightPixels * 0.75).toFloat() else (dm.heightPixels * 0.25).toFloat()
        val endY = if (scrollDown) (dm.heightPixels * 0.25).toFloat() else (dm.heightPixels * 0.75).toFloat()

        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun clickTextOnScreen(targetText: String) {
        val rootNode = rootInActiveWindow ?: return
        val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
        if (nodes.isNullOrEmpty()) {
            speak("Cannot find $targetText")
            return
        }
        for (node in nodes) {
            if (performClickOnNode(node)) {
                speak("Clicked $targetText")
                return
            }
        }
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun typeAndSendMessage(message: String) {
        val rootNode = rootInActiveWindow ?: return
        val editText = findEditTextNode(rootNode)
        if (editText != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            speak("Sending $message")
            handler.postDelayed({ clickTextOnScreen("Send") }, 1000)
        } else {
            speak("No chat box open")
        }
    }

    private fun findEditTextNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.contains("EditText") == true || node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditTextNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun composeEmail(bodyText: String) {
        speak("Opening email")
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "Voice Email (Driving)")
            putExtra(Intent.EXTRA_TEXT, bodyText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    @SuppressLint("Range")
    private fun callContactByName(name: String) {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        if (cursor != null && cursor.moveToFirst()) {
            val number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            val foundName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
            cursor.close()
            speak("Calling $foundName")
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
        } else {
            cursor?.close()
            speak("Contact $name not found")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
        instance = null
    }
}
