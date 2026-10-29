<#
.SYNOPSIS
  Near-white pixel gate for the DreamKeeper dark theme (design hard rule: no pure white).

.DESCRIPTION
  Decodes each PNG with .NET System.Drawing, samples pixels on a fixed grid
  (x/y step, optional top offset to skip the status bar), counts pixels with
  R,G,B all above -Threshold (default 245) and FAILS when the ratio exceeds
  -MaxRatio (default 0.01 = 1%). Also validates PNG magic first, prints the
  center pixel color and SHA256 so the result is auditable evidence.

.PARAMETER Path
  One or more PNG files to check (wildcards allowed by the caller shell).

.PARAMETER Threshold
  R/G/B channel threshold, default 245 (strictly greater than).

.PARAMETER MaxRatio
  Max allowed near-white ratio, default 0.01.

.PARAMETER Step
  Sampling step in pixels, default 6.

.PARAMETER TopOffset
  Start sampling at this y, default 0. Use e.g. 80 to exclude the status bar.

.PARAMETER SelfTest
  Creates a white fixture (MUST fail) and a dark fixture (MUST pass) in %TEMP%,
  plus a corrupted fixture (MUST be rejected as unreadable). Device not needed.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\*.png

.NOTES
  Exit codes: 0 = all OK, 1 = at least one FAIL. ASCII-only on purpose.
