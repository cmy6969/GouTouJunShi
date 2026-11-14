<#
.SYNOPSIS
    Runs the offline checks that ship with the module.

.DESCRIPTION
    1. NormTest   - plain JVM test of the answer normaliser (no Android needed).
    2. probe.test - jsdom test of the injected page scripts (scan / fill / next) against a
                    synthetic question page.

    Requires a JDK. The jsdom test additionally needs `jsdom` in node_modules
    (`pnpm add jsdom` or `npm install jsdom` in the project root).
#>
[CmdletBinding()]
param(
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot | Split-Path -Parent
$build = Join-Path $root 'build\tests'

if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
    $guess = 'C:\Users\Hyper\.dsh\plugins\dsh-infinite-gen-5\runtimes\ghidra\jdk-21.0.12.1+1'
    if (Test-Path (Join-Path $guess 'bin\javac.exe')) { $Jdk = $guess } else { Write-Host 'JDK not found' -ForegroundColor Red; exit 1 }
}

New-Item -ItemType Directory -Force -Path $build | Out-Null

Write-Host '== Norm (JVM) ==' -ForegroundColor Cyan
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $build `
    (Join-Path $root 'test\NormTest.java') `
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\Norm.java')
if ($LASTEXITCODE -ne 0) { Write-Host 'javac failed' -ForegroundColor Red; exit 1 }
& (Join-Path $Jdk 'bin\java.exe') -cp $build NormTest
$normExit = $LASTEXITCODE

Write-Host ''
Write-Host '== page scripts (jsdom) ==' -ForegroundColor Cyan
$node = $env:NODE_EXE
if (-not $node) {
    $candidate = 'C:\Users\Hyper\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe'
    if (Test-Path $candidate) { $node = $candidate } else { $node = 'node' }
}
& $node (Join-Path $root 'test\probe.test.mjs')
$jsExit = $LASTEXITCODE

Write-Host ''
Write-Host '== AXE question bank client ==' -ForegroundColor Cyan
$json = Join-Path $root 'tools\lib\json.jar'
$androidJar = $env:ANDROID_JAR
if (-not $androidJar) { $androidJar = 'E:\Develop\StarX\sdk\pf\android-35\android.jar' }
$stub = Join-Path $root 'app\libs\xposed-api-stub.jar'
$axeOut = Join-Path $build 'axe'
New-Item -ItemType Directory -Force -Path $axeOut | Out-Null
$axeSources = @(
    (Join-Path $root 'test\AxeBankTest.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\AxeBank.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\Norm.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\Question.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Http.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Logx.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Cfg.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Self.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Activities.java')
)
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -nowarn -cp "$json;$androidJar;$stub" -d $axeOut @axeSources
if ($LASTEXITCODE -ne 0) { Write-Host 'javac (axe) failed' -ForegroundColor Red; exit 1 }
& (Join-Path $Jdk 'bin\java.exe') -cp "$axeOut;$json;$androidJar;$stub" com.goutou.junshi.answer.AxeBankTest
$axeExit = $LASTEXITCODE

Write-Host ''
Write-Host '== AI fallback client ==' -ForegroundColor Cyan
$aiOut = Join-Path $build 'ai'
New-Item -ItemType Directory -Force -Path $aiOut | Out-Null
$aiSources = @(
    (Join-Path $root 'test\AiBankTest.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\AiBank.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\AxeBank.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\Norm.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\answer\Question.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Http.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Logx.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Cfg.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Self.java'),
    (Join-Path $root 'app\src\main\java\com\goutou\junshi\core\Activities.java')
)
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -nowarn -cp "$json;$androidJar;$stub" -d $aiOut @aiSources
if ($LASTEXITCODE -ne 0) { Write-Host 'javac (ai) failed' -ForegroundColor Red; exit 1 }
& (Join-Path $Jdk 'bin\java.exe') -cp "$aiOut;$json;$androidJar;$stub" com.goutou.junshi.answer.AiBankTest
$aiExit = $LASTEXITCODE

Write-Host ''
if ($normExit -eq 0 -and $jsExit -eq 0 -and $axeExit -eq 0 -and $aiExit -eq 0) {
    Write-Host 'ALL TESTS PASSED' -ForegroundColor Green
    exit 0
}
Write-Host 'TESTS FAILED' -ForegroundColor Red
exit 1
