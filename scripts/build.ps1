param([switch]$SkipTests, [switch]$VerboseBuild, [switch]$Instrumentation)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$drive = @('R:','Q:','S:','T:') | Where-Object { -not (Test-Path "$_\") } | Select-Object -First 1
if (-not $drive) { throw 'No free drive letter for the local build path.' }
& subst.exe $drive $repoRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not map local build directory.' }
$projectRoot = $repoRoot
$repoRoot = "$drive/"
$locationPushed = $false
try {
    $env:JAVA_HOME = if ($env:APK_JAVA_HOME) { $env:APK_JAVA_HOME } else { 'C:\devsoft\ideaIC\jbr' }
    $env:GRADLE_USER_HOME = Join-Path $repoRoot '.cache\gradle'
    $env:ANDROID_USER_HOME = Join-Path $repoRoot '.cache\android'
    $env:ANDROID_HOME = Join-Path $repoRoot '.tooling\sdk'
    $env:TEMP = Join-Path $repoRoot 'tmp'
    $env:TMP = $env:TEMP
    $env:PIP_CACHE_DIR = Join-Path $repoRoot '.cache\pip'
    $env:PYTHONUSERBASE = Join-Path $repoRoot '.cache\python-user'
    New-Item -ItemType Directory -Force -Path $env:TEMP,$env:ANDROID_USER_HOME | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=$repoRoot/.cache/home -Djava.io.tmpdir=$env:TEMP -Dfile.encoding=UTF-8"
    $env:GRADLE_OPTS = '-Dorg.gradle.internal.instrumentation.agent=false'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    & (Join-Path $repoRoot '.tooling\python-full\tools\python.exe') -B (Join-Path $repoRoot 'scripts\create_signing.py')
    if ($LASTEXITCODE -ne 0) { throw 'Release signing setup failed.' }
    $sdkPath = $env:ANDROID_HOME.Replace('\','/')
    [IO.File]::WriteAllText((Join-Path $repoRoot 'android\local.properties'), "sdk.dir=$sdkPath", [Text.UTF8Encoding]::new($false))
    $gradle = Join-Path $repoRoot '.tooling\gradle\gradle-8.13\bin\gradle.bat'
    Push-Location (Join-Path $repoRoot 'android')
    $locationPushed = $true
    $tasks = if ($SkipTests) { @('assembleDebug','assembleRelease') } else { @('testDebugUnitTest','lintDebug','assembleDebug','assembleRelease') }
    if ($Instrumentation) { $tasks += 'assembleDebugAndroidTest' }
    $arguments = @('--no-daemon','--console=plain')
    if ($VerboseBuild) { $arguments += '--info' }
    $arguments += $tasks
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit $LASTEXITCODE" }
    $distribution = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Force -Path $distribution | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'android/app/build/outputs/apk/release/app-release.apk') -Destination (Join-Path $distribution 'APK_Autocount_tasks-0.1.0.apk') -Force
} finally {
    if ($locationPushed) { Pop-Location }
    & subst.exe $drive /D
}
