$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../../../../..')).Path
if (-not (Test-Path (Join-Path $repo 'src/android/app/src/main'))) { throw 'Cannot locate OpenMinis checkout' }
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Artifact([string]$group, [string]$name, [string]$version) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cache "$group/$name/$version") -Recurse -Filter '*.jar' | Select-Object -First 1
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
$appJar = Join-Path $repo 'src/android/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$dependencies = @(
    (Artifact 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.1.0'),
    (Artifact 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.9.0'),
    (Artifact 'junit' 'junit' '4.13.2'),
    (Artifact 'org.hamcrest' 'hamcrest-core' '1.3'),
    (Artifact 'org.jetbrains' 'annotations' '13.0'),
    (Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'), $appJar
)
$groups = @('org.robolectric', 'org.ow2.asm', 'com.google.guava', 'com.google.errorprone', 'com.google.j2objc',
    'com.google.code.findbugs', 'javax.inject', 'javax.annotation', 'org.conscrypt', 'org.bouncycastle',
    'org.yaml', 'com.google.testparameterinjector', 'com.almworks.sqlite4java', 'com.google.code.gson')
foreach ($group in $groups) {
    $dependencies += Get-ChildItem -LiteralPath (Join-Path $cache $group) -Recurse -Filter '*.jar' |
        Where-Object { $_.FullName -notmatch 'android-all' -and $_.Name -notmatch '-sources|-javadoc' } | ForEach-Object { $_.FullName }
}
$transforms = Join-Path $env:USERPROFILE '.gradle/caches/8.13/transforms'
$dependencies += Get-ChildItem -LiteralPath $cache -Directory -Filter 'androidx.*' |
    Get-ChildItem -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '-sources|-javadoc' } | ForEach-Object { $_.FullName }
$dependencies += Get-ChildItem -LiteralPath $transforms -Recurse -Filter '*-runtime.jar' | ForEach-Object { $_.FullName }
$work = Join-Path ([IO.Path]::GetTempPath()) ('openminis-stable-ui-' + [guid]::NewGuid().ToString('N'))
$out = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Only these resource ids are needed by the platform adapters compiled here.
# UI/Android resource integration is verified by the owner's unified build.
$resources = Join-Path $work 'R.kt'
@'
package com.openminis.app
object R { object string {
    const val skill_source_url_copied = 0x7f17ff01
    const val build34_picker_unavailable = 0x7f17ff02
} }
'@ | Set-Content -LiteralPath $resources -Encoding utf8
$main = Join-Path $repo 'src/android/app/src/main/java/com/openminis/app'
$tests = Join-Path $repo 'src/android/app/src/test/java/com/openminis/app'
$files = @(
    "$main/util/SafeActivityLaunch.kt", "$main/ui/sandbox/PreviewLinkResolver.kt",
    "$main/ui/sandbox/MarkdownPreviewLinks.kt", "$main/ui/settings/SkillSourceUrl.kt", "$main/ui/sessions/SessionDuplicateTitle.kt",
    "$tests/ProductionSources.kt", "$tests/ui/components/SafePickerLaunchGuardTest.kt",
    "$tests/ui/components/SafePickerLaunchRobolectricTest.kt", "$tests/ui/sandbox/PreviewLinkResolverTest.kt",
    "$tests/ui/sandbox/MarkdownPreviewLinksTest.kt", "$tests/ui/settings/SkillSourceUrlTest.kt",
    "$tests/ui/sessions/SessionDuplicateTitleTest.kt", $resources
)
$argsFile = Join-Path $work 'compile.args'
$argsLines = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ('"' + (($dependencies -join ';').Replace('\', '/')) + '"'),
    '-d', ('"' + $out.Replace('\', '/') + '"'), ('-Xfriend-paths=' + $appJar.Replace('\', '/')))
$argsLines += $files | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($argsFile, $argsLines, [Text.UTF8Encoding]::new($false))
$compileLog = Join-Path $work 'compile.log'
& java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ('@' + $argsFile) *> $compileLog
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $compileLog -Tail 70; throw "Focused compile failed: $work" }
$runtimeArgs = Join-Path $work 'runtime.args'
[IO.File]::WriteAllLines($runtimeArgs, @('-Drobolectric.offline=true',
    ('-Drobolectric.dependency.dir=' + (Join-Path $env:USERPROFILE '.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7').Replace('\', '/')),
    '-cp', ('"' + ((@($out) + $dependencies) -join ';').Replace('\', '/') + '"'),
    'org.junit.runner.JUnitCore',
    'com.openminis.app.ui.components.SafePickerLaunchGuardTest',
    'com.openminis.app.ui.components.SafePickerLaunchRobolectricTest',
    'com.openminis.app.ui.sandbox.PreviewLinkResolverTest',
    'com.openminis.app.ui.sandbox.MarkdownPreviewLinksTest',
    'com.openminis.app.ui.settings.SkillSourceUrlTest',
    'com.openminis.app.ui.sessions.SessionDuplicateTitleTest'), [Text.UTF8Encoding]::new($false))
$testLog = Join-Path $work 'tests.log'
Push-Location $repo
try { & java ('@' + $runtimeArgs) *> $testLog } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $testLog -Tail 90; throw "Focused tests failed: $work" }
Get-Content -LiteralPath $testLog -Tail 5
Write-Output "UI rule/guard/Robolectric checks passed. Compose screens, app resources and device runtime require unified validation. Workspace: $work"
