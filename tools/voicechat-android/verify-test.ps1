param(
    [Parameter(Mandatory=$true)][string]$Original,
    [Parameter(Mandatory=$true)][string]$Candidate,
    [string]$Java = 'D:/Java/jdk-21/bin/java.exe',
    [string]$Jdk = 'D:/Java/jdk-21',
    [string]$AndroidSdk = $(if ($env:VOICECHAT_ANDROID_SDK) { $env:VOICECHAT_ANDROID_SDK } else { 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk' })
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$work = Join-Path $root ('.tmp/mobile-tools/verifier-regression-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ExpectRejected([string]$Path, [string]$Expected) {
    $failure = $null
    try {
        & (Join-Path $PSScriptRoot 'verify.ps1') -Original $Original -Candidate $Path -Java $Java -Jdk $Jdk -AndroidSdk $AndroidSdk | Out-Null
    } catch { $failure = $_.Exception.Message }
    if (!$failure -or $failure -notmatch [Regex]::Escape($Expected)) {
        throw "Expected rejection '$Expected'; got '$failure'"
    }
    Write-Output "PASS rejected $Expected"
}
$duplicate = Join-Path $work 'duplicate.jar'
Copy-Item -LiteralPath $Candidate -Destination $duplicate
$archive = [IO.Compression.ZipFile]::Open($duplicate, [IO.Compression.ZipArchiveMode]::Update)
try {
    $name = @($archive.Entries | Where-Object { !$_.FullName.EndsWith('/') })[0].FullName
    $entry = $archive.CreateEntry($name)
    $stream = $entry.Open()
    try { $stream.WriteByte(0) } finally { $stream.Dispose() }
} finally { $archive.Dispose() }
ExpectRejected $duplicate 'Duplicate JAR entry'

foreach ($name in @('librnnoise4j.so','libopus4j.so')) {
    $corrupt = Join-Path $work "corrupt-$name.jar"
    Copy-Item -LiteralPath $Candidate -Destination $corrupt
    $archive = [IO.Compression.ZipFile]::Open($corrupt, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entryName = "natives/android-aarch64/$name"
        $archive.GetEntry($entryName).Delete()
        $entry = $archive.CreateEntry($entryName)
        $stream = $entry.Open()
        try { $stream.WriteByte(0) } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
    ExpectRejected $corrupt 'Incompatible Android ELF'
}

$index = 0
foreach ($name in @('natives/android-aarch64/libopus4j.so','META-INF/voicechat-android/Opus-COPYING.txt')) {
    $missing = Join-Path $work "missing-opus-$index.jar"
    $index++
    Copy-Item -LiteralPath $Candidate -Destination $missing
    $archive = [IO.Compression.ZipFile]::Open($missing, [IO.Compression.ZipArchiveMode]::Update)
    try { $archive.GetEntry($name).Delete() } finally { $archive.Dispose() }
    ExpectRejected $missing 'Unexpected new JAR entries'
}

# Rename one exported encoder/decoder JNI symbol without changing the ELF layout.
# This proves that both Java classes are checked, rather than merely finding an
# AArch64 Opus library or one valid JNI export.
foreach ($test in @(@('OpusEncoder','encode0'), @('OpusDecoder','decode0'))) {
    $symbol = "Java_de_maxhenkel_opus4j_$($test[0])_$($test[1])"
    $jniCandidate = Join-Path $work "missing-$($test[0])-jni.jar"
    Copy-Item -LiteralPath $Candidate -Destination $jniCandidate
    $archive = [IO.Compression.ZipFile]::Open($jniCandidate, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $name = 'natives/android-aarch64/libopus4j.so'
        $entry = $archive.GetEntry($name)
        $stream = $entry.Open()
        $buffer = [IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $data = $buffer.ToArray() }
        finally { $stream.Dispose(); $buffer.Dispose() }
        $needle = [Text.Encoding]::ASCII.GetBytes($symbol)
        $changed = 0
        for ($offset = 0; $offset -le $data.Length - $needle.Length; $offset++) {
            if ($data[$offset] -ne $needle[0]) { continue }
            $matchesSymbol = $true
            for ($index = 1; $index -lt $needle.Length; $index++) {
                if ($data[$offset + $index] -ne $needle[$index]) { $matchesSymbol = $false; break }
            }
            if ($matchesSymbol) { $data[$offset + $needle.Length - 1] = [byte][char]'x'; $changed++ }
        }
        if ($changed -eq 0) { throw "Could not find JNI fixture symbol: $symbol" }
        $entry.Delete()
        $stream = $archive.CreateEntry($name).Open()
        try { $stream.Write($data, 0, $data.Length) } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
    ExpectRejected $jniCandidate "Missing JNI method: $symbol"
}
Write-Output 'PASS verifier rejects duplicate entries, missing Opus files, corrupt libraries, and missing encoder/decoder JNI exports'
