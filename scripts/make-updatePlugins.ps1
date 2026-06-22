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

$BaseUrl = $BaseUrl.TrimEnd("/")
$outPath = Join-Path $projectRoot $OutDir
New-Item -ItemType Directory -Force -Path $outPath | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Reads META-INF/plugin.xml from the plugin jar inside a distribution zip. We classify each zip by
# its REAL <idea-version>/<depends> (not the file name), so the universal (1.12.x-251) and the fat
# (1.13.x-251) — which share the -251 suffix but differ in since/until — are emitted correctly, and
# the since/until values can never drift from what was actually built.
function Get-PluginXml([string]$zipPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($je in ($zip.Entries | Where-Object { $_.FullName -match 'lib/.*\.jar$' })) {
            $ms = New-Object System.IO.MemoryStream
            $s = $je.Open(); $s.CopyTo($ms); $s.Close(); $ms.Position = 0
            $jar = New-Object System.IO.Compression.ZipArchive($ms, [System.IO.Compression.ZipArchiveMode]::Read)
            try {
                $px = $jar.Entries | Where-Object { $_.FullName -eq 'META-INF/plugin.xml' } | Select-Object -First 1
                if ($px) {
                    $r = New-Object System.IO.StreamReader($px.Open())
                    $xml = $r.ReadToEnd(); $r.Close()
                    return $xml
                }
            } finally { $jar.Dispose(); $ms.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $null
}

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
    $url = "$BaseUrl/$($zip.Name)"

    $xml = Get-PluginXml $zip.FullName
    if (-not $xml) { throw "No plugin.xml found in $($zip.Name)" }
    if ($xml -notmatch '<version>([^<]+)</version>') { throw "No <version> in $($zip.Name) plugin.xml" }
    $version = $Matches[1]
    if ($xml -notmatch '(<idea-version[^>]*/>)') { throw "No <idea-version/> in $($zip.Name) plugin.xml" }
    $ideaVersion = $Matches[1].Trim()

    [void]$sb.AppendLine("  <plugin id=`"$(Esc $pluginId)`" url=`"$(Esc $url)`" version=`"$(Esc $version)`">")
    [void]$sb.AppendLine("    <name>$(Esc $pluginName)</name>")
    [void]$sb.AppendLine("    $ideaVersion")
    foreach ($m in [regex]::Matches($xml, '<depends>(com\.intellij\.modules\.(?:os|arch)\.[^<]+)</depends>')) {
        [void]$sb.AppendLine("    <depends>$($m.Groups[1].Value)</depends>")
    }
    [void]$sb.AppendLine('  </plugin>')
    Write-Host "staged $($zip.Name)  (version=$version, $ideaVersion)"
}

[void]$sb.AppendLine('</plugins>')

$xmlPath = Join-Path $outPath "updatePlugins.xml"
[System.IO.File]::WriteAllText($xmlPath, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "updatePlugins.xml -> $xmlPath"
Write-Host "Custom repo URL    -> $BaseUrl/updatePlugins.xml"
Write-Host "Next: pwsh scripts/serve-repo.ps1  (serves '$OutDir' on $BaseUrl)"
