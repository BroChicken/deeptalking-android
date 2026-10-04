# Read/bump the app version in android-lite/app/build.gradle.kts.
#
# The app version name is X.Y.Z and versionCode is a monotonic integer.
# Per AGENTS.md, every APK update increments Z and versionCode by 1; X and Y
# are only changed when the user asks.
#
# Usage:
#   powershell -File tools/native-version.ps1                 # print current
#   powershell -File tools/native-version.ps1 -BumpPatch      # Z+1, versionCode+1
#   powershell -File tools/native-version.ps1 -Set 2.0.0      # set X.Y.Z (versionCode+1)
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [switch]$BumpPatch,
  [string]$Set
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root 'android-lite/app/build.gradle.kts'

if (-not (Test-Path -LiteralPath $gradleFile)) {
  Write-Host ('MISSING FILE: ' + $gradleFile) -ForegroundColor Red
  exit 1
}

$content = Get-Content -LiteralPath $gradleFile -Raw
$nameMatch = [regex]::Match($content, 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"')
$codeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
if (-not $nameMatch.Success -or -not $codeMatch.Success) {
  Write-Host 'Could not parse versionName/versionCode.' -ForegroundColor Red
  exit 1
}

$major = [int]$nameMatch.Groups[1].Value
$minor = [int]$nameMatch.Groups[2].Value
$patch = [int]$nameMatch.Groups[3].Value
$code = [int]$codeMatch.Groups[1].Value

if (-not $BumpPatch -and -not $Set) {
  Write-Host ('versionName=' + $major + '.' + $minor + '.' + $patch + '  versionCode=' + $code) -ForegroundColor Cyan
  exit 0
}

if ($Set) {
  $m = [regex]::Match($Set, '^(\d+)\.(\d+)\.(\d+)$')
  if (-not $m.Success) { Write-Host '-Set must be X.Y.Z' -ForegroundColor Red; exit 1 }
  $major = [int]$m.Groups[1].Value
  $minor = [int]$m.Groups[2].Value
  $patch = [int]$m.Groups[3].Value
} else {
  $patch += 1
}
$code += 1
$newName = "$major.$minor.$patch"

$content = [regex]::Replace($content, 'versionName\s*=\s*"\d+\.\d+\.\d+"', 'versionName = "' + $newName + '"')
$content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', 'versionCode = ' + $code)
Set-Content -LiteralPath $gradleFile -Value $content -NoNewline

Write-Host ('Bumped to versionName=' + $newName + ' versionCode=' + $code) -ForegroundColor Green
exit 0
