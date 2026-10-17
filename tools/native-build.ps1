# Build the native Android app.
# Usage:
#   powershell -File tools/native-build.ps1 [-Release] [-Clean] [-TimeoutMinutes 30]
#   powershell -File tools/native-build.ps1 -Release -Publish
#
# The signed release APK is produced HERE, on this machine: the CI workflow only
# compiles and runs the unit tests (it never builds or publishes a release, so it
# needs no keystore and can never overwrite a published APK).
#
# -Publish (implies -Release) then
#   1. archives the APK as apk/DeepTalking-lite-v<versionName>.apk (apk/ is ignored by git), and
#   2. creates - or re-uploads to - the GitHub Release tagged v<versionName> with that exact
#      file, so the Release always carries the locally verified build.
#
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [switch]$Release,
  # Upload the signed APK to the GitHub Release for the current versionName.
  [switch]$Publish,
  [switch]$Clean,
  [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android-lite'

if ($Publish) { $Release = $true }

$envOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'native/env.ps1') -PrintPathOnly
$gradleExe = ($envOutput | Where-Object { $_ -like 'GRADLE=*' }) -replace '^GRADLE=', ''
$env:JAVA_HOME = ($envOutput | Where-Object { $_ -like 'JAVA_HOME=*' }) -replace '^JAVA_HOME=', ''
$env:ANDROID_SDK_ROOT = ($envOutput | Where-Object { $_ -like 'SDK=*' }) -replace '^SDK=', ''
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
if (-not $gradleExe -or -not (Test-Path -LiteralPath $gradleExe)) {
  Write-Host 'Could not resolve gradle via tools/native/env.ps1' -ForegroundColor Red
  exit 1
}

function Invoke-Gradle {
  param([string[]]$Tasks, [string]$Label)
  $log = Join-Path $env:TEMP ('native-build-' + [Guid]::NewGuid().ToString('N') + '.log')
  Write-Host ('== ' + $Label + ' ==') -ForegroundColor Cyan
  $args = @($Tasks) + @('--console=plain', '--no-daemon')
  $proc = Start-Process -FilePath $gradleExe -ArgumentList $args -WorkingDirectory $android `
    -RedirectStandardOutput $log -RedirectStandardError ($log + '.err') -PassThru -NoNewWindow
  $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
  while (-not $proc.HasExited) {
    if ((Get-Date) -gt $deadline) {
      try { $proc.Kill() } catch {}
      Write-Host ('  TIMEOUT after ' + $TimeoutMinutes + ' minutes') -ForegroundColor Red
      Get-Content -LiteralPath $log -Tail 20 -ErrorAction SilentlyContinue | Write-Host
      return 124
    }
    Start-Sleep -Seconds 3
  }
  Get-Content -LiteralPath $log -Tail 8 -ErrorAction SilentlyContinue | Write-Host
  # Start-Process on gradle.bat yields an empty ExitCode on Windows PowerShell,
  # so derive success from Gradle's terminal status line instead.
  $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
  if ($text -match 'BUILD SUCCESSFUL') { return 0 }
  return 1
}

if ($Clean) {
  $c = Invoke-Gradle -Tasks @('clean') -Label 'clean'
  if ($c -ne 0) { exit $c }
}

$task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
$code = Invoke-Gradle -Tasks @($task) -Label ('Building ' + $task)
if ($code -ne 0) { exit $code }

$kind = if ($Release) { 'release' } else { 'debug' }
$apkDir = Join-Path $android ('app\build\outputs\apk\' + $kind)
$apk = Get-ChildItem -LiteralPath $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
  $mb = [math]::Round($apk.Length / 1MB, 1)
  Write-Host ('BUILD OK: ' + $apk.FullName + ' (' + $mb + ' MB)') -ForegroundColor Green
} else {
  Write-Host 'BUILD OK (no APK found in outputs)' -ForegroundColor Green
  if ($Publish) {
    Write-Host 'Nothing to publish: no APK in ' + $apkDir -ForegroundColor Red
    exit 1
  }
}

if ($Publish) {
  $gradleKts = Get-Content -LiteralPath (Join-Path $android 'app\build.gradle.kts') -Raw
  $version = ([regex]::Match($gradleKts, 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
  if ($version -notmatch '^\d+\.\d+\.\d+$') {
    Write-Host 'Could not read an X.Y.Z versionName from app/build.gradle.kts' -ForegroundColor Red
    exit 1
  }
  if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host 'gh (GitHub CLI) not found. Install it and run: gh auth login' -ForegroundColor Red
    exit 1
  }

  $tag = 'v' + $version
  $localApkDir = Join-Path $root 'apk'
  if (-not (Test-Path -LiteralPath $localApkDir)) { New-Item -ItemType Directory -Path $localApkDir | Out-Null }
  $dest = Join-Path $localApkDir ('DeepTalking-lite-v' + $version + '.apk')
  Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force
  Write-Host ('Archived locally: ' + $dest) -ForegroundColor Cyan

  $exists = $false
  try {
    $view = gh release view $tag --json tagName 2>$null
    $exists = ($LASTEXITCODE -eq 0 -and $view)
  } catch {
    $exists = $false
  }

  if ($exists) {
    Write-Host ('Release ' + $tag + ' exists - uploading this local APK (--clobber) ...') -ForegroundColor Cyan
    gh release upload $tag $dest --clobber
  } else {
    Write-Host ('Creating Release ' + $tag + ' with this local APK ...') -ForegroundColor Cyan
    gh release create $tag $dest --title $tag `
      --notes ('DeepTalking Lite ' + $tag + ' - signed release APK, built and verified locally.') --latest
  }
  if ($LASTEXITCODE -ne 0) {
    Write-Host ('PUBLISH FAILED (gh exit code ' + $LASTEXITCODE + ').') -ForegroundColor Red
    exit 1
  }
  $url = gh release view $tag --json url --jq '.url' 2>$null
  Write-Host ('PUBLISH OK: ' + $url) -ForegroundColor Green
}

exit 0