#>
[CmdletBinding(DefaultParameterSetName = 'Check')]
param(
    [Parameter(ParameterSetName = 'Check', Mandatory = $true, Position = 0)]
    [string[]]$Path,

    [Parameter(ParameterSetName = 'Check')]
    [int]$Threshold = 245,

    [Parameter(ParameterSetName = 'Check')]
    [double]$MaxRatio = 0.01,

    [Parameter(ParameterSetName = 'Check')]
    [int]$Step = 6,

    [Parameter(ParameterSetName = 'Check')]
    [int]$TopOffset = 0,

    [Parameter(ParameterSetName = 'SelfTest', Mandatory = $true)]
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# `powershell -File script.ps1 -Path a.png,b.png` passes one string "a,b.png"; split it.
if ($Path.Count -eq 1 -and $Path[0] -like '*,*') { $Path = $Path[0] -split ',' }
# Expand wildcards ourselves: -File invocation does not expand them for advanced functions.
$expanded = New-Object System.Collections.ArrayList
foreach ($item in $Path) {
    if ($item -match '[*?]') {
        $hits = @(Get-ChildItem -Path $item -File -ErrorAction SilentlyContinue)
        if ($hits.Count -gt 0) { foreach ($h in $hits) { [void]$expanded.Add($h.FullName) } }
        else { [void]$expanded.Add($item) }
    }
    else { [void]$expanded.Add($item) }
}
$Path = @($expanded)


function Get-FileMagicHex {
    param([string]$File)
    $b = [System.IO.File]::ReadAllBytes($File)
    if ($b.Length -lt 8) { return '' }
    return (($b[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
}

function Test-NearWhiteFile {
    param([string]$File, [int]$Threshold, [double]$MaxRatio, [int]$Step, [int]$TopOffset)
    $result = [pscustomobject]@{
        Ok = $false; Path = $File; Bytes = 0; Width = 0; Height = 0; Samples = 0
        NearWhite = 0; Ratio = 0.0; CenterHex = ''; Sha256 = ''; Reason = ''
    }
    if (-not (Test-Path -LiteralPath $File)) { $result.Reason = 'file missing'; return $result }
    $resolved = (Resolve-Path -LiteralPath $File).Path
    $result.Bytes = (Get-Item -LiteralPath $resolved).Length

    $magic = Get-FileMagicHex -File $resolved
    $magicOk = ($magic -eq '89 50 4E 47 0D 0A 1A 0A')
    if (-not $magicOk) {
        $result.Reason = 'bad PNG magic (' + $magic + ') - corrupted capture?'
        return $result
    }

    Add-Type -AssemblyName System.Drawing
    $bmp = $null
    try { $bmp = New-Object System.Drawing.Bitmap($resolved) }
    catch { $result.Reason = 'decode failed: ' + $_.Exception.Message; return $result }

    try {
        $w = $bmp.Width
        $h = $bmp.Height
        $result.Width = $w
        $result.Height = $h
        $startY = [Math]::Max(0, $TopOffset)
        $total = 0
        $nearWhite = 0
        for ($y = $startY; $y -lt $h; $y += $Step) {
            for ($x = 0; $x -lt $w; $x += $Step) {
                $c = $bmp.GetPixel($x, $y)
                $total++
                if ($c.R -gt $Threshold -and $c.G -gt $Threshold -and $c.B -gt $Threshold) { $nearWhite++ }
            }
        }
        $result.Samples = $total
        $result.NearWhite = $nearWhite
        if ($total -gt 0) { $result.Ratio = [Math]::Round($nearWhite / $total, 6) }
        $center = $bmp.GetPixel([int][Math]::Floor($w / 2), [int][Math]::Floor($h / 2))
        $result.CenterHex = ('#{0:X2}{1:X2}{2:X2}' -f $center.R, $center.G, $center.B)
        $result.Sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash
        if ($result.Ratio -gt $MaxRatio) {
            $result.Reason = ('near-white ratio {0:P3} > max {1:P3}' -f $result.Ratio, $MaxRatio)
            return $result
        }
        $result.Ok = $true
        $result.Reason = 'ok'
        return $result
    }
    finally {
        if ($bmp) { $bmp.Dispose() }
    }
}

function New-SolidPng {
    param([string]$File, [int]$Width, [int]$Height, [int]$Red, [int]$Green, [int]$Blue)
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.Clear([System.Drawing.Color]::FromArgb(255, $Red, $Green, $Blue))
    $bmp.Save($File, [System.Drawing.Imaging.ImageFormat]::Png)
    $gfx.Dispose()
    $bmp.Dispose()
}

if ($SelfTest) {
    $tmp = Join-Path $env:TEMP ('dk-qa-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Output ('SELFTEST dir: ' + $tmp)
    $failed = $false

    $white = Join-Path $tmp 'white.png'
    New-SolidPng -File $white -Width 120 -Height 90 -Red 255 -Green 255 -Blue 255
    $rw = Test-NearWhiteFile -File $white -Threshold 245 -MaxRatio 0.01 -Step 6 -TopOffset 0
    if ($rw.Ok) { Write-Output 'SELFTEST RED FAILED: white fixture passed'; $failed = $true }
    else { Write-Output ('SELFTEST RED ok: white fixture failed -> ' + $rw.Reason + ' ratio=' + $rw.Ratio) }

    $dark = Join-Path $tmp 'dark.png'
    New-SolidPng -File $dark -Width 120 -Height 90 -Red 11 -Green 14 -Blue 19
    $rd = Test-NearWhiteFile -File $dark -Threshold 245 -MaxRatio 0.01 -Step 6 -TopOffset 0
    if (-not $rd.Ok) { Write-Output ('SELFTEST GREEN FAILED: dark fixture rejected -> ' + $rd.Reason); $failed = $true }
    else { Write-Output ('SELFTEST GREEN ok: dark fixture passed center=' + $rd.CenterHex + ' nearWhite=0/' + $rd.Samples) }

    $corrupt = Join-Path $tmp 'corrupt.png'
    [System.IO.File]::WriteAllBytes($corrupt, [System.Text.Encoding]::Unicode.GetBytes('not a png at all'))
    $rc = Test-NearWhiteFile -File $corrupt -Threshold 245 -MaxRatio 0.01 -Step 6 -TopOffset 0
    if ($rc.Ok) { Write-Output 'SELFTEST CORRUPT FAILED: corrupted fixture passed'; $failed = $true }
    else { Write-Output ('SELFTEST CORRUPT ok: corrupted fixture rejected -> ' + $rc.Reason) }

    if ($failed) { exit 1 }
    exit 0
}

$anyFail = $false
foreach ($file in $Path) {
    try {
        $r = Test-NearWhiteFile -File $file -Threshold $Threshold -MaxRatio $MaxRatio -Step $Step -TopOffset $TopOffset
        if ($r.Ok) {
            Write-Output ('RESULT: OK image=' + $r.Path + ' dims=' + $r.Width + 'x' + $r.Height + ' samples=' + $r.Samples + ' nearWhite=' + $r.NearWhite + ' ratio=' + $r.Ratio.ToString('P4') + ' center=' + $r.CenterHex + ' sha256=' + $r.Sha256)
        }
        else {
            Write-Output ('RESULT: FAIL image=' + $file + ' reason=' + $r.Reason + ' dims=' + $r.Width + 'x' + $r.Height + ' samples=' + $r.Samples + ' nearWhite=' + $r.NearWhite + ' ratio=' + $r.Ratio.ToString('P4') + ' center=' + $r.CenterHex)
            $anyFail = $true
        }
    }
    catch {
        Write-Output ('RESULT: FAIL image=' + $file + ' error=' + $_.Exception.Message)
        $anyFail = $true
    }
}

if ($anyFail) { exit 1 }
exit 0
