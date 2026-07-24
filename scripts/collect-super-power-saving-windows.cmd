@echo off
setlocal EnableExtensions

set "ZAPRET_PKG=io.github.zapretkvn.android"
set "ZAPRET_IDLE_SECONDS=300"
set "ADB=adb.exe"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"

if exist "%~dp0adb.exe" goto adb_ready
where adb.exe >nul 2>&1
if not errorlevel 1 goto adb_ready

echo ERROR: adb.exe was not found.
echo Extract this script into the Android platform-tools directory and run it there.
pause
exit /b 1

:adb_ready
"%ADB%" get-state >nul 2>&1
if not errorlevel 1 goto device_ready

echo ERROR: no authorized Android device is available through ADB.
"%ADB%" devices -l
pause
exit /b 1

:device_ready
for /f %%T in ('powershell.exe -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "ZAPRET_STAMP=%%T"
set "ZAPRET_EVIDENCE=%USERPROFILE%\Desktop\zapret-super-power-saving-%ZAPRET_STAMP%"
set "ZAPRET_ARCHIVE=%ZAPRET_EVIDENCE%.zip"
mkdir "%ZAPRET_EVIDENCE%" >nul 2>&1

"%ADB%" shell pm path %ZAPRET_PKG% > "%ZAPRET_EVIDENCE%\package-path.txt" 2>&1
findstr /b /c:"package:" "%ZAPRET_EVIDENCE%\package-path.txt" >nul
if not errorlevel 1 goto package_ready

echo ERROR: Zapret KVN release package is not installed.
type "%ZAPRET_EVIDENCE%\package-path.txt"
pause
exit /b 1

:package_ready
"%ADB%" devices -l > "%ZAPRET_EVIDENCE%\devices.txt" 2>&1
"%ADB%" shell dumpsys package %ZAPRET_PKG% > "%ZAPRET_EVIDENCE%\package.txt" 2>&1
findstr /i "versionCode versionName userId" "%ZAPRET_EVIDENCE%\package.txt" > "%ZAPRET_EVIDENCE%\installed-version.txt"
"%ADB%" shell getprop ro.build.version.release > "%ZAPRET_EVIDENCE%\android-release.txt"
"%ADB%" shell getprop ro.build.version.sdk > "%ZAPRET_EVIDENCE%\android-api.txt"
"%ADB%" shell getprop ro.product.cpu.abi > "%ZAPRET_EVIDENCE%\android-abi.txt"
"%ADB%" shell getprop ro.product.manufacturer > "%ZAPRET_EVIDENCE%\manufacturer.txt"
"%ADB%" shell getprop ro.product.model > "%ZAPRET_EVIDENCE%\model.txt"
"%ADB%" shell getprop ro.build.fingerprint > "%ZAPRET_EVIDENCE%\build-fingerprint.txt"
"%ADB%" shell settings get global private_dns_mode > "%ZAPRET_EVIDENCE%\private-dns-mode.txt" 2>&1
"%ADB%" shell settings get global private_dns_specifier > "%ZAPRET_EVIDENCE%\private-dns-specifier.txt" 2>&1
"%ADB%" shell dumpsys -l > "%ZAPRET_EVIDENCE%\dumpsys-services.txt" 2>&1
"%ADB%" shell dumpsys deviceidle whitelist > "%ZAPRET_EVIDENCE%\deviceidle-whitelist.txt" 2>&1
"%ADB%" shell dumpsys deviceidle except-idle-whitelist > "%ZAPRET_EVIDENCE%\deviceidle-except-idle-whitelist.txt" 2>&1
"%ADB%" shell cmd appops get %ZAPRET_PKG% > "%ZAPRET_EVIDENCE%\appops.txt" 2>&1
"%ADB%" logcat -c

(
  echo collector_version=1
  echo package=%ZAPRET_PKG%
  echo idle_seconds=%ZAPRET_IDLE_SECONDS%
  echo started=%DATE% %TIME%
  echo mutations=none
) > "%ZAPRET_EVIDENCE%\collector.txt"

echo.
echo Zapret KVN super power saving A/B collector
echo -------------------------------------------
type "%ZAPRET_EVIDENCE%\installed-version.txt"
echo.
echo The collector does not change VPN, battery, DNS, Wi-Fi, mobile data,
echo profiles, app data, or Android idle state. Keep USB debugging connected.
echo Each five-minute idle window uses a host-side timer and sends no ADB
echo commands to the phone.
echo.
echo Phase 1/2 - normal battery mode:
echo 1. Disable the phone's super/ultra power saving mode.
echo 2. Connect Zapret KVN and start a network audio/live stream in an app
echo    selected for VPN. Use content that normally keeps playing when locked.
echo 3. Return Home without stopping playback and lock the phone.
echo 4. Without touching the phone, press any key here.
pause >nul
call :capture_idle normal

echo.
echo Unlock the phone. Without opening or reconnecting Zapret KVN, check whether
echo playback survived and test the same site or stream in the same application.
set "ZAPRET_NORMAL_RESULT="
set /p "ZAPRET_NORMAL_RESULT=Type WORKS or STOPS-after-N-seconds, then press Enter: "
> "%ZAPRET_EVIDENCE%\normal-user-result.txt" echo %ZAPRET_NORMAL_RESULT%

echo.
echo Phase 2/2 - super/ultra power saving:
echo 1. Enable the phone's super/ultra power saving mode manually.
echo 2. Make sure Zapret KVN is still connected; reconnect it only if the mode
echo    explicitly disconnected it, and record that fact in the result below.
echo 3. Start the same network audio/live stream in the selected app, return
echo    Home without stopping playback, and lock the phone.
echo 4. Without touching the phone, press any key here.
pause >nul
call :capture_idle super-power-saving

echo.
echo Unlock the phone. Do not open Zapret KVN and do not reconnect it yet.
echo Check whether playback survived and test the same site or stream.
set "ZAPRET_SUPER_RESULT="
set /p "ZAPRET_SUPER_RESULT=Type WORKS or STOPS-after-N-seconds, then press Enter: "
> "%ZAPRET_EVIDENCE%\super-power-saving-user-result.txt" echo %ZAPRET_SUPER_RESULT%

echo.
echo If the super-power-saving attempt failed, open Zapret KVN without
echo force-stopping it, go to Settings - Diagnostics, and export the diagnostic.
echo Save or send that JSON separately. When the Sharesheet appears, press any
echo key here to finish the system capture.
pause >nul

"%ADB%" logcat -d -b main -b system -b crash -v threadtime > "%ZAPRET_EVIDENCE%\logcat.txt" 2>&1
"%ADB%" shell dumpsys connectivity > "%ZAPRET_EVIDENCE%\connectivity-final.txt" 2>&1
"%ADB%" shell dumpsys netd > "%ZAPRET_EVIDENCE%\netd-final.txt" 2>&1
"%ADB%" shell dumpsys activity exit-info %ZAPRET_PKG% > "%ZAPRET_EVIDENCE%\exit-info.txt" 2>&1
"%ADB%" shell dumpsys procstats %ZAPRET_PKG% > "%ZAPRET_EVIDENCE%\procstats-final.txt" 2>&1

powershell.exe -NoProfile -Command "Compress-Archive -Path ($env:ZAPRET_EVIDENCE + '\*') -DestinationPath $env:ZAPRET_ARCHIVE -Force"
if not errorlevel 1 goto archive_ready

echo ERROR: PowerShell could not create the ZIP archive.
pause
exit /b 1

:archive_ready
echo.
echo Evidence archive created:
echo %ZAPRET_ARCHIVE%
echo.
echo Send this ZIP and, on failure, the exported redacted diagnostic JSON.
echo Raw system evidence can contain network and device identifiers.
pause
exit /b 0

:capture_idle
set "PHASE=%~1"
set "PHASE_DIR=%ZAPRET_EVIDENCE%\%PHASE%"
mkdir "%PHASE_DIR%" >nul 2>&1

(
  echo phase=%PHASE%
  echo seconds=%ZAPRET_IDLE_SECONDS%
  echo started=%DATE% %TIME%
) > "%PHASE_DIR%\phase.txt"

"%ADB%" shell log -t ZapretPowerSaving "phase_start:%PHASE%" >nul 2>&1
call :snapshot before

echo Waiting %ZAPRET_IDLE_SECONDS% seconds. Do not touch the phone...
powershell.exe -NoProfile -Command "Start-Sleep -Seconds $env:ZAPRET_IDLE_SECONDS"

call :snapshot after
"%ADB%" shell log -t ZapretPowerSaving "phase_end:%PHASE%" >nul 2>&1
echo finished=%DATE% %TIME%>> "%PHASE_DIR%\phase.txt"
exit /b 0

:snapshot
set "SNAPSHOT=%~1"
"%ADB%" shell date > "%PHASE_DIR%\%SNAPSHOT%-device-time.txt" 2>&1
"%ADB%" shell pidof -s %ZAPRET_PKG% > "%PHASE_DIR%\%SNAPSHOT%-pid.txt" 2>&1
set "ZAPRET_PID="
set /p "ZAPRET_PID="<"%PHASE_DIR%\%SNAPSHOT%-pid.txt"
"%ADB%" shell dumpsys power > "%PHASE_DIR%\%SNAPSHOT%-power.txt" 2>&1
"%ADB%" shell dumpsys battery > "%PHASE_DIR%\%SNAPSHOT%-battery.txt" 2>&1
"%ADB%" shell cmd deviceidle get deep > "%PHASE_DIR%\%SNAPSHOT%-deviceidle-deep.txt" 2>&1
"%ADB%" shell cmd deviceidle get light > "%PHASE_DIR%\%SNAPSHOT%-deviceidle-light.txt" 2>&1
"%ADB%" shell dumpsys activity services %ZAPRET_PKG% > "%PHASE_DIR%\%SNAPSHOT%-activity-services.txt" 2>&1
"%ADB%" shell dumpsys connectivity > "%PHASE_DIR%\%SNAPSHOT%-connectivity.txt" 2>&1
"%ADB%" shell dumpsys netpolicy > "%PHASE_DIR%\%SNAPSHOT%-netpolicy.txt" 2>&1
"%ADB%" shell dumpsys meminfo %ZAPRET_PKG% > "%PHASE_DIR%\%SNAPSHOT%-meminfo.txt" 2>&1
"%ADB%" shell ip -s link > "%PHASE_DIR%\%SNAPSHOT%-network-links.txt" 2>&1
findstr /i /r /c:"^ *vpn *$" "%ZAPRET_EVIDENCE%\dumpsys-services.txt" >nul
if errorlevel 1 goto no_vpn_service
"%ADB%" shell dumpsys vpn > "%PHASE_DIR%\%SNAPSHOT%-vpn.txt" 2>&1
:no_vpn_service
findstr /i /r /c:"^ *vpn_management *$" "%ZAPRET_EVIDENCE%\dumpsys-services.txt" >nul
if errorlevel 1 goto no_vpn_management_service
"%ADB%" shell dumpsys vpn_management > "%PHASE_DIR%\%SNAPSHOT%-vpn-management.txt" 2>&1
:no_vpn_management_service
if not defined ZAPRET_PID exit /b 0
"%ADB%" shell cat /proc/%ZAPRET_PID%/stat > "%PHASE_DIR%\%SNAPSHOT%-proc-stat.txt" 2>&1
"%ADB%" shell cat /proc/%ZAPRET_PID%/status > "%PHASE_DIR%\%SNAPSHOT%-proc-status.txt" 2>&1
exit /b 0
