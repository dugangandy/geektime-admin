@echo off
setlocal EnableExtensions
rem  Pure ASCII on purpose. cmd.exe decodes .bat with the OEM code page
rem  (GBK on zh-CN); UTF-8 bytes here would shift the parse of the whole block.
rem  A non-ASCII course folder goes into data-dir.txt (UTF-8), read by serve.py.
cd /d "%~dp0"
set "ROOT=%~dp0"

rem  Ports and remote target: needed by both run and stop modes.
set "PORT=8888"
set "RPORT=8878"
set "PUBLIC=http://139.198.29.121:8878/"
set "REMOTE=root@139.198.29.121"
set "SSHOPT=-o BatchMode=yes -o ConnectTimeout=8"
set "PING=%SystemRoot%\System32\ping.exe"
set "CURL=%SystemRoot%\System32\curl.exe"

if /i "%~1"=="stop" goto STOP

rem  Course folder: keep the path in data-dir.txt (UTF-8, one line) next to
rem  this script, or set GK_DATA_DIR. Nothing here, so this file stays ASCII.
rem  serve.py resolves it: --data-dir > GK_DATA_DIR > data-dir.txt > this folder.
set "DATAOPT="
if defined GK_DATA_DIR set DATAOPT=--data-dir "%GK_DATA_DIR%"

echo.
echo   ==== geekbang offline reader ====

rem 0) auth.json (password hash) is mandatory
if not exist "%ROOT%auth.json" (
  echo   [auth] auth.json not found. Set the access password first:
  echo     python "%ROOT%serve.py" --set-password
  echo.
  pause
  exit /b 1
)

set "STARTED_HTTP=0"
set "STARTED_SSH=0"

rem 1) Local server, started in THIS console (start /b = no extra window).
rem    Never start a duplicate: Windows SO_REUSEADDR lets several python
rem    processes bind 127.0.0.1:8888 and split requests.
netstat -ano | findstr /c:"127.0.0.1:%PORT%" | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  start "" /b python "%ROOT%serve.py" %PORT% . --bind 127.0.0.1 %DATAOPT%
  set "STARTED_HTTP=1"
  echo   [http] starting local server in this window ...
  "%PING%" -n 3 127.0.0.1 >nul
) else (
  echo   [http] 127.0.0.1:%PORT% already listening - reused from another session
)

netstat -ano | findstr /c:"127.0.0.1:%PORT%" | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  echo   [ERROR] local server did not start. Check the output above:
  echo           course folder path in data-dir.txt / GK_DATA_DIR, port %PORT%,
  echo           or stop leftover processes with: %~nx0 stop
  pause
  exit /b 1
)

rem 2) If the public side already answers, leave the healthy tunnel alone.
rem    fuser -k would otherwise kill the very session we are using.
if exist "%CURL%" (
  "%CURL%" -s -o nul -m 5 %PUBLIC% >nul 2>&1
  if not errorlevel 1 (
    echo   [ssh ] public %RPORT% already reachable - tunnel untouched
    goto READY
  )
)

rem 3) Rebuild the tunnel, also in THIS console. fuser -k kills the stale
rem    sshd session; the port needs a moment to free, so wait, then verify.
rem    Use absolute ping.exe: GNU timeout from Git Bash shadows "timeout /t".
set /a TRIES=0
:RETRY
set /a TRIES+=1
echo   [ssh ] cleaning stale listener on remote:%RPORT% ...
ssh %SSHOPT% %REMOTE% "fuser -k %RPORT%/tcp 2>/dev/null; echo cleaned"
"%PING%" -n 4 127.0.0.1 >nul
start "" /b ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -R %RPORT%:127.0.0.1:%PORT% %REMOTE%
set "STARTED_SSH=1"
"%PING%" -n 4 127.0.0.1 >nul
ssh %SSHOPT% %REMOTE% "ss -tln | grep -q ':%RPORT%'" >nul 2>&1
if not errorlevel 1 goto READY
if %TRIES% LSS 3 (
  echo   [ssh ] bind failed, retry %TRIES%/3 ...
  goto RETRY
)
echo   [ERROR] remote:%RPORT% still has no listener after 3 attempts.
echo           Check the SSH output above, ssh keys and remote GatewayPorts yes.
if "%STARTED_HTTP%"=="1" call :kill_http
if "%STARTED_SSH%"=="1" call :kill_ssh
pause
exit /b 1

:READY
echo.
echo   OK   %PUBLIC%
if "%STARTED_HTTP%%STARTED_SSH%"=="00" (
  echo   Nothing was started by this window: the running services belong to
  echo   another session. Run "%~nx0 stop" to force-stop them.
  echo.
  pause
  exit /b 0
)
echo   Keep this window open while using the site.
if "%STARTED_HTTP%"=="0" echo   [note] local server left running, started by another session
if "%STARTED_SSH%"=="0" echo   [note] ssh tunnel left running, started by another session
echo   Press any key to stop what this window started.
echo.
pause >nul
echo.
call :kill_http
call :kill_ssh
echo   stopped.
"%PING%" -n 2 127.0.0.1 >nul
exit /b 0

rem ------------------------------------------------------------------ stop
:STOP
echo.
echo   ==== stopping geekbang offline reader ====
call :kill_http
call :kill_ssh
echo   done.
exit /b 0

rem  Kill the python listener on %PORT% only (never another program).
:kill_http
set "FOUND=0"
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /c:":%PORT%" ^| findstr /c:"LISTENING"') do (
  tasklist /fi "PID eq %%P" 2>nul | findstr /i "python" >nul
  if not errorlevel 1 (
    taskkill /f /pid %%P >nul 2>&1
    if not errorlevel 1 (
      echo   [http] stopped pid %%P
      set "FOUND=1"
    )
  )
)
if "%FOUND%"=="0" echo   [http] no python listener on port %PORT%
exit /b 0

rem  Kill only ssh processes forwarding %RPORT% -> 127.0.0.1:%PORT%.
:kill_ssh
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process -Filter 'Name=''ssh.exe''' | Where-Object { $_.CommandLine -like '*-R %RPORT%:127.0.0.1:%PORT%*' }; if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host ('  [ssh ] stopped pid ' + $_.ProcessId) } } else { Write-Host '  [ssh ] no matching tunnel process' }"
exit /b 0
