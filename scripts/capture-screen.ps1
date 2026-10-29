<#
.SYNOPSIS
  Binary-safe Android screenshot capture + PNG integrity gate.

.DESCRIPTION
  Why this script exists:
   - `adb` is NOT on PATH in the DreamKeeper dev shell, so bare `adb ...` fails.
   - PowerShell 5.1 `adb exec-out screencap -p > file.png` silently corrupts the PNG
     (UTF-16LE transcoding: the file starts with FF FE ... and no viewer can open it).
  This script uses the absolute adb path, captures through `cmd.exe /c` redirect
  (binary-safe, verified), then validates magic / IHDR / IEND and prints SHA256.

.PARAMETER OutPath
  Target png path. Parent directory is created if missing.

.PARAMETER Serial
  Device serial (e.g. emulator-5554). Auto-detected when omitted (single device,
  or the only emulator if several devices are attached).

.PARAMETER VerifyOnly
  Do not touch adb; only validate an existing png file.

.PARAMETER SelfTest
  Run built-in self-test: a deliberately corrupted PNG MUST go red ("SELFTEST RED ok"),
  a generated valid PNG MUST go green, and a real device capture is attempted when a
  device is attached (skipped otherwise).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task6-a.png

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -VerifyOnly docs\evidence\plan1\plan1-task1-home.png

.NOTES
  Exit codes: 0 = OK, 1 = FAIL. ASCII-only on purpose (PS 5.1 parses BOM-less
  non-ASCII scripts as ANSI and breaks).
#>
[CmdletBinding(DefaultParameterSetName = 'Capture')]
param(
    [Parameter(ParameterSetName = 'Capture', Position = 0)]
    [string]$OutPath,

    [Parameter(ParameterSetName = 'Capture')]
    [string]$Serial,

    [Parameter(ParameterSetName = 'Verify', Mandatory = $true)]
    [string]$VerifyOnly,

    [Parameter(ParameterSetName = 'SelfTest', Mandatory = $true)]
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

function Get-AdbPath {
    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += 'C:\Android\Sdk\platform-tools\adb.exe'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return (Resolve-Path -LiteralPath $c).Path }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Set ANDROID_HOME or install Android SDK platform-tools.'
}

