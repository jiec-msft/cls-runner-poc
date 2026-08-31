# IntelliJ Platform Gradle Plugin native variants POC

## Scope

This POC evaluates IntelliJ Platform Gradle Plugin `2.19.0-SNAPSHOT` native variants without
replacing the existing manual fat/slim timeline. Enable the snapshot path with
`-PnativeVariants=true`. The legacy path remains the control.

The snapshot is resolved from Maven Central snapshots. The native build targets IntelliJ IDEA
2026.1.5 and declares `since-build="261"`.

## Build and local evidence

```powershell
pwsh scripts/download-binaries.ps1
./gradlew clean cleanSandbox buildPlugin buildPluginVariants prepareSandbox_runIde `
  -PnativeVariants=true -PpocBaseVersion=11.0.0
pwsh scripts/verify-zips.ps1 -RequireNativeVariants
```

Validated on 2026-08-30 with the snapshot published on that date:

| Output | Native payload | Size |
|---|---|---:|
| `linux-arm64` | `linux-arm64` | 62.6 MB |
| `linux-x86_64` | `linux-x64` | 64.3 MB |
| `mac-arm64` | `darwin-arm64` | 57.5 MB |
| `mac-x86_64` | `darwin-x64` | 59.9 MB |
| `windows-arm64` | `win32-arm64` | 57.6 MB |
| `windows-x86_64` | `win32-x64` | 60.9 MB |
| regular `buildPlugin` | all six payloads | 362.6 MB |

Every variant contained exactly one native directory, `since-build="261"`, no `until-build`, and
the matching `com.intellij.modules.os.*` and `com.intellij.modules.arch.*` dependencies. The
regular `buildPlugin` archive still contained all six native directories and no OS/architecture
dependencies.

`prepareSandbox_runIde` on Windows x86_64 selected only `win32-x64` and patched the sandbox plugin
version to `11.0.1-nativevariants.0-261-windows-x86_64`. The equivalent CI check expects
`linux-x64` and `linux-x86_64`.

The legacy control also passed after the upgrade to the snapshot plugin:

- `-PnativeVariants=false`
- all six native directories
- `since-build="251"`, `until-build="253.*"`
- no OS/architecture dependencies

## Compatibility limits

- Native variants hard-fail below `since-build` 261. They cannot replace the 251-253 build in the
  same Gradle invocation. Projects supporting both ranges still need separate legacy and modern
  compilations.
- IntelliJ IDEA 2026.1.5 libraries use Kotlin metadata 2.3. The POC had to move from Kotlin 2.1.20
  to 2.3.10 before it could compile against that platform.
- Variant names are fixed by the Gradle plugin: `mac`, `windows`, `linux` and `x86_64`, `arm64`.
  Existing release automation uses `macos` and `x64`, so consumers must accept the new names or the
  Gradle plugin must provide a naming hook.
- `buildPluginVariants` does not build the regular `buildPlugin` archive. This is desirable for
  publication but means release workflows must request the regular archive separately when it is
  required as evidence or fallback.
- In the current snapshot, `publishPlugin` switches from `buildPlugin`/`signPlugin` to the six
  `buildPluginVariants` archives when native variants are enabled. Variant signing is not exposed
  by that task graph. Projects that sign plugins still need a documented per-variant signing path.

## Marketplace and nightly channel

Marketplace plugin 32412 already proves that manually generated OS/architecture variants are
accepted on the `nightly` channel. Its public updates API lists approved 261 variants, and the
custom repository URL is:

`https://plugins.jetbrains.com/plugins/nightly/32412`

The existing `Publish to Marketplace` workflow accepts `build_mode=nativeVariants` and adds
explicit safety controls:

- publication is restricted to the `nightly` channel
- only the six variant ZIPs are uploaded; the regular 362 MB archive is retained as workflow
  evidence but excluded from publication
- visible publication requires the explicit confirmation text `publish-visible-nightly`

The workflow and visible nightly channel were validated with:

- [dry-run build 33319876093](https://github.com/jiec-msft/cls-runner-poc/actions/runs/33319876093)
- [visible `11.0.1-nightly.8` publication 33320112946](https://github.com/jiec-msft/cls-runner-poc/actions/runs/33320112946)
- [visible `11.0.1-nightly.9` publication 33320561043](https://github.com/jiec-msft/cls-runner-poc/actions/runs/33320561043)

All six archives in both visible publications were approved by Marketplace. Repository queries
with build `IU-261.27258.48`, the matching OS value, and `arch=X86_64` or `arch=ARM64` selected
the correct Linux, macOS, and Windows update. A repository request without the IDE's build, OS,
and architecture parameters can return an older universal update and is not a valid routing test.

The Windows x86_64 install/update path was validated in an isolated IntelliJ IDEA 2026.1.5
configuration using build `IU-261.27258.48`:

1. `installPlugins` with `https://plugins.jetbrains.com/plugins/nightly/32412` installed
   `11.0.1-nightly.8-261-windows-x86_64`, containing only `win32-x64`.
2. A headless `update` run with
   `-Didea.plugin.hosts=https://plugins.jetbrains.com/plugins/nightly/32412` discovered
   `11.0.1-nightly.9-261-windows-x86_64`.
3. The first `update` run prepared the update. A second run with
   `-Didea.force.plugin.updates=true` applied it. The final installed descriptor reported the
   `.9` Windows x86_64 version and still contained only `win32-x64`.

The custom repository passed to `installPlugins` is not automatically reused by the headless
`update` starter; `idea.plugin.hosts` is required. Older 261 patches remain outside the supported
custom-channel contract communicated by JetBrains.

## Agent Probe evaluation

Existing Agent Probe infrastructure is useful but cannot verify Marketplace routing or update
migration without changes:

- `agent-probe-run.yml` always starts the IDE with a local ZIP supplied through
  `path.to.build.plugin`; that bypasses Marketplace selection.
- Probe scripts have UI actions for opening Settings and reusable `SettingsSearch` navigation to
  the Plugins page.
- The action vocabulary has no restart/relaunch primitive, while plugin installation and update
  verification require at least one restart and two plugin versions.
- Cross-run plugin artifacts are not currently shared with this POC.

The smallest future automation is a two-phase Starter test that starts without a preinstalled
plugin, adds the custom repository, installs the first version, restarts the same sandbox, checks
for the second version, applies it, and verifies the installed plugin descriptor. The existing
Settings navigation from
`automatic-error-collection/automatic-error-collection/tc-003.json` can be reused. For this focused
POC, IntelliJ's headless `installPlugins` and `update` application starters are a better fit than
changing production Agent Probe code.

## Recommendation

Keep the manual packager through 2.19.0 GA. The new DSL correctly builds, publishes, routes,
installs, and updates native payloads and is a strong replacement candidate for the six-archive
repack step. Adoption should wait for:

1. A normal publication cycle confirming Marketplace selection, installation, and update behavior.
2. A decision on fixed variant archive/version names.
3. A dual-baseline design that preserves the 251-253 artifact.

## Feedback for JetBrains

- Document how projects using `signPlugin` sign every archive consumed by `publishPlugin`.
- Provide archive/version suffix customization or document the fixed naming contract as stable.
- Fail when an enabled variant file collection is empty; an empty archive can otherwise publish
  successfully and fail only at runtime.
- Add an official custom-channel integration test and document the minimum 2026.1.5 client build.
- Document the build, OS, and architecture query contract and how headless update tests configure
  custom repositories.
- Document that `runIde` uses a separate host-variant sandbox while `buildPlugin` remains the base
  distribution.
