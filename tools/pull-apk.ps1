# Download the latest built APK into the local apk/ directory.
#
# The GitHub Actions workflow builds and publishes a Release tagged v<versionName>
# with the APK attached. This script pulls that asset (or, as a fallback, the
# artifact from the most recent successful run) and saves it as
#   apk/DeepTalking-lite-v<version>.apk
#
# Usage:
#   powershell -File tools/pull-apk.ps1              # use versionName from build.gradle.kts
#   powershell -File tools/pull-apk.ps1 -Version 1.2.0
#   powershell -File tools/pull-apk.ps1 -Tag v1.2.0  # force a specific release tag
#   powershell -File tools/pull-apk.ps1 -FromArtifact  # skip Release, pull the latest run artifact
#
# Requires the GitHub CLI (gh) to be installed and authenticated.
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [string]$Version,
  [string]$Tag,
  [switch]$FromArtifact
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$apkDir = Join-Path $root 'apk'
$gradleFile = Join-Path $root 'android-lite/app/build.gradle.kts'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  Write-Host 'gh (GitHub CLI) not found. Install it and run: gh auth login' -ForegroundColor Red
  exit 1
}

if (-not (Test-Path -LiteralPath $gradleFile)) {
  Write-Host ('MISSING FILE: ' + $gradleFile) -ForegroundColor Red
  exit 1
}

if (-not $Version) {
  $match = Select-String -LiteralPath $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
  if (-not $match) {
    Write-Host 'Could not read versionName from build.gradle.kts' -ForegroundColor Red
    exit 1
  }
  $Version = $match.Matches[0].Groups[1].Value
}
if (-not $Tag) { $Tag = 'v' + $Version }

if (-not (Test-Path -LiteralPath $apkDir)) {
  New-Item -ItemType Directory -Path $apkDir | Out-Null
}
$dest = Join-Path $apkDir ('DeepTalking-lite-v' + $Version + '.apk')

Write-Host ('Target: ' + $dest) -ForegroundColor Cyan

$downloaded = $null

if (-not $FromArtifact) {
  # Preferred path: release asset named DeepTalking-lite-v<version>.apk
  $assetName = 'DeepTalking-lite-v' + $Version + '.apk'
  Write-Host ('Downloading release asset ' + $assetName + ' from ' + $Tag + ' ...') -ForegroundColor Cyan
  $tmpDir = Join-Path $env:TEMP ('deeptalking-apk-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmpDir | Out-Null
  try {
    gh release download $Tag --pattern $assetName --dir $tmpDir
    $candidate = Join-Path $tmpDir $assetName
    if (Test-Path -LiteralPath $candidate) { $downloaded = $candidate }
  } catch {
    Write-Host ('Release download failed: ' + $_.Exception.Message) -ForegroundColor Yellow
  }
  if (-not $downloaded) {
    Write-Host 'Falling back to latest successful workflow run artifact ...' -ForegroundColor Yellow
  }
}

if (-not $downloaded) {
  $runJson = gh run list --workflow 'build-lite-apk.yml' --status success --limit 1 --json databaseId,headSha | ConvertFrom-Json
  if (-not $runJson -or $runJson.Count -eq 0) {
    Write-Host 'No successful workflow run found.' -ForegroundColor Red
    exit 1
  }
  $runId = $runJson[0].databaseId
  $tmpDir = Join-Path $env:TEMP ('deeptalking-apk-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmpDir | Out-Null
  Write-Host ('Downloading artifact from run ' + $runId + ' ...') -ForegroundColor Cyan
  gh run download $runId -n deeptalking-lite-release-apk -D $tmpDir
  $found = Get-ChildItem -Path $tmpDir -Recurse -Filter '*.apk' | Select-Object -First 1
  if ($found) { $downloaded = $found.FullName }
}

if (-not $downloaded -or -not (Test-Path -LiteralPath $downloaded)) {
  Write-Host 'APK download failed.' -ForegroundColor Red
  exit 1
}

Copy-Item -LiteralPath $downloaded -Destination $dest -Force
$sizeMb = [math]::Round((Get-Item -LiteralPath $dest).Length / 1MB, 2)
Write-Host ('Saved: ' + $dest + ' (' + $sizeMb + ' MB)') -ForegroundColor Green
exit 0
