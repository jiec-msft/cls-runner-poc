# CLS Runner (POC)

A deliberately minimal IntelliJ Platform plugin used to validate **fat / slim split-plugin
distribution**, **JetBrains Marketplace OS/arch routing**, and **auto-update / migration**.

The plugin does exactly one thing: adds a **Tools → Launch CLS (--stdio)** action that finds the
bundled `copilot-language-server` binary for the current OS/arch under the plugin's own install
path and starts it with `--stdio`. It only proves the absolute-path launch mechanism — it does not
speak LSP to the process.

> Mirrors the mechanism in microsoft/copilot-intellij PR #12887 (`buildNativePlugins` /
> `NativePluginPatcher`) on a throwaway plugin id (`com.jiec.cls.runner`), so the production plugin
> is never touched.

## Distribution model

| Artifact | version | since-build | until-build | os depends | arch depends |
|---|---|---|---|---|---|
| fat | `1.13.0-251` | 251 | 260.* | — | — |
| slim | `1.13.0-261-windows-x64` | 261 | — | os.windows | arch.x86_64 |
| slim | `1.13.0-261-windows-arm64` | 261 | — | os.windows | arch.arm64 |
| slim | `1.13.0-261-macos-x64` | 261 | — | os.mac | arch.x86_64 |
| slim | `1.13.0-261-macos-arm64` | 261 | — | os.mac | arch.arm64 |
| slim | `1.13.0-261-linux-x64` | 261 | — | os.linux | arch.x86_64 |
| slim | `1.13.0-261-linux-arm64` | 261 | — | os.linux | arch.arm64 |

All seven artifacts share the **same plugin id**, which is what makes auto-update / migration work.
`< 261` IDEs get the fat build (all 6 binaries); `>= 261` IDEs get the matching slim (one binary).

## Build

```powershell
# 1. Fetch the real CLS binaries for all 6 platforms (~590 MB, not committed)
pwsh scripts/download-binaries.ps1

# 2. Build the fat ZIP (build/distributions/cls-runner-1.13.0-251.zip)
./gradlew buildPlugin

# 3. Repack into the 6 slim ZIPs (build/native-distributions/)
./gradlew buildNativePlugins
```

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
