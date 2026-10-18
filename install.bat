@echo off
REM Double-click to install the test app to every connected, authorized device (USB or wireless debugging).
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk
"%ADB%" devices -l
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
  if "%%b"=="device" (
    echo Installing to %%a ...
    "%ADB%" -s %%a install -r "%APK%"
    "%ADB%" -s %%a shell am start -n tw.marco.caraudio.stage0/.MainActivity
  )
  if "%%b"=="unauthorized" echo %%a is UNAUTHORIZED - tap "Allow" on the device screen, then run again.
)
pause
