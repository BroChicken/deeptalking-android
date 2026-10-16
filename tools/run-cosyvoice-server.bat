@echo off
rem ============================================================
rem  One-click launcher for the PC-side cosyvoice-server that backs
rem  the phone's "remote server" read-aloud engine (docs/REMOTE_TTS.md).
rem
rem    run-cosyvoice-server.bat            API mode, CUDA  (the phone connects here)
rem    run-cosyvoice-server.bat webui      WebUI mode      (register / extract voices)
rem    run-cosyvoice-server.bat stop       stop a running server
rem    run-cosyvoice-server.bat restart    stop, then start again (fresh bind address)
rem    run-cosyvoice-server.bat status     where is a running server listening?
rem    run-cosyvoice-server.bat diag       network / token / firewall report
rem
rem  Anything else is passed straight through to the PowerShell launcher, e.g.
rem    run-cosyvoice-server.bat -Api -Bind any -Port 9000
rem
rem  The access token is read from tools\tts-token.txt (git-ignored) and must be
rem  entered in the app under "access token" as well.
rem
rem  Every other setting lives in tools/.work/cosyvoice-server/run-server.ps1
rem  (bin/, models/, voices/ are its siblings). This file only picks the mode
rem  and keeps the window open on failure.
rem  Keep this file ASCII-only (Windows console codepage).
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
  echo     If it says NOTHING, the model is probably still loading - wait a bit,
  echo     or run "run-cosyvoice-server.bat diag" if it stays that way.
  goto :done
)

echo.
echo Starting API mode ^(CUDA^). The model load takes ~30-90s.
echo The address for the phone is printed as "PHONE -^> http://..." below;
echo the access token is in tools\tts-token.txt (same value goes in the app).
echo Press Ctrl+C in this window to stop the server.
echo.

%PS% -Api -OpenFirewall

:done
echo.
echo [launcher exited, code %ERRORLEVEL%]
pause
