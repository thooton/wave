# Wave

A package manager and modding framework for the Sonim XP3800 flip phone, in
the spirit of Cydia. Jailbreak the phone from a web page, and Wave appears on
the launcher: add sources, browse and search them, install apps and system
mods, and keep them up to date.

Status: design and scaffold. Nothing here runs yet.

- [The design](docs/design.md)
- [The source format](docs/source-format.md): how to publish packages for Wave
- [The jailbreak](docs/jailbreak.md)
- [Decisions and open questions](docs/decisions.md)
- [The work, as briefs](docs/tasks/README.md)

| Folder | What |
| :--- | :--- |
| `app/` | The Wave Android app |
| `common/` | Shared Android code, copied from `wave_packages` |
| `site/` | The website: landing page, jailbreak, setup-code generator |
| `spec/` | JSON Schemas for the source format and an example source |
| `tools/` | `wavesrc`: checks a source and publishes packages into one |
| `docs/` | Design, specs, briefs |

Related repositories: [wave_packages](https://github.com/thooton/wave_packages)
(the code of the packages in the default source) and
[wave_repository](https://github.com/thooton/wave_repository) (the default
source).
