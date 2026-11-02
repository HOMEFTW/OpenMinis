param(
    [string]$GradlePath,
    [switch]$RunModelTests,
    [switch]$RunAllTests,
    [switch]$Clean,
    [switch]$BuildAndroidTests,
    [string[]]$TestFilter = @()
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$androidRoot = Join-Path $repoRoot 'src/android'
$forgeUi = Join-Path $androidRoot 'forge-ui'
$forgeNode = Get-Command node -ErrorAction SilentlyContinue
if ($forgeNode -and (Test-Path -LiteralPath (Join-Path $forgeUi 'node_modules/esbuild'))) {
    & $forgeNode.Source (Join-Path $repoRoot 'scripts/build_forge_ui.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Forge UI build failed.' }
} elseif (!(Test-Path -LiteralPath (Join-Path $androidRoot 'app/src/main/assets/forge/ui/app.js'))) {
    throw 'Forge UI is missing. Run npm ci in src/android/forge-ui, then use this build script again.'
}
$requiredFiles = @(
    'app/libs/rclone.aar',
    'app/src/main/assets/alpine-minirootfs.tar.gz',
    'app/src/main/assets/proot-aarch64',
    'app/src/main/jniLibs/arm64-v8a/libproot.so',
    'app/src/main/jniLibs/arm64-v8a/libproot-loader.so',
    'app/src/main/jniLibs/arm64-v8a/libproot-loader32.so'
)
foreach ($relativePath in $requiredFiles) {
    $dependency = Join-Path $androidRoot $relativePath
    if (!(Test-Path -LiteralPath $dependency -PathType Leaf)) {
        throw "Missing native dependency: $dependency. See docs/android-windows-build.md."
    }
}

if (!$GradlePath) {
    $installed = Get-Command gradle -ErrorAction SilentlyContinue
    if ($installed) {
        $GradlePath = $installed.Source
    } else {
        # Reuse the Gradle version validated for this checkout when already cached.
        $cached = Get-ChildItem "$env:USERPROFILE/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle.bat" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($cached) { $GradlePath = $cached.FullName }
    }
}

$prootPath = Join-Path $androidRoot 'app/src/main/jniLibs/arm64-v8a/libproot.so'
$prootStrings = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($prootPath))
if (!$prootStrings.Contains('--fake-netlink') -or !$prootStrings.Contains('--xnet')) {
    throw 'PRoot is outdated. Run scripts/build_proot_windows.ps1 before building the APK.'
}

$gradleArgs = @('--no-daemon')
if ($Clean) { $gradleArgs += 'clean' }
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $gitRoot = Split-Path (Split-Path $git.Source -Parent) -Parent
    $gitBash = Join-Path $gitRoot 'bin/bash.exe'
    if (Test-Path -LiteralPath $gitBash) { $gradleArgs += "-PminisBashPath=$gitBash" }
}
if ($TestFilter.Count -gt 0) {
    $gradleArgs += ':app:testDebugUnitTest'
    foreach ($filter in $TestFilter) { $gradleArgs += @('--tests', $filter) }
} elseif ($RunAllTests) {
    $gradleArgs += ':app:testDebugUnitTest'
} elseif ($RunModelTests) {
    $gradleArgs += @(':app:testDebugUnitTest', '--tests', 'com.openminis.app.provider.AstraImage25Test', '--tests', 'com.openminis.app.sandbox.offload.ModelUseImageResultTest')
}
$gradleArgs += ':app:assembleDebug'
if ($BuildAndroidTests) { $gradleArgs += ':app:assembleDebugAndroidTest' }

Push-Location $androidRoot
try {
    if ($GradlePath) {
        & $GradlePath @gradleArgs
    } else {
        # The repository has gradlew but no gradlew.bat; invoke its wrapper directly.
        & java -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @gradleArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
    $apk = Join-Path $androidRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (!(Test-Path -LiteralPath $apk)) { throw "Gradle completed without expected APK: $apk" }
    Write-Output "APK: $apk"
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    Pop-Location
}
