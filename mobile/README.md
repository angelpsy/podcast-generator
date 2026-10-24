# Podcast SIWC Spike

Android-only React Native + TypeScript prototype. OAuth, encrypted credentials and
Responses streaming live in the Kotlin `Siwc` TurboModule; JavaScript receives safe snapshots only.

From the repository root:

```sh
source tools/android-env.sh
cd mobile
npm ci
npm start -- --host 127.0.0.1
```

In another terminal with the same environment:

```sh
adb reverse tcp:8081 tcp:8081
cd mobile
npm run android -- --no-packager
```

For an APK with embedded JS (local test signing only):

```sh
cd mobile/android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.podcast.siwcspike/.MainActivity
```

This uses the machine's standard Android debug keystore for both build variants.
It is not a publishing configuration. Never add API keys or ChatGPT/Codex tokens.
See ../docs/ANDROID_DEVICE_TESTING.md for device setup and testing steps.
