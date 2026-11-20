# tools

- `wavesrc/`: `lint` checks a source against the format, `verify` checks
  the hosted assets and pins, and `publish` writes a built package's manifest
  and icon. Python 3, standard library only. The lint and publish Actions are
  at the repo root (`lint/`, `publish/`). See [publishing](../docs/publishing.md).
- `sync-common.sh`: refreshes `common/` from a `wave_packages` checkout or
  verifies the recorded copy with `--check`.
- `qa/`: [handset smoke checklist](qa/README.md) and phone-lock helper.
