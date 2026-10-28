$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$scp = 'D:\Git\usr\bin\scp.exe'
$key = 'D:\Temp\asean_key.pem'
$root = 'D:\idea project\asean-weather-logistics'
$jar = Join-Path $root 'build\libs\asean-weather-logistics-0.0.1-SNAPSHOT.jar'
$dist = Join-Path $root 'frontend\dist'
$tar = 'D:\Temp\dist.tar.gz'

# 0) sanity: confirm the new jar really carries the riskCleared backend change
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead($jar)
$e = $z.Entries | Where-Object { $_.FullName -like '*OutreachService.class' } | Select-Object -First 1
if ($e) {
  $s = $e.Open(); $ms = New-Object IO.MemoryStream; $s.CopyTo($ms); $s.Close()
  $txt = [Text.Encoding]::UTF8.GetString($ms.ToArray()); $ms.Dispose()
  Write-Output ('  jar OutreachService.class carries riskCleared: ' + $txt.Contains('riskCleared'))
} else { Write-Output '  !! OutreachService.class not found in jar' }
$z.Dispose()
Write-Output ('  local jar size = ' + [math]::Round((Get-Item $jar).Length / 1MB, 2) + ' MB')

# 1) tar the dist contents (so we replace the whole SPA in one transfer)
if (Test-Path $tar) { Remove-Item $tar -Force }
& tar -czf $tar -C $dist .
Write-Output ('  tar exit=' + $LASTEXITCODE + '  dist.tar.gz = ' + [math]::Round((Get-Item $tar).Length / 1MB, 2) + ' MB')

# 2) upload jar to a staging name (do NOT overwrite the live one yet)
Write-Output '  scp jar -> /opt/asean/app.jar.new ...'
& $scp -i $key -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 $jar 'root@8.138.229.14:/opt/asean/app.jar.new'
Write-Output ('  scp jar exit=' + $LASTEXITCODE)

# 3) upload dist tarball
Write-Output '  scp dist -> /tmp/dist.tar.gz ...'
& $scp -i $key -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 $tar 'root@8.138.229.14:/tmp/dist.tar.gz'
Write-Output ('  scp dist exit=' + $LASTEXITCODE)
