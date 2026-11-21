# Sony Camera Driver Installer — installs libusbK driver for Sony cameras
# Uses pnputil to install the Sony-provided INF driver. One UAC prompt.
# After install, the Sony SDK (Cr_Core.dll) can communicate with the camera.

param([string]$Action = "install")

$ErrorActionPreference = "Continue"

# Find the driver INF file
$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    $scriptDir = Split-Path $MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue
}

# Look for INF in driver subdirectories
$infFiles = @()
$amd64Dir = Join-Path $scriptDir "amd64"
$x86Dir = Join-Path $scriptDir "x86"
$driverRoot = Split-Path $scriptDir -ErrorAction SilentlyContinue
$amd64Dir2 = Join-Path $driverRoot "amd64"

foreach ($dir in @($amd64Dir, $x86Dir, $amd64Dir2, $driverRoot)) {
    if (Test-Path $dir) {
        $infs = Get-ChildItem -Path $dir -Filter "*.inf" -ErrorAction SilentlyContinue
        foreach ($inf in $infs) {
            if ($inf.Name -match "sony|srcamera") {
                $infFiles += $inf.FullName
            }
        }
    }
}

if ($infFiles.Count -eq 0) {
    @{ success = $false; message = "No Sony driver INF found. Expected srcameradriver.inf in driver/ directory." } | ConvertTo-Json -Compress
    exit 1
}

$infPath = $infFiles[0]
Write-Host "Installing Sony driver: $infPath"

# Install via pnputil (needs admin → elevate)
$outFile = Join-Path $env:TEMP "saatiril-sony-driver-$(Get-Date -Format 'yyyyMMddHHmmss').txt"

$psScript = @"
try {
    `$out = & pnputil /add-driver "$infPath" /install 2>&1
    `$code = `$LASTEXITCODE
    "`$out" | Out-File -FilePath "$outFile" -Encoding UTF8
    "EXITCODE:`$code" | Out-File -FilePath "$outFile" -Append -Encoding UTF8
} catch {
    `$_.Exception.Message | Out-File -FilePath "$outFile" -Encoding UTF8
    "EXITCODE:999" | Out-File -FilePath "$outFile" -Append -Encoding UTF8
}
"@

# Write script to temp file and elevate
$scriptPath = Join-Path $env:TEMP "saatiril-sony-driver-install-$(Get-Date -Format 'yyyyMMddHHmmss').ps1"
$psScript | Out-File -FilePath $scriptPath -Encoding UTF8

Start-Process powershell -ArgumentList "-NoProfile","-ExecutionPolicy","Bypass","-File",$scriptPath -Verb RunAs -Wait

# Read result
$result = ""
if (Test-Path $outFile) {
    $result = Get-Content $outFile -Raw
    Remove-Item $outFile -Force -ErrorAction SilentlyContinue
}
Remove-Item $scriptPath -Force -ErrorAction SilentlyContinue

if ($result -match "EXITCODE:0") {
    @{ success = $true; message = "Sony driver installed successfully. Please unplug and replug the camera." } | ConvertTo-Json -Compress
} elseif ($result -match "EXITCODE:(\d+)") {
    $code = $matches[1]
    @{ success = $false; message = "Driver install failed (exit code $code). $result" } | ConvertTo-Json -Compress
} else {
    @{ success = $false; message = "Driver install cancelled or UAC declined." } | ConvertTo-Json -Compress
}
