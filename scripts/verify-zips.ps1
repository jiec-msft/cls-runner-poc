#requires -Version 5.1
# Verifies the built fat + slim ZIPs: native dirs kept, plugin.xml id/version/idea-version/depends.
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
$root = "D:\jbws-workspaces\split-plugin\.tmp\cls-runner-poc"

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

foreach ($z in $zips) {
    $mb = [math]::Round($z.Length / 1MB, 1)
    Write-Host "==================================================================="
    Write-Host ("{0}   ({1} MB)" -f $z.Name, $mb)
    Write-Host "  native: $(Get-NativePlatforms $z.FullName)"
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
}
Write-Host "==================================================================="
