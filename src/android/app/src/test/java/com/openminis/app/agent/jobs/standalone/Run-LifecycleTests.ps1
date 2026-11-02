$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../../../../../..')).Path
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
$work = Join-Path ([IO.Path]::GetTempPath()) ('openminis-lifecycle-' + [guid]::NewGuid().ToString('N'))
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
        if ($entry.FullName -match '^com/openminis/app/ui/chat/ToolResultPersistenceKt(\$|\.)') { continue }
        $target = $outputJar.CreateEntry($entry.FullName)
        $sourceStream = $entry.Open()
        $targetStream = $target.Open()
        try { $sourceStream.CopyTo($targetStream) } finally { $sourceStream.Dispose(); $targetStream.Dispose() }
    }
} finally { $inputJar.Dispose(); $outputJar.Dispose() }
$dependencies = $dependencies | ForEach-Object { if ($_ -eq $appJar) { $dependencyJar } else { $_ } }
# Android clock/log adapters only; all broker, persistence, registry and clock policy code below is production source.
$clock = Join-Path $work 'SystemClock.java'
@'
package android.os;
public final class SystemClock { public static long elapsedRealtime() { return System.nanoTime() / 1000000L; } }
'@ | Set-Content -LiteralPath $clock -Encoding utf8
$logger = Join-Path $work 'AppLogger.kt'
@'
package com.openminis.app.logging
object AppLogger {
    fun info(category: String, message: String) {}
    fun warning(category: String, message: String) {}
}
'@ | Set-Content -LiteralPath $logger -Encoding utf8
$serviceAdapter = Join-Path $work 'OAuthWaitService.kt'
@'
package com.openminis.app.service
object OAuthWaitService { fun start(context: android.content.Context) { error("A standalone test must not start an Android service") } }
'@ | Set-Content -LiteralPath $serviceAdapter -Encoding utf8
& javac -d $out $clock
if ($LASTEXITCODE -ne 0) { throw 'Clock adapter compile failed' }
$main = Join-Path $repo 'src/android/app/src/main/java/com/openminis/app'
$tests = Join-Path $repo 'src/android/app/src/test/java/com/openminis/app'
$files = @(
    "$main/ui/chat/UserQuestion.kt", "$main/agent/jobs/UserQuestionTool.kt", "$main/agent/jobs/UserQuestionSettings.kt",
    "$main/data/model/AgentToolDefinition.kt",
    "$main/agent/jobs/UserQuestionRegistry.kt", "$main/agent/jobs/UserQuestionRecordStore.kt", "$main/agent/jobs/UserQuestionRecovery.kt",
    "$main/agent/jobs/AgentJobRegistry.kt", "$main/service/SessionConcurrencyManager.kt",
    "$main/service/AgentNotificationContent.kt", "$main/service/ScheduledSessionState.kt",
    "$main/service/OAuthWaitKeepAlive.kt",
    "$main/sandbox/SessionCommandRegistry.kt",
    "$tests/agent/jobs/UserQuestionToolTest.kt", "$tests/agent/jobs/UserQuestionRecoveryTest.kt", "$tests/agent/jobs/UserQuestionStable115Test.kt",
    "$main/ui/chat/ContextTrimPlanner.kt", "$main/ui/chat/QueuedCallbackReminder.kt", "$main/tools/BatchToolOrder.kt",
    "$main/ui/chat/StopLateGuard.kt", "$main/ui/chat/ThinkingRowSettle.kt", "$main/ui/chat/PromptAttachments.kt",
    "$main/ui/chat/FolderOfferFormat.kt", "$main/ui/chat/SessionTitleRules.kt", "$main/ui/chat/ToolDurationWire.kt",
    "$main/ui/chat/ToolResultPersistence.kt", "$tests/ui/chat/Stable115LoopCoreTest.kt",
    "$tests/agent/jobs/AgentJobRegistryRaceTest.kt", "$tests/service/SessionConcurrencyManagerTest.kt",
    "$tests/service/AgentNotificationContentTest.kt", "$tests/service/ScheduledSessionStateTest.kt",
    "$tests/service/OAuthWaitKeepAliveTest.kt", $logger, $serviceAdapter
    "$tests/sandbox/SessionCommandRegistryTest.kt"
)
$argsFile = Join-Path $work 'compile.args'
$argsLines = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ('"' + (((@($out) + $dependencies) -join ';').Replace('\', '/')) + '"'), '-d', ('"' + $out.Replace('\', '/') + '"'))
$argsLines += '-Xfriend-paths=' + $dependencyJar.Replace('\', '/')
$argsLines += $files | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($argsFile, $argsLines, [Text.UTF8Encoding]::new($false))
$compileLog = Join-Path $work 'compile.log'
& java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ('@' + $argsFile) *> $compileLog
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $compileLog -Tail 80; throw "Focused compile failed. Workspace: $work" }
$testLog = Join-Path $work 'tests.log'
& java -cp ((@($out) + $dependencies) -join ';') org.junit.runner.JUnitCore `
    com.openminis.app.agent.jobs.UserQuestionToolTest `
    com.openminis.app.agent.jobs.UserQuestionRecoveryTest `
    com.openminis.app.agent.jobs.UserQuestionStable115Test `
    com.openminis.app.ui.chat.Stable115LoopCoreTest `
    com.openminis.app.agent.jobs.AgentJobRegistryRaceTest `
    com.openminis.app.service.SessionConcurrencyManagerTest `
    com.openminis.app.service.AgentNotificationContentTest `
    com.openminis.app.service.ScheduledSessionStateTest `
    com.openminis.app.service.OAuthWaitKeepAliveTest `
    com.openminis.app.sandbox.SessionCommandRegistryTest *> $testLog
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $testLog -Tail 100; throw "Focused tests failed; workspace: $work" }
Get-Content -LiteralPath $testLog -Tail 5
Write-Output "Standalone checks passed. Android notification/UI runtime and unified build remain for the main agent. Workspace: $work"
