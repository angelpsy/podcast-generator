# Source this file from zsh/bash: source tools/android-env.sh
# Only affects the current shell; does not modify the user's shell profile.
export JAVA_HOME="/Users/first/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:/Users/first/.homebrew/bin:$PATH"
if [ -x "$HOME/.nvm/versions/node/v24.16.0/bin/node" ]; then
  export PATH="$HOME/.nvm/versions/node/v24.16.0/bin:$PATH"
fi
