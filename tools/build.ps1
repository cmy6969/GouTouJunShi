<#
.SYNOPSIS
    Builds the 狗头军师 Xposed module APK without Gradle.

.DESCRIPTION
    The project can be opened in Android Studio and built with Gradle, but a full Android SDK
    installation is not required: this script only needs a JDK, `android.jar` and the three
    build-tools binaries (aapt2, d8, apksigner/zipalign).

    1. javac     -> build/classes
    2. d8        -> build/dex/classes.dex
    3. aapt2     -> build/base.apk (resources + manifest + assets)
    4. zip       -> classes.dex appended
    5. zipalign  -> build/aligned.apk
    6. apksigner -> dist/GouTouJunShi-<version>.apk

.PARAMETER Jdk        JDK home (defaults to $env:JAVA_HOME).
.PARAMETER AndroidJar Path to android.jar (defaults to $env:ANDROID_JAR or -AndroidSdk).
.PARAMETER BuildTools Directory with aapt2.exe / d8.jar / apksigner.jar.
.PARAMETER AndroidSdk Android SDK root used to locate platform + build-tools.
#>
[CmdletBinding()]
param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$AndroidJar,
    [string]$BuildTools,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$SkipSign
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$root = $PSScriptRoot | Split-Path -Parent
$app = Join-Path $root 'app'
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "[ .. ] $msg" -ForegroundColor Cyan }
function Done($msg) { Write-Host "[ OK ] $msg" -ForegroundColor Green }

# ---------------------------------------------------------------- toolchain
if (-not $Jdk) { $Jdk = $env:JAVA_HOME }
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
    $guess = 'C:\Users\Hyper\.dsh\plugins\dsh-infinite-gen-5\runtimes\ghidra\jdk-21.0.12.1+1'
    if (Test-Path (Join-Path $guess 'bin\javac.exe')) { $Jdk = $guess } else { Fail 'JDK not found, pass -Jdk <home>' }
}
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'

if (-not $BuildTools) {
    if ($AndroidSdk -and (Test-Path (Join-Path $AndroidSdk 'build-tools'))) {
        $BuildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
            Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    } else {
        $BuildTools = 'E:\Develop\StarX\sdk\bt\android-14'
    }
}
if (-not (Test-Path (Join-Path $BuildTools 'aapt2.exe'))) {
    $alt = Get-ChildItem (Join-Path $BuildTools '*') -Filter aapt2.exe -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($alt) {
        $BuildTools = $alt.DirectoryName
    } else {
        Fail "aapt2.exe not found under $BuildTools"
    }
}

if (-not $AndroidJar) {
    $AndroidJar = $env:ANDROID_JAR
    if (-not $AndroidJar -and $AndroidSdk) {
        $cand = Get-ChildItem (Join-Path $AndroidSdk 'platforms') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($cand) { $AndroidJar = Join-Path $cand.FullName 'android.jar' }
    }
    if (-not $AndroidJar) { $AndroidJar = 'E:\Develop\StarX\sdk\pf\android-35\android.jar' }
}
if (-not (Test-Path $AndroidJar)) { Fail "android.jar not found: $AndroidJar" }

$d8 = Join-Path $BuildTools 'lib\d8.jar'
# The d8 shipped with build-tools 34 (R8 8.2) crashes on some anonymous classes produced by
# javac 21 ("NullPointerException in DexString").  A newer R8 jar is used when available.
$bundledD8 = Join-Path $PSScriptRoot 'lib\r8.jar'
if (Test-Path $bundledD8) {
    $d8 = $bundledD8
} elseif ($env:R8_JAR -and (Test-Path $env:R8_JAR)) {
    $d8 = $env:R8_JAR
}
$apksigner = Join-Path $BuildTools 'lib\apksigner.jar'
$aapt2 = Join-Path $BuildTools 'aapt2.exe'
$zipalign = Join-Path $BuildTools 'zipalign.exe'

Write-Host "JDK        : $Jdk"
Write-Host "android.jar: $AndroidJar"
Write-Host "build-tools: $BuildTools"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, $dist | Out-Null

