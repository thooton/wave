# Wave

Welcome to Wave, a package manager and enhancement suite for the Sonim XP3800 flip phone!

## Messages Improved and Launcher Improved

The Argon projects were imported and renamed in `wave_packages`, not in the
Wave app: [Messages Improved](https://github.com/thooton/wave_packages/tree/master/mods/messages)
and [Launcher Improved](https://github.com/thooton/wave_packages/tree/master/mods/launcher).
Launcher Improved modifies the stock launcher. The standalone AppDrawer is retired.

Both mods build, but neither is in the [default catalog](https://github.com/thooton/wave_repository).
Their APKs replace privileged stock apps, so CI only builds unsigned requests.
Releases need matching APKs signed by an authorized OEM signer; then they can
be tested on an XP3800 and published using the
[mod release instructions](https://github.com/thooton/wave_packages/blob/master/mods/AGENTS.md#oem-signing-and-release).

The port includes Launcher Quick Settings changes and a separate experimental
[SQLite overlay](https://github.com/thooton/wave_packages/tree/master/mods/sqlite).
Messages Improved is SMS/MMS-only; the original Argon project retains RCS.
