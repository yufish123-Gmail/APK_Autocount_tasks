param([string]$Serial = 'emulator-5580', [int]$AdbPort = 15537)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only a local emulator is permitted by this test script.' }
$adb = Join-Path $repoRoot '.tooling\sdk\platform-tools\adb.exe'
$prefix = @('-P',"$AdbPort",'-s',$Serial)
$emulated = & $adb @prefix shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or "$emulated".Trim() -ne '1') { throw 'The selected local emulator is not ready.' }
& $adb @prefix install -r (Join-Path $repoRoot 'android/app/build/outputs/apk/debug/app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
& $adb @prefix install -r (Join-Path $repoRoot 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
$service='com.yufish.autocount/com.yufish.autocount.AutomationService'
$existing=(& $adb @prefix shell settings get secure enabled_accessibility_services).Trim()
$services=if ($existing -eq 'null' -or -not $existing) { @() } else { @($existing -split ':') }
if ($service -notin $services) { $services += $service }
& $adb @prefix shell settings put secure enabled_accessibility_services ($services -join ':')
& $adb @prefix shell settings put secure accessibility_enabled 1
& $adb @prefix shell settings put system screen_off_timeout 2147483647
& $adb @prefix shell wm size 1080x2400
& $adb @prefix shell wm dismiss-keyguard
$output = & $adb @prefix shell am instrument -w 'com.yufish.autocount.test/androidx.test.runner.AndroidJUnitRunner'
$output
if ($LASTEXITCODE -ne 0 -or ($output -join "\n") -notmatch 'OK \(1 test\)') { throw 'Local emulator smoke test failed.' }
