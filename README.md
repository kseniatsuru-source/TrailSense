# TrailSense

Android hiking/activity application built with Kotlin, Jetpack Compose and Room.
The initial Git baseline is version **0.3.4**, Android version code **16**.
Earlier local snapshots are not represented as fabricated Git history.

## Build

Requirements: JDK 17, Android SDK with platform 35, and network access for Gradle dependencies. Configure the SDK with `ANDROID_HOME` or an untracked `local.properties` file containing `sdk.dir`.

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Generated APKs are under `app/build/outputs/apk/` and are intentionally untracked.
The Gradle wrapper JAR is included so the checked-in wrapper can run.

## Signing

The current release build uses the Android **debug signing configuration** for local testing. This is not production signing. On a different machine its automatically generated debug key will differ; it cannot update an installation signed with an existing key. Signing keys and credentials must remain outside Git. Configure production signing separately before distribution.

## Private local data

No real activity exports, private sleep images, databases, diagnostic logs, APKs, signing keys or SDK-local configuration are bundled. Existing private audit/planning documents and nine tests coupled to real activity metrics or private fixture paths remain locally present but are excluded from this baseline; therefore the tracked test suite is a subset of the local suite. Most checked-in tests use synthetic data. Do not commit real user fixtures or credentials.

The initial commit records the current code only. No license has been selected or implied by this setup.
