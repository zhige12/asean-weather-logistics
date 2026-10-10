# Render the architecture figure to a PNG with a headless browser so it can be dropped
# straight into Word/PPT. Kept ASCII-only: PowerShell 5.1 parses a BOM-less .ps1 as ANSI,
# which would mangle any Chinese literal here, so file names are discovered instead.
#   usage: tools/_arch_shot.ps1          (default 08-*.html, 1560x1000)
#          tools/_arch_shot.ps1 -H 900   (tighten height when there is blank space)
#          tools/_arch_shot.ps1 -Pattern '09-*.html' -H 2400
param(
  [int]$W = 1560,
  [int]$H = 1000,
  [double]$Scale = 2,
  [string]$Pattern = '08-*.html'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# Project root is the parent of this script's folder, so the figure pipeline works on any
# machine without hard-coding one dev's checkout path.
$root = Split-Path -Parent $PSScriptRoot
$html = Get-ChildItem -LiteralPath $root -Recurse -Depth 2 -Filter $Pattern |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $html) { throw ('figure source ' + $Pattern + ' not found') }
Write-Output ('  source : ' + $html.FullName)
$uri = (New-Object Uri($html.FullName)).AbsoluteUri

$png = [IO.Path]::ChangeExtension($html.FullName, '.png')
# Edge is flaky about writing into a non-ASCII directory, and the deliverables folder is
# Chinese-named, so rasterize into an ASCII path first and copy afterwards.
$tmpDir = Join-Path $env:TEMP 'arch_shot'
if (-not (Test-Path -LiteralPath $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null }
$tmpPng = Join-Path $tmpDir 'arch_shot.png'
foreach ($f in @($png, $tmpPng)) { if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force } }

$cand = @(
  'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
  'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
  'C:\Program Files\Google\Chrome\Application\chrome.exe',
  'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
)
$br = $cand | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $br) { throw 'no Edge/Chrome found - cannot rasterize' }
Write-Output ('  browser: ' + $br)

function Shoot($mode, $out) {
  $flags = @(
    $mode, '--disable-gpu', '--no-first-run', '--disable-extensions',
    # Edge paints Chinese spellcheck squiggles under static page text in this build, and they
    # end up inside the exported figure - not acceptable for a document-ready image.
    '--disable-features=Spellcheck,TextPrediction', '--disable-spellcheck',
    '--hide-scrollbars', '--default-background-color=FFFFFFFF',
    ('--force-device-scale-factor=' + $Scale),
    ('--user-data-dir=' + (Join-Path $env:TEMP ('archshot-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)))),
    ('--window-size=' + $W + ',' + $H),
    '--virtual-time-budget=10000',
    ('--screenshot=' + $out),
    $uri
  )
  $errF = Join-Path $tmpDir 'arch_shot_err.txt'
  if (Test-Path -LiteralPath $errF) { Remove-Item -LiteralPath $errF -Force }
  $p = Start-Process -FilePath $br -ArgumentList $flags -PassThru -WindowStyle Hidden `
        -RedirectStandardError $errF
  $p | Wait-Process -Timeout 90 -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  if (!$p.HasExited) { $p | Stop-Process -Force; Start-Sleep -Seconds 1 }
  if (Test-Path -LiteralPath $out) { return $true }
  Write-Output ('  [' + $mode + '] stderr:')
  if (Test-Path -LiteralPath $errF) {
    foreach ($l in @([IO.File]::ReadAllLines($errF))) { if ($l.Trim().Length -gt 0) { Write-Output ('    ' + $l.Trim()) } }
  } else { Write-Output '    (empty)' }
  return $false
}

$ok = Shoot '--headless=new' $tmpPng
if (-not $ok) { $ok = Shoot '--headless' $tmpPng }
if (-not $ok) { throw 'headless screenshot failed under both --headless=new and --headless' }
Copy-Item -LiteralPath $tmpPng -Destination $png -Force

if (-not (Test-Path -LiteralPath $png)) { throw 'copied screenshot is missing' }
$i = Get-Item -LiteralPath $png
Write-Output ('  PNG    : ' + $i.FullName)
Write-Output ('         ' + [math]::Round($i.Length / 1KB, 1) + ' KB   pixels ' + $W + 'x' + $H + ' @' + $Scale + 'x')

# Report where the drawing actually ends so the window height can be tightened.
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($png)
$last = 0
for ($y = $bmp.Height - 1; $y -gt 0; $y -= 4) {
  for ($x = 0; $x -lt $bmp.Width; $x += 17) {
    $c = $bmp.GetPixel($x, $y)
    if (-not ($c.R -ge 250 -and $c.G -ge 250 -and $c.B -ge 250)) { $last = $y; $y = 0; break }
  }
}
$cssLast = [math]::Ceiling($last / $Scale)
$bmp.Dispose()
Write-Output ('  content ends at y=' + $cssLast + ' css px  ->  re-run with -H ' + ([math]::Ceiling($cssLast / 10) * 10 + 10))
