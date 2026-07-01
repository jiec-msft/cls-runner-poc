# CLS Runner — split-plugin test scenarios

End-to-end validation plan for **fat / slim split-plugin distribution** on JetBrains Marketplace,
using the throwaway plugin **CLS Runner** (`com.jiec.cls.runner`, Marketplace id **32412**).

This doc is the "what to test and what should happen" companion to [README.md](README.md)
(the mechanism) and [PUBLISHING.md](PUBLISHING.md) (how to publish).

---

## 0. What we are proving

JetBrains Marketplace recently **enabled OS/CPU-arch module dependencies
(`com.intellij.modules.os.*` / `com.intellij.modules.arch.*`) for all third-party plugin ids**
(previously JetBrains-authored plugins only). That is the single capability the whole "slim" idea
rests on: it lets one plugin id ship a small, single-platform artifact to 2026.1+ IDEs while the
Marketplace routes each user to the right OS/arch build.

The production goal (mirrored here on a throwaway id) is to move Copilot from **one ~360 MB fat
plugin that bundles all 6 language-server binaries** to **a small per-platform "slim" plugin on new
IDEs**, without breaking auto-update for the millions of users already on the fat plugin — including
the ability to **roll back to the fat plugin** if the slim path regresses.

This plan exercises that full lifecycle **including two rollbacks and two re-adoptions**, across
**IDE build, OS/arch, update channel, and install method**.

---

## 1. The three artifact shapes (verified metadata)

| Shape | `since-build` | `until-build` | os/arch `<depends>` | Installs on | Bundles |
|---|---|---|---|---|---|
| **universal** (legacy fat) | `251.25410` | *(none)* | none | 2025.1.1 **and** 2026.1+ (all) | all 6 binaries |
| **split fat** | `251` | `253.*` | none | 2025.x only | all 6 binaries |
| **split slim** ×6 | `261` | *(none)* | `os.<x>` + `arch.<y>` | 2026.1+, matching OS/arch only | 1 binary |

Two facts make routing unambiguous:

1. **The split fat (251–253.\*) and the slims (261+) have DISJOINT build ranges.** No IDE is ever
   offered both, so there is never a fat-vs-slim tie to break by version. `260` is deliberately not
   used as a cap — there is no `260`/`2026.0` branch, and Marketplace rejects made-up build numbers;
   `253.*` is the last 2025.x branch.
2. **The universal build has no `until-build`,** so it is the one shape that spans the 2025↔2026
   boundary. It is what "roll back to a single fat plugin" produces.

---

## 2. Version scheme for the POC timeline

The abstract story is "v1…v9". On Marketplace id 32412 the existing history already tops out at
`1.13.x`, so the POC timeline is based at **major 2** and bumps the major once per step. This keeps
the **core version strictly monotonic (v1 < v2 < … < v9)** — the single invariant that makes *any*
later step installable as an update over *any* earlier step within a compatible IDE.

| Step | mode | core | artifacts (version strings) |
|---|---|---|---|
| **v1** | universal | `2.0.0`  | `2.0.0-251` |
| **v2** | universal | `3.0.0`  | `3.0.0-251` |
| **v3** | universal | `4.0.0`  | `4.0.0-251` |
| **v4** | **split** | `5.0.0`  | `5.0.0-251` (fat) + `5.0.0-261-{os}-{arch}` ×6 |
| **v5** | **split** | `6.0.0`  | `6.0.0-251` (fat) + `6.0.0-261-{os}-{arch}` ×6 |
| **v6** | universal | `7.0.0`  | `7.0.0-251`  ← **rollback to single fat** |
| **v7** | universal | `8.0.0`  | `8.0.0-251` |
| **v8** | **split** | `9.0.0`  | `9.0.0-251` (fat) + `9.0.0-261-{os}-{arch}` ×6 |
| **v9** | **split** | `10.0.0` | `10.0.0-251` (fat) + `10.0.0-261-{os}-{arch}` ×6 |

`{os}` ∈ {windows, macos, linux}, `{arch}` ∈ {x64, arm64}. The `-251` / `-261` tail is the
since-build branch (cosmetic in the string; real compatibility is the `<idea-version>`/`<depends>`).

### Why this ordering always works (the two comparison systems agree)

- **Cross-step (monotonic core).** `7.0.0-251` (v6 universal) vs `6.0.0-261-windows-x64` (v5 slim):
  major `7 > 6`, so v6 wins on both Marketplace semver and IDE `VersionComparatorUtil`. The v6
  universal has **no os/arch depends**, so it is installable on the 261 machine → the slim→fat
  rollback is offered and installs. Same logic makes v8 offered over v7, etc.
