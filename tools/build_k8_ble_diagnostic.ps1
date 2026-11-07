param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(Mandatory = $true)][string]$Android19Jar,
    [Parameter(Mandatory = $true)][string]$JdkHome
)

$ErrorActionPreference = 'Stop'

function Resolve-InputFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing build input: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $Program) failed with exit code $LASTEXITCODE."
    }
}

$repo = Split-Path -Parent $PSScriptRoot
$source = Join-Path $repo 'ble-diagnostic'
$manifest = Resolve-InputFile (Join-Path $source 'AndroidManifest.xml')
$androidJar = Resolve-InputFile $Android19Jar
$platformProperties = Resolve-InputFile (Join-Path (Split-Path -Parent $androidJar) 'source.properties')
if (-not (Select-String -LiteralPath $platformProperties -Pattern '^AndroidVersion\.ApiLevel=19$' -Quiet)) {
    throw 'Android19Jar must be from the Android SDK Platform 19 package.'
}

$buildTools = Join-Path $SdkRoot 'build-tools/34.0.0'
$aapt = Resolve-InputFile (Join-Path $buildTools 'aapt.exe')
$d8 = Resolve-InputFile (Join-Path $buildTools 'lib/d8.jar')
$zipalign = Resolve-InputFile (Join-Path $buildTools 'zipalign.exe')
$apksigner = Resolve-InputFile (Join-Path $buildTools 'lib/apksigner.jar')
$java = Resolve-InputFile (Join-Path $JdkHome 'bin/java.exe')
$javac = Resolve-InputFile (Join-Path $JdkHome 'bin/javac.exe')
$keytool = Resolve-InputFile (Join-Path $JdkHome 'bin/keytool.exe')

$private = Join-Path $repo 'private/ble-diagnostic'
$tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path.TrimEnd('\')
$stageName = 'gen5w-ble-' + [Guid]::NewGuid().ToString('N')
$stage = Join-Path $tempRoot $stageName
if ($stage -match '[^\x20-\x7e]') {
    throw 'Android build tools require an ASCII temporary directory. Set TEMP to a writable ASCII path.'
}
$classes = Join-Path $stage 'classes'
$dex = Join-Path $stage 'dex'
New-Item -ItemType Directory -Path $private, $classes, $dex -Force | Out-Null

$unsignedApk = Join-Path $stage 'unsigned.apk'
$alignedApk = Join-Path $stage 'aligned.apk'
$signedApk = Join-Path $stage 'gen5w-ble-diagnostic.apk'
$keystore = Join-Path $private 'diagnostic-debug.p12'

try {
    # Legacy aapt on Windows needs ASCII file arguments even when the checkout uses Unicode paths.
    $stagedManifest = Join-Path $stage 'AndroidManifest.xml'
    $stagedJar = Join-Path $stage 'android.jar'
    Copy-Item -LiteralPath $manifest -Destination $stagedManifest
    Copy-Item -LiteralPath $androidJar -Destination $stagedJar
    Copy-Item -LiteralPath (Join-Path $source 'src') -Destination $stage -Recurse
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $stage 'src') -Recurse -File -Filter '*.java' |
        Sort-Object FullName | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        throw 'No diagnostic Java sources found.'
    }

    Invoke-Checked $javac (@('-encoding', 'UTF-8', '-source', '7', '-target', '7',
        '-Xlint:-options', '-bootclasspath', $stagedJar, '-d', $classes) + $sources)
    $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -File -Filter '*.class' |
        Sort-Object FullName | ForEach-Object { $_.FullName })
    Invoke-Checked $java (@('-cp', $d8, 'com.android.tools.r8.D8', '--min-api', '19',
        '--lib', $stagedJar, '--output', $dex) + $classFiles)
    Invoke-Checked $aapt @('package', '-f', '-M', $stagedManifest, '-I', $stagedJar, '-F', $unsignedApk)
    Push-Location -LiteralPath $dex
    try {
        Invoke-Checked $aapt @('add', $unsignedApk, 'classes.dex')
    } finally {
        Pop-Location
    }
    Invoke-Checked $zipalign @('-f', '4', $unsignedApk, $alignedApk)

    # Dedicated local diagnostic debug key. The public debug password does not protect a release key.
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        $newKey = Join-Path $stage 'new-debug.p12'
        Invoke-Checked $keytool @('-genkeypair', '-noprompt', '-storetype', 'PKCS12',
            '-keystore', $newKey, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'gen5wble', '-dname', 'CN=Gen5W BLE Diagnostic', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000')
        try {
            [IO.File]::Copy($newKey, $keystore, $false)
        } catch [IO.IOException] {
            if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) { throw }
        }
    }
    $stagedKey = Join-Path $stage 'diagnostic-debug.p12'
    Copy-Item -LiteralPath $keystore -Destination $stagedKey
    Invoke-Checked $java @('-jar', $apksigner, 'sign', '--min-sdk-version', '19',
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
        '--ks', $stagedKey, '--ks-key-alias', 'gen5wble', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', $signedApk, $alignedApk)
    Invoke-Checked $java @('-jar', $apksigner, 'verify', '--min-sdk-version', '19', '--verbose', $signedApk)
    Invoke-Checked $zipalign @('-c', '4', $signedApk)
    $badging = @(Invoke-Checked $aapt @('dump', 'badging', $signedApk))
    if ($badging -notcontains "sdkVersion:'19'" -or $badging -notcontains "targetSdkVersion:'19'") {
        throw 'Packaged SDK versions do not match API 19.'
    }
    $badging | Where-Object { $_ -match '^(package:|sdkVersion:|targetSdkVersion:|uses-permission:)' }

    $output = Join-Path $private 'gen5w-ble-diagnostic.apk'
    $candidate = Join-Path $private ($stageName + '.apk')
    Copy-Item -LiteralPath $signedApk -Destination $candidate
    Move-Item -LiteralPath $candidate -Destination $output -Force
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally {
    $resolvedStage = (Resolve-Path -LiteralPath $stage).Path
    if ($resolvedStage -eq (Join-Path $tempRoot $stageName) -and
        -not ((Get-Item -LiteralPath $resolvedStage).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
