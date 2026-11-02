$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../../../../..')).Path
if (-not (Test-Path (Join-Path $repo 'src/android/app/src/main'))) { throw 'Cannot locate OpenMinis checkout' }
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Artifact([string]$group, [string]$name, [string]$version) {
    $folder = Join-Path $cache "$group/$name/$version"
    $jar = Get-ChildItem -LiteralPath $folder -Recurse -Filter '*.jar' | Select-Object -First 1
    if ($null -eq $jar) { throw "Missing cached artifact $group/$name/$version" }
    $jar.FullName
}
$compiler = @(
    (Artifact 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.1.0'),
    (Artifact 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.1.0'),
    (Artifact 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.1.0'),
    (Artifact 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (Artifact 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Artifact 'org.jetbrains' 'annotations' '13.0'),
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4')
)
$dependencies = @(
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-serialization-core-jvm' '1.7.3'),
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-serialization-json-jvm' '1.7.3'),
    (Artifact 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.1.0'),
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.9.0'),
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-coroutines-test-jvm' '1.9.0'),
    (Artifact 'org.json' 'json' '20231013'),
    (Artifact 'junit' 'junit' '4.13.2'),
    (Artifact 'org.hamcrest' 'hamcrest-core' '1.3'),
    (Artifact 'org.jetbrains' 'annotations' '13.0'),
    (Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'),
    (Join-Path $repo 'src/android/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'),
    (Join-Path $repo 'src/android/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar')
)
$annotation = Get-ChildItem -LiteralPath (Join-Path $cache 'androidx.annotation/annotation-jvm') -Recurse -Filter '*.jar' | Select-Object -First 1
if ($annotation) { $dependencies += $annotation.FullName }
$work = Join-Path ([IO.Path]::GetTempPath()) ('openminis-backup-' + [guid]::NewGuid().ToString('N'))
$out = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Snapshot cached dependency bytecode without the notification functions compiled
# below. Otherwise Kotlin sees both the old and new default-argument signatures.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$appJar = Join-Path $repo 'src/android/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$dependencyJar = Join-Path $work 'app-dependencies.jar'
$inputJar = [IO.Compression.ZipFile]::OpenRead($appJar)
$outputJar = [IO.Compression.ZipFile]::Open($dependencyJar, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $inputJar.Entries) {
        if ($entry.FullName -match '^com/openminis/app/service/(AgentNotificationContentKt|AgentPhase)(\$|\.)') { continue }
        $target = $outputJar.CreateEntry($entry.FullName)
        $sourceStream = $entry.Open()
        $targetStream = $target.Open()
        try { $sourceStream.CopyTo($targetStream) } finally { $sourceStream.Dispose(); $targetStream.Dispose() }
    }
} finally { $inputJar.Dispose(); $outputJar.Dispose() }
$dependencies = $dependencies | ForEach-Object { if ($_ -eq $appJar) { $dependencyJar } else { $_ } }
# Platform logging only; the backup policies/history serializers below are production sources.
$logger = Join-Path $work 'AppLogger.kt'
@'
package com.openminis.app.logging
object AppLogger {
    fun info(category: String, message: String) {}
    fun warning(category: String, message: String) {}
    fun error(category: String, message: String) {}
}
'@ | Set-Content -LiteralPath $logger -Encoding utf8
$main = Join-Path $repo 'src/android/app/src/main/java/com/openminis/app'
$tests = Join-Path $repo 'src/android/app/src/test/java/com/openminis/app'
$files = @(
    "$main/backup/BackupFormat.kt", "$main/backup/BackupCredentialRestore.kt",
    "$main/backup/BackupProviderCredentials.kt", "$main/backup/BackupProviderWire.kt",
    "$main/backup/BackupProviderMerge.kt",
    "$main/backup/BackupLogRules.kt", "$main/backup/BackupHistory.kt", "$main/backup/BackupHistoryStorage.kt",
    "$tests/backup/BackupProviderCredentialsTest.kt", "$tests/backup/BackupProviderWireTest.kt",
    "$tests/backup/BackupHistoryLifecycleTest.kt", "$tests/backup/BackupHistoryRecordTest.kt",
    "$tests/backup/BackupHistoryStorageTest.kt", $logger
    "$tests/backup/BackupProviderMergeTest.kt"
)
$plugin = Artifact 'org.jetbrains.kotlin' 'kotlin-serialization-compiler-plugin-embeddable' '2.1.0'
$argsFile = Join-Path $work 'compile.args'
$argsLines = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ('"' + (((@($out) + $dependencies) -join ';').Replace('\', '/')) + '"'), '-d', ('"' + $out.Replace('\', '/') + '"'))
$argsLines += '-Xplugin=' + $plugin.Replace('\', '/')
$argsLines += '-Xfriend-paths=' + $dependencyJar.Replace('\', '/')
$argsLines += $files | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($argsFile, $argsLines, [Text.UTF8Encoding]::new($false))
$compileLog = Join-Path $work 'compile.log'
& java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ('@' + $argsFile) *> $compileLog
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $compileLog -Tail 80; throw "Focused compile failed. Workspace: $work" }
$testLog = Join-Path $work 'tests.log'
& java -cp ((@($out) + $dependencies) -join ';') org.junit.runner.JUnitCore `
    com.openminis.app.backup.BackupProviderCredentialsTest `
    com.openminis.app.backup.BackupProviderWireTest `
    com.openminis.app.backup.BackupHistoryLifecycleTest `
    com.openminis.app.backup.BackupHistoryRecordTest `
    com.openminis.app.backup.BackupHistoryStorageTest `
    com.openminis.app.backup.BackupProviderMergeTest *> $testLog
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $testLog -Tail 100; throw "Focused tests failed; workspace: $work" }
Get-Content -LiteralPath $testLog -Tail 5
Write-Output "Standalone checks passed. Android repository/SAF/remote runtime and unified build remain for the main agent. Workspace: $work"
