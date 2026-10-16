# Drive Voice Assist (KingDrive – native Android)

Hands-free voice control while driving: open WeChat / WhatsApp / Gmail, scroll, tap buttons by name, dictate and send messages, dictate an email, call a contact.

## Get an APK without installing anything (recommended)

1. Create a new **GitHub** repository (private is fine).
2. Upload everything in this folder to it (drag-and-drop in the GitHub web UI works, or `git push`).
3. Open the repo's **Actions** tab → **Build APK** → wait ~4 minutes.
4. Open the finished run and download **DriveVoiceAssist-debug-apk** (a zip containing `app-debug.apk`).
5. Copy the APK to your phone, allow "install from unknown sources", install.

The workflow runs again on every push, so every code change gives you a fresh APK.

## Build on a PC instead

Install Android Studio, choose *Open* → this folder, let it sync, then **Build → Build APK(s)**.
Or from a terminal with JDK 17 + Android SDK installed: `./gradlew assembleDebug`
→ `app/build/outputs/apk/debug/app-debug.apk`.

## First run on the phone

1. Open the app, grant Microphone / Phone / Contacts when prompted.
2. Tap **STEP 1: ENABLE ACCESSIBILITY** → Agree → switch on *Drive Voice Assist* in the Accessibility list.
3. Back in the app tap **STEP 2: START DRIVING MODE**. It announces "Driving mode active" and listens continuously.

## Voice commands (v1.1)

Say it naturally — filler words ("hey King, please…") are ignored and names are fuzzy-matched against your contacts.

| Say | Does |
|---|---|
| **WhatsApp Allan saying on my way** · *WhatsApp Allan on my way* · *tell Allan I'll be late* | finds the contact, opens the chat with the text, taps Send |
| **WhatsApp Allan** | asks "What's the message for Allan?" then sends |
| **WeChat Allan saying …** · *message Allan on WeChat …* | opens WeChat, searches the contact, types, sends |
| **Text Allan …** | SMS |
| **Call Allan** | dials (asks "one or two?" if several contacts match) |
| **Email Allan about the meeting tomorrow** | drafts an email on that topic (AI if a key is set, template otherwise) and opens Gmail |
| **Email Allan saying I will send the file tonight** | email with that exact body |
| **Reply on my way** | types into the open chat and sends |
| **Open WeChat / YouTube / Maps / any app** | launches it |
| **Navigate to KLCC** | Google Maps turn-by-turn |
| **Read screen** | reads the visible text aloud |
| scroll down / up · go back · home · click *[text]* · send | screen control |
| **yes** / **no** / **cancel** | confirm or abort a pending send |
| **stop driving mode** | turns listening off |

Settings in the app: speech language (EN / 中文 / Malay), confirm-before-send, auto-send email, WhatsApp country code, your name for email sign-off, optional Claude API key + model for AI-written emails.

## Play Store notes

Accessibility-service apps need the prominent-disclosure dialog (already in `MainActivity`) and a justification in the Play Console *App content → Accessibility* form. Release builds need a signing key: add a `signingConfigs` block to `app/build.gradle` and store the keystore as a GitHub secret before switching the workflow to `assembleRelease`.
