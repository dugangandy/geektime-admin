@echo off
setlocal EnableExtensions
rem  Pure ASCII on purpose. cmd.exe decodes .bat with the OEM code page
rem  (GBK on zh-CN); UTF-8 Chinese comments get mis-parsed and can even be
rem  executed as commands. Keep every line in this file ASCII.
cd /d "%~dp0"
set "ROOT=%~dp0"

echo.
echo   ==== geekbang offline reader (LAN mode) ====
echo.

if not exist "%ROOT%auth.json" (
  echo   [auth] auth.json not found. Set the access password first:
  echo     python "%ROOT%serve.py" --set-password
  echo.
  pause
  exit /b 1
)

rem Print this machine's IPv4 addresses so the phone can reach the right one.
echo   [net ] local IPv4 addresses:
for /f "tokens=2 delims=:" %%A in ('ipconfig ^| findstr /c:"IPv4"') do (
  for /f "tokens=* delims= " %%B in ("%%A") do echo         http://%%B:8888
)
echo.
echo   [note] Windows Firewall must allow inbound TCP 8888 for python.exe.
echo          Phone must be on the same Wi-Fi / LAN as this PC.
echo.

netstat -ano | findstr /c:":8888" | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  echo   [http] starting server on 0.0.0.0:8888 ...
  start "gk-http-lan" python "%ROOT%serve.py" 8888 . --bind 0.0.0.0
) else (
  echo   [http] port 8888 already listening - close it first if it is bound to 127.0.0.1
  pause
)

echo.
echo   Close the gk-http-lan window to stop.
echo.
pause
