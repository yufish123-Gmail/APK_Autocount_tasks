param([string]$HostPython = 'python')
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:TEMP = Join-Path $repoRoot 'tmp'
$env:TMP = $env:TEMP
$env:PIP_CACHE_DIR = Join-Path $repoRoot '.cache\pip'
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
Push-Location $repoRoot
try {
    & $HostPython -B scripts/bootstrap.py
    if ($LASTEXITCODE -ne 0) { throw 'Build tool download failed.' }
    $env:JAVA_HOME = if ($env:APK_JAVA_HOME) { $env:APK_JAVA_HOME } else { 'C:\devsoft\ideaIC\jbr' }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:ANDROID_USER_HOME = Join-Path $repoRoot '.cache\android'
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=$repoRoot/.cache/home -Djava.io.tmpdir=$env:TEMP"
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
    # Android SDK components require accepting the SDK license.
    1..120 | ForEach-Object { 'y' } | & .tooling/sdk/cmdline-tools/latest/cmdline-tools/bin/sdkmanager.bat --sdk_root=.tooling/sdk 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK setup failed.' }
    & $HostPython -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Local test environment setup failed.' }
    & .venv/Scripts/python.exe -m pip install -r requirements-dev.txt
    if ($LASTEXITCODE -ne 0) { throw 'Local test dependency install failed.' }
} finally { Pop-Location }
