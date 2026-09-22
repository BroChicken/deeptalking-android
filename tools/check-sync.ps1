# Verify that the repo-root hub.html and the APK copy
# (android-lite/app/src/main/assets/hub.html) are byte-identical.
# Per AGENTS.md rule 2 both files must match, otherwise committing is forbidden.
# Usage: powershell -File tools/check-sync.ps1   (exit 0 = match, exit 1 = mismatch)
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$devFile = Join-Path $root 'hub.html'
$apkFile = Join-Path $root 'android-lite/app/src/main/assets/hub.html'

foreach ($file in @($devFile, $apkFile)) {
  if (-not (Test-Path -LiteralPath $file)) {
    Write-Host ("MISSING FILE: " + $file) -ForegroundColor Red
    exit 1
  }
}

$devHash = (Get-FileHash -LiteralPath $devFile -Algorithm SHA256).Hash
$apkHash = (Get-FileHash -LiteralPath $apkFile -Algorithm SHA256).Hash

if ($devHash -ne $apkHash) {
  Write-Host 'SHA256 MISMATCH - do not commit:' -ForegroundColor Red
  Write-Host ("  hub.html                                  " + $devHash)
  Write-Host ("  android-lite/app/src/main/assets/hub.html " + $apkHash)
  Write-Host 'Sync first: Copy-Item -LiteralPath .\hub.html -Destination .\android-lite\app\src\main\assets\hub.html -Force'
  exit 1
}

Write-Host ("SHA MATCH " + $devHash) -ForegroundColor Green
exit 0
