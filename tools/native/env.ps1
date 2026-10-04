# Shared environment for the native Android build/test scripts.
# Resolves a JDK 17 and the Android SDK, then puts `gradle` on PATH.
# Dot-source this:      . tools/native/env.ps1
# Machine-readable:     powershell -File tools/native/env.ps1 -PrintPathOnly
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.

param(
  [switch]$PrintPathOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidRoot = Join-Path $repoRoot 'android-lite'

function Test-Jdk17 {
  param([string]$Path)
  if (-not $Path) { return $false }
  $javac = Join-Path $Path 'bin\javac.exe'
  if (-not (Test-Path -LiteralPath $javac)) { return $false }
  $ver = & $javac -version 2>&1
  return ($ver -match '17\.')
}

function Resolve-Jdk17 {
  if (Test-Jdk17 $env:JAVA_HOME) { return $env:JAVA_HOME }
  if ($env:DEVTOOLS_JDK17 -and (Test-Jdk17 $env:DEVTOOLS_JDK17)) { return $env:DEVTOOLS_JDK17 }
  $candidates = @()
  if ($env:DEVTOOLS_JDK17) { $candidates += $env:DEVTOOLS_JDK17 }
  $candidates += @(
    'D:\devtools\temurin17\jdk-17.0.20.1+1',
    'C:\Program Files\Eclipse Adoptium',
    'C:\Program Files\Java'
  )
  foreach ($c in $candidates) {
    if (-not $c) { continue }
    if (Test-Jdk17 $c) { return $c }
    if (Test-Path -LiteralPath $c) {
      $sub = Get-ChildItem -LiteralPath $c -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue |
        Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe')) -and (Test-Jdk17 $_.FullName) } |
        Select-Object -First 1
      if ($sub) { return $sub.FullName }
    }
  }
  throw 'JDK 17 not found. Set JAVA_HOME or DEVTOOLS_JDK17 to a JDK 17 install.'
}

function Resolve-AndroidSdk {
  if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
  if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
  $local = Join-Path $androidRoot 'local.properties'
  if (Test-Path -LiteralPath $local) {
    $line = Select-String -LiteralPath $local -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
    if ($line) {
      $sdk = $line.Matches[0].Groups[1].Value.Trim().Replace('\\', '\').Replace('\:', ':')
      if (Test-Path -LiteralPath $sdk) { return $sdk }
    }
  }
  foreach ($c in @($env:DEVTOOLS_SDK, 'D:\Android', (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
    if ($c -and (Test-Path -LiteralPath $c)) { return $c }
  }
  throw 'Android SDK not found. Set ANDROID_SDK_ROOT or android-lite/local.properties sdk.dir.'
}

function Resolve-Gradle {
  $cmd = Get-Command gradle -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $gradleHome = $env:DEVTOOLS_GRADLE
  if (-not $gradleHome) {
    foreach ($base in @('D:\devtools', 'C:\Gradle', (Join-Path $env:USERPROFILE '.gradle'))) {
      if (-not (Test-Path -LiteralPath $base)) { continue }
      $found = Get-ChildItem -LiteralPath $base -Recurse -Depth 2 -Directory -Filter 'gradle-*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\gradle.bat') } |
        Sort-Object Name -Descending | Select-Object -First 1
      if ($found) { $gradleHome = $found.FullName; break }
    }
  }
  if ($gradleHome) {
    $bat = Join-Path $gradleHome 'bin\gradle.bat'
    if (Test-Path -LiteralPath $bat) { return $bat }
  }
  throw 'Gradle not found. Install gradle on PATH or set DEVTOOLS_GRADLE to a gradle distribution dir.'
}

$resolvedJava = Resolve-Jdk17
$resolvedSdk = Resolve-AndroidSdk
$resolvedGradle = Resolve-Gradle

# Machine-readable mode: print the resolved paths and exit (no side effects).
if ($PrintPathOnly -or $args -contains '-PrintPathOnly') {
  Write-Output ('JAVA_HOME=' + $resolvedJava)
  Write-Output ('SDK=' + $resolvedSdk)
  Write-Output ('GRADLE=' + $resolvedGradle)
  exit 0
}

$env:JAVA_HOME = $resolvedJava
$env:ANDROID_SDK_ROOT = $resolvedSdk
$env:ANDROID_HOME = $resolvedSdk
$global:GradleExe = $resolvedGradle

Write-Host ('env: JAVA_HOME=' + $env:JAVA_HOME) -ForegroundColor DarkGray
Write-Host ('env: ANDROID_SDK_ROOT=' + $env:ANDROID_SDK_ROOT) -ForegroundColor DarkGray
Write-Host ('env: gradle=' + $global:GradleExe) -ForegroundColor DarkGray
