# Locate near-white pixels (min(R,G,B) > 245) in a screenshot: total / inside the
# notification app-icon box / outside it. ASCII only: Windows PowerShell 5.1 reads
# .ps1 as ANSI, so non-ASCII would break parsing.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File docs/evidence/plan2/p2-fgs-01-whitepixels.ps1 -Path docs/evidence/plan2/p2-fgs-01-notify.png -TopOffset 100
param(
  [Parameter(Mandatory = $true)][string]$Path,
  [int]$TopOffset = 100,
  # Bounding box of the app icon + SystemUI's white circular background (1080x2400, measured with margin)
  [int]$IconX0 = 50, [int]$IconX1 = 210, [int]$IconY0 = 650, [int]$IconY1 = 950
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Path))
try {
  $total = 0; $inIcon = 0; $outside = 0
  $minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = -1; $maxY = -1
  for ($y = $TopOffset; $y -lt $bmp.Height; $y += 2) {
    for ($x = 0; $x -lt $bmp.Width; $x += 2) {
      $c = $bmp.GetPixel($x, $y)
      if ([Math]::Min([Math]::Min($c.R, $c.G), $c.B) -le 245) { continue }
      $total++
      if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
      if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
      if ($x -ge $IconX0 -and $x -le $IconX1 -and $y -ge $IconY0 -and $y -le $IconY1) { $inIcon++ } else { $outside++ }
    }
  }
  "file: $Path"
  "top_offset: $TopOffset (same as check-near-white.ps1: status bar is skipped)"
  "nearwhite_total: $total (1 sample per 2x2 pixels, i.e. about $($total * 4) real pixels)"
  "nearwhite_in_icon_box: $inIcon (icon box x=$IconX0..$IconX1 y=$IconY0..$IconY1)"
  "nearwhite_outside_icon_box: $outside"
  "nearwhite_bbox: x=$minX..$maxX y=$minY..$maxY"
} finally {
  $bmp.Dispose()
}
