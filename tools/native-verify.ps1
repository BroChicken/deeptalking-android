# Full native verification: compile every module, run all unit tests, report.
# Mirrors AGENTS.md section 5. This is the single command to run before commit.
# Usage: powershell -File tools/native-verify.ps1 [-TimeoutMinutes 20]
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

[CmdletBinding()]
param(
  [int]$TimeoutMinutes = 20
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android-lite'

$envScript = Join-Path $PSScriptRoot 'native/env.ps1'
$envOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $envScript -PrintPathOnly
$gradleExe = ($envOutput | Where-Object { $_ -like 'GRADLE=*' }) -replace '^GRADLE=', ''
$javaHome = ($envOutput | Where-Object { $_ -like 'JAVA_HOME=*' }) -replace '^JAVA_HOME=', ''
$sdkRoot = ($envOutput | Where-Object { $_ -like 'SDK=*' }) -replace '^SDK=', ''
if (-not $gradleExe -or -not (Test-Path -LiteralPath $gradleExe)) {
  Write-Host 'Could not resolve gradle via tools/native/env.ps1' -ForegroundColor Red
  exit 1
}
$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot

# [0/2] Static assertions (parity with the legacy verify-hub checks):
# version format/code and required docs must be present before building.
Write-Host '== [0/2] Static checks (version + docs) ==' -ForegroundColor Cyan
$staticBad = 0
$gradleKts = Get-Content -LiteralPath (Join-Path $android 'app\build.gradle.kts') -Raw
$vName = ([regex]::Match($gradleKts, 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
$vCodeMatch = [regex]::Match($gradleKts, 'versionCode\s*=\s*(\d+)')
$vCode = if ($vCodeMatch.Success) { [int]$vCodeMatch.Groups[1].Value } else { -1 }
Write-Host ('  versionName=' + $vName + ' versionCode=' + $vCode)
if ($vName -notmatch '^\d+\.\d+\.\d+$') { Write-Host '  FAIL: versionName must be X.Y.Z' -ForegroundColor Red; $staticBad++ }
if ($vCode -lt 1) { Write-Host '  FAIL: versionCode missing or not positive' -ForegroundColor Red; $staticBad++ }
foreach ($doc in @('docs\AGENT_ARCHITECTURE.md', 'docs\NATIVE_MODULES.md')) {
  if (-not (Test-Path -LiteralPath (Join-Path $root $doc))) { Write-Host ('  FAIL: missing ' + $doc) -ForegroundColor Red; $staticBad++ }
}
if ($staticBad -ne 0) { Write-Host 'VERIFY FAILED (static checks)' -ForegroundColor Red; exit 1 }
Write-Host '  static checks OK' -ForegroundColor Green

$testTasks = @(
  ':engine:ondevice:test',
  ':engine:cosyvoice:testDebugUnitTest',
  ':domain:agent:test',
  ':core:network:test',
  ':domain:memory:test',
  ':core:data:testDebugUnitTest',
  ':feature:richtext:testDebugUnitTest'
)

function Invoke-Gradle {
  param([string[]]$Tasks, [string]$Label)
  $log = Join-Path $env:TEMP ('native-' + [Guid]::NewGuid().ToString('N') + '.log')
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

$compileExit = Invoke-Gradle -Tasks @(':app:assembleDebug') -Label '[1/2] Compile all modules (assembleDebug)'
$testExit = Invoke-Gradle -Tasks $testTasks -Label '[2/2] Unit tests'

Write-Host ''
Write-Host '== Summary ==' -ForegroundColor Cyan
$total = 0; $bad = 0
foreach ($m in @('engine\ondevice','engine\cosyvoice','domain\agent','core\network','domain\memory','core\data','feature\richtext')) {
  $dir = Join-Path $android ($m + '\build\test-results')
  if (-not (Test-Path -LiteralPath $dir)) { continue }
  $modTotal = 0; $modFail = 0
  Get-ChildItem -LiteralPath $dir -Recurse -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$x = Get-Content -LiteralPath $_.FullName
    $modTotal += [int]$x.testsuite.tests
    $modFail += ([int]$x.testsuite.failures + [int]$x.testsuite.errors)
  }
  $total += $modTotal; $bad += $modFail
  Write-Host ('  ' + $m.PadRight(22) + ' tests=' + $modTotal + ' fail=' + $modFail)
}
Write-Host ('  TOTAL=' + $total + ' FAILURES=' + $bad + ' compileExit=' + $compileExit + ' testExit=' + $testExit)

if ($compileExit -ne 0 -or $testExit -ne 0 -or $bad -ne 0) {
  Write-Host 'VERIFY FAILED' -ForegroundColor Red
  exit 1
}
Write-Host 'VERIFY OK' -ForegroundColor Green
exit 0
