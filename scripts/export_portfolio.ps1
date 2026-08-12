# docs/portfolio/*.md -> docs/pdf/dist/portfolio.pdf
param(
    [string]$BrowserPath
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$pdfRoot = Join-Path $projectRoot "docs\pdf"
$distRoot = Join-Path $pdfRoot "dist"

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

python (Join-Path $PSScriptRoot "build_portfolio.py")
if ($LASTEXITCODE -ne 0) {
    throw "포트폴리오 HTML 생성에 실패했습니다."
}

$candidates = @(
    $BrowserPath,
    "C:\Program Files\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { $_ -and (Test-Path $_) }

if (-not $candidates) {
    throw "PDF 변환에 사용할 브라우저를 찾지 못했습니다. -BrowserPath 로 직접 지정하세요."
}

$browser = $candidates[0]
$htmlPath = (Resolve-Path (Join-Path $pdfRoot "portfolio.html")).Path
$pdfPath = Join-Path $distRoot "portfolio.pdf"
$uri = "file:///" + ($htmlPath -replace "\\", "/")

if (Test-Path $pdfPath) {
    Remove-Item $pdfPath -Force
}

$chromeArgs = @(
    "--headless=new",
    "--disable-gpu",
    "--run-all-compositor-stages-before-draw",
    "--virtual-time-budget=4000",
    "--no-pdf-header-footer",
    "--print-to-pdf=$pdfPath",
    $uri
)

Start-Process -FilePath $browser -ArgumentList $chromeArgs -PassThru -Wait | Out-Null

$exported = $false
for ($i = 0; $i -lt 15; $i++) {
    if ((Test-Path $pdfPath) -and ((Get-Item $pdfPath).Length -gt 0)) {
        $exported = $true
        break
    }
    Start-Sleep -Milliseconds 300
}

if (-not $exported) {
    throw "PDF 출력에 실패했습니다: $pdfPath"
}

$sizeKb = [math]::Round((Get-Item $pdfPath).Length / 1KB, 1)
Write-Output "PDF 생성 완료: $pdfPath ($sizeKb KB)"