- **Same-step (fat vs slim).** Never compared in practice (disjoint ranges), but if it were:
  `C-261-…` > `C-251` under both systems (261 > 251 numerically), so the slim would win on a
  hypothetical overlapping IDE. We never rely on this.

---

## 3. Channels

- **Stable** = the default channel (empty `channel`), served by the built-in Marketplace.
- **Nightly** = a named channel; only reaches users who **add the custom repo URL**
  `https://plugins.jetbrains.com/plugins/nightly/32412` (Settings → Plugins → ⚙ → Manage Plugin
  Repositories).
- Nightly cores are the stable core **with patch+1 and `-nightly.<seq>`** appended, e.g. stable
  `5.0.0` → nightly `5.0.1-nightly.<seq>`. That sorts **above** stable `5.0.0` and **below** stable
  `6.0.0`, so a nightly subscriber is always a hair ahead of stable but never skips a whole step.

Channel isolation is a first-class thing to test: a **default-only** user must **never** be offered
a nightly upload; a nightly subscriber sees `max(stable, nightly)`.

---

## 4. Test axes

| Axis | Values |
|---|---|
| IDE build | `251` (2025.1.1), `253` (2025.3, the fat cap), `261` (2026.1) |
| OS / arch | windows-x64, windows-arm64, macos-x64, macos-arm64, linux-x64, linux-arm64 |
| Channel | stable, nightly |
| Install method | (a) Marketplace (routed + auto-update)  (b) download ZIP from web page → install from disk |
| Transition | fresh install · plugin update (same IDE) · IDE upgrade with plugin installed |

---

## 5. Scenario catalog

Legend for expected outcome: **✅ offered/installs**, **⛔ not offered / refused**, **⚠️ offered but
must be handled**.

### Group A — Fresh install (routing correctness)

| # | IDE build | OS/arch | Channel | Timeline state | Expect |
|---|---|---|---|---|---|
| A1 | 251 | any | stable | after v1 (universal only) | ✅ gets `2.0.0-251` (universal) |
| A2 | 261 | win-x64 | stable | after v1 | ✅ gets `2.0.0-251` (universal spans 261) |
| A3 | 251 | any | stable | after v4 (split) | ✅ gets `5.0.0-251` (fat); slims ⛔ (need 261) |
| A4 | 261 | win-x64 | stable | after v4 | ✅ gets `5.0.0-261-windows-x64`; fat ⛔ (cap 253) |
| A5 | 261 | mac-arm64 | stable | after v4 | ✅ gets `5.0.0-261-macos-arm64` only |
| A6 | 261 | linux-x64 | stable | after v4 | ✅ gets `5.0.0-261-linux-x64` only |
| A7 | 253 | any | stable | after v4 | ✅ gets `5.0.0-251` (fat covers 251–253.\*) |
| A8 | 261 | win-x64 | nightly | after v4 nightly | ✅ gets `5.0.1-nightly.<n>-261-windows-x64` |

**Verifies:** the Marketplace serves exactly one artifact per (build, os, arch); the six slims do
not collide; the universal reaches 261; the fat’s `253.*` cap excludes 261.

### Group B — Normal forward update (same generation)

| # | Start | Publish | IDE/OS | Expect |
|---|---|---|---|---|
| B1 | on v1 `2.0.0-251` | v2 `3.0.0-251` | 251 | ✅ update → v2 |
| B2 | on v2 `3.0.0-251` | v3 `4.0.0-251` | 261 | ✅ update → v3 (universal→universal) |
| B3 | on v4 slim `5.0.0-261-windows-x64` | v5 slim | 261 win-x64 | ✅ update → `6.0.0-261-windows-x64` |
| B4 | on v4 fat `5.0.0-251` | v5 fat | 251 | ✅ update → `6.0.0-251` |
| B5 | on v8 slim `9.0.0-…` | v9 slim | 261 | ✅ update → `10.0.0-…` |

**Verifies:** plain monotonic auto-update within a generation, both on the fat lane (251) and the
slim lane (261), per OS/arch.

### Group C — Fat → slim migration (introducing the split): v3 → v4

| # | Start (v3 universal) | IDE/OS | Expect at v4 |
|---|---|---|---|
| C1 | `4.0.0-251` | 251 | ✅ update → `5.0.0-251` (**fat**); user stays on the fat lane |
| C2 | `4.0.0-251` | 261 win-x64 | ✅ update → `5.0.0-261-windows-x64` (**slim**); binary shrinks ~360→~60 MB |
| C3 | `4.0.0-251` | 261 mac-arm64 | ✅ update → `5.0.0-261-macos-arm64` |

**Verifies:** the headline transition — a 261 user on the universal fat is silently moved to the
matching slim, while 251 users keep the fat, all under one plugin id.

### Group D — IDE upgrade across the split boundary

The migration can also be triggered by the *user upgrading their IDE* while already on the fat.

