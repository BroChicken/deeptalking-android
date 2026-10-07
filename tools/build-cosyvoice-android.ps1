# Builds the arm64 libcosyvoice.so stack (with the ONNX voice frontend) used by
# :engine:cosyvoice and copies the shared libraries into the module's jniLibs.
#
# Reproduces the community cosyvoice.cpp (GGML) + ONNX Runtime build. Requires a
# Windows host; downloads its own NDK/SIMDe/ONNX Runtime on first run.
#
# GGML_CPU_ARM_ARCH enables ARM dot-product + FP16 vector arithmetic kernels;
# without it ggml cross-compiles to the armv8-a baseline (no sdot / no fmla.8h)
# and inference is several times slower. See docs/NATIVE_MODULES.md.
#
#   powershell -File tools/build-cosyvoice-android.ps1
#
# Output: android-lite/engine/cosyvoice/src/main/jniLibs/arm64-v8a/*.so
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it.

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:TEMP 'cosyvoice-android-build'
New-Item -ItemType Directory -Force $work | Out-Null

$NDK_VERSION = '27.0.12077973'
$NDK_URL = 'https://mirrors.cloud.tencent.com/AndroidSDK/android-ndk-r27-windows.zip'
$ORT_VERSION = '1.25.1'
$ORT_AAR = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"
$ORT_HDR = "https://github.com/microsoft/onnxruntime/releases/download/v$ORT_VERSION/onnxruntime-linux-x64-$ORT_VERSION.tgz"

$ndk = Join-Path $work "android-ndk-r27"
if (-not (Test-Path (Join-Path $ndk 'source.properties'))) {
  Write-Host 'Downloading NDK...'
  $zip = Join-Path $work 'ndk.zip'
  if (-not (Test-Path $zip)) { curl.exe -L --retry 3 -o $zip $NDK_URL }
  New-Item -ItemType Directory -Force (Join-Path $work 'ndk') | Out-Null
  tar -xf $zip -C (Join-Path $work 'ndk')
  $ndk = Join-Path (Join-Path $work 'ndk') 'android-ndk-r27'
}

$simde = Join-Path $work 'simde'
if (-not (Test-Path (Join-Path $simde 'simde\x86\sse4.2.h'))) {
  git clone --depth 1 https://github.com/simd-everywhere/simde.git $simde
}

$ortPre = Join-Path $work 'ort\prebuilt'
if (-not (Test-Path (Join-Path $ortPre 'include\onnxruntime_c_api.h'))) {
  New-Item -ItemType Directory -Force (Join-Path $work 'ort\aar') | Out-Null
  $aar = Join-Path $work 'ort.aar'; $tgz = Join-Path $work 'ort.tgz'
  curl.exe -L --ssl-no-revoke --retry 2 -o $aar $ORT_AAR
  curl.exe -L --ssl-no-revoke --retry 2 -o $tgz $ORT_HDR
  tar -xf $aar -C (Join-Path $work 'ort\aar')
  New-Item -ItemType Directory -Force (Join-Path $work 'ort\pkg') | Out-Null
  tar -xzf $tgz -C (Join-Path $work 'ort\pkg')
  $inc = (Get-ChildItem (Join-Path $work 'ort\pkg') -Recurse -Filter onnxruntime_cxx_api.h | Select-Object -First 1).DirectoryName
  New-Item -ItemType Directory -Force (Join-Path $ortPre 'lib') | Out-Null
  Copy-Item -Recurse -Force $inc (Join-Path $ortPre 'include')
  Copy-Item -Force (Join-Path $work 'ort\aar\jni\arm64-v8a\libonnxruntime.so') (Join-Path $ortPre 'lib\libonnxruntime.so')
}

$src = Join-Path $work 'cosyvoice.cpp'
if (-not (Test-Path (Join-Path $src 'CMakeLists.txt'))) {
  git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/Lourdle/cosyvoice.cpp.git $src
}

# Performance patch: the app loads the model through the high-level API
# (context params v1), so the authors' streaming optimizations live in the
# defaults. Turn on the DiT KV cache (2 fixed slots, "balanced speed/memory")
# and the dedicated inference buffer policy (recommended for streaming).
function Patch-File([string]$path, [string]$from, [string]$to) {
  $txt = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
  if (-not $txt.Contains($from)) { return $false }
  [System.IO.File]::WriteAllText($path, $txt.Replace($from, $to), (New-Object System.Text.UTF8Encoding($false)))
  return $true
}
$p1 = Patch-File (Join-Path $src 'src\cosyvoice-model.cpp') 'COSYVOICE_INFERENCE_BUFFER_POLICY_BALANCED' 'COSYVOICE_INFERENCE_BUFFER_POLICY_DEDICATED'
$p2 = Patch-File (Join-Path $src 'src\cosyvoice.cpp') 'params_v3.dit_kv_fixed_slots = 0;' 'params_v3.dit_kv_fixed_slots = 2;'
if (-not ($p1 -and $p2)) { Write-Warning "cosyvoice perf patch matched buffer=$p1 dit=$p2 (upstream source changed?)" }

$build = Join-Path $work 'build-android-fe'
cmake -S $src -B $build -G Ninja `
  "-DCMAKE_TOOLCHAIN_FILE=$(Join-Path $ndk 'build\cmake\android.toolchain.cmake')" `
  -DCMAKE_BUILD_TYPE=Release -DANDROID_PLATFORM=26 -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_shared `
  "-DSIMDE_INCLUDE_DIR=$simde" "-DORT_PREBUILT_DIR=$ortPre" -DCOSYVOICE_NO_ICU=ON -DBUILD_SHARED_LIBS=ON `
  "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16" `
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build $build --target cosyvoice

$dst = Join-Path $repoRoot 'android-lite\engine\cosyvoice\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $dst | Out-Null
$lib = Join-Path $build 'lib'
Copy-Item -Force (Join-Path $lib 'libcosyvoice.so'), (Join-Path $lib 'libggml.so'), (Join-Path $lib 'libggml-cpu.so'), (Join-Path $lib 'libggml-base.so') $dst
Copy-Item -Force (Join-Path $ortPre 'lib\libonnxruntime.so') $dst
$sysroot = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'
Copy-Item -Force $sysroot $dst
# ggml links OpenMP; ship the NDK's aarch64 runtime or dlopen fails.
$libomp = Get-ChildItem (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\lib\clang') -Recurse -Filter libomp.so |
  Where-Object { $_.FullName -match 'linux\\aarch64\\libomp.so$' } | Select-Object -First 1
if ($libomp) { Copy-Item -Force $libomp.FullName $dst } else { Write-Warning 'aarch64 libomp.so not found in NDK' }
Get-ChildItem $dst | Select-Object Name, Length
Write-Host "Copied native libraries to $dst"
