@echo off
rem ============================================================
rem  One-click launcher for the PC-side cosyvoice-server that backs
rem  the phone's "remote server" read-aloud engine (docs/REMOTE_TTS.md).
rem
rem    run-cosyvoice-server.bat            API mode, CUDA  (the phone connects here)
rem    run-cosyvoice-server.bat webui      WebUI mode      (register / extract voices)
rem    run-cosyvoice-server.bat usb        API mode, bound to the USB-tethering link
rem    run-cosyvoice-server.bat bt         API mode, bound to the Bluetooth PAN link
rem    run-cosyvoice-server.bat hotspot    API mode, bound to the phone hotspot (learns its SSID)
rem    run-cosyvoice-server.bat local      API mode on 127.0.0.1 only (this PC, nothing exposed)
rem    run-cosyvoice-server.bat stop       stop a running server
rem    run-cosyvoice-server.bat restart    stop, then start again (fresh bind address)
rem    run-cosyvoice-server.bat status     where is a running server listening?
rem    run-cosyvoice-server.bat diag       network / token / firewall report
rem
rem  Anything else is passed straight through to the PowerShell launcher, e.g.
rem    run-cosyvoice-server.bat -Api -Bind any -Port 9000
rem
rem  The access token is read from tools\tts-token.txt (git-ignored) and must be
rem  entered in the app under "access token" as well. The link the launcher
rem  remembers lives in tools\tts-link.txt (also git-ignored).
rem
rem  The server binds ONE phone-link address, never 0.0.0.0: a port scan from any
rem  other network this PC is on (campus Wi-Fi, ...) cannot even tell that the port
rem  is open. If the phone link is not up yet the launcher waits for it (Ctrl+C gives
rem  up). While it runs, a brief blip that comes back on the same address keeps the
rem  server as it is; only a real address change makes it restart.
rem
rem  Every other setting lives in tools/.work/cosyvoice-server/run-server.ps1
rem  (bin/, models/, voices/ are its siblings). Keep this file ASCII-only.
rem ============================================================
setlocal
cd /d "%~dp0"

set "WORK=%~dp0.work\cosyvoice-server"
set "PS=powershell -NoProfile -ExecutionPolicy Bypass -File "%WORK%\run-server.ps1""

if not exist "%WORK%\run-server.ps1" (
  echo.
  echo [x] Not found: %WORK%\run-server.ps1
  echo     The PC-side server lives outside git. See docs/REMOTE_TTS.md
  echo     for building cosyvoice-server and laying out bin\ models\ voices\.
  goto :done
)

if /i "%~1"=="stop"    ( %PS% -Stop & goto :done )
if /i "%~1"=="status"  ( %PS% -Status & goto :done )
if /i "%~1"=="diag"    ( %PS% -Diag & goto :done )
if /i "%~1"=="webui"   ( %PS% & goto :done )
if /i "%~1"=="usb"     ( %PS% -Api -Bind usb -OpenFirewall & goto :done )
if /i "%~1"=="bt"      ( %PS% -Api -Bind bt -OpenFirewall & goto :done )
if /i "%~1"=="hotspot" ( %PS% -Api -Bind hotspot -OpenFirewall & goto :done )
if /i "%~1"=="local"   ( %PS% -Api -Bind local & goto :done )
if /i "%~1"=="restart" ( %PS% -Stop & %PS% -Api -OpenFirewall & goto :done )

if not "%~1"=="" ( %PS% %* & goto :done )

rem ---------- default: API mode on the CUDA build ----------
tasklist /FI "IMAGENAME eq cosyvoice-server.exe" 2>nul | find /i "cosyvoice-server.exe" >nul
if not errorlevel 1 (
  echo.
  echo [!] cosyvoice-server is ALREADY running. Where it listens right now:
  echo.
  %PS% -Status
  echo.
  echo     If that address says STALE above, the phone cannot reach it - run:
  echo         run-cosyvoice-server.bat restart
  goto :done
)

echo.
echo Starting API mode ^(CUDA^). The model load takes ~30-90s.
echo Bound to the phone link only. If that link is not up yet the launcher waits for
echo it (plug the cable / enable tethering / join the hotspot; Ctrl+C gives up).
echo The URL for the phone is printed as "PHONE -^> http://..." below; the access
echo token is in tools\tts-token.txt. Ctrl+C in this window stops the server.
echo.

%PS% -Api -OpenFirewall

:done
echo.
echo [launcher exited, code %ERRORLEVEL%]
pause
