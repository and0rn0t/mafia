@echo off
setlocal

rem ===================================================================
rem  EDIT THIS ONCE: full path of your Skript scripts folder
rem  (no trailing backslash). Example:
rem  set "SCRIPTS_DIR=E:\server\mafia\plugins\Skript\scripts"
rem ===================================================================
set "SCRIPTS_DIR=C:\path\to\mafia-server\plugins\Skript\scripts"

rem This file must stay inside the cloned repository folder.
pushd "%~dp0"

if not exist ".git" (
	echo [ERROR] This is not a git clone. Keep this file inside the cloned repo folder.
	exit /b 1
)
if not exist "%SCRIPTS_DIR%\" (
	echo [ERROR] SCRIPTS_DIR not found: %SCRIPTS_DIR%
	exit /b 1
)

git pull --ff-only origin main >nul 2>&1
if errorlevel 1 (
	echo %date% %time% [ERROR] git pull failed >> sync.log
	exit /b 1
)

rem Copy only *.sk (other scripts in the folder, e.g. test files, are never touched or deleted).
rem robocopy exit code 1 = something was copied, 0 = nothing changed, 8 or more = error.
robocopy "." "%SCRIPTS_DIR%" *.sk /NJH /NJS /NDL /NP /R:1 /W:1 >nul
if %errorlevel% geq 8 (
	echo %date% %time% [ERROR] copy failed >> sync.log
	exit /b 1
)
if %errorlevel% geq 1 (
	echo %date% %time% updated - run /sk reload all >> sync.log
)
exit /b 0
