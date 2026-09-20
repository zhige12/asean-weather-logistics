$ErrorActionPreference = 'Continue'

Write-Output "=== Test conda run -n drl (by NAME) ==="
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' run -n drl python -c "import sys,torch,numpy,gymnasium;print('python',sys.version.split()[0]);print('torch',torch.__version__,'| cuda',torch.cuda.is_available(),'| gpu',torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'N/A');print('numpy',numpy.__version__,'| gymnasium',gymnasium.__version__)" 2>&1

Write-Output ""
Write-Output "=== Set HF_HOME user env var to D:\DL-cache ==="
New-Item -ItemType Directory -Path 'D:\DL-cache\huggingface' -Force | Out-Null
[Environment]::SetEnvironmentVariable('HF_HOME','D:\DL-cache\huggingface','User')
Write-Output ("  HF_HOME set: " + [Environment]::GetEnvironmentVariable('HF_HOME','User'))

Write-Output ""
Write-Output "=== Disk now ==="
Get-PSDrive C,D | ForEach-Object { Write-Output ("  {0}: Used {1} GB / Free {2} GB" -f $_.Name, [math]::Round($_.Used/1GB,1), [math]::Round($_.Free/1GB,1)) }
Write-Output "  C pkgs remaining: " + (Get-ChildItem 'C:\Users\Administrator\miniconda3\pkgs' -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count + " files"
