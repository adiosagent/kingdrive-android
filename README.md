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

## Voice commands

| Say | Does |
|---|---|
| open wechat / open whatsapp / open gmail | launches the app |
| close / go home · go back | Home / Back |
| scroll down / scroll up | swipe gesture |
| click *Alice* / tap *Send* | taps the first clickable element with that text |
| send message *on my way* / type *…* | types into the open chat box, then taps "Send" |
| write email saying *…* | opens the mail composer with that body |
| call *Alice* | dials the first matching contact |

## Play Store notes

Accessibility-service apps need the prominent-disclosure dialog (already in `MainActivity`) and a justification in the Play Console *App content → Accessibility* form. Release builds need a signing key: add a `signingConfigs` block to `app/build.gradle` and store the keystore as a GitHub secret before switching the workflow to `assembleRelease`.