# ------------------------------------------------------------------- stub
$stubJar = Join-Path $app 'libs\xposed-api-stub.jar'
if (-not (Test-Path $stubJar)) {
    Step 'compiling Xposed API stub'
    $stubSrc = Join-Path $root 'tools\xposed-stub'
    $stubOut = Join-Path $build 'stub'
    New-Item -ItemType Directory -Force -Path $stubOut, (Join-Path $app 'libs') | Out-Null
    $stubFiles = Get-ChildItem $stubSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & $javac -source 8 -target 8 -nowarn -bootclasspath $AndroidJar -d $stubOut @stubFiles
    if ($LASTEXITCODE -ne 0) { Fail 'stub compilation failed' }
    & (Join-Path $Jdk 'bin\jar.exe') cf $stubJar -C $stubOut .
    if ($LASTEXITCODE -ne 0) { Fail 'stub jar failed' }
    Done 'xposed-api-stub.jar'
}

# ------------------------------------------------------------------ javac
Step 'compiling module sources'
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$srcFiles = Get-ChildItem (Join-Path $app 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argFile = Join-Path $build 'sources.txt'
[System.IO.File]::WriteAllLines($argFile, $srcFiles, (New-Object System.Text.UTF8Encoding($false)))
& $javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -g `
    -bootclasspath $AndroidJar -classpath $stubJar -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { Fail 'javac failed' }
Done ("compiled " + $srcFiles.Count + " files")

# -------------------------------------------------------------------- dex
Step 'dexing with d8'
$dexOut = Join-Path $build 'dex'
New-Item -ItemType Directory -Force -Path $dexOut | Out-Null
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $java -cp $d8 com.android.tools.r8.D8 --release --min-api 26 --lib $AndroidJar --output $dexOut @classFiles
if ($LASTEXITCODE -ne 0) { Fail 'd8 failed' }
Done 'classes.dex'

# ------------------------------------------------------------------ aapt2
Step 'linking resources'
$res = Join-Path $app 'src\main\res'
$assets = Join-Path $app 'src\main\assets'
$manifest = Join-Path $app 'src\main\AndroidManifest.xml'
$flat = Join-Path $build 'res.zip'
& $aapt2 compile --dir $res -o $flat
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile failed' }
$baseApk = Join-Path $build 'base.apk'
& $aapt2 link -o $baseApk -I $AndroidJar --manifest $manifest -A $assets -R $flat `
    --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link failed' }

# aapt2 on Windows stores assets with backslashes; Android's ZipFile matches literally.
$py = (Get-Command python -ErrorAction SilentlyContinue)
if ($py) {
    Step 'normalizing asset paths'
    & $py.Source (Join-Path $PSScriptRoot 'normalize_apk.py') $baseApk "$baseApk.norm"
    if ($LASTEXITCODE -ne 0) { Fail 'asset path normalisation failed' }
    Move-Item "$baseApk.norm" $baseApk -Force
}
Done 'base.apk'

# ------------------------------------------------------------------- pack
Step 'packing classes.dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dexFile = Join-Path $dexOut 'classes.dex'
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile, 'classes.dex')
} finally { $zip.Dispose() }
Done 'classes.dex packed'

# -------------------------------------------------------------- align/sign
$aligned = Join-Path $build 'aligned.apk'
if (Test-Path $zipalign) {
    Step 'zipalign'
    & $zipalign -f 4 $baseApk $aligned
    if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }
} else {
    Copy-Item $baseApk $aligned -Force
}

$version = '1.0.0'
$outApk = Join-Path $dist "GouTouJunShi-$version.apk"

if ($SkipSign) {
    Copy-Item $aligned $outApk -Force
    Done "unsigned apk: $outApk"
    exit 0
}

$ks = Join-Path $root 'lumen.keystore'
if (-not (Test-Path $ks)) {
    Step 'generating signing key'
    & $keytool -genkeypair -keystore $ks -alias lumen -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android -dname "CN=GouTouJunShi, OU=Module, O=GouTouJunShi, C=CN" 2>$null
    if ($LASTEXITCODE -ne 0) { Fail 'keytool failed' }
    Done 'lumen.keystore'
}

Step 'signing'
& $java -jar $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 26 --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { Fail 'apksigner failed' }
& $java -jar $apksigner verify --min-sdk-version 26 $outApk
if ($LASTEXITCODE -ne 0) { Fail 'signature verification failed' }
Done "APK: $outApk"
Write-Host ("size: " + [math]::Round((Get-Item $outApk).Length / 1KB, 1) + " KB")
