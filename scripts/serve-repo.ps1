#requires -Version 5.1
<#
.SYNOPSIS
  Serves the staged custom plugin repo (dist-repo/) over localhost so the IDE can poll it.
  Configure the IDE: Settings -> Plugins -> (gear) -> Manage Plugin Repositories ->
    http://localhost:8181/updatePlugins.xml
#>
param(
    [int]$Port = 8181,
    [string]$Dir = "dist-repo"
)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$serveDir = Join-Path $projectRoot $Dir
if (-not (Test-Path (Join-Path $serveDir "updatePlugins.xml"))) {
    throw "No updatePlugins.xml in $serveDir. Run scripts/make-updatePlugins.ps1 first."
}
Write-Host "Serving $serveDir"
Write-Host "Custom repo URL: http://localhost:$Port/updatePlugins.xml"
Write-Host "(Ctrl+C to stop)"
python -m http.server $Port --bind 127.0.0.1 --directory $serveDir
