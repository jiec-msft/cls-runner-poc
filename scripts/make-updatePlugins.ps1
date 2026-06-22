#requires -Version 5.1
<#
.SYNOPSIS
  Stages the built fat + 6 slim ZIPs into a custom-plugin-repo folder and generates the
  updatePlugins.xml the IDE polls.

  Why a LOCAL server (not GitHub Releases): the hosting repo is PRIVATE, and private-repo
  Release assets are NOT anonymously downloadable — the IDE's plugin downloader sends no
  GitHub token, so it would get 404. A localhost static server is the correct, instant tool
  for local custom-repo iteration. Point the IDE at:
      Settings -> Plugins -> (gear) -> Manage Plugin Repositories -> http://localhost:8181/updatePlugins.xml
  then run scripts/serve-repo.ps1.

.PARAMETER BaseUrl
  URL prefix the IDE will use to fetch the ZIPs. Must match how you serve OutDir.

.PARAMETER OutDir
  Folder to stage the repo into (served as the web root).

.PARAMETER OnlyFat
  Emit only the fat entry (for the pure auto-update experiment: install fat, bump, re-stage).
#>
param(
    [string]$BaseUrl = "http://localhost:8181",
    [string]$OutDir = "dist-repo",
    [switch]$OnlyFat
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$pluginId = "com.jiec.cls.runner"
$pluginName = "CLS Runner"
$universalSinceBuild = "251.25410"   # 2025.1.1 — keep in sync with gradle.properties

$BaseUrl = $BaseUrl.TrimEnd("/")
$outPath = Join-Path $projectRoot $OutDir
New-Item -ItemType Directory -Force -Path $outPath | Out-Null

# Maps for the trailing platform suffix on slim ZIPs.
$osModule = @{ windows = "windows"; macos = "mac"; linux = "linux" }
$archModule = @{ x64 = "x86_64"; arm64 = "arm64" }

function Get-Zips {
    $fat = Get-ChildItem -Path (Join-Path $projectRoot "build\distributions") -Filter "cls-runner-*.zip" -ErrorAction SilentlyContinue
    if ($OnlyFat) { return $fat }
    $slim = Get-ChildItem -Path (Join-Path $projectRoot "build\native-distributions") -Filter "cls-runner-*.zip" -ErrorAction SilentlyContinue
    return @($fat) + @($slim)
}

function Esc([string]$s) { [System.Security.SecurityElement]::Escape($s) }

$zips = Get-Zips
if (-not $zips -or $zips.Count -eq 0) {
    throw "No ZIPs found. Run './gradlew buildPlugin buildNativePlugins' first."
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$sb.AppendLine('<plugins>')

foreach ($zip in $zips) {
    Copy-Item $zip.FullName (Join-Path $outPath $zip.Name) -Force
    # version = file name minus 'cls-runner-' prefix and '.zip'
    $version = $zip.BaseName -replace '^cls-runner-', ''
    $url = "$BaseUrl/$($zip.Name)"

    [void]$sb.AppendLine("  <plugin id=`"$(Esc $pluginId)`" url=`"$(Esc $url)`" version=`"$(Esc $version)`">")
    [void]$sb.AppendLine("    <name>$(Esc $pluginName)</name>")

    # Classify by the trailing suffix (robust for stable, nightly, universal):
    #   ...-261-{os}-{arch} -> slim     ...-251 -> fat     anything else -> universal (e.g. 1.12.0)
    if ($version -match '-261-(windows|macos|linux)-(x64|arm64)$') {
        [void]$sb.AppendLine('    <idea-version since-build="261"/>')
        [void]$sb.AppendLine("    <depends>com.intellij.modules.os.$($osModule[$Matches[1]])</depends>")
        [void]$sb.AppendLine("    <depends>com.intellij.modules.arch.$($archModule[$Matches[2]])</depends>")
    } elseif ($version -match '-251$') {
        [void]$sb.AppendLine('    <idea-version since-build="251" until-build="260.*"/>')
    } else {
        # universal (legacy) build: 2025.1.1+ with no upper bound
        [void]$sb.AppendLine("    <idea-version since-build=`"$universalSinceBuild`"/>")
    }
    [void]$sb.AppendLine('  </plugin>')
    Write-Host "staged $($zip.Name)  (version=$version)"
}

[void]$sb.AppendLine('</plugins>')

$xmlPath = Join-Path $outPath "updatePlugins.xml"
[System.IO.File]::WriteAllText($xmlPath, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "updatePlugins.xml -> $xmlPath"
Write-Host "Custom repo URL    -> $BaseUrl/updatePlugins.xml"
Write-Host "Next: pwsh scripts/serve-repo.ps1  (serves '$OutDir' on $BaseUrl)"
