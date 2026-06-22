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
then install "CLS Runner (POC)" from the Marketplace tab.

Auto-update experiment: serve only the fat (`make-updatePlugins.ps1 -OnlyFat`), install on 2025.1,
bump `pocBaseVersion` to `1.13.1` (fat becomes `1.13.1-251`), rebuild + re-stage, then **Check for Updates**.

## B. JetBrains Marketplace (real routing + upgrade)

Max plugin size is **400 MB**; the fat is ~362 MB, so **both fat and slims fit**.

1. **Create the listing (once, web UI)** — https://plugins.jetbrains.com/plugin/add → upload the fat
   `cls-runner-1.13.0-251.zip`. This creates plugin id `com.jiec.cls.runner` and starts moderation.
   To stay semi-private (like jb nightly), publish to a non-default **channel** and/or use
   **isHidden**; users install by adding the channel repo URL.
2. **Get a token** — https://plugins.jetbrains.com/author/me/tokens (a `perm:...` token).
3. **Upload the rest (script)**:
   ```powershell
   pwsh scripts/publish-marketplace.ps1 -Token perm:xxxx               # all built zips
   pwsh scripts/publish-marketplace.ps1 -Token perm:xxxx -Channel nightly -Hidden
   ```

### Version / routing recap

| Artifact | version | since/until | os/arch depends |
|---|---|---|---|
| fat | `1.13.0-251` | 251 / 260.* | — |
| slim ×6 | `1.13.0-261-{os}-{arch}` | 261 / — | `os.*` + `arch.*` |

`1.13.0-261-…` sorts **newer** than `1.13.0-251` (token `261 > 251`), so a 2026.1 IDE that still
has the fat installed (e.g. after an IDE upgrade from 2025.1) is offered the matching slim as an
**update** — the fat→slim migration this POC validates.

## C. CI pipeline — stable + nightly channels

The [`Publish to Marketplace`](.github/workflows/publish-to-marketplace.yml) workflow
(`workflow_dispatch`) mirrors jb's flow: it builds the fat + 6 slims, then uploads each ZIP via
curl to `/plugin/uploadPlugin` with `-F channel` + `isHidden` + retry
([composite action](.github/actions/publish-to-marketplace/action.yml)). Uses repo secret
`JETBRAINS_MARKETPLACE_TOKEN`.

Inputs: `channel` (stable | nightly), `visibility` (hidden | visible), `release_version` (core),
`plugin_id` (numeric, optional — recommended once known).

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

> First run for a brand-new plugin: do the one-time web-UI upload (§B.1) first so the plugin exists,
> then set `plugin_id` and run the workflow for every subsequent version.

## Experiments to record (findings)


1. Fresh install: 2025.1 → fat; 2026.1 Win-x64 → `windows-x64` slim.
2. Upgrade: install fat on 2025.1 → upgrade IDE to 2026.1 → slim offered as update.
3. Auto-update notification timing (custom repo, fat→newer fat; slim not offered to 251).
4. Button: Tools → "Launch CLS (--stdio)" → notification with abs path + PID.
