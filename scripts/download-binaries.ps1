#requires -Version 5.1
<#
.SYNOPSIS
  Downloads the real copilot-language-server binaries for all 6 platforms from npm
  and lays them out under copilot-agent/native/<npm-platform>/ for bundling.

  Layout produced (matches PR #12887's NativePluginPatcher expectations):
    copilot-agent/native/win32-x64/copilot-language-server.exe
    copilot-agent/native/win32-arm64/copilot-language-server.exe
    copilot-agent/native/darwin-x64/copilot-language-server
    copilot-agent/native/darwin-arm64/copilot-language-server
    copilot-agent/native/linux-x64/copilot-language-server
    copilot-agent/native/linux-arm64/copilot-language-server
#>
param(
    [string]$ClsVersion = "1.509.0"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot "copilot-agent\native"

$platforms = @("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64", "linux-arm64")

$work = Join-Path $env:TEMP ("cls-dl-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $work | Out-Null

foreach ($p in $platforms) {
    $pkg = "@github/copilot-language-server-$p@$ClsVersion"
    Write-Host "==> $pkg"
    $dest = Join-Path $nativeRoot $p
    if (Test-Path (Join-Path $dest "copilot-language-server*")) {
        Write-Host "    already present, skipping"
        continue
    }
    $pdir = Join-Path $work $p
    New-Item -ItemType Directory -Force -Path $pdir | Out-Null
    Push-Location $pdir
    try {
        npm pack $pkg --silent --pack-destination $pdir | Out-Null
        $tgz = Get-ChildItem -Path $pdir -Filter *.tgz | Select-Object -First 1
        if (-not $tgz) { throw "npm pack produced no tarball for $pkg" }
        tar -xzf $tgz.FullName -C $pdir
        $bin = Get-ChildItem -Path (Join-Path $pdir "package") -Filter "copilot-language-server*" -File | Select-Object -First 1
        if (-not $bin) { throw "no copilot-language-server binary inside $pkg" }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Copy-Item $bin.FullName (Join-Path $dest $bin.Name) -Force
        $mb = [math]::Round((Get-Item (Join-Path $dest $bin.Name)).Length / 1MB, 1)
        Write-Host "    -> $dest\$($bin.Name)  ($mb MB)"
    }
    finally {
        Pop-Location
    }
}

Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "=== summary ==="
Get-ChildItem -Path $nativeRoot -Recurse -File | ForEach-Object {
    "{0,8:N1} MB  {1}" -f ($_.Length / 1MB), $_.FullName.Substring($projectRoot.Length + 1)
}
