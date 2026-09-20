$p = Get-CimInstance Win32_Process -Filter "Name='python.exe'"
$using = $p | Where-Object { $_.ExecutablePath -like '*envs\drl*' }
if ($using) {
    $using | Select-Object ProcessId, ExecutablePath | Format-Table -AutoSize | Out-String -Width 200
    Write-Output "OCCUPIED"
} else {
    Write-Output "No process uses envs\drl. OK to remove."
}
