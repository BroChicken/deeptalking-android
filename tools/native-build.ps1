# Build the native Android app.
# Usage:
#   powershell -File tools/native-build.ps1 [-Release] [-Clean] [-TimeoutMinutes 30]
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [switch]$Release,
  [switch]$Clean,
  [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android-lite'

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
}
exit 0
