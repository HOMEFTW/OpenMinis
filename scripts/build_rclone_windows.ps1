param(
    [string]$GoPath,
    [string]$GomobilePath,
    [string]$NdkPath = "$env:ANDROID_HOME/ndk/27.0.12077973",
    [string]$BuildDirectory = 'D:/Code/.tools/openminis-rclone-build',
    [switch]$RunTests,
    [string]$WslDistribution = 'Ubuntu',
    [string]$WslGoPath = 'go'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (!$GoPath) { $GoPath = (Get-Command go -ErrorAction Stop).Source }
if (!$GomobilePath) { $GomobilePath = (Get-Command gomobile -ErrorAction Stop).Source }
foreach ($path in @($GoPath, $GomobilePath, "$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing build input: $path" }
}
$env:ANDROID_NDK_HOME = $NdkPath
$env:PATH = "$(Split-Path $GoPath);$(Split-Path $GomobilePath);$env:PATH"
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$aar = Join-Path $BuildDirectory 'rclone.aar'
Push-Location (Join-Path $repo 'deps/rclone-mobile')
try {
    if ($RunTests) {
        # Transparent networking uses Unix syscalls; run host regressions in Linux.
        $linuxRepo = (& wsl.exe -d $WslDistribution -- wslpath -a (Join-Path $repo 'deps/rclone-mobile')).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the repository path in WSL.' }
        & wsl.exe -d $WslDistribution --cd $linuxRepo -- env CGO_ENABLED=0 $WslGoPath test '-tags=ts_omit_logtail,ts_omit_portmapper' ./nethub ./tsnet ./rcloneroute ./netfs
        if ($LASTEXITCODE -ne 0) { throw 'Network Go tests failed.' }
    }
    $bindArgs = @('bind', '-target=android/arm64', '-androidapi=26', '-javapkg=com.openminis.rclone', '-ldflags=-s -w', '-tags=ts_omit_logtail,ts_omit_portmapper', '-o', $aar, './gomobile')
    & $GomobilePath @bindArgs
    if ($LASTEXITCODE -ne 0) { throw 'gomobile bind failed.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($aar)
    try {
        foreach ($required in @('classes.jar', 'jni/arm64-v8a/libgojni.so')) {
            if ($null -eq $archive.GetEntry($required)) { throw "Missing AAR entry: $required" }
        }
    } finally { $archive.Dispose() }
    $target = Join-Path $repo 'src/android/app/libs/rclone.aar'
    Copy-Item -LiteralPath $aar -Destination $target -Force
    Get-FileHash -LiteralPath $target -Algorithm SHA256
} finally { Pop-Location }
