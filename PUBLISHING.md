# Publishing & E2E runbook (JetBrains Marketplace)

All testing is done **directly on the real JetBrains Marketplace** — true server-side OS/arch
routing, the fat→slim upgrade prompt, channel isolation, and the web-page "install from disk" flow.
The full v1..v9 lifecycle and the scenarios to run against it are in
[TEST-SCENARIOS.md](TEST-SCENARIOS.md); this file is the how-to-publish.

- Plugin id: **32412** (`com.jiec.cls.runner`), vendor Mason Chen MSFT.
- Max artifact size **400 MB**; the fat is ~362 MB, so fat + slims all fit.
- Auth: repo secret `JETBRAINS_MARKETPLACE_TOKEN` (a `perm:…` token from
  https://plugins.jetbrains.com/author/me/tokens). The first version of a brand-new plugin needs a
  one-time web-UI upload + moderation; 32412 already exists, so updates just publish.

## Publish the whole timeline (recommended)

[`Publish timeline (v1..v9)`](.github/workflows/publish-timeline.yml) builds every step in order and
uploads each step's ZIPs before the next, so the run log doubles as a publish trace.

```bash
# all steps, nightly channel, actually upload
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc \
  -f channel=nightly -f visibility=visible -f up_to=9 -f dry_run=false
```

**To E2E each migration prompt, publish incrementally.** Once vN+1 is live it supersedes vN, so the
IDE only ever offers the newest — to *watch* v3→v4 (fat→slim) or v5→v6 (rollback), publish up to the
"before" step, install/observe, then bump `up_to`:

```bash
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc -f up_to=3 -f dry_run=false ...
# install v3 in a 2026.1 IDE, then:
gh workflow run publish-timeline.yml --repo jiec-msft/cls-runner-poc -f up_to=4 -f dry_run=false ...
# back in the IDE: Check for Updates -> should offer the 5.0.0-261-{os}-{arch} slim
```

Re-running a lower/equal `up_to` is safe: already-published versions return HTTP 400 "already
contains version" and are treated as success (idempotent).

Inputs: `channel` (nightly|stable), `visibility` (visible|hidden — visible is required for the IDE
to *offer* a version), `up_to` (1..9), `plugin_id` (default 32412), `dry_run` (build only).

## Publish a single version

[`Publish to Marketplace`](.github/workflows/publish-to-marketplace.yml) does one
`(release_version, build_mode, channel)` per dispatch — handy for a one-off fix:

```bash
gh workflow run publish-to-marketplace.yml --repo jiec-msft/cls-runner-poc \
  -f release_version=5.0.0 -f build_mode=split -f channel=nightly -f visibility=visible
```

Or locally (needs the token in-shell):

```powershell
pwsh scripts/download-binaries.ps1                                   # once, ~590 MB
./gradlew buildNativePlugins "-PpocBaseVersion=5.0.0"                # fat + 6 slims (split)
./gradlew buildPlugin -PuniversalBuild=true "-PpocBaseVersion=2.0.0" # universal (single fat)
pwsh scripts/verify-zips.ps1                                          # sanity-check metadata
pwsh scripts/publish-marketplace.ps1 -Token perm:xxxx -Channel nightly
```

## Channels

- **stable** = default channel; reaches everyone via the built-in Marketplace.
- **nightly** = a named channel; only reaches users who add the custom repo URL
  `https://plugins.jetbrains.com/plugins/nightly/32412` (Settings → Plugins → ⚙ → Manage Plugin
  Repositories). Use nightly for POC test versions to keep them off the default channel.
- Nightly cores are the stable core with **patch+1 and `-nightly.<run>`**, so a nightly build always
  sorts just above the matching stable but below the next stable major (see the workflow's version
  step). This mirrors jb `release.yml`.

## Version → artifact scheme

Single source of truth is the core version (`-PpocBaseVersion`); the build derives `{core}-251`
(fat/universal) and `{core}-261-{os}-{arch}` (slims). The universal vs split-fat difference is set by
`-PuniversalBuild` (no `until-build` vs `until-build=253.*`). The v1..v9 core mapping is in
[TEST-SCENARIOS.md §2](TEST-SCENARIOS.md).

> **Marketplace-verifier note:** the no-until-build universal is verified against future EAPs, so the
> plugin uses no `@ApiStatus.Internal` / scheduled-for-removal API — the binary path comes from the
> plugin's own `PluginAwareClassLoader` (jb `CopilotPlugin.getPluginBasePath()` pattern) and arch
> from `CpuArch`.
