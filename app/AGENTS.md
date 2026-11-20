# Wave Android app

[`SPEC.md`](SPEC.md) describes current behavior. Use Java 8, API 27 or
lower, and no third-party dependencies in the app or its tests.

- `:app:core`: plain Java source and install logic, compiled with `--release 8`.
  Tests use a `SelfTest` main and `com.xp3.common.Check`. Tests that start a
  shell must use `Sandbox` and stop what they start; its final leak check fails
  if it has to kill a process.
- `:app:app`: Android UI (`app.WaveActivity`), catalog (`data.SourceController`)
  and foreground install queue (`EngineService`). Put screen decisions in
  host-tested classes such as `Pages`, `Actions`, and `Format`, not in views.
  `src/debug` contains fixtures and the adb-only `DebugActivity`.

Root is opt-in. Stock APK installs use the signature-protected Installer
(`com.installer.xp3`); mods require approved root. Every app change bumps
both `versionName` and `versionCode` in `app/app/build.gradle`.

From the repository root, use JDK 21, Android SDK platform 35, and Build
Tools 35.0.1. Set `ANDROID_HOME` or `sdk.dir` in ignored `local.properties`.
CI runs the same checks and uploads `wave.apk`.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:selfTest :app:app:selfTest :app:app:assembleRelease
```

On-device checks: [d-pad smoke checklist](../tools/qa/README.md) and
[rooted-phone procedure](ROOT-TESTING.md).