| # | Setup | Action | Expect |
|---|---|---|---|
| D1 | 2025.1 with v4 fat `5.0.0-251` installed | upgrade IDE to 2026.1 | On restart the fat (`until 253.*`) is **incompatible**; Marketplace offers `5.0.0-261-{os}-{arch}` (slim) as the compatible replacement → ✅ |
| D2 | 2025.1 with v1 universal `2.0.0-251` | upgrade IDE to 2026.1, then publish v4 | universal keeps working on 261 (no cap); at v4 the 261 IDE is offered the slim → ✅ |
| D3 | 2026.1 with v4 slim | *downgrade* IDE back to 2025.3 | slim (`since 261`) is now incompatible; Marketplace offers the fat `5.0.0-251` (251–253.\*) → ✅ |

**Verifies:** the disjoint ranges hand the user off cleanly in **both** directions when the IDE
build crosses 253↔261, with no manual reinstall.

### Group E — Rollback: split → single fat (the regression path): v5 → v6

The whole reason to keep the universal shape available.

| # | Start (v5 split) | IDE/OS | Expect at v6 (universal `7.0.0-251`) |
|---|---|---|---|
| E1 | slim `6.0.0-261-windows-x64` | 261 win-x64 | ✅ update → `7.0.0-251` (**universal fat**); slim replaced by fat, binary grows back |
| E2 | slim `6.0.0-261-macos-arm64` | 261 mac-arm64 | ✅ update → `7.0.0-251` |
| E3 | fat `6.0.0-251` | 251 | ✅ update → `7.0.0-251` (fat→universal, both on 251 lane) |
| E4 | slim `6.0.0-261-linux-x64` | 261 linux-x64 | ✅ then v7 `8.0.0-251` also offered (rollback holds for 2 releases) |

**Verifies (critical):** a 261 user sitting on an os/arch-constrained slim is pulled **back** onto
a no-depends universal fat, purely because the core version increased and the universal carries no
unmet module dependency. This is the safety valve for a slim-path regression.

### Group F — Re-adopt split after rollback: v7 → v8

| # | Start (v7 universal `8.0.0-251`) | IDE/OS | Expect at v8 |
|---|---|---|---|
| F1 | `8.0.0-251` | 261 win-x64 | ✅ update → `9.0.0-261-windows-x64` (universal→slim again) |
| F2 | `8.0.0-251` | 251 | ✅ update → `9.0.0-251` (fat) |
| F3 | on v8 | 261 | ✅ v9 `10.0.0-261-…` offered (split holds for 2 releases) |

**Verifies:** the split can be re-introduced after a rollback with no residue; the same 261 user
oscillated slim → universal → slim across v4…v9 with every hop being a normal update.

### Group G — Channel isolation & switching

| # | Setup | Action | Expect |
|---|---|---|---|
| G1 | default-only user on stable v4 | publish v5 **nightly** | ⛔ NOT offered (nightly invisible to default channel) |
| G2 | same user | publish v5 **stable** | ✅ offered `6.0.0-…` |
| G3 | nightly subscriber on `5.0.1-nightly.7-…` | stable v5 `6.0.0` published | ✅ offered `6.0.0` (nightly sees max(stable,nightly)) |
| G4 | nightly subscriber | next nightly `6.0.1-nightly.8` published | ✅ offered the newer nightly |
| G5 | user removes the nightly custom-repo URL | check for updates | falls back to stable-only; a *lower* stable is **not** auto-installed over an already-installed higher nightly |

**Verifies:** the named-channel boundary; nightly ordering stays above stable; unsubscribing does
not force a downgrade.

### Group H — Manual install-from-disk (bypasses Marketplace routing)

Downloading the ZIP from the web page and using **Install Plugin from Disk** skips server-side
routing, so the IDE’s own compatibility checks are the only guard. All of these must fail safe.

| # | Downloaded artifact | Target IDE/OS | Expect |
|---|---|---|---|
| H1 | `5.0.0-261-windows-x64` (slim) | 2026.1 **win-x64** | ✅ installs & runs |
| H2 | `5.0.0-261-windows-x64` (slim) | 2026.1 **macOS** | ⛔ refused: unmet `com.intellij.modules.os.windows` (plugin won’t load) |
| H3 | `5.0.0-261-windows-arm64` (slim) | 2026.1 win-**x64** | ⛔ refused: unmet `com.intellij.modules.arch.arm64` |
| H4 | `5.0.0-261-…` (slim) | 2025.1 (build 251) | ⛔ refused: requires build ≥ 261 |
| H5 | `5.0.0-251` (split fat) | 2026.1 (build 261) | ⛔ refused: `until-build 253.*` < 261 |
| H6 | `2.0.0-251` (universal) | 2026.1 | ✅ installs (universal has no cap) |
| H7 | `2.0.0-251` (universal) | 2025.1 | ✅ installs |
| H8 | older `2.0.0-251` over installed `5.0.0-…` | any | ⚠️ install-from-disk **allows** the downgrade (no "must be newer" rule); documents the manual-install footgun |

