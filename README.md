# PyroChat — the apps

PyroChat is written by Zee (contact@zeemsg.com).

The source code of the PyroChat phone apps and of pyrobot, the demo contact. PyroChat is an
end-to-end encrypted messenger with no account on any server: the identity is created on the
phone, messages are encrypted with the Signal Protocol (libsignal) and relayed by a server that
holds only a hash of the username and public keys. https://pyrochat.ca

```
ios/        the iPhone app  — Swift / SwiftUI, Xcode 26
android/    the Android app — Kotlin, Android 10+
bot/        pyrobot, an automated peer that runs a real client (Node)
```

Licence: GNU AGPL v3 for all three (see LICENSE). They link libsignal, which is AGPL v3; the
relay server, the billing server and the website are separate proprietary programs and are not
in this repository. The apps talk to `wss://relay.pyrochat.ca`; the relay address is a setting in
the app, so a build of this code can be pointed at another relay that speaks the same protocol.

## Building the iPhone app

Requirements: Xcode 26, `xcodegen` and `cocoapods` (`brew install xcodegen cocoapods`), an Apple
Developer team.

```
cd ios
# put your team id in project.yml: DEVELOPMENT_TEAM: YOUR_TEAM_ID
xcodegen generate
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install
open PyroChat.xcworkspace
```

Then build and run the `PyroChat` scheme. The unit and UI tests are in `ios/Tests` and
`ios/UITests`; the tests that need a relay skip when none runs on 127.0.0.1:7444.

## Building the Android app

Requirements: a JDK 21 (Android Studio's bundled one works), the Android SDK (API 36); the Gradle
wrapper is included.

```
cd android
echo "sdk.dir=/path/to/your/Android/sdk" > local.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 21
./gradlew :app:assembleDebug
```

Push notifications need an optional `app/firebase.properties` (format in `android/README.md`);
without it the app builds and runs, with no wake pushes. Release signing reads the keystore and
its passwords from Gradle properties (`PYRO_UPLOAD_*`), never from the tree.

## Running pyrobot

```
cd bot
npm install
RELAY_URL=wss://your-relay/ws BOT_USERNAME=pyrobot STATE=./state.json \
SEALED_ROOT=<base64 root public key of your relay> node index.mjs
```

`state.json` holds the bot's identity keys; keep it private. The bot's tests are not in this
repository because they drive the relay's test fixtures.

## Contributing and reporting

Issues and pull requests are welcome. Security findings: contact@zeemsg.com.
PyroChat is not affiliated with or endorsed by Signal Messenger, LLC.
