#requires -Version 5.1
# Verifies the built fat + slim ZIPs: native dirs kept, plugin.xml id/version/idea-version/depends.
param(
    [switch]$RequireNativeVariants
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
$root = Split-Path -Parent $PSScriptRoot

$zips = @()
$zips += Get-ChildItem "$root\build\distributions\cls-runner-*.zip" -ErrorAction SilentlyContinue
$zips += Get-ChildItem "$root\build\native-distributions\cls-runner-*.zip" -ErrorAction SilentlyContinue

function Read-PluginXml($zipPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $jarEntry = $zip.Entries | Where-Object { $_.FullName -match 'lib/.*\.jar$' -and $_.FullName -notmatch 'searchableOptions' } | Select-Object -First 5
        foreach ($je in $jarEntry) {
            $ms = New-Object System.IO.MemoryStream
            $s = $je.Open(); $s.CopyTo($ms); $s.Close(); $ms.Position = 0
            $jar = New-Object System.IO.Compression.ZipArchive($ms, [System.IO.Compression.ZipArchiveMode]::Read)
            $px = $jar.Entries | Where-Object { $_.FullName -eq 'META-INF/plugin.xml' } | Select-Object -First 1
            if ($px) {
                $r = New-Object System.IO.StreamReader($px.Open())
                $xml = $r.ReadToEnd(); $r.Close(); $jar.Dispose(); $ms.Dispose()
                return @{ jar = $je.FullName; xml = $xml }
            }
            $jar.Dispose(); $ms.Dispose()
        }
    } finally { $zip.Dispose() }
    return $null
}

function Get-NativePlatforms($zipPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $set = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($e in $zip.Entries) {
            if ($e.FullName -match 'copilot-agent/native/([^/]+)/') { [void]$set.Add($Matches[1]) }
        }
        return ($set | Sort-Object) -join ", "
    } finally { $zip.Dispose() }
}

$metadata = @()
foreach ($z in $zips) {
    $mb = [math]::Round($z.Length / 1MB, 1)
    Write-Host "==================================================================="
    Write-Host ("{0}   ({1} MB)" -f $z.Name, $mb)
    $native = Get-NativePlatforms $z.FullName
    Write-Host "  native: $native"
    $p = Read-PluginXml $z.FullName
    if (-not $p) { Write-Host "  plugin.xml: NOT FOUND"; continue }
    Write-Host "  jar: $($p.jar)"
    $id      = if ($p.xml -match '<id>([^<]+)</id>') { $Matches[1] } else { "?" }
    $ver     = if ($p.xml -match '<version>([^<]+)</version>') { $Matches[1] } else { "?" }
    $idea    = if ($p.xml -match '(<idea-version[^>]*/>)') { $Matches[1] } else { "?" }
    Write-Host "  id:      $id"
    Write-Host "  version: $ver"
    Write-Host "  idea:    $idea"
    $deps = [regex]::Matches($p.xml, '<depends>(com\.intellij\.modules\.(?:os|arch)\.[^<]+)</depends>')
    if ($deps.Count -gt 0) {
        Write-Host "  os/arch depends: $(($deps | ForEach-Object { $_.Groups[1].Value }) -join ', ')"
    } else {
        Write-Host "  os/arch depends: (none)"
    }
    $metadata += [pscustomobject]@{
        File = $z
        Name = $z.Name
        Native = $native
        Version = $ver
        IdeaVersion = $idea
        Dependencies = @($deps | ForEach-Object { $_.Groups[1].Value })
    }
}
Write-Host "==================================================================="

if ($RequireNativeVariants) {
    $variants = [ordered]@{
        "linux-x86_64" = @("linux-x64", "com.intellij.modules.os.linux", "com.intellij.modules.arch.x86_64")
        "linux-arm64" = @("linux-arm64", "com.intellij.modules.os.linux", "com.intellij.modules.arch.arm64")
        "mac-x86_64" = @("darwin-x64", "com.intellij.modules.os.mac", "com.intellij.modules.arch.x86_64")
        "mac-arm64" = @("darwin-arm64", "com.intellij.modules.os.mac", "com.intellij.modules.arch.arm64")
        "windows-x86_64" = @("win32-x64", "com.intellij.modules.os.windows", "com.intellij.modules.arch.x86_64")
        "windows-arm64" = @("win32-arm64", "com.intellij.modules.os.windows", "com.intellij.modules.arch.arm64")
    }

    foreach ($suffix in $variants.Keys) {
        $matches = @($metadata | Where-Object { $_.Name -match "-$([regex]::Escape($suffix))\.zip$" })
        if ($matches.Count -ne 1) {
            throw "Expected exactly one '$suffix' variant ZIP, found $($matches.Count)."
        }
        $item = $matches[0]
        $expected = $variants[$suffix]
        if ($item.Native -ne $expected[0]) {
            throw "$($item.Name) contains native '$($item.Native)', expected '$($expected[0])'."
        }
        if ($item.Version -notmatch "-$([regex]::Escape($suffix))$") {
            throw "$($item.Name) has plugin version '$($item.Version)', expected suffix '-$suffix'."
        }
        if ($item.IdeaVersion -notmatch 'since-build="261"' -or $item.IdeaVersion -match 'until-build=') {
            throw "$($item.Name) has unexpected compatibility: $($item.IdeaVersion)"
        }
        foreach ($dependency in $expected[1..2]) {
            if ($dependency -notin $item.Dependencies) {
                throw "$($item.Name) is missing dependency '$dependency'."
            }
        }
    }

    $regular = @(
        $metadata | Where-Object {
            $_.File.Directory.Name -eq "distributions" -and
            $_.Name -notmatch '-(linux|mac|windows)-(x86_64|arm64)\.zip$'
        }
    )
    if ($regular.Count -ne 1) {
        throw "Expected exactly one regular buildPlugin ZIP, found $($regular.Count)."
    }
    $expectedAllPlatforms = "darwin-arm64, darwin-x64, linux-arm64, linux-x64, win32-arm64, win32-x64"
    if ($regular[0].Native -ne $expectedAllPlatforms) {
        throw "$($regular[0].Name) contains '$($regular[0].Native)', expected all six native platforms."
    }
    if ($regular[0].Dependencies.Count -ne 0) {
        throw "$($regular[0].Name) unexpectedly declares native OS/architecture dependencies."
    }

    Write-Host "Native variant validation passed: 6 routed variants + unchanged regular buildPlugin ZIP."
}