function Resolve-DeviceSerial {
    param([string]$AdbPath, [string]$Requested)
    $raw = & $AdbPath devices 2>&1
    $devices = @()
    foreach ($line in $raw) {
        $text = [string]$line
        if ($text -match '^\s*([^\s]+)\s+(device|offline|unauthorized)\s*$') {
            $devices += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
    if ($Requested) {
        $hit = $devices | Where-Object { $_.Serial -eq $Requested }
        if (-not $hit) { throw ("requested device '{0}' not in adb devices: {1}" -f $Requested, (($devices | ForEach-Object { $_.Serial + '=' + $_.State }) -join ', ')) }
        if ($hit.State -ne 'device') { throw ("device '{0}' is {1}, not usable" -f $Requested, $hit.State) }
        return $Requested
    }
    $usable = @($devices | Where-Object { $_.State -eq 'device' })
    if ($usable.Count -eq 0) { throw 'no usable device. adb devices => ' + (($devices | ForEach-Object { $_.Serial + '=' + $_.State }) -join ', ') }
    if ($usable.Count -eq 1) { return $usable[0].Serial }
    $emu = @($usable | Where-Object { $_.Serial -like 'emulator-*' })
    if ($emu.Count -eq 1) { return $emu[0].Serial }
    throw ('several devices attached: ' + (($usable | ForEach-Object { $_.Serial }) -join ', ') + '; pass -Serial')
}

function Test-PngFile {
    param([string]$Path)
    $result = [pscustomobject]@{
        Ok = $false; Path = $Path; Bytes = 0; Width = 0; Height = 0
        MagicHex = ''; Sha256 = ''; Reason = ''
    }
    if (-not (Test-Path -LiteralPath $Path)) { $result.Reason = 'file missing'; return $result }
    $b = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).Path)
    $result.Bytes = $b.Length
    if ($b.Length -lt 33) { $result.Reason = ('too small: {0} bytes' -f $b.Length); return $result }
    $result.MagicHex = (($b[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
    $magicOk = ($b[0] -eq 0x89 -and $b[1] -eq 0x50 -and $b[2] -eq 0x4E -and $b[3] -eq 0x47 -and
                $b[4] -eq 0x0D -and $b[5] -eq 0x0A -and $b[6] -eq 0x1A -and $b[7] -eq 0x0A)
    if (-not $magicOk) { $result.Reason = 'bad PNG magic (corrupted redirect / UTF-16LE?)'; return $result }
    if (-not ($b[12] -eq 0x49 -and $b[13] -eq 0x48 -and $b[14] -eq 0x44 -and $b[15] -eq 0x52)) { $result.Reason = 'missing IHDR'; return $result }
    $result.Width = [int]$b[16] * 16777216 + [int]$b[17] * 65536 + [int]$b[18] * 256 + [int]$b[19]
    $result.Height = [int]$b[20] * 16777216 + [int]$b[21] * 65536 + [int]$b[22] * 256 + [int]$b[23]
    if ($result.Width -le 0 -or $result.Height -le 0) { $result.Reason = 'bad IHDR dimensions'; return $result }
    $tailOk = $true
    $tail = @(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
    for ($i = 0; $i -lt 8; $i++) {
        if ($b[$b.Length - 8 + $i] -ne $tail[$i]) { $tailOk = $false; break }
    }
    if (-not $tailOk) { $result.Reason = 'truncated PNG (IEND missing)'; return $result }
    $result.Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    $result.Ok = $true
    $result.Reason = 'ok'
    return $result
}

function Invoke-Capture {
    param([string]$AdbPath, [string]$DeviceSerial, [string]$Target)
    $parent = Split-Path -Parent $Target
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Force }
    $serialArg = ''
    if ($DeviceSerial) { $serialArg = '-s ' + $DeviceSerial + ' ' }
    $cmdLine = '"{0}" {1}exec-out screencap -p > "{2}"' -f $AdbPath, $serialArg, $Target
    & cmd.exe /c $cmdLine | Out-Null
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw ('adb/cmd redirect failed, exit={0}' -f $code) }
    if (-not (Test-Path -LiteralPath $Target)) { throw 'capture produced no file' }
}

function New-TestPng {
    param([string]$Path, [int]$Width, [int]$Height, [string]$ColorName)
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $color = [System.Drawing.Color]::FromName($ColorName)
    $g.Clear($color)
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

$failed = $false

if ($SelfTest) {
    $tmp = Join-Path $env:TEMP ('dk-qa-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Output ('SELFTEST dir: ' + $tmp)

    $bad = Join-Path $tmp 'bad-mangled.png'
    [System.IO.File]::WriteAllBytes($bad, [System.Text.Encoding]::Unicode.GetBytes('this is not a png: UTF-16LE redirect simulation'))
    $rBad = Test-PngFile -Path $bad
    if ($rBad.Ok) { Write-Output 'SELFTEST RED FAILED: corrupted fixture was accepted'; $failed = $true }
    else { Write-Output ('SELFTEST RED ok: corrupt fixture rejected -> ' + $rBad.Reason) }

    $good = Join-Path $tmp 'good.png'
    New-TestPng -Path $good -Width 40 -Height 30 -ColorName 'White'
    $rGood = Test-PngFile -Path $good
    if (-not $rGood.Ok) { Write-Output ('SELFTEST GREEN FAILED: valid fixture rejected -> ' + $rGood.Reason); $failed = $true }
    else { Write-Output ('SELFTEST GREEN ok: valid fixture accepted ' + $rGood.Width + 'x' + $rGood.Height + ' bytes=' + $rGood.Bytes) }

    try {
        $adb = Get-AdbPath
        $ser = Resolve-DeviceSerial -AdbPath $adb -Requested ''
        $shot = Join-Path $tmp 'device.png'
        Invoke-Capture -AdbPath $adb -DeviceSerial $ser -Target $shot
        $rDev = Test-PngFile -Path $shot
        if (-not $rDev.Ok) { Write-Output ('SELFTEST DEVICE FAILED: ' + $rDev.Reason); $failed = $true }
        else { Write-Output ('SELFTEST DEVICE ok: ' + $ser + ' ' + $rDev.Width + 'x' + $rDev.Height + ' bytes=' + $rDev.Bytes + ' sha256=' + $rDev.Sha256) }
    }
    catch {
        Write-Output ('SELFTEST DEVICE skipped: ' + $_.Exception.Message)
    }
    if ($failed) { exit 1 }
    exit 0
}

if ($VerifyOnly) {
    $r = Test-PngFile -Path $VerifyOnly
    if ($r.Ok) {
        Write-Output ('RESULT: OK path=' + $r.Path + ' bytes=' + $r.Bytes + ' dims=' + $r.Width + 'x' + $r.Height + ' sha256=' + $r.Sha256)
        exit 0
    }
    Write-Output ('RESULT: FAIL path=' + $VerifyOnly + ' reason=' + $r.Reason)
    exit 1
}

try {
    if (-not $OutPath) {
        $OutPath = Join-Path (Get-Location) ('screen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.png')
    }
    $adb = Get-AdbPath
    $deviceSerial = Resolve-DeviceSerial -AdbPath $adb -Requested $Serial
    Invoke-Capture -AdbPath $adb -DeviceSerial $deviceSerial -Target $OutPath
    $check = Test-PngFile -Path $OutPath
    if (-not $check.Ok) {
        Write-Output ('RESULT: FAIL path=' + $OutPath + ' reason=' + $check.Reason)
        exit 1
    }
    Write-Output ('RESULT: OK path=' + $check.Path + ' serial=' + $deviceSerial + ' bytes=' + $check.Bytes + ' dims=' + $check.Width + 'x' + $check.Height + ' sha256=' + $check.Sha256)
    exit 0
}
catch {
    Write-Output ('RESULT: FAIL error=' + $_.Exception.Message)
    exit 1
}
