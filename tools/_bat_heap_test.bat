@echo off
rem Replicates ONLY the auto-detect lines from asean-demo\start.bat, to prove the
rem quoting / redirection / GEQ parsing actually works under cmd.exe before we ship it.
setlocal enabledelayedexpansion
set "RAM=0"
for /f "usebackq delims=" %%M in (`powershell -NoProfile -Command "[int]((Get-CimInstance -ClassName Win32_ComputerSystem).TotalPhysicalMemory/1GB)" 2^>nul`) do set "RAM=%%M"
set "HEAP=1g"
if %RAM% GEQ 8 set "HEAP=2g"
if %RAM% GEQ 16 set "HEAP=3g"
echo RAM=[%RAM%] HEAP=[%HEAP%]

rem negative test: simulate a machine where powershell is missing
set "RAM2=0"
for /f "usebackq delims=" %%M in (`definitely-not-a-command 2^>nul`) do set "RAM2=%%M"
echo FALLBACK_RAM=[%RAM2%]
