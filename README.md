![Ante Meridiem](design/banner.png)

# Ante Meridiem

An LSPosed module for OriginOS that brings custom lock screen clock fonts, custom styles, clock size scaling.

## Build

Requires **JDK 17** and **Android SDK 35** (`ANDROID_HOME` set or `sdk.dir=...` in `local.properties`).

```bash
# Using Make (creates dist/*.apk)
make

# Or using Gradle directly
./gradlew :app:assembleDebug
```

The built APK is located at `/dist/*.apk`.
