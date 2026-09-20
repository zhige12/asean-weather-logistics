$ErrorActionPreference = 'Continue'

function Get-SizeGB($p) {
    if (Test-Path $p) {
        $s = (Get-ChildItem $p -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        return [math]::Round($s/1GB,2)
    }
    return 0
}

$targets = @(
    'C:\ProgramData\NVIDIA Corporation\NVIDIA App\UpdateFramework',
    'C:\Users\Administrator\AppData\Local\NVIDIA\DXCache'
)

foreach ($t in $targets) {
    if (Test-Path $t) {
        $before = Get-SizeGB $t
        Write-Output ("Deleting: {0}  ({1} GB)" -f $t, $before)
        try {
            Remove-Item $t -Recurse -Force -ErrorAction Stop
            Write-Output ("  -> deleted OK")
        } catch {
            Write-Output ("  -> FAILED: {0}" -f $_.Exception.Message)
        }
    } else {
        Write-Output ("Already gone: {0}" -f $t)
    }
}

Write-Output ""
Write-Output "=== C: free space after ==="
Get-PSDrive C | ForEach-Object { Write-Output ("C: Free {0} GB" -f [math]::Round($_.Free/1GB,1)) }
