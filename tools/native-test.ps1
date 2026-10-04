# Run the native Android unit-test suite (the authoritative gate).
# Usage: powershell -File tools/native-test.ps1 [-TimeoutMinutes 15]
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [int]$TimeoutMinutes = 15
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

$modules = @(
  ':engine:ondevice:test',
  ':domain:agent:test',
  ':domain:memory:test',
  ':core:data:testDebugUnitTest',
  ':feature:richtext:testDebugUnitTest'
)

$log = Join-Path $env:TEMP ('native-test-' + [Guid]::NewGuid().ToString('N') + '.log')
Write-Host ('Running ' + $modules.Count + ' native test task(s) ...') -ForegroundColor Cyan
$args = @($modules) + @('--console=plain', '--no-daemon')
$proc = Start-Process -FilePath $gradleExe -ArgumentList $args -WorkingDirectory $android `
  -RedirectStandardOutput $log -RedirectStandardError ($log + '.err') -PassThru -NoNewWindow
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (-not $proc.HasExited) {
  if ((Get-Date) -gt $deadline) {
    try { $proc.Kill() } catch {}
    Write-Host ('TIMEOUT after ' + $TimeoutMinutes + ' minutes') -ForegroundColor Red
    exit 124
  }
  Start-Sleep -Seconds 3
}
$proc.Refresh()
$exit = [int]$proc.ExitCode
if ($exit -ne 0) {
  Get-Content -LiteralPath $log -Tail 25 -ErrorAction SilentlyContinue | Write-Host
  Write-Host ('gradle test tasks failed (exit ' + $exit + ')') -ForegroundColor Red
  exit $exit
}

$total = 0; $fail = 0
foreach ($m in @('engine\ondevice','domain\agent','domain\memory','core\data','feature\richtext')) {
  $dir = Join-Path $android ($m + '\build\test-results')
  if (-not (Test-Path -LiteralPath $dir)) { continue }
  Get-ChildItem -LiteralPath $dir -Recurse -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$x = Get-Content -LiteralPath $_.FullName
    $total += [int]$x.testsuite.tests
    $fail += ([int]$x.testsuite.failures + [int]$x.testsuite.errors)
  }
}
if ($fail -gt 0) {
  Write-Host ('NATIVE TESTS FAILED: ' + $fail + ' of ' + $total) -ForegroundColor Red
  exit 1
}
Write-Host ('NATIVE TESTS OK: ' + $total + ' passed, 0 failed') -ForegroundColor Green
exit 0
