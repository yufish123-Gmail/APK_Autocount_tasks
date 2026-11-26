$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:PYTHONPATH = Join-Path $repoRoot 'android\app\src\main\python'
$env:PYTHONIOENCODING = 'utf-8'
$env:TEMP = Join-Path $repoRoot 'tmp'
$env:TMP = $env:TEMP
$env:PIP_CACHE_DIR = Join-Path $repoRoot '.cache\pip'
Push-Location $repoRoot
try {
    & .venv/Scripts/python.exe -B -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Local rule or adapter tests failed.' }
} finally { Pop-Location }
