#!/bin/bash
set -eu
task_root="$(cd "$(dirname "$0")/.." && pwd)"
source "$task_root/tools/android-env.sh"
printf 'Java: %s\nSDK: %s\n' "$JAVA_HOME" "$ANDROID_HOME"
java -version
node --version
npm --version
if [ -d '/Applications/Android Studio.app' ]; then
  /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' '/Applications/Android Studio.app/Contents/Info.plist'
else
  printf 'Android Studio is missing.\n' >&2
  exit 1
fi
for task_component in platform-tools/adb platforms/android-36/android.jar build-tools/36.0.0/aapt2 ndk/27.1.12297006/source.properties cmake/3.22.1/bin/cmake; do
  if [ ! -e "$ANDROID_HOME/$task_component" ]; then
    printf 'Missing SDK component: %s\n' "$task_component" >&2
    exit 1
  fi
done
if command -v sdkmanager >/dev/null 2>&1; then
  sdkmanager --sdk_root="$ANDROID_HOME" --version
else
  printf 'Android Command-line Tools are missing from PATH.\n' >&2
  exit 1
fi
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" --version
"$ANDROID_HOME/ndk/27.1.12297006/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" --version
if [ -x "$task_root/.tooling/gradle-9.3.1/bin/gradle" ]; then
  "$task_root/.tooling/gradle-9.3.1/bin/gradle" --version
fi
printf 'Application builds must use the future mobile/android/gradlew.\n'
adb version
"$ANDROID_HOME/build-tools/36.0.0/aapt2" version
adb devices -l
printf '\nDevice state must be "device". Empty list means no phone is connected.\n'
