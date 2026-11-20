# Data-layer stress: a community's worth of sources

Wave's format says a phone holds at most 128 sources, 16 MiB of package manifests in memory, 12,000 files
per source and 200 search results. These were tested one at a time. This folder tests them together, on the
desktop and on the phone, and is how to repeat it.

| File | What it is |
| --- | --- |
| `sources.py build WORK` | writes 128 sources (19,140 packages, 31 MiB of `package.json`) as trees and as Git repositories |
| `sources.py serve WORK` | serves them over HTTPS on `127.0.0.1:4484` with `git http-backend`, and logs each request to `WORK/requests.jsonl` |
| `:app:core:stress` | the host benchmark: the same store and index code the app uses, a fake fetcher, 1/8/32/127 sources |
| `phone.py` | one run on the phone: install, add the sources, refresh them all, sample memory and responsiveness, search, restart |
| `test_phone.py` | host checks of `phone.py`'s parsers (`python3 tools/qa/stress/test_phone.py`) |

## The data set

`python3 tools/qa/stress/sources.py build WORK --small 120` (takes 8 minutes: one `git init` and commit per
source; needs about 1.3 GB). With the defaults: `s000` has 3,000 packages, `s001` to `s005` have 300 each,
the other 122 have 120. Every package has a 1.5 KB manifest (description, three changes, download block)
and a 96x96 icon. Names reuse 70 common words, so a one-letter query matches thousands of packages.
`--small 20` makes a quick set (a few thousand packages, under the 16 MiB budget).

The 128 sources hold 19,140 packages and 31 MiB of manifests, twice the 16 MiB budget, so the set also
shows what Wave does past it: it loads sources in list order until the budget is gone, and says
"Not loaded: Wave's sources hold more packages than it can keep" for the rest (9,876 packages loaded,
9,144 not, in this set).

## On the desktop

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:stress \
    -Pstress="$PWD/WORK/trees $PWD/WORK/files 1,8,32,127"
```

Paths must be absolute (Gradle runs from `app/core`). For each count it adds that many sources to a fresh
store and refreshes them two ways: `each` reads the index after every source's refresh (what
`SourceController` used to do), `batch` refreshes all and reads once. The last count also reports memory,
the 129th add, a first read in a new process (digests checked) and search timings. Run it on a quiet machine
(`uptime`): a loaded one makes every number here 5 to 20 times worse, and a failing test run once left 80
busy shells behind that did exactly that.

Results, 8 cores, load average 3, Wave 0.7.25 (before) and 0.7.26 (after):

| sources | refresh and read, `each` before | `each` after | batch before | batch after |
| --- | --- | --- | --- | --- |
| 1 (3,000 packages) | 3.4 s | 2.9 s | 2.1 s | 1.3 s |
| 8 | 6.2 s | 2.8 s | 2.1 s | 1.7 s |
| 32 | 19.7 s | 5.1 s | 3.6 s | 3.1 s |
| 127 | 115 s | 16 s | 9.5 s | 7.2 s |

| 127 sources, 9,876 packages loaded | before | after |
| --- | --- | --- |
| slowest single index read | 1.9 s | 0.4 s |
| index read, nothing changed | 971 ms | 29 ms |
| first index read in a new process (digests checked) | 4.4 s | 5.4 s |
| heap held by the index | 40 MB | 41 MB |
| search "a", "note", "clock" (200 results each) | 2 to 3 ms | 2 to 3 ms |
| search with no match | 2 ms | 2 ms |
| 129th source | refused at once: "Wave can't hold more sources. Remove one first." | same |

The 129th is refused when the address is checked (before anything is fetched or the trust dialog shows).

## What was wrong, and the fix

`SourceController` read the whole index again after every source's refresh. With 127 sources refreshing two
at a time that is 127 reads of up to 10,000 manifests, each longer than the refresh it followed. Three changes
(Wave 0.7.26):

* `SourceStore.index()` keeps what it read of each source and reuses it while the source is unchanged. The
  16 MiB budget is shared between sources in list order, so a kept reading is used only when the room it
  was read with is the room there is now, or it never ran short and still doesn't. A damaged source is
  looked at every time. `SourcesSelfTest` checks the result is the same as a fresh read after a refresh, a
  removal and a damaged tree.
* `SourceController` reads once for however many refreshes finish while a read runs, so a screen still
  updates between sources, but a read never queues behind another.
* `Phone.apps` asks the system for the installed packages once instead of calling `getPackageInfo` for each
  of the thousands of listed ids (a binder call and an exception each).

## On the phone

`phone.py` installs a QA build, so Wave must not be installed (it never replaces an install and removes its
own when done), and the phone needs 50% battery (it stops and cleans up under 30%; a long run with the
screen on drains faster than USB charges,, and a long soak is suspected of having flattened it once).

```
# the build: a debug build that trusts the throwaway certificate, built-in source = the stress server
python3 tools/qa/rooted/apk.py --commit HEAD --work $PWD/WORK --default-url https://localhost:4484/s000.git --tag stress
python3 tools/qa/stress/sources.py serve WORK &
tools/qa/phone-run.sh --who "you: stress" --wait 3600 -- \
    python3 tools/qa/stress/phone.py --apk WORK/wave-stress-HASH.apk --work WORK --sources 128 --out OUT --search
```

`--sources N` counts the built-in one; the others are written into Wave's own `sources.json` as Add Source
records them (URL, name, no commit), because adding 127 by d-pad takes hours (the screen's add path
is not exercised here). Wave then does what it always does at start: reads them and refreshes them
all. `phone.py` samples while it works:

* every 1.5 s: sources saved so far, `dumpsys meminfo` (Dalvik and native heap allocated and sizes, PSS),
  battery and temperature every tenth sample;
* a d-pad key a second, then `dumpsys gfxinfo framestats`: how long the oldest input waited before the main
  thread took it, and the frame times; the focus is compared before and after a key at the start, half way
  and at the end;
* `logcat` for ANR, crash and FATAL EXCEPTION;
* the server's `requests.jsonl`: first to last request per source;
* `--search`: Search tab, one letter typed, time to the last frame drawn, then keys down the list;
* a restart with everything saved: how long Home says Loading.

`OUT/result.json` has the numbers, `OUT/samples.csv` the series, and there are screenshots.

PHONE_RESULTS

## Limits worth knowing

* 16 MiB of manifests is about 10,000 packages (1.6 KB each). A community past that sees the first sources
  fully and the rest listed as not loaded. Raising the budget costs heap one for one on a phone with 100 MB
  to spare, so it is a choice, not a fix.
* Sources are loaded in list order. The built-in source is first and always loaded.
* A refresh of every source at start is one fetch per source: with 128 that is 128 requests every launch
  (unchanged ones are a short exchange).
