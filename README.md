# Whisper Offline (Android)

Offline Whisper transcription on Android using whisper.cpp.

1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab -> "Build Whisper Offline APK" -> Run workflow (set language: bn / en / auto).
3. Download the `WhisperOffline-apk` artifact, unzip, install `WhisperOffline.apk`.
4. In the app: choose model -> choose audio -> wait -> Save text.

Notes
- Model must be a whisper.cpp ggml Whisper file (ggml-*.bin). GGUF files may not load.
- Each CI build signs with a new key: uninstall the old app before installing a new build.
- Keep the app open and screen on while it runs (it keeps the screen awake).
