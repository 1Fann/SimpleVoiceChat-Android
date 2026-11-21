param(
    [Parameter(Mandatory=$true)][string]$FabricOriginal,
    [Parameter(Mandatory=$true)][string]$ForgeOriginal,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$AndroidSdk = $(if ($env:VOICECHAT_ANDROID_SDK) { $env:VOICECHAT_ANDROID_SDK } else { 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk' }),
    [string]$Jdk = 'D:/Java/jdk-21',
    [string]$Java8 = 'D:/Java/jre1.8.0_301/bin/java.exe',
    [string]$Asm = 'D:/CodeX/PluginsMaker/VersionSwitch/.gradle-fabric/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8/dc19ecb3f7889b7860697215cae99c0f9b6f6b4b/asm-9.8.jar',
    [switch]$SkipDownload
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$fabric = (Resolve-Path -LiteralPath $FabricOriginal).Path
$forge = (Resolve-Path -LiteralPath $ForgeOriginal).Path
$version = '2.6.27'
$revision = "android-$version-r1"
# Modrinth versions dyhq1XyO (Fabric) and YCa5DHCu (Forge).
if ((Get-FileHash -LiteralPath $fabric -Algorithm SHA512).Hash -ne '4ab4e18279bb17b999d67271f45e6eccaaf30b5c86a27693860eaf8dd8d3d3b26030aabcbcfcc51d87b9a28169d753ce96cc8a9e0ceba3619aee9fd1ffc9413c' -or
    (Get-FileHash -LiteralPath $forge -Algorithm SHA512).Hash -ne '4193b05d538011e9d82e4f45fc3360266c4e02377008452bfec040c78329e99d42023e2d9dbe686ff8435f946b3221cd5aebc451ea0d9197370b73ae079ccc48') {
    throw "Original JAR hashes do not match official Fabric 1.21.1 / Forge 1.12.2 version $version."
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new output directory; this builder does not overwrite releases.' }
$build = Join-Path $root ('.tmp/mobile-build-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
function Run([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Command" }
}
if (!$SkipDownload) {
    Run 'node' @((Join-Path $PSScriptRoot 'download.mjs'))
    Run 'node' @((Join-Path $PSScriptRoot 'download-opus.mjs'))
}
$cmake = Join-Path $AndroidSdk 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $AndroidSdk 'cmake/3.22.1/bin/ninja.exe'
$ndk = Join-Path $AndroidSdk 'ndk/27.0.12077973'
$llvm = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$native = Join-Path $build 'native'
Run $cmake @('-S',$PSScriptRoot,'-B',$native,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DCMAKE_BUILD_TYPE=Release')
Run $cmake @('--build',$native,'--target','rnnoise4j','speex4j','opus4j','--parallel','4')
$stripped = Join-Path $build 'stripped'
$classes = Join-Path $build 'classes'
$helper = Join-Path $build 'helper'
$sources = Join-Path $build 'sources'
New-Item -ItemType Directory -Path $stripped,$classes,$helper,"$sources/rnnoise","$sources/speexdsp","$sources/opus" | Out-Null
foreach ($name in @('rnnoise4j','speex4j','opus4j')) {
    Run "$llvm/llvm-strip.exe" @('--strip-unneeded','-o',"$stripped/lib$name.so","$native/lib$name.so")
}
Run "$Jdk/bin/javac.exe" @('--release','8','-Xlint:-options','-d',$helper,(Join-Path $PSScriptRoot 'NativePlatform.java'))
Run "$Jdk/bin/javac.exe" @('-cp',$Asm,'-d',$classes,(Join-Path $PSScriptRoot 'PatchVoicechat.java'),(Join-Path $PSScriptRoot 'PatchVoicechatTest.java'))
Run "$Jdk/bin/java.exe" @('-cp',"$classes;$Asm",'PatchVoicechatTest',"$build/patch-tests",$fabric,$forge)
Copy-Item -LiteralPath "$root/.build-tools/voicechat-android/rnnoise/COPYING" -Destination "$sources/rnnoise/COPYING"
Copy-Item -LiteralPath "$root/.build-tools/voicechat-android/speexdsp/COPYING" -Destination "$sources/speexdsp/COPYING"
Copy-Item -LiteralPath "$root/.build-tools/voicechat-opus/opus/COPYING" -Destination "$sources/opus/COPYING"
$provenance = @(Get-Content -Raw -LiteralPath "$root/.build-tools/voicechat-android/sources.json" | ConvertFrom-Json)
$provenance += @(Get-Content -Raw -LiteralPath "$root/.build-tools/voicechat-opus/sources.json" | ConvertFrom-Json)
$localFiles = @('CMakeLists.txt','opus-android.cmake','android-rnnoise.c','harden-rnnoise.cmake','PatchVoicechat.java','NativePlatform.java') | ForEach-Object {
    [ordered]@{path="tools/voicechat-android/$_";sha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$nativeFiles = @('rnnoise4j','speex4j','opus4j') | ForEach-Object {
    [ordered]@{name="lib$_.so";sha256=(Get-FileHash -LiteralPath "$stripped/lib$_.so" -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$provenance += [ordered]@{name='local-android-mobile';revision=$revision;files=$localFiles;natives=$nativeFiles}
ConvertTo-Json -InputObject $provenance -Depth 10 | Set-Content -LiteralPath "$sources/sources.json" -Encoding utf8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$weights = @()
foreach ($item in @(@('fabric',$fabric),@('forge',$forge))) {
    $archive = [IO.Compression.ZipFile]::OpenRead($item[1])
    $path = "$build/$($item[0])-weights.bin"
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('rnnoise/weights_blob.bin'),$path) }
    finally { $archive.Dispose() }
    $weights += $path
}
$previousSource = $env:VOICECHAT_RNNOISE_SOURCE
$previousSdk = $env:VOICECHAT_ANDROID_SDK
try {
    $env:VOICECHAT_RNNOISE_SOURCE = "$native/rnnoise/src"
    $env:VOICECHAT_ANDROID_SDK = $AndroidSdk
    Run 'node' (@((Join-Path $PSScriptRoot 'test-model.mjs')) + $weights)
    Run 'node' (@((Join-Path $PSScriptRoot 'test-lifecycle.mjs')) + $weights)
    Run 'node' @((Join-Path $PSScriptRoot 'test-rnnoise-wrapper.mjs'))
} finally {
    $env:VOICECHAT_RNNOISE_SOURCE = $previousSource
    $env:VOICECHAT_ANDROID_SDK = $previousSdk
}
# Keep unfinished artifacts in the build directory until both packages pass.
$packages = @()
foreach ($item in @(@('fabric-1.21.1',$fabric,"$Jdk/bin/java.exe"),@('forge-1.12.2',$forge,$Java8))) {
    $name = "voicechat-$($item[0])-$version-android.jar"
    $candidate = Join-Path $build $name
    Run "$Jdk/bin/java.exe" @('-cp',"$classes;$Asm",'PatchVoicechat',$item[1],$candidate,$helper,$stripped,$sources)
    & (Join-Path $PSScriptRoot 'verify.ps1') -Original $item[1] -Candidate $candidate -Java $item[2] -Jdk $Jdk -AndroidSdk $AndroidSdk
    $packages += [ordered]@{file=$name;candidate=$candidate;sha256=(Get-FileHash -LiteralPath $candidate).Hash;originalSha256=(Get-FileHash -LiteralPath $item[1]).Hash}
}
$oldCodecJava = $env:VOICECHAT_JAVA
$oldCodecJavac = $env:VOICECHAT_JAVAC
$oldCodecSdk = $env:VOICECHAT_ANDROID_SDK
try {
    $env:VOICECHAT_JAVA = "$Jdk/bin/java.exe"
    $env:VOICECHAT_JAVAC = "$Jdk/bin/javac.exe"
    $env:VOICECHAT_ANDROID_SDK = $AndroidSdk
    Run 'node' @((Join-Path $PSScriptRoot 'test-opus.mjs'),$packages[0].candidate)
    $env:VOICECHAT_JAVA = $Java8
    Run 'node' @((Join-Path $PSScriptRoot 'test-opus.mjs'),$packages[1].candidate)
} finally {
    $env:VOICECHAT_JAVA = $oldCodecJava
    $env:VOICECHAT_JAVAC = $oldCodecJavac
    $env:VOICECHAT_ANDROID_SDK = $oldCodecSdk
}
# Publish the complete directory only after all copies and the receipt succeed.
# Failed staging remains visibly marked .partial, never a final install directory.
$stagedOutput = $output + '.partial-' + [Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
New-Item -ItemType Directory -Path $stagedOutput | Out-Null
foreach ($package in $packages) {
    $destination = Join-Path $stagedOutput $package.file
    [IO.File]::Copy($package.candidate,$destination,$false)
    if ((Get-FileHash -LiteralPath $destination).Hash -ne $package.sha256) { throw 'Staged package hash mismatch' }
}
[ordered]@{revision=$revision;abi='arm64-v8a';minimumAndroidApi=26;build=$build;packages=$packages;androidDeviceTested=$false} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $stagedOutput 'build-receipt.json') -Encoding utf8
[IO.Directory]::Move($stagedOutput,$output)
Write-Output "PASS mobile packages ready in $output"
