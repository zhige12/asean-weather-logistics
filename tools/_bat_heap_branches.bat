@echo off
rem Same three lines as asean-demo\start.bat, walked through every branch by hand.
setlocal enabledelayedexpansion

set "RAM=8"
set "HEAP=1g"
if %RAM% GEQ 8 set "HEAP=2g"
if %RAM% GEQ 16 set "HEAP=3g"
echo RAM=8   -> HEAP=%HEAP%

set "RAM=16"
set "HEAP=1g"
if %RAM% GEQ 8 set "HEAP=2g"
if %RAM% GEQ 16 set "HEAP=3g"
echo RAM=16  -> HEAP=%HEAP%

set "RAM=4"
set "HEAP=1g"
if %RAM% GEQ 8 set "HEAP=2g"
if %RAM% GEQ 16 set "HEAP=3g"
echo RAM=4   -> HEAP=%HEAP%

set "RAM=0"
set "HEAP=1g"
if %RAM% GEQ 8 set "HEAP=2g"
if %RAM% GEQ 16 set "HEAP=3g"
echo RAM=0   -> HEAP=%HEAP%   (powershell unreadable, the safe floor)
