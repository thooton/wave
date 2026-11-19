# Committed sources imported

Baseline checks use clean clones, never the old worktrees. Each commit was
rewritten below `mods/<name>/`; the obsolete APK signature-splicing helper
was deliberately **excluded from the entire history**. No OEM private keys
are available. Full original refs remain in the siblings. P has stock tags
`stock-messages`, `stock-launcher` and `stock-music`.

Original HEADs and `git status --short -uall` paths excluded at import:

## messages (f782dbfa9867e41343520c7cd564c4073c2ef617)

```text
 M .gitignore
 M AGENTS.md
 M README.md
 M apps/BMms/apktool/smali/com/android/mms/rcs/RcsMessageStatusService.smali
 M apps/BMms/apktool/smali/com/android/mms/transaction/SendTransaction.smali
 M apps/BMms/apktool/smali/com/android/mms/transaction/SmsReceiverService.smali
 M apps/BMms/apktool/smali/com/android/mms/transaction/SmsSingleRecipientSender.smali
 M apps/BMms/apktool/smali/com/android/mms/transaction/TransactionService$ServiceHandler.smali
 M apps/BMms/apktool/smali/com/android/mms/ui/MessageUtils.smali
 M apps/BMms/apktool/smali/com/android/mms/ui/MessagingPreferenceActivity.smali
 M apps/BMms/apktool/smali/com/android/mms/ui/MessagingReportsPreferenceActivity.smali
 M apps/BMms/java/com/android/mms/argon/ArgonHooks.java
 M docs/rcs/android.md
 M tools/build.sh
 M tools/lib/env.sh
 M tools/pull_firmware.sh
 M tools/setup.sh
?? docs/fixes/sqlite-upgrade/README.md
?? module/sqlite/post-fs-data.sh
?? module/sqlite/service.sh
?? native/sqlite/bench/contacts2.sql
?? native/sqlite/bench/mmssms.sql
?? native/sqlite/include/log/log.h
?? native/sqlite/sqlbench.c
?? tools/lib/strip_feature.py
?? tools/sqlite/bench.sh
?? tools/sqlite/build.sh
?? tools/sqlite/deploy.sh
```

## launcher (26c11bea4a4693594b40f7671ae8747a1213e1d3)

```text
 M apps/SonimLauncher/apktool/smali/com/sonim/borqs/launcher/LauncherActivity$2.smali
 M apps/SonimLauncher/apktool/smali/com/sonim/borqs/launcher/LauncherActivity.smali
 M apps/SonimLauncher/apktool/smali/com/sonim/borqs/launcher/QuickSettingFragment.smali
 M apps/SonimLauncher/java/com/sonim/borqs/launcher/ArgonDefaults.java
 M apps/SonimLauncher/java/com/sonim/borqs/launcher/ArgonTray.java
 M docs/tray.md
 M tools/patches/tray.py
 M tools/test/LayoutTest.java
?? apps/SonimLauncher/java/com/sonim/borqs/launcher/ArgonQuickSettings.java
?? apps/SonimLauncher/stubs/com/sonim/borqs/launcher/QuickSettingFragment.java
?? docs/performance.md
```

## music (6aa43cc0055d31b048bb9566d8ed9bf38876183c)

```text
```

Music was clean. SQLite's native source, build tools, strip-feature work
and the launcher Quick Settings work were not committed and were not
copied. No SQLite release or NDK build is enabled. The stock build.prop
is the one from the Messages and Launcher dump; only the five build APKs
and this file were copied from upstream firmware.
