param(
    [string]$NdkRoot = $env:ANDROID_NDK_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $NdkRoot) { throw 'Set ANDROID_NDK_HOME or pass -NdkRoot.' }
$bin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$compiler = Join-Path $bin 'aarch64-linux-android35-clang++.cmd'
$strip = Join-Path $bin 'llvm-strip.exe'
$source = Join-Path $PSScriptRoot 'headtracker_uhid.cpp'
$output = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..\app\src\main\assets\headtracker\arm64-v8a')) 'headtracker_uhid'
& $compiler -std=c++20 -O2 -Wall -Wextra -Werror -static-libstdc++ $source -o $output
if ($LASTEXITCODE -ne 0) { throw "Compiler exited with $LASTEXITCODE" }
& $strip --strip-unneeded $output
if ($LASTEXITCODE -ne 0) { throw "Strip exited with $LASTEXITCODE" }
Get-FileHash $output -Algorithm SHA256