**Verifies:** the `<depends>` os/arch modules + `since/until` are enforced client-side on manual
install, so a user can’t brick their IDE by grabbing the wrong ZIP — the worst case is the plugin
being marked incompatible and disabled. H8 documents that manual install can move *backwards*.

### Group I — Backward compatibility & uninstall

| # | Setup | Action | Expect |
|---|---|---|---|
| I1 | 261 user on v9 slim | uninstall plugin | clean removal; no leftover CLS process (owned by an app service, killed on unload/shutdown) |
| I2 | 251 user rides the **entire** timeline v1→v9 | check updates at each step | ✅ every hop is fat/universal `-251`; the user never sees a slim (all slims need 261) |
| I3 | 261 user rides the entire timeline | check updates at each step | ✅ hops: universal ×3 → slim ×2 → universal ×2 → slim ×2, all as normal updates |
| I4 | fresh 251 install of the **oldest** still-listed version | install `2.0.0-251` | ✅ old universal still installs & runs (Marketplace keeps all versions) |

### Group J — Edge cases

| # | Case | Expect |
|---|---|---|
| J1 | 400 MB Marketplace size cap | fat ~362 MB ✅ under cap; if it grew past 400 MB the fat lane would need splitting too |
| J2 | EAP build (e.g. `261.*` EAP) on 261 | slim `since 261` matches EAP builds ✅ |
| J3 | until-build boundary: IDE exactly on `253.9999` | split fat still ✅; one build past (261) → slim |
| J4 | Android Studio / Rider / other IDEs | compatibility table follows the same `since/until`; slims route by the same os/arch modules ✅ |
| J5 | Upload the os/arch slim as a non-JetBrains vendor | ✅ **accepted** (this is the newly-enabled feature; previously HTTP 400 "JetBrains-authored") |
| J6 | Re-upload an already-published version | Marketplace 400 "already contains version" → treat as idempotent success |

---

## 6. How to run (all on the live JetBrains Marketplace)

Everything is exercised directly against the real listing (id 32412) — that is the whole point:
real server-side OS/arch routing, the real update prompt, real channels, and the real web-page
"install from disk". Publishing is driven from CI (details in [PUBLISHING.md](PUBLISHING.md)).

### Publish the timeline

```bash
# whole lifecycle, nightly channel, visible so the IDE will offer it
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc \
  -f channel=nightly -f visibility=visible -f up_to=9 -f dry_run=false
```

**Observing each migration (Groups B–F) needs incremental publishing.** Once vN+1 is live the
Marketplace only ever offers the newest, so to watch a specific hop (e.g. C = v3→v4 fat→slim, or
E = v5→v6 rollback), publish up to the "before" step, install/observe in the target IDE, then bump
`up_to` and hit **Check for Updates**:

```bash
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc -f up_to=5 -f channel=nightly -f visibility=visible -f dry_run=false
# ... install v5, then:
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc -f up_to=6 -f channel=nightly -f visibility=visible -f dry_run=false
```

Re-runs are idempotent (already-published versions return "already contains version" → success).

### Subscribe an IDE to the right channel

- **stable** user: nothing to do — the built-in Marketplace serves the default channel.
- **nightly** user: Settings → Plugins → ⚙ → **Manage Plugin Repositories** → add
  `https://plugins.jetbrains.com/plugins/nightly/32412`. (Group G isolation: a stable-only IDE must
  never see a nightly build.)

### Install methods (Group H)

- **Marketplace**: Settings → Plugins → Marketplace → search "CLS Runner" → Install / Update. Routing
  is automatic; the IDE receives only the artifact compatible with its build + OS + arch.
- **From disk**: open the version on
  `https://plugins.jetbrains.com/plugin/32412-cls-runner/versions`, download a specific ZIP, then
  Settings → Plugins → ⚙ → **Install Plugin from Disk**. This bypasses routing, so it is the way to
  test the fail-safes (wrong OS/arch slim, wrong IDE build, downgrade) in Group H.

### Verify what each build actually declares

`pwsh scripts/verify-zips.ps1` prints the `id` / `version` / `since-until` / os-arch `<depends>` read
straight out of each built ZIP's `plugin.xml`, so you can confirm routing metadata before uploading.

---

## 7. Result log

Record outcomes here as scenarios are run (date, IDE build, OS/arch, channel, method, ✅/⛔, notes).

| Date | Scenario | IDE / OS / arch | Channel | Method | Result | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
