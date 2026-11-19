# Wave

Wave is a Cydia-style package manager for the Sonim XP3800 flip phone. Browse
and search Git sources, inspect package details, and plan installs and updates.
System mods require a rooted phone and explicit approval in Magisk. The web
[device guide](docs/jailbreak.md) does not root the phone for you.

The app and site build locally. See the [first phone record](docs/first-phone.md)
for what ran on stock hardware and the [owner checklist](docs/tasks/01-owner-checklist.md)
for pending GitHub setup.

## Build and test

Run from this repository's root. The app needs JDK 21 and the Android SDK
with platform 35 and build-tools 35.0.1. Set `ANDROID_HOME` to your SDK or
write `sdk.dir=/path/to/android-sdk` in the ignored `local.properties` file.
The checked-in Gradle wrapper uses network access on its first run to fetch
Gradle and dependencies; no sibling checkout is required to build Wave.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:selfTest :app:app:assembleRelease
```

Set `JAVA_HOME` to your installed JDK 21 path if it differs. The APK is
`app/app/build/outputs/apk/release/app-release.apk`.

With Python 3 and Git (and Android SDK `aapt2` for the APK integration test):

```sh
python3 -m unittest discover -s tools/wavesrc
./tools/wavesrc/wavesrc lint spec/example-source
```

The example source contains deliberate placeholder downloads; it is not an
installable source. Use `wavesrc verify` on a checkout containing real assets.
See [publishing](docs/publishing.md), the [site build](site/README.md), and
[handset QA](tools/qa/README.md) for more checks.

## Repositories and design

- [Design](docs/design.md), [source format](docs/source-format.md),
  [decisions](docs/decisions.md), [work briefs](docs/tasks/README.md),
  [status](docs/status.md) and the [refactor log](docs/refactor-log.md).
- [wave_packages](https://github.com/thooton/wave_packages) holds the package
  code and the upstream `common/` library. Wave builds from a checked-in copy.
- [wave_repository](https://github.com/thooton/wave_repository) is the default
  source, with generated package manifests and icons.
