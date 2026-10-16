param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$signingPropertiesFile = Join-Path $env:USERPROFILE '.android\PiperOS\signing.properties'
$lineageFile = Join-Path $repoRoot 'app\signing\piperos-signing-lineage.bin'

if (-not (Test-Path -LiteralPath $signingPropertiesFile -PathType Leaf)) {
    throw "Local signing properties not found: $signingPropertiesFile"
}
if (-not (Test-Path -LiteralPath $lineageFile -PathType Leaf)) {
    throw "Signing lineage not found: $lineageFile"
}
$inputApk = (Resolve-Path -LiteralPath $ApkPath).Path
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path (Split-Path -Parent $inputApk) (([IO.Path]::GetFileNameWithoutExtension($inputApk)) + '-rotated.apk')
}
$outputDirectory = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)

$signing = Get-Content -LiteralPath $signingPropertiesFile -Raw | ConvertFrom-StringData
$oldKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path -LiteralPath $oldKeystore -PathType Leaf)) {
    throw "Previous signing keystore not found: $oldKeystore"
}
$sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$sdkRoot = $sdkCandidates | Where-Object { Test-Path (Join-Path $_ 'build-tools') } | Select-Object -First 1
if (-not $sdkRoot) {
    throw 'Android SDK build-tools were not found. Set ANDROID_SDK_ROOT or ANDROID_HOME.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+(\.\d+)+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "apksigner not found: $apksigner"
}
$javaHomeCandidates = @(
    $env:JAVA_HOME,
    $env:ANDROID_STUDIO_JDK,
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    $javaHome = $javaHomeCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if (-not $javaHome) {
        throw 'Java was not found. Set JAVA_HOME or ANDROID_STUDIO_JDK.'
    }
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"
}

$env:PIPEROS_SIGNING_STORE_PASSWORD = $signing.storePassword
$env:PIPEROS_SIGNING_KEY_PASSWORD = $signing.keyPassword
try {
    & $apksigner sign `
        --ks $oldKeystore `
        --ks-key-alias androiddebugkey `
        --ks-pass pass:android `
        --key-pass pass:android `
        --next-signer `
        --ks $signing.storeFile `
        --ks-key-alias $signing.keyAlias `
        --ks-pass env:PIPEROS_SIGNING_STORE_PASSWORD `
        --key-pass env:PIPEROS_SIGNING_KEY_PASSWORD `
        --lineage $lineageFile `
        --rotation-min-sdk-version 28 `
        --v1-signing-enabled false `
        --v2-signing-enabled true `
        --v3-signing-enabled true `
        --out $OutputPath `
        $inputApk
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:PIPEROS_SIGNING_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PIPEROS_SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
}

Write-Output "Signed APK with v3 lineage: $OutputPath"
