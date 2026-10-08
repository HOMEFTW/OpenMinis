param(
    [string]$NdkPath = "$env:ANDROID_HOME/ndk/27.0.12077973",
    [string]$BuildDirectory = 'D:/Code/.tools/openminis-proot-build'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repo 'deps/proot/src'
$talloc = Join-Path $repo 'deps/talloc'
$bin = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$clang = Join-Path $bin 'clang.exe'
$objcopy = Join-Path $bin 'llvm-objcopy.exe'
$readelf = Join-Path $bin 'llvm-readelf.exe'
$jni = Join-Path $repo 'src/android/app/src/main/jniLibs/arm64-v8a'
foreach ($path in @($clang, $objcopy, $readelf, "$source/GNUmakefile", "$talloc/replace.h")) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing build input: $path" }
}
# These loaders carry Termux Android patches. Never replace them with fork loaders.
$loaders = @{
    'libproot-loader.so' = '44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04'
    'libproot-loader32.so' = '25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34'
}
foreach ($name in $loaders.Keys) {
    if ((Get-FileHash -LiteralPath "$jni/$name" -Algorithm SHA256).Hash -ne $loaders[$name]) {
        throw "Unexpected Termux loader: $name"
    }
}
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$common = @('--target=aarch64-linux-android26', '-O2', '-fPIE', '-D_FILE_OFFSET_BITS=64', '-D_GNU_SOURCE', '-DARG_MAX=131072', "-I$BuildDirectory", "-I$source", "-I$talloc")
Push-Location $BuildDirectory
try {
    $version = & git -C "$repo/deps/proot" rev-parse --short HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify PRoot revision' }
    $header = @('#ifndef BUILD_H', '#define BUILD_H', "#define VERSION `"$version`"")
    foreach ($feature in @('process_vm', 'seccomp_filter')) {
        & $clang @common "$source/.check_$feature.c" -o "check-$feature"
        if ($LASTEXITCODE -eq 0) { $header += "#define HAVE_$($feature.ToUpperInvariant())" }
    }
    $header += '#endif'
    # Generated build configuration; source files remain untouched.
    $header | Set-Content -LiteralPath 'build.h' -Encoding ascii
    & $clang @common '-DHAVE_STDARG_H=1' '-DHAVE_VA_COPY=1' '-DHAVE_UNISTD_H=1' '-DHAVE_INTPTR_T=1' '-std=gnu99' -c "$talloc/talloc.c" -o 'talloc.o'
    if ($LASTEXITCODE -ne 0) { throw 'talloc compilation failed' }
    # Pinned Termux loader: entry 0x2000000000; the str/BRK stub is at
    # 0x2000000460. Verify its bytes rather than using fork-loader offsets.
    $disassembly = & "$bin/llvm-objdump.exe" -d "$jni/libproot-loader.so"
    if (($disassembly -join "`n") -notmatch '2000000460: f9000041' -or
        ($disassembly -join "`n") -notmatch '2000000464: f7f0a000') { throw 'Loader workaround ABI mismatch' }
    '#include <unistd.h>', 'const ssize_t offset_to_pokedata_workaround = 0x460;' |
        Set-Content -LiteralPath 'loader-info.c' -Encoding ascii
    & $clang @common -c 'loader-info.c' -o 'loader-info.o'
    if ($LASTEXITCODE -ne 0) { throw 'Loader metadata compilation failed' }
    $objects = @('talloc.o', 'loader-info.o')
    $makefile = Get-Content -LiteralPath "$source/GNUmakefile" -Raw
    $block = $makefile.Substring($makefile.IndexOf('OBJECTS +=')) -split 'define define_from_arch.h', 2
    $sources = [regex]::Matches($block[0], '[a-zA-Z0-9_/-]+\.o') | ForEach-Object { $_.Value }
    foreach ($object in $sources) {
        $output = $object.Replace('/', '_')
        $inputFile = $object.Substring(0, $object.Length - 2) + '.c'
        & $clang @common -c "$source/$inputFile" -o $output
        if ($LASTEXITCODE -ne 0) { throw "PRoot compilation failed: $inputFile" }
        $objects += $output
    }
    foreach ($pair in @(@('libproot-loader.so', 'loader.exe'), @('libproot-loader32.so', 'loader-m32.exe'))) {
        Copy-Item -LiteralPath "$jni/$($pair[0])" -Destination $pair[1] -Force
        $output = "$($pair[1]).o"
        & $objcopy --input-target=binary --output-target=elf64-littleaarch64 $pair[1] $output
        if ($LASTEXITCODE -ne 0) { throw "Loader wrapping failed: $($pair[0])" }
        $objects += $output
    }
    & $clang --target=aarch64-linux-android26 -pie '-Wl,-z,noexecstack' @objects -o 'proot-aarch64'
    if ($LASTEXITCODE -ne 0) { throw 'PRoot link failed' }
    & "$bin/llvm-strip.exe" 'proot-aarch64'
    if ($LASTEXITCODE -ne 0) { throw 'PRoot strip failed' }
    $elf = & $readelf -h 'proot-aarch64'
    if ($LASTEXITCODE -ne 0 -or ($elf -join "`n") -notmatch 'AArch64') { throw 'Output is not AArch64 ELF' }
    $strings = & "$bin/llvm-strings.exe" 'proot-aarch64'
    if ($strings -notcontains '--fake-netlink') { throw 'Missing fake-netlink extension' }
    Copy-Item -LiteralPath 'proot-aarch64' -Destination "$repo/src/android/app/src/main/assets/proot-aarch64" -Force
    Copy-Item -LiteralPath 'proot-aarch64' -Destination "$jni/libproot.so" -Force
    Get-FileHash -LiteralPath "$jni/libproot.so" -Algorithm SHA256
} finally {
    Pop-Location
}
