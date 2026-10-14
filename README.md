# fieldlog

[![CI](https://github.com/ahmed-hashim-pro/fieldlog/actions/workflows/ci.yml/badge.svg)](https://github.com/ahmed-hashim-pro/fieldlog/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An offline-first Android notebook for logging boreholes and outcrops on
site. Every phone holds the whole site and works with no signal at all. When
phones can reach each other again, their edits merge with no conflicts to
resolve and no server deciding who wins. The logs export straight into
[strata](https://github.com/ahmed-hashim-pro/strata), which turns them into a
ground model with error bars.

On a site investigation, logging happens where there is no signal. Two or
three people log holes at once, often for the same site. Usually the logs go
on paper and get typed up later, which means a second round of mistakes.
Cloud apps need a connection, or they quietly keep whichever save came last.
Merging is the hard part, and so is the case merging can't fix: two loggers
who both called their new hole BH41.

![Two Android emulators side by side. Both are synced to a relay holding three boreholes. Both switch sync off. pixel-a logs BH41 at (600, 480) with four intervals and a water-strike flag. pixel-b also logs a hole it calls BH41, at (560, 520). Both switch sync back on. pixel-a's list now shows both holes named BH41, marked "name clash" in red. pixel-b, still on its hole, sees "another borehole is also called BH41; rename one". It renames the hole BH42; both lists turn green, and pixel-a's export dialog says "Ready for strata: 5 boreholes ready".](docs/two-phones.gif)

*Two Android emulators (API 36) and a relay on the host, recorded by
`scripts/two-phones.sh`. The taps and typing are a scripted UI test running
against the real app (`DemoScript.kt`), not an animation.*

## The merge, and what strata does with it

`fieldlog demo` tells the same story over real HTTP, starting from strata's
40-hole example site. Then strata reads the merged export:

![Terminal recording. fieldlog demo: a relay starts, a desk imports strata's 40-hole example site, two phones sync it and go offline. pixel-a logs BH41 at (600, 480), pixel-b logs a different hole also called BH41, and both type into BH16's notes at once. After syncing, both have 42 holes and the same state hash, and BH16's notes hold both sentences. The export is refused because two holes are called BH41. pixel-b renames its hole BH42, and the export writes 42 boreholes. Then strata query at (600, 480): before, the base of gravel is REFUSED (sigma over the limit); after, every contact has a value.](docs/demo.gif)

```
$ strata query examples/site.csv 600 480       # before: nearest hole 120 m away
base of sand              55.41     52.55 .. 58.26       1.45
base of gravel          REFUSED    sigma over limit    1.52

$ strata query merged.csv 600 480              # after: BH41, logged offline and merged
base of sand              54.32     54.32 .. 54.32       0.00
base of gravel            51.42     51.42 .. 51.42       0.00
```

BH41 is logged from the synthetic site's true geology at that point, so the
base of gravel strata now reports (51.42 m) can be checked against the truth
(51.40 m). `make strata-check` runs these checks with the real strata binary,
and so does CI:

- strata validates the merged export.
- The refusal at (600, 480) becomes an answer.
- Importing strata's example site and exporting it again changes nothing strata
  can see: `strata cv` output is byte-identical.
- A log with a gap is refused by both tools, with the same words:
  "gap between 1 and 1.5 m".

## Quickstart

You need Docker. Nothing else is installed on the host.

```bash
git clone https://github.com/ahmed-hashim-pro/fieldlog.git && cd fieldlog

make test     # the CRDT, fuzzer, strata export, relay and CLI: 33 JVM tests
make apk      # app/build/outputs/apk/debug/app-debug.apk, built in a container with the Android SDK
make cli      # cli/build/install/fieldlog/bin/fieldlog
```

The first run of each downloads Gradle and its dependencies: `make test` took
4 minutes from a fresh clone, then under a minute. The first `make apk` also
builds an image with the Android SDK.

Run a relay, then point the app at it:

```bash
docker run --rm -u "$(id -u):$(id -g)" -p 8787:8787 -v "$PWD:/w" -w /w eclipse-temurin:17-jdk \
  cli/build/install/fieldlog/bin/fieldlog relay --host 0.0.0.0 --data relay.ops
```

- **Emulator:** install with `adb install app/build/outputs/apk/debug/app-debug.apk`.
  The app's default relay, `http://10.0.2.2:8787`, is how an emulator sees this
  machine.
- **Phone on the same network:** in the app, go to Settings and set the relay
  URL to `http://<this machine's IP>:8787`.

To start with the example site rather than an empty notebook, send it to the
relay:

```bash
B=cli/build/install/fieldlog/bin/fieldlog
docker run --rm --network host -u "$(id -u):$(id -g)" -v "$PWD:/w" -w /w eclipse-temurin:17-jdk sh -c \
  "$B import examples/site.csv -o /tmp/site.ops --site Riverside && $B push /tmp/site.ops --relay http://127.0.0.1:8787"
```

Other commands:

```bash
B=cli/build/install/fieldlog/bin/fieldlog   # needs Java 17, or wrap in the docker run above
$B demo -o merged.csv                       # the terminal recording above
$B fuzz hunt --seeds 5000                   # the merge under a hostile network
$B fuzz mutants --seeds 2000                # the table below
$B fuzz run --seed 7 --bug rga-no-skip --trace   # replay one failure, step by step
$B pull --relay http://127.0.0.1:8787 -o site.ops && $B export site.ops -o site.csv
make strata-check                           # the export, read by the real strata
make check                                  # all of it
```

## How it works

### One log of operations

Every edit becomes a small operation with a Lamport timestamp, written as one
line of text. The operation log is the notebook. The app appends to it on
every change, the relay stores it, and a sync sends the lines the other side
doesn't have yet.

```
S  12@pixel-a  hole/9@pixel-a      collar   64.62          a field
A  13@pixel-a  iv/9@pixel-a        13@pixel-a              into a set
R  31@pixel-b  flags/9@pixel-a     refusal  27@pixel-a     out of a set (the adds it saw)
I  40@pixel-a  notes/9@pixel-a     39@pixel-a   W          a character, after another
D  44@pixel-b  notes/9@pixel-a     40@pixel-a              a character, tombstoned
```

### Three CRDTs, one clock

- **Fields are last-writer-wins registers.** These are the hole name, kind,
  x, y, collar, and each interval's unit, top and base. The higher timestamp
  wins on every phone. A phone's clock jumps past every timestamp it
  receives, so an edit made after seeing another edit always wins over it.
- **Membership is an observed-remove set.** This covers the holes on the site,
  the intervals in a hole and the flags on a hole. A removal removes only the
  adds the remover had seen, so if one person unflags "water-strike" while
  another flags it, the flag stays. Removing a hole keeps its data, and
  "Undo" restores it with everything it had.
- **Notes are an RGA sequence.** Every character is inserted after another
  one. Concurrent inserts at the same spot are ordered by timestamp, so two
  people typing in the same place get both words intact, not interleaved
  letters.

An operation that refers to another one waits until that one has arrived.
Examples are deleting a character, or removing a flag someone else added. The
phone keeps a small buffer, indexed by the missing operation, so a 1,000-line
log arriving in random order is applied in one pass.

### The relay has no authority

The relay is an append-only list of lines, deduplicated by operation id and
saved to a file. It parses each line and refuses malformed ones, but it never
interprets an operation. So it can't resolve a conflict, and it can't be the
reason two phones disagree. Sync is two HTTP calls: POST this phone's new
operations, then GET everything after a cursor. Losing the cursor costs a
bigger transfer, never data.

### Converged is not the same as correct

The CRDT guarantees that every phone ends up with the same notebook. It
doesn't guarantee the notebook makes geological sense. Two loggers can both
create a BH41. Two people can edit the same interval so that it now overlaps
the next one. These are real problems, and quietly picking one version would
hide them. The app checks every borehole with the same rules strata uses, in
strata's words:

- gaps and overlaps
- units out of stratigraphic order
- units missing from the column
- names that aren't valid
- two holes at one location
- two holes with one name

It marks each problem on the hole and refuses to export until it's fixed.
Every phone shows the same problems, because they all have the same notebook.

## The fuzzer, and the bugs it has to catch

`fieldlog fuzz` runs two to four replicas through 80 to 260 random edits:

- holes added, removed and restored
- fields set from small pools of values, so concurrent writes collide
- intervals logged, edited and removed
- flags toggled
- notes typed and deleted, all in the same two holes so typing collides

Meanwhile the network drops 8% of messages, duplicates 8%, and delays the rest
by 1 to 40 ticks, so they arrive out of order. Phones also go offline at
random, and on reconnecting each one sends its whole log in a scrambled order.
At the end every phone syncs. Three properties are checked:

- **Local intent:** every edit shows up, immediately, on the phone that made
  it. A typed word appears where it was typed, a set field reads back, and a
  removed interval is gone.
- **Convergence:** every replica ends in the same state, compared on a full
  dump that includes tombstones and set tags. So "looks the same but isn't"
  is caught.
- **Order independence:** a fresh replica given all the operations in another
  random order ends in that same state.

A run is a pure function of its seed, so any failure replays exactly with
`--trace`. To show the fuzzer has teeth, the merge has six deliberate bugs
behind a switch:

```
$ fieldlog fuzz mutants --seeds 2000
variant              result   first seed  seeds failed  caught by
correct              clean    -           0 of 2000     every check held
lww-by-arrival       caught   1           100%          convergence, order independence
rga-no-skip          caught   1           100%          convergence, order independence
no-causal-buffer     caught   1           100%          convergence
remove-unobserved    caught   1           75%           convergence, order independence
accept-duplicates    caught   1           100%          convergence, local intent
clock-no-bump        caught   1           69%           local intent
```

| Bug | What goes wrong |
| --- | --- |
| `lww-by-arrival` | A field keeps whichever write arrived last, not the latest write |
| `rga-no-skip` | A typed character goes straight after its anchor, ignoring concurrent inserts there |
| `no-causal-buffer` | An operation is applied before the operations it refers to |
| `remove-unobserved` | A removal also removes concurrent adds it never saw |
| `accept-duplicates` | An operation delivered twice is applied twice |
| `clock-no-bump` | Receiving an operation doesn't advance the local clock, so your next edit can lose to one you'd already seen |

The correct merge is clean on 25,000 seeds (CI runs 20,000 on every push), at
about 3 ms per seed. `clock-no-bump` is the quiet one: every phone still
converges, it just isn't on what you typed. Only the local-intent check
catches it.

The fuzzer found a real problem early, though not a correctness one. The
first causal buffer rescanned every waiting operation after each one it
applied. That is quadratic when a whole log arrives shuffled, which is
exactly what reconnecting does, and the fuzz suite went from minutes to
seconds once waiting operations were indexed by what they wait for.

## Tests

| Where | What | Count |
| --- | --- | --- |
| `core` (JVM) | codec, the three CRDTs, strata export/import against `examples/site.csv`, 300 fuzz seeds per variant, determinism | 24 |
| `cli` (JVM) | relay over real HTTP: two devices converge, dedupe, restart, malformed input, offline; the demo; exit codes | 9 |
| `app` unit (JVM) | the on-device store: the notebook and sync position survive a restart; the device name freezes after the first edit | 3 |
| `app` on a device | Compose UI tests on the real activity: log a borehole offline (a gap is flagged, fixed, exported); offline edits survive the app being killed; a removed hole comes back with Undo | 3 |
| CI | the above; 20,000 fuzz seeds; the mutant table; `make strata-check`; the UI tests on an API 34 emulator; `make test` and `make apk` in Docker | |

## What this does not do

- **It hasn't been tested on a physical phone.** It has run on emulators:
  API 36 on my machine, API 34 in CI, plus the two-emulator recording. Real
  devices, with real radios dropping in and out, are untested.
- **There's no GPS.** x and y are typed in metres on the site grid, the way
  strata reads them. Taking a fix and projecting it onto a site grid is the
  obvious next feature.
- **The relay has no authentication or TLS.** Anyone who can reach it can read
  and write the site. Run it on your own machine or LAN. Don't put it on the
  internet as it is.
- **Last writer wins on fields.** If two people correct the same collar
  differently while offline, one value wins on every phone. Which one is
  consistent, but the other is not flagged. Notes and sets don't lose edits;
  single-value fields can.
- **The log only grows.** There's no compaction or garbage collection of
  tombstones. Every typed character is an operation. A site with thousands
  of operations is fine; years of notes in one notebook haven't been tried.
- **A removed hole's edits disappear with it.** If one person removes a hole
  while another is still logging it, the hole is gone on both phones. Nothing
  is lost underneath, but only the phone that removed it gets the "Undo";
  there's no list of removed holes to restore from yet.
- **The release APK is unsigned.** `make apk` builds a debug APK, signed with
  a throwaway debug key.

## Layout

```
core/      the notebook, independent of Android: Op, Codec, Replica (the CRDTs),
           Notebook (sites, holes, intervals, flags, notes), Strata (check,
           export, import), Fuzz, Sync (the relay client)
cli/       fieldlog: relay, import/export/check, push/pull, demo, fuzz
app/       the Android app (Kotlin, Jetpack Compose): FieldStore, FieldViewModel, Screens;
           androidTest/ has the UI tests and the scripted two-phone recording
scripts/   two-phones.sh + compose.sh (docs/two-phones.gif), strata-check.sh
docker/    the Android SDK image `make apk` builds in
docs/      the recordings, and demo.tape / demo.Dockerfile for `make demo`
examples/  site.csv (strata's example site), small-site.csv (the recording's seed)
```

## License

MIT
