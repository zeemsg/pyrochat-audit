# source this before ./gradlew — libsignal 0.102.2 is Java-21 bytecode, so Gradle must run on a 21 JDK.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
