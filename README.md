# CLS Runner

A deliberately minimal IntelliJ Platform plugin used to validate **fat / slim split-plugin
distribution**, **JetBrains Marketplace OS/arch routing**, and **auto-update / migration**.

The plugin adds two Tools-menu actions: **Launch CLS (--stdio)** finds the bundled
`copilot-language-server` binary for the current OS/arch under the plugin's own install path and
starts it with `--stdio`; **Stop CLS** stops it again. It only proves the absolute-path launch
mechanism — it does not speak LSP to the process. The process is owned by an application service, so
it is also killed automatically when the IDE shuts down (no orphaned processes).

> Mirrors the mechanism in microsoft/copilot-intellij PR #12887 (`buildNativePlugins` /
> `NativePluginPatcher`) on a throwaway plugin id (`com.jiec.cls.runner`), so the production plugin
> is never touched.

## Distribution model

Two generations, sharing one plugin id (`com.jiec.cls.runner`) so auto-update / migration works:

**`1.12.0` — universal (legacy) build** — one plugin, all 6 binaries, since-build `251.25410`
(2025.1.1) with **no until-build**, so it installs on every IDE incl. 2026.1+. This is the "old
plugin" a user has before the split.

**`1.13.0` — split build** — the fat + 6 slims:

| Artifact | version | since-build | until-build | os depends | arch depends |
|---|---|---|---|---|---|
| fat | `1.13.0-251` | 251 | 260.* | — | — |
| slim | `1.13.0-261-windows-x64` | 261 | — | os.windows | arch.x86_64 |
| slim | `1.13.0-261-windows-arm64` | 261 | — | os.windows | arch.arm64 |
| slim | `1.13.0-261-macos-x64` | 261 | — | os.mac | arch.x86_64 |
| slim | `1.13.0-261-macos-arm64` | 261 | — | os.mac | arch.arm64 |
| slim | `1.13.0-261-linux-x64` | 261 | — | os.linux | arch.x86_64 |
| slim | `1.13.0-261-linux-arm64` | 261 | — | os.linux | arch.arm64 |

Migration story: a user on `1.12.0` is offered `1.13.0-251` (fat) on a `< 261` IDE, or the matching
`1.13.0-261-{os}-{arch}` (slim) on a `>= 261` IDE — because the fat's until-build `260.*` excludes
261, leaving only the slim there. Both `1.13.0` variants sort above `1.12.0`.

## Build

```powershell
# 1. Fetch the real CLS binaries for all 6 platforms (~590 MB, not committed)
pwsh scripts/download-binaries.ps1

# 2a. Universal (legacy) 1.12.1 — single ZIP, 2025.1.1+, no cap
#     -> build/distributions/cls-runner-1.12.1.zip
./gradlew buildPlugin -PuniversalBuild=true -PpocBaseVersion=1.12.1

# 2b. Split 1.13.1 — fat ZIP + 6 slim ZIPs
#     -> build/distributions/cls-runner-1.13.1-251.zip
#     -> build/native-distributions/cls-runner-1.13.1-261-{os}-{arch}.zip
./gradlew buildNativePlugins -PpocBaseVersion=1.13.1
```

> **Marketplace-verifier clean (1.12.1 / 1.13.1):** the binary path is resolved from the plugin
> jar's own code source (pure JDK) and arch from `CpuArch`, so the plugin uses no
> `@ApiStatus.Internal` (`PluginManagerCore.getPlugin`) or scheduled-for-removal
> (`SystemInfo.isAarch64`) API — both flagged by the verifier on 2026.2+ because the universal build
> has no until-build cap.

## Publish

- **Custom plugin repo** (fast local iteration): `scripts/make-updatePlugins.ps1` stages the ZIPs +
  generates `updatePlugins.xml`; `scripts/serve-repo.ps1` serves them on `http://localhost:8181`.
  Point the IDE at it via Settings → Plugins → ⚙ → Manage Plugin Repositories.
- **JetBrains Marketplace** (stable + nightly): the
  [`Publish to Marketplace`](.github/workflows/publish-to-marketplace.yml) GitHub Actions workflow
  builds the fat + slims and uploads them via curl (mirrors jb). Needs the repo secret
  `JETBRAINS_MARKETPLACE_TOKEN`. New plugins require a one-time web-UI upload + moderation before the
  workflow can publish updates. See [PUBLISHING.md](PUBLISHING.md).

## Layout

```
build.gradle.kts                          # IPGP 2.16.0; bundles native/**; buildNativePlugins task
buildSrc/.../NativePluginPatcher.kt        # fat ZIP -> 6 slim ZIPs (adapted from PR #12887)
src/main/kotlin/.../LaunchClsAction.kt      # the launch button (runs off the EDT)
src/main/resources/META-INF/plugin.xml
copilot-agent/native/<6 platforms>/...      # real binaries (gitignored, fetched by the script)
scripts/                                    # download / updatePlugins / release helpers
```
