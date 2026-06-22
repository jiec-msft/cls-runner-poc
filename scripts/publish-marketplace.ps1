#requires -Version 5.1
<#
.SYNOPSIS
  Uploads the built fat + slim ZIPs to JetBrains Marketplace via the plugin upload API.

  NOTE: the API uploads UPDATES to an ALREADY-EXISTING plugin. The very first upload of a
  brand-new plugin must be done once via the web UI (https://plugins.jetbrains.com/plugin/add)
  to create the listing (this also triggers moderation). After the plugin exists, use this
  script (keyed by xmlId) for every subsequent artifact.

  Max plugin size is 400 MB; the fat is ~362 MB, so it fits.

.PARAMETER Token       perm:... token from https://plugins.jetbrains.com/author/me/tokens
.PARAMETER Channel     release channel; "" = Stable, e.g. "nightly" to mirror jb and stay semi-private
.PARAMETER Hidden      set isHidden=true (approved but not publicly released)
.PARAMETER Zips        explicit ZIP list; default = the fat + all 6 slims under build/
#>
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$XmlId = "com.jiec.cls.runner",
    [string]$Channel = "",
    [switch]$Hidden,
    [string[]]$Zips
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $Zips -or $Zips.Count -eq 0) {
    $Zips = @()
    $Zips += (Get-ChildItem "$projectRoot\build\distributions\cls-runner-*.zip" -ErrorAction SilentlyContinue).FullName
    $Zips += (Get-ChildItem "$projectRoot\build\native-distributions\cls-runner-*.zip" -ErrorAction SilentlyContinue).FullName
}
if (-not $Zips -or $Zips.Count -eq 0) { throw "No ZIPs found. Run './gradlew buildNativePlugins' first." }

foreach ($zip in $Zips) {
    if (-not (Test-Path $zip)) { Write-Warning "skip (missing): $zip"; continue }
    $mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
    Write-Host "==> uploading $(Split-Path $zip -Leaf)  ($mb MB)  channel='$Channel' hidden=$($Hidden.IsPresent)"

    $args = @(
        "-s", "-S", "-i",
        "--header", "Authorization: Bearer $Token",
        "-F", "xmlId=$XmlId",
        "-F", "file=@$zip"
    )
    if ($Channel) { $args += @("-F", "channel=$Channel") }
    if ($Hidden) { $args += @("-F", "isHidden=true") }
    $args += "https://plugins.jetbrains.com/api/updates/upload"

    $resp = & curl.exe @args
    ($resp | Select-Object -First 1)   # status line
    if ($resp -join "`n" -notmatch "HTTP/.* 20\d") {
        Write-Warning "upload may have failed for $(Split-Path $zip -Leaf):"
        $resp | Select-Object -First 8 | ForEach-Object { "    $_" }
    }
}
Write-Host "done."
