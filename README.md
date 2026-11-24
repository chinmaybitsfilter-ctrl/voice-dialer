# Voice Dialer (Android)

A simple Android app that shows all your phone contacts and has a built‑in voice assistant:
tap **Speak**, say *"Call Mom"*, and it calls.

## Features
- All contacts with phone numbers, sorted A–Z, with search (by name, nickname or number)
- Tap a contact (or the phone icon) to call; contacts with several numbers ask which one
- **Voice assistant**: tap *Speak* and say
  - "Call Rahul", "Dial Priya Sharma"
  - "Call Mom **on mobile**" / "**at work**" / "**home**"
  - Hinglish: "Papa ko call karo", "Bhai ko phone lagao"
  - A number: "Call 98765 43210"
- Fuzzy matching (handles small mis-hearings like "Chin may" → "Chinmay"); asks "Which one?" when two names are close
- Speaks back "Calling …" and waits 2.5 s with a **Cancel** button before dialing
- **Voice nicknames**: long‑press a contact to set "Mom", "Bhai", "Boss"… then just say "call boss"
- Long‑press the app icon → **Voice call** shortcut jumps straight to listening
- Menu option: *Start listening when app opens*

## Get the APK (no Android Studio needed)
GitHub Actions builds the app for you:

1. Push this folder to a GitHub repository (branch `main`).
2. Open the repo's **Actions** tab — the *Build APK* workflow runs automatically (~3–5 min).
3. When it's green, open the repo's **Releases** → **latest** → download `VoiceDialer.apk` on your phone.
4. Open the file, allow "Install unknown apps" for your browser/files app when asked, and install.
5. Open Voice Dialer and allow **Contacts** and **Phone** permissions.

To rebuild after a change, push again or click **Run workflow** in the Actions tab.

## Requirements
- Android 8.0+ (API 26)
- Speech recognition uses the phone's built‑in recognizer (the Google app on most phones).
  Set your phone language to English (India) for best results with Indian names.

## Project layout
```
app/src/main/java/com/chinmay/voicedialer/
  MainActivity.kt        UI, permissions, speech, text-to-speech, calling
  ContactRepository.kt   reads contacts + stores voice nicknames
  VoiceCommandParser.kt  "call mom on mobile" → contact + number type, fuzzy matching
  ContactAdapter.kt      contact list rows
.github/workflows/build-apk.yml   builds the APK on GitHub
```
