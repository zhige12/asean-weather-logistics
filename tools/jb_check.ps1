$procs = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match 'idea|pycharm|studio|clion|goland|webstorm|rider|datagrip|phpstorm|rubymine|appcode|fleet|fsnotifier|jetbrains' }
if ($procs) {
    $procs | Select-Object ProcessName, Id | Format-Table -AutoSize | Out-String -Width 120
    Write-Output "IDE_RUNNING"
} else {
    Write-Output "No JetBrains IDE running. OK to migrate."
}
