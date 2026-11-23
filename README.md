# Circle Alert for Android

Circle Alert is a native Android client for the friends-testing service. Sign in with your existing account or create one with a private testing or invitation code. The app uses Kotlin and Jetpack Compose. The service stores accounts, contact consent and alerts. An alert reaches the inboxes of the sender’s three confirmed contacts. A recipient acknowledges it personally. The sender can then mark themselves safe.

[Download CircleAlert-1.0.apk](downloads/CircleAlert-1.0.apk?raw=1)

## Try it on a phone

1. Open the signed Circle Alert 1.0 APK on Android 8 or later and tap **Install**. If Android asks, allow installs from the app that opened the download for this installation.
2. Open **Circle Alert** and sign in. For a new account, save the recovery code somewhere private.
3. In **My people**, create an invitation for each friend. Share the individual code privately. After the friend accepts, check their identity and tap **Confirm this is my friend**. Receiving an alert does not require three outgoing contacts.
4. On each recipient’s phone, **Set up phone notifications** opens the existing Circle Alert site in the browser. Sign in there with the same account, tap **Enable notifications**, and allow the prompt. Leave that browser account signed in. A notification opens the browser inbox. The APK’s **Alert inbox** shows the same server records. Sign out in both places when sharing a phone or ending the test.
5. Send **a test alert** first. It goes to actual connected recipients. In **Alert inbox**, a recipient taps **I’m responding**. The sender sees the acknowledgement and taps **I’m safe now**. The red button requires a continuous 1.5-second hold.

The accounts, recovery, contact invitations, sender status, inbox, acknowledgement, safe closure, alert-time location and Maps link run natively. Background delivery uses the existing service’s browser Web Push. The app requests a fresh position after the server accepts an alert. Declining location still permits the alert. Choosing **Open location in Maps** shares the displayed coordinates with Google. Circle Alert does not contact emergency services. Try locked-phone notification delivery with the intended recipients before relying on it.

## Build and test

Open this directory in Android Studio with JDK 17, SDK 35 and Build Tools 34.0.0. The Gradle wrapper pins version 8.9 and checks its distribution hash. The APK is signed for testing. Its Android package is `uk.co.agentengines.circlealert`, its version is `1.0` and its version code is `1`.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The two instrumentation scenarios exercise the native app and the original v0.2 API using disposable accounts. They require an Android emulator, Node.js 24, OpenSSL, the matching private server checkout and `npm ci` in that checkout. Supply the disposable emulator serial reported by `adb devices`.

```sh
tools/test-reference.sh /path/to/circle-alert emulator-5554
```

The test script uses a temporary SQLite database, a loopback TLS connection and a disposable test certificate. It requires ports 41892 and 41893 and only accepts emulator serials. The installed app uses Android’s normal HTTPS trust store for the configured service. Tests do not require live accounts or production credentials.

## Build record

AgentEngines team build agreement v1.0 and **Build a Native Android App**. The Android implementation references the friends-test server commit `1be068111173a1edaf7927c5d0c2c5b3a284bf13` (v0.2.0). It uses the server’s existing session cookie, exact origin, CSRF token, invitation and alert API. The private signing key is excluded from this source. Android allows in-place updates when the package ID and signing certificate match and the version code is compatible.

The original Gradle scaffold attribution is in `licenses/ThoughtDrop-MIT.txt`.

**Signed APK SHA-256**

~~~text
031c7c7bbbc10c76a783411b6ee86d1ad55e147ab0d1b6202dbec634b1905587
~~~

**Test signing certificate SHA-256**

~~~text
88bbbd5bfda89b2cabb8cb58a56f3cfe815c87dfd80be829ed4b73294ce17fcb
~~~
