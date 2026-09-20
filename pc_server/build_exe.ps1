<#
.SYNOPSIS
    Builds pc_server/dist/PhonePadServer.exe (single windowed exe) with PyInstaller.

.DESCRIPTION
    Creates a throwaway virtualenv OUTSIDE the repository, installs the runtime
    dependencies (pystray, Pillow) plus PyInstaller into it, and builds the exe
    from phone_pad_server.spec.

    The global Python installation is never modified. That is deliberate: this
    repo's pytest baseline assumes pystray/Pillow are NOT importable, so that the
    "tray unavailable -> console fallback" path is what the default test run
    exercises. Installing them globally would silently change what the tests cover.

    The venv is reused across builds. Delete it (or pass -Recreate) to start clean.

.PARAMETER VenvPath
    Where to build the venv. Default: $env:TEMP\phone_pad_build_venv
    (outside the repository on purpose - never point this inside pc_server\).

.PARAMETER Python
    Interpreter used to create the venv. Default: "python".

.PARAMETER Recreate
    Delete and rebuild the venv before installing.

.PARAMETER SkipInstall
    Reuse the venv as-is (faster rebuilds when dependencies have not changed).

.EXAMPLE
    ./build_exe.ps1
    ./build_exe.ps1 -Recreate
#>
[CmdletBinding()]
param(
    [string]$VenvPath = (Join-Path $env:TEMP "phone_pad_build_venv"),
    [string]$Python = "python",
    [switch]$Recreate,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$ServerDir = Split-Path -Parent $PSCommandPath
$SpecFile  = Join-Path $ServerDir "phone_pad_server.spec"
$DistDir   = Join-Path $ServerDir "dist"
$WorkDir   = Join-Path $ServerDir "build"

if ($VenvPath.StartsWith($ServerDir, [StringComparison]::OrdinalIgnoreCase)) {
    throw "VenvPath must live outside the repository (got '$VenvPath')."
}

Write-Host "[=] Repo dir : $ServerDir"
Write-Host "[=] Venv     : $VenvPath"

if ($Recreate -and (Test-Path $VenvPath)) {
    Write-Host "[=] Removing existing venv"
    Remove-Item -Recurse -Force $VenvPath
}

if (-not (Test-Path $VenvPath)) {
    Write-Host "[=] Creating venv"
    & $Python -m venv $VenvPath
    if ($LASTEXITCODE -ne 0) { throw "venv creation failed (exit $LASTEXITCODE)" }
}

$VenvPython = Join-Path $VenvPath "Scripts\python.exe"
if (-not (Test-Path $VenvPython)) { throw "venv python not found at $VenvPython" }

if (-not $SkipInstall) {
    Write-Host "[=] Installing build dependencies into the venv"
    & $VenvPython -m pip install --upgrade pip --quiet
    if ($LASTEXITCODE -ne 0) { throw "pip upgrade failed (exit $LASTEXITCODE)" }
    # requirements.txt = runtime deps (pystray, Pillow). PyInstaller is build-only,
    # so it is named here instead of being added to requirements.txt.
    & $VenvPython -m pip install -r (Join-Path $ServerDir "requirements.txt") "pyinstaller>=6.0" --quiet
    if ($LASTEXITCODE -ne 0) { throw "dependency install failed (exit $LASTEXITCODE)" }
}

Write-Host "[=] Building exe"
Push-Location $ServerDir
try {
    & $VenvPython -m PyInstaller --noconfirm --clean `
        --distpath $DistDir --workpath $WorkDir $SpecFile
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

$Exe = Join-Path $DistDir "PhonePadServer.exe"
if (-not (Test-Path $Exe)) { throw "expected exe not found at $Exe" }

$SizeMb = [math]::Round((Get-Item $Exe).Length / 1MB, 1)
Write-Host ""
Write-Host "[=] Built $Exe ($SizeMb MB)"
Write-Host "[=] Run it by double-clicking (windowed, tray icon, no console)."
Write-Host "[=] Logs: $env:LOCALAPPDATA\PhonePad\server.log"
