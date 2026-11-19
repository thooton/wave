# First phone, end to end

Brief [17](tasks/17-first-phone.md) remains open. This is the stock-device
integration record, not evidence of a rooted-device or legacy migration run.

## Scope and fixture

- Sonim XP3800, Android 8.1, stock and reserved for this run. No rooting,
  reboot, system writes, device trust changes, or package install was
  confirmed inside Wave.
- The stock app under test was Wave 0.6.0 (`60f1abd`). The local source walk
  used a disposable debug build of the same revision, labeled
  `0.6.0-qa17`, code 7. There was no `FakeWaveData` or networking stub: Wave
  used its normal source storage, parser, index and smart Git v2 client.
- To avoid publishing infrastructure or changing phone-wide TLS trust,
  the debug build trusted a temporary TLS certificate for `localhost` and
  `127.0.0.1` only, using Android's app `networkSecurityConfig`. Its built-in
  URL was changed only in the scratch copy to `https://localhost:4483/default`.
  `adb reverse` forwarded that port to a host loopback HTTPS server running
  `git http-backend`. Neither the certificate nor this configuration ships.
- `/default` was the exact locally prepared default source at `a3c0049`.
  It contains no published package manifests yet. `/populated` was a
  separate copy of that commit plus the two inert example manifests and
  icons from `spec/example-source`: List and Messages Improved. Both lint;
  the example download URLs are placeholders and were not used.

## Measured on the handset

Firmware `3A.0.1-02-8.1.0-11.20.00`, model `XP3800`. The release APK
SHA-256 was `4c000b984edfce2d517acb44478f92f9099e059cdb240c193a1ed8de7b874751`;
the test APK SHA-256 was
`71713742491b3722319022537681c263eb06bd26739d821d80e91f4bf97c8c1a`.

| Check | Evidence |
| :--- | :--- |
| Install and launch release | ADB reported `Success` and `Status: ok` for `WaveActivity`. Home showed `Welcome to Wave`. The public URL failed with `Could not connect to the Git host`; this run did not distinguish network failure from an unpublished source. |
| No-root guidance | Home showed `Root for System Mods, Not granted`. Pressing OK opened `Wave Needs Root for Mods`, said apps install without root, and displayed `thooton.github.io/wave` and `Check Again`. The guide itself states that it does not root the phone. No root request was made. |
| Exact local default | `Nothing featured yet.` after startup; Sources listed `Wave, localhost:4483/default`. The server logged one `GET /default/info/refs?service=git-upload-pack` and two `POST /default/git-upload-pack` responses, all HTTP 200 over TLS. |
| Add the populated source | The real preview showed `Wave`, `thooton, 2 packages`, and the trust warning. Confirming **Add Source** added `localhost:4483/populated`, with a separate Git advertisement and two upload-pack POSTs returning 200. |
| Browse and inspect | The added source showed `All Packages 2`, `Applications 1`, `System 1`. List details showed `OPEN`, the installed `Version 1.2.6` and `Already on a newer version`; the fixture offers 1.0.0. No downgrade or launch was attempted. |
| Stock mod refusal | Messages Improved named the reboot requirement and showed `Not tested on this phone's firmware`. Pressing GET showed `Can't Install` and `System mods need root access in Wave`, before any download or install. |
| Search | Search started at `Names and descriptions`. Entering `list` returned List and its summary from the local catalog. |
| Remove and clean up | Removed only the added source via its menu and confirmed the default remained listed. Uninstalled only our temporary Wave APKs and removed the temporary reverse mapping. No other app was modified. |

All navigation was d-pad/menu/Back. Address entry needed long presses on the
number keys: plain `adb shell input text` translated `4483` into letters on
this handset's input configuration, and `KEYCODE_NUMPAD_*` did not enter the
digits. The final address used long presses for `4`, `4`, `8`, `3`, with
brief pauses, then submitted normally. No touchscreen or system CA changes
were needed. Screenshot and UI-tree captures are in the local scratch run
`.oak/tmp/temp7/phone17/` and are not part of the release.

## Still pending

- A hosted default source with its real immutable releases, plus the complete
  install/open, update-after-CI and remove cycle. Wave permits APK plans on
  stock with its installer path, but this non-destructive pass did not test an
  actual package installation. The on-device browse source intentionally had
  no usable download assets.
- Onboarding into a rooted Wave session, installation and reboot/removal of
  all three signed mods, their behavior on this newer firmware, the watchdog
  recovery cycle, and end-to-end Rescue/Restore. A stock refusal and an
  advisory firmware warning are not a compatibility test for those APKs.
- Legacy migration on a reserved rooted device: adoption and Argon data
  retention, Installer device-owner handover, private Source/Tablet updates,
  setup import and migration inventory. Do not retire the legacy channel on
  the strength of this pass.
