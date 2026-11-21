# Wave

Wave is a package manager for the Sonim XP3800 (Android 8.1, API 27,
240×320, d-pad only). Browsing works on stock; system mods require root.

- `app/`: Android app and plain Java core. See [app/AGENTS.md](app/AGENTS.md)
  and [app/SPEC.md](app/SPEC.md).
- `spec/`: source and setup JSON schemas, plus source fixtures used by tests.
- `common/`: vendored from `../wave_packages`; edit upstream, then run
  `tools/sync-common.sh`. Follow that repo's `AGENTS.md` and `DESIGN.md` for
  shared phone code conventions.
- `site/`: static installer and recovery site. See [site/AGENTS.md](site/AGENTS.md).
- `tools/wavesrc/`: source tooling. See [CLI usage](tools/wavesrc/README.md).

Sibling checkouts: `../wave_packages` for package development,
`../wave_repository` for the default source, `../wave_private_packages` for
private packages. `../sonim` is the legacy repo, not the upstream of `common/`.

For interactive Android UI checks from a worker without KVM, start a disposable
[GitHub Actions XP3 emulator](tools/qa/README.md#interactive-github-actions-emulator).
Use synthetic data only; public session results are public Git objects.

Keep specs and fixtures in sync with behavior changes. Report what was verified
and what was blocked. Run device-changing steps only for the assigned task and
on an authorized phone reserved by serial. Never write to `/system`, flash a
boot image patched for another phone, or restore anything but the target
phone's verified stock backup.

Commit author: thooton. No AI attribution in commits or pull requests.
