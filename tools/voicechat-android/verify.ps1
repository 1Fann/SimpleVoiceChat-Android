param(
    [Parameter(Mandatory=$true)][string]$Original,
    [Parameter(Mandatory=$true)][string]$Candidate,
    [string]$Java = 'D:/Java/jdk-21/bin/java.exe',
    [string]$Jdk = 'D:/Java/jdk-21',
    [string]$AndroidSdk = $(if ($env:VOICECHAT_ANDROID_SDK) { $env:VOICECHAT_ANDROID_SDK } else { 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk' })
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$candidatePath = (Resolve-Path -LiteralPath $Candidate).Path
$originalPath = (Resolve-Path -LiteralPath $Original).Path
$work = Join-Path $root ('.tmp/mobile-tools/verify-voicechat-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function AssertUniqueEntries($archive) {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        if (!$names.Add($entry.FullName)) { throw "Duplicate JAR entry: $($entry.FullName)" }
    }
}
function EntryHash($entry) {
    $stream = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)) }
    finally { $stream.Dispose(); $sha.Dispose() }
}
$expectedChanged = @('de/maxhenkel/rnnoise4j/LibraryLoader.class','de/maxhenkel/speex4j/LibraryLoader.class',
    'de/maxhenkel/opus4j/LibraryLoader.class','de/maxhenkel/voicechat/natives/ClientNativeManager.class')
$expectedAdded = @('cn/fan/voicechat/android/NativePlatform.class',
    'natives/android-aarch64/librnnoise4j.so','natives/android-aarch64/libspeex4j.so','natives/android-aarch64/libopus4j.so',
    'META-INF/voicechat-android/RNNoise-COPYING.txt','META-INF/voicechat-android/SpeexDSP-COPYING.txt',
    'META-INF/voicechat-android/Opus-COPYING.txt','META-INF/voicechat-android/sources.json')
$before = [IO.Compression.ZipFile]::OpenRead($originalPath)
$after = [IO.Compression.ZipFile]::OpenRead($candidatePath)
try {
    AssertUniqueEntries $before
    AssertUniqueEntries $after
    $changes = @()
    foreach ($entry in $before.Entries) {
        $other = $after.GetEntry($entry.FullName)
        if ($null -eq $other) { throw "Removed entry: $($entry.FullName)" }
        if ((EntryHash $entry) -cne (EntryHash $other)) { $changes += $entry.FullName }
    }
    $added = @($after.Entries | Where-Object { $null -eq $before.GetEntry($_.FullName) } | ForEach-Object FullName)
    if ($changes.Count -ne $expectedChanged.Count -or
        (Compare-Object @($changes | Sort-Object) @($expectedChanged | Sort-Object) -CaseSensitive)) { throw 'Unexpected JAR modifications' }
    if ($added.Count -ne $expectedAdded.Count -or
        (Compare-Object @($added | Sort-Object) @($expectedAdded | Sort-Object) -CaseSensitive)) { throw 'Unexpected new JAR entries' }
    foreach ($name in @('librnnoise4j.so','libspeex4j.so','libopus4j.so')) {
        $entry = $after.GetEntry("natives/android-aarch64/$name")
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $work $name))
    }
    Write-Output 'PASS exact JAR diff: only 3 loaders + Android connection warning changed; 8 entries added; all original native libraries/metadata unchanged.'
} finally { $before.Dispose(); $after.Dispose() }

$vars = @('FCL_NATIVEDIR','POJAV_NATIVEDIR','ANDROID_ROOT')
$saved = @{}
foreach ($name in $vars) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
& "$Jdk/bin/javac.exe" --release 8 -d $classes (Join-Path $PSScriptRoot 'LoaderTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Loader test compilation failed' }
try {
    $cases = @(@('Windows 11','amd64','windows-x64',''), @('Linux','aarch64','linux-aarch64',''),
        @('Linux','amd64','linux-x64',''), @('Mac OS X','aarch64','mac-aarch64',''),
        @('Linux','aarch64','android-aarch64','FCL_NATIVEDIR'), @('Linux','arm64','android-aarch64','POJAV_NATIVEDIR'),
        @('Linux','aarch64','android-aarch64','ANDROID_ROOT'), @('Windows 11','amd64','windows-x64','FCL_NATIVEDIR'))
    foreach ($case in $cases) {
        foreach ($name in $vars) { [Environment]::SetEnvironmentVariable($name,$null) }
        if ($case[3]) { [Environment]::SetEnvironmentVariable($case[3],'/test/android') }
        & $Java -Xverify:all "-Dos.name=$($case[0])" "-Dos.arch=$($case[1])" -cp $classes LoaderTest $candidatePath $case[2]
        if ($LASTEXITCODE -ne 0) { throw 'Platform loader test failed' }
    }
    foreach ($name in $vars) { [Environment]::SetEnvironmentVariable($name,$null) }
    & $Java -Xverify:all -cp $classes LoaderTest $candidatePath windows-x64 smoke
    if ($LASTEXITCODE -ne 0) { throw 'Windows native smoke test failed' }
} finally { foreach ($name in $vars) { [Environment]::SetEnvironmentVariable($name,$saved[$name]) } }

$bin = Join-Path $AndroidSdk 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin'
foreach ($name in @('rnnoise4j','speex4j','opus4j')) {
    $so = Join-Path $work "lib$name.so"
    $elf = (& "$bin/llvm-readelf.exe" --file-header --dynamic --program-headers $so) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $elf -notmatch 'AArch64' -or $elf -match 'libm.so.6|libc.so.6|libstdc\+\+') { throw 'Incompatible Android ELF' }
    $loads = @($elf -split "`n" | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loads.Count -eq 0 -or ($loads | Where-Object { $_ -notmatch '0x4000\s*$' })) { throw 'Library is not 16 KB aligned' }
    $symbols = (& "$bin/llvm-nm.exe" -D --defined-only $so) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect JNI exports' }
    $types = switch ($name) {
        'rnnoise4j' { @('Denoiser') }
        'speex4j' { @('AutomaticGainControl') }
        'opus4j' { @('OpusEncoder','OpusDecoder') }
    }
    $count = 0
    foreach ($type in $types) {
        $nativeMethods = & "$Jdk/bin/javap.exe" -p -classpath $candidatePath "de.maxhenkel.$name.$type"
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect Java native methods: $type" }
        $classCount = 0
        foreach ($method in $nativeMethods) {
            if ($method -match '\bnative\s+\S+\s+(\w+)\(') {
                $symbol = "Java_de_maxhenkel_${name}_${type}_$($Matches[1])"
                if ($symbols -notmatch "\b$symbol\b") { throw "Missing JNI method: $symbol" }
                $classCount++
            }
        }
        if ($classCount -eq 0) { throw "No JNI methods checked: $type" }
        $count += $classCount
    }
    Write-Output "PASS $name Android ELF / 16 KB pages / $count JNI exports"
}
