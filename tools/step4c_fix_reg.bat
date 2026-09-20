@echo off
setlocal
set "USF=HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders"
set "SF=HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders"
set "BASE=D:\Users\Administrator"

reg add "%USF%" /v "{374DE290-123F-4565-9164-39C4925E467B}" /t REG_EXPAND_SZ /d "%BASE%\Downloads" /f
reg add "%USF%" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_EXPAND_SZ /d "%BASE%\Desktop" /f
reg add "%USF%" /v "{FDD39AD0-238F-46AF-ADB4-6C85480369C7}" /t REG_EXPAND_SZ /d "%BASE%\Documents" /f
reg add "%USF%" /v "{33E28130-4E1E-4676-835A-98395C3BC3BB}" /t REG_EXPAND_SZ /d "%BASE%\Pictures" /f
reg add "%USF%" /v "{18989B1D-99B5-455B-841C-AB7C74E4DDFC}" /t REG_EXPAND_SZ /d "%BASE%\Videos" /f
reg add "%USF%" /v "{4BD8D571-6D19-48D3-BE97-422220080E43}" /t REG_EXPAND_SZ /d "%BASE%\Music" /f

reg add "%SF%" /v "{374DE290-123F-4565-9164-39C4925E467B}" /t REG_SZ /d "%BASE%\Downloads" /f
reg add "%SF%" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_SZ /d "%BASE%\Desktop" /f
reg add "%SF%" /v "{FDD39AD0-238F-46AF-ADB4-6C85480369C7}" /t REG_SZ /d "%BASE%\Documents" /f
reg add "%SF%" /v "{33E28130-4E1E-4676-835A-98395C3BC3BB}" /t REG_SZ /d "%BASE%\Pictures" /f
reg add "%SF%" /v "{18989B1D-99B5-455B-841C-AB7C74E4DDFC}" /t REG_SZ /d "%BASE%\Videos" /f
reg add "%SF%" /v "{4BD8D571-6D19-48D3-BE97-422220080E43}" /t REG_SZ /d "%BASE%\Music" /f

echo.
echo === Verify User Shell Folders ===
reg query "%USF%"
echo.
echo === Verify Shell Folders ===
reg query "%SF%"
