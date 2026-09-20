$usf = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
Write-Output "=== Current redirection (User Shell Folders) ==="
foreach ($name in @('{374DE290-123F-4565-9164-39C4925E467B}','{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}','{FDD39AD0-238F-46AF-ADB4-6C85480369C7}','{33E28130-4E1E-4676-835A-98395C3BC3BB}','{18989B1D-99B5-455B-841C-AB7C74E4DDFC}','{4BD8D571-6D19-48D3-BE97-422220080E43}')) {
    $v = (Get-ItemProperty -Path $usf -Name $name -ErrorAction SilentlyContinue).$name
    if ($v) { Write-Output ("  " + $v) }
}
Write-Output ""
Write-Output "=== D: user folders exist? ==="
Get-ChildItem 'D:\Users\Administrator' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ("  D:\Users\Administrator\" + $_.Name) }
Write-Output ""
Write-Output "=== C: free space ==="
Get-PSDrive C | ForEach-Object { Write-Output ("C: Free {0} GB" -f [math]::Round($_.Free/1GB,1)) }
Write-Output "=== D: free space ==="
Get-PSDrive D | ForEach-Object { Write-Output ("D: Free {0} GB" -f [math]::Round($_.Free/1GB,1)) }
