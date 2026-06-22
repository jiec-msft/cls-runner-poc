# Publishing & test runbook

Two channels: a **local custom repo** (fast iteration, no moderation) and the **real
JetBrains Marketplace** (true server-side OS/arch routing + the fat→slim upgrade prompt).

## A. Local custom plugin repo (fast loop)

```powershell
pwsh scripts/download-binaries.ps1        # once, ~590 MB
./gradlew buildNativePlugins              # fat + 6 slims
pwsh scripts/make-updatePlugins.ps1       # stage dist-repo/ + updatePlugins.xml
pwsh scripts/serve-repo.ps1               # serve http://localhost:8181
```

In the IDE: **Settings → Plugins → ⚙ → Manage Plugin Repositories → `http://localhost:8181/updatePlugins.xml`**,
then install "CLS Runner" from the Marketplace tab.

Auto-update experiment: serve only the fat (`make-updatePlugins.ps1 -OnlyFat`), install on 2025.1,
bump `pocBaseVersion` to `1.13.1` (fat becomes `1.13.1-251`), rebuild + re-stage, then **Check for Updates**.

## B. JetBrains Marketplace (real routing + upgrade)

Max plugin size is **400 MB**; the fat is ~362 MB, so **both fat and slims fit**.

> The verifier checks the no-until-build universal plugin against future EAPs (2026.2+). `1.12.1-251`
> / `1.13.1` are the first verifier-clean versions: the binary path comes from this plugin's own
> `PluginAwareClassLoader` (jb's `CopilotPlugin.getPluginBasePath()` pattern) and arch from
> `CpuArch`, so no internal/scheduled-for-removal API is used. Use these (not `1.12.0` / `1.13.0`).

**Recommended sequence (exercises the real-world fat→slim migration):**

0. **Build + upload the universal `1.12.1-251` first** (the "old plugin" users already have):
   ```powershell
   ./gradlew buildPlugin -PuniversalBuild=true -PpocBaseVersion=1.12.1
   ```
   Upload `build/distributions/cls-runner-1.12.1-251.zip` via the web UI
   (https://plugins.jetbrains.com/plugin/add). This creates plugin id `com.jiec.cls.runner` (since
   it is a brand-new plugin) and starts moderation. `1.12.1-251` is 2025.1.1+ with no cap, so it
   installs on every IDE. To stay semi-private (like jb nightly), use a non-default **channel** and/or
   **isHidden**.
1. **Then build + upload the split `1.13.1`** (fat + 6 slims):
   ```powershell
   ./gradlew buildNativePlugins -PpocBaseVersion=1.13.1
   ```
   Now a `1.12.1-251` user is offered `1.13.1-251` on `< 261` IDEs, or the matching slim on `>= 261`.
2. **Get a token** — https://plugins.jetbrains.com/author/me/tokens (a `perm:...` token).
3. **Upload (script or CI)**:
   ```powershell
   pwsh scripts/publish-marketplace.ps1 -Token perm:xxxx               # all built zips
   pwsh scripts/publish-marketplace.ps1 -Token perm:xxxx -Channel nightly -Hidden
   ```

### Version / routing recap

| Artifact | version | since/until | os/arch depends |
|---|---|---|---|
| universal | `1.12.1-251` | 251.25410 / — | — |
| fat | `1.13.1-251` | 251 / 253.* | — |
| slim ×6 | `1.13.1-261-{os}-{arch}` | 261 / — | `os.*` + `arch.*` |

`until-build` is `253.*` (last 2025.x branch), not `260.*` — `260`/`2026.0` is not a real branch and
Marketplace rejects made-up build numbers. `1.13.1-261-…` sorts **newer** than `1.13.1-251` (token
`261 > 251`), so a 2026.1 IDE that still has the fat installed (e.g. after an IDE upgrade from 2025.1)
is offered the matching slim as an **update** — the fat→slim migration this POC validates.

## C. CI pipeline — stable + nightly channels

The [`Publish to Marketplace`](.github/workflows/publish-to-marketplace.yml) workflow
(`workflow_dispatch`) mirrors jb's flow: it builds the fat + 6 slims, then uploads each ZIP via
curl to `/plugin/uploadPlugin` with `-F channel` + `isHidden` + retry
([composite action](.github/actions/publish-to-marketplace/action.yml)). Uses repo secret
`JETBRAINS_MARKETPLACE_TOKEN`.

Inputs: `channel` (stable | nightly), `visibility` (hidden | visible), `release_version` (core),
`build_mode` (split | universal), `plugin_id` (numeric, optional — recommended once known),
`dry_run` (build only, skip upload). `build_mode=universal` builds the single `1.12.1-251`-style
legacy ZIP (`buildPlugin -PuniversalBuild=true`); `split` builds the fat + 6 slims (`buildNativePlugins`).

### SemVer: how nightly stays above stable (the jb trick)

The core version is the single source of truth (`-PpocBaseVersion`); the build derives
`{core}-251` (fat) and `{core}-261-{os}-{arch}` (slims).

| channel | core | fat | example slim |
|---|---|---|---|
| stable | `1.13.0` | `1.13.0-251` | `1.13.0-261-windows-x64` |
| nightly | `1.13.1-nightly.<run>` | `1.13.1-nightly.<run>-251` | `1.13.1-nightly.<run>-261-windows-x64` |

Nightly **bumps the patch** (`1.13.0` → `1.13.1`) before appending `-nightly.<run>`, so the nightly
core (`1.13.1-nightly.<run>`) sorts **above** the latest stable (`1.13.0`). Without the bump,
`1.13.0-nightly.<run>` would be a pre-release of `1.13.0` and sort *below* stable, so nightly-channel
users would never be offered the update. `<run>` (`github.run_number`) keeps successive nightlies
monotonically increasing. This mirrors jb `release.yml`.

> First run for a brand-new plugin: do the one-time web-UI upload (§B.0, the universal `1.12.1-251`)
> first so the plugin exists, then set `plugin_id` and run the workflow for every subsequent version.

## Experiments to record (findings)


1. Fresh install: 2025.1 → fat; 2026.1 Win-x64 → `windows-x64` slim.
2. Upgrade: install fat on 2025.1 → upgrade IDE to 2026.1 → slim offered as update.
3. Auto-update notification timing (custom repo, fat→newer fat; slim not offered to 251).
4. Button: Tools → "Launch CLS (--stdio)" → notification with abs path + PID.
