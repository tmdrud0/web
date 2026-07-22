param(
    [string]$BrowserPath
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$pdfRoot = Join-Path $projectRoot "docs\pdf"
$distRoot = Join-Path $pdfRoot "dist"

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

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
$targets = @(
    @{ Html = "contest-insert-portfolio.html"; Pdf = "contest-insert-portfolio.pdf" },
    @{ Html = "rank-query-portfolio.html"; Pdf = "rank-query-portfolio.pdf" },
    @{ Html = "scoreboard-recovery-portfolio.html"; Pdf = "scoreboard-recovery-portfolio.pdf" }
)

foreach ($target in $targets) {
    $htmlPath = (Resolve-Path (Join-Path $pdfRoot $target.Html)).Path
    $pdfPath = Join-Path $distRoot $target.Pdf
    $uri = "file:///" + ($htmlPath -replace "\\", "/")

    if (Test-Path $pdfPath) {
        Remove-Item $pdfPath -Force
    }

    & $browser `
        "--headless=new" `
        "--disable-gpu" `
        "--run-all-compositor-stages-before-draw" `
        "--virtual-time-budget=2000" `
        "--print-to-pdf-no-header" `
        "--print-to-pdf=$pdfPath" `
        $uri

    $exported = $false
    for ($i = 0; $i -lt 10; $i++) {
        if ((Test-Path $pdfPath) -and ((Get-Item $pdfPath).Length -gt 0)) {
            $exported = $true
            break
        }

        Start-Sleep -Milliseconds 300
    }

    if (-not $exported) {
        throw "Failed to export $($target.Html)"
    }
}

Write-Output "Exported PDFs to $distRoot"
