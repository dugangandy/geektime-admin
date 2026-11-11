@echo off
setlocal EnableExtensions
rem  Pure ASCII on purpose. cmd.exe decodes .bat with the OEM code page
rem  (GBK on zh-CN); UTF-8 Chinese comments get mis-parsed and can even be
rem  executed as commands. Keep every line in this file ASCII.
cd /d "%~dp0"
set "ROOT=%~dp0"

rem  Optional: folder holding the per-course subdirectories.
rem  Leave empty to use this script's folder. Env var GK_DATA_DIR overrides.
rem  Example: set "DATA_DIR=D:\GeekTime Courses"
set "DATA_DIR="
if defined GK_DATA_DIR set "DATA_DIR=%GK_DATA_DIR%"
if not "%DATA_DIR%"=="" if not exist "%DATA_DIR%\" (
  echo   [ERROR] course data folder not found: %DATA_DIR%
  pause
  exit /b 1
)
set "DATAOPT="
if not "%DATA_DIR%"=="" set DATAOPT=--data-dir "%DATA_DIR%"

set "REMOTE=root@139.198.29.121"
set "SSHOPT=-o BatchMode=yes -o ConnectTimeout=8"
set "PING=%SystemRoot%\System32\ping.exe"
set "CURL=%SystemRoot%\System32\curl.exe"

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

rem 1) Local server. Never start a duplicate: Windows SO_REUSEADDR lets
rem    several python processes bind 127.0.0.1:8888 and split requests.
netstat -ano | findstr /c:"127.0.0.1:8888" | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  start "gk-http" /min python "%ROOT%serve.py" 8888 . --bind 127.0.0.1 %DATAOPT%
  echo   [http] starting local server ...
  "%PING%" -n 3 127.0.0.1 >nul
) else (
  echo   [http] 127.0.0.1:8888 already listening - reused
)

netstat -ano | findstr /c:"127.0.0.1:8888" | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  echo   [ERROR] local server did not start. Look at the gk-http window.
  pause
  exit /b 1
)

rem 2) If the public side already answers, leave the healthy tunnel alone.
rem    fuser -k would otherwise kill the very session we are using.
if exist "%CURL%" (
  "%CURL%" -s -o nul -m 5 http://139.198.29.121:8888/ >nul 2>&1
  if not errorlevel 1 (
    echo   [ssh ] public 8888 already reachable - tunnel untouched
    goto READY
  )
)

rem 3) Rebuild the tunnel. fuser -k kills the stale sshd session; the port
rem    needs a moment to free, so wait, then verify the new listener.
rem    Use absolute ping.exe: GNU timeout from Git Bash shadows "timeout /t".
set /a TRIES=0
:RETRY
set /a TRIES+=1
echo   [ssh ] cleaning stale listener on remote:8888 ...
ssh %SSHOPT% %REMOTE% "fuser -k 8888/tcp 2>/dev/null; echo cleaned"
"%PING%" -n 4 127.0.0.1 >nul
start "gk-ssh" /min ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -R 8888:127.0.0.1:8888 %REMOTE%
"%PING%" -n 4 127.0.0.1 >nul
ssh %SSHOPT% %REMOTE% "ss -tln | grep -q ':8888'" >nul 2>&1
if not errorlevel 1 goto READY
if %TRIES% LSS 3 (
  echo   [ssh ] bind failed, retry %TRIES%/3 ...
  goto RETRY
)
echo   [ERROR] remote:8888 still has no listener after 3 attempts.
echo           Check the gk-ssh window, ssh keys and remote GatewayPorts yes.
pause
exit /b 1

:READY
echo.
echo   OK   http://139.198.29.121:8888/
echo   Close the minimized gk-http / gk-ssh windows to stop.
echo.
pause
