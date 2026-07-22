param(
    [string]$BrowserPath
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$pdfRoot = Join-Path $projectRoot "docs\pdf"
$distRoot = Join-Path $pdfRoot "dist"

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

python (Join-Path $PSScriptRoot "build_combined_portfolio.py")
if ($LASTEXITCODE -ne 0) {
    throw "Failed to build combined portfolio HTML."
}

$candidates = @(
    $BrowserPath,
    "C:\Program Files\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { $_ -and (Test-Path $_) }

if (-not $candidates) {
    throw "No compatible browser was found for PDF export."
}

$browser = $candidates[0]
$htmlPath = (Resolve-Path (Join-Path $pdfRoot "portfolio-combined.html")).Path
$pdfPath = Join-Path $distRoot "portfolio-combined.pdf"
$uri = "file:///" + ($htmlPath -replace "\\", "/")

if (Test-Path $pdfPath) {
    Remove-Item $pdfPath -Force
}

$args = @(
    "--headless=new",
    "--disable-gpu",
    "--run-all-compositor-stages-before-draw",
    "--virtual-time-budget=2500",
    "--print-to-pdf-no-header",
    "--print-to-pdf=$pdfPath",
    $uri
)

$process = Start-Process -FilePath $browser -ArgumentList $args -PassThru -Wait

$exported = $false
for ($i = 0; $i -lt 10; $i++) {
    if ((Test-Path $pdfPath) -and ((Get-Item $pdfPath).Length -gt 0)) {
        $exported = $true
        break
    }
    Start-Sleep -Milliseconds 300
}

if (-not $exported) {
    throw "Failed to export portfolio-combined.html"
}

Write-Output "Exported PDF to $pdfPath"
