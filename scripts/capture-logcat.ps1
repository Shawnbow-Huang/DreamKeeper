<#
.SYNOPSIS
  Cold-start launch timing + logcat crash gate for the DreamKeeper Android app.

.DESCRIPTION
  Reproduces the plan's Task 8/9 evidence protocol with one command:
    adb logcat -c  ->  force-stop  ->  `am start -W` x N (default 3)  ->  logcat -d dump
  Then it fails (exit 1) when any of these is found in the dump:
    FATAL EXCEPTION (AndroidRuntime) / ANR in <appId> / signal 11 /
    Force finishing activity / E AndroidRuntime for <appId>
  and when any cold-start TotalTime exceeds -MaxStartMs (default 2000).
  Full dump is written with `Out-File -Encoding utf8` (text evidence, not binary redirect).

.PARAMETER AppId
  Package id, default com.dreamkeeper.

.PARAMETER MainActivity
  Component to launch, default com.dreamkeeper/.MainActivity.

.PARAMETER Runs
  Number of cold starts, default 3.

.PARAMETER MaxStartMs
  Per-run TotalTime ceiling. 0 disables the timing gate.

.PARAMETER OutFile
  Logcat dump destination (default: .\logcat-<timestamp>.txt).

.PARAMETER ReportFile
  Optional extra summary file (same lines printed to stdout).

.PARAMETER Serial
  Device serial, default auto-detect.

.PARAMETER NoRestart
  Safe mode: do NOT clear logs, do NOT force-stop/launch the app. Dump the current
  buffer only (used when another agent owns the emulator state). Skips timing.
  NOTE: a dump may then contain older crashes, so treat this as plumbing check only.

.PARAMETER AnalyzeFile
  Offline mode: analyze an existing logcat file (for regressions on archived evidence).

.PARAMETER SelfTest
  Built-in self-test with synthetic good/bad log files; does not touch the device.
  The bad fixture MUST go red, the clean fixture MUST go green.

.NOTES
  Exit codes: 0 = OK, 1 = FAIL. ASCII-only on purpose (PS 5.1 ANSI parsing).
#>
[CmdletBinding(DefaultParameterSetName = 'Live')]
param(
    [string]$AppId = 'com.dreamkeeper',
    [string]$MainActivity = 'com.dreamkeeper/.MainActivity',
    [int]$Runs = 3,
    [int]$MaxStartMs = 2000,
    [string]$OutFile,
    [string]$ReportFile,
    [string]$Serial,
    [Parameter(ParameterSetName = 'Safe')]
    [switch]$NoRestart,
    [Parameter(ParameterSetName = 'Analyze', Mandatory = $true)]
    [string]$AnalyzeFile,
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

function Get-LineContext {
    param([string[]]$Lines, [int]$Index, [int]$Before, [int]$After)
    $start = [Math]::Max(0, $Index - $Before)
    $end = [Math]::Min($Lines.Count - 1, $Index + $After)
    $chunk = @()
    for ($i = $start; $i -le $end; $i++) { $chunk += $Lines[$i] }
    return ($chunk -join "`n")
}

function Find-CrashHits {
    param([string]$File, [string]$PackageId)
    $escaped = [regex]::Escape($PackageId)
    $lines = @(Get-Content -LiteralPath $File -Encoding UTF8 -ErrorAction Stop)
    $hits = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = [string]$lines[$i]
        $hitsHere = $false
        $kind = ''
        if ($line -match 'FATAL EXCEPTION') { $hitsHere = $true; $kind = 'FATAL EXCEPTION' }
        elseif ($line -match ('ANR in ' + $escaped)) { $hitsHere = $true; $kind = 'ANR' }
        elseif ($line -match 'signal 11') { $hitsHere = $true; $kind = 'signal 11' }
        elseif ($line -match ('Force finishing activity.*' + $escaped)) { $hitsHere = $true; $kind = 'Force finishing' }
        elseif ($line -match ('E AndroidRuntime.*' + $escaped)) { $hitsHere = $true; $kind = 'AndroidRuntime error' }
        if (-not $hitsHere) { continue }
        $context = Get-LineContext -Lines $lines -Index $i -Before 2 -After 6
        if ($context -match $escaped -or $line -match $escaped) {
            [void]$hits.Add([pscustomobject]@{ Line = $i + 1; Kind = $kind; Text = $line.Trim() })
        }
    }
    return $hits
}

function Format-HitList {
    param($Hits)
    foreach ($h in $Hits) { Write-Output ('HIT line=' + $h.Line + ' kind=' + $h.Kind + ' text=' + $h.Text) }
}

$failed = $false
$report = New-Object System.Collections.ArrayList
function Add-Report { param([string]$Line) [void]$report.Add($Line); Write-Output $Line }

if ($SelfTest) {
    $tmp = Join-Path $env:TEMP ('dk-qa-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Add-Report ('SELFTEST dir: ' + $tmp)

    $bad = Join-Path $tmp 'bad-logcat.txt'
    $badLines = @(
        '10-30 00:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main',
        '10-30 00:00:00.001  1234  1234 E AndroidRuntime: Process: com.dreamkeeper, PID: 1234',
        '10-30 00:00:00.002  1234  1234 E AndroidRuntime: java.lang.IllegalStateException: boom'
    )
    $badLines | Out-File -FilePath $bad -Encoding utf8
    $badHits = @(Find-CrashHits -File $bad -PackageId 'com.dreamkeeper')
    if ($badHits.Count -eq 0) { Add-Report 'SELFTEST RED FAILED: crash fixture not detected'; $failed = $true }
    else { Add-Report ('SELFTEST RED ok: crash fixture detected, hits=' + $badHits.Count + ' first=' + $badHits[0].Kind) }

    $good = Join-Path $tmp 'clean-logcat.txt'
    $goodLines = @(
        '10-30 00:00:00.000  1000  1100 I ActivityManager: Start proc 1234:com.dreamkeeper/u0a123 for activity',
        '10-30 00:00:01.000  1234  1234 I DreamKeeper: app started normally',
        '10-30 00:00:02.000  1000  1100 I ActivityManager: Displayed com.dreamkeeper/.MainActivity: +742ms'
    )
    $goodLines | Out-File -FilePath $good -Encoding utf8
    $goodHits = @(Find-CrashHits -File $good -PackageId 'com.dreamkeeper')
    if ($goodHits.Count -ne 0) { Add-Report ('SELFTEST GREEN FAILED: false positive hits=' + $goodHits.Count); $failed = $true }
    else { Add-Report 'SELFTEST GREEN ok: clean fixture has no hits' }

    $foreign = Join-Path $tmp 'foreign-crash.txt'
    $foreignLines = @(
        '10-30 00:00:00.000  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main',
        '10-30 00:00:00.001  4321  4321 E AndroidRuntime: Process: com.android.other, PID: 4321'
    )
    $foreignLines | Out-File -FilePath $foreign -Encoding utf8
    $foreignHits = @(Find-CrashHits -File $foreign -PackageId 'com.dreamkeeper')
    if ($foreignHits.Count -ne 0) { Add-Report ('SELFTEST ISOLATION FAILED: other app crash attributed to dreamkeeper'); $failed = $true }
    else { Add-Report 'SELFTEST ISOLATION ok: other app crash ignored' }

    if ($failed) { exit 1 }
    exit 0
}

if ($AnalyzeFile) {
    try {
        $analyzeHits = @(Find-CrashHits -File $AnalyzeFile -PackageId $AppId)
        $size = (Get-Item -LiteralPath $AnalyzeFile).Length
        Add-Report ('LOGCAT: ' + $AnalyzeFile + ' bytes=' + $size + ' crashes=' + $analyzeHits.Count)
        Format-HitList -Hits $analyzeHits | ForEach-Object { Add-Report $_ }
        if ($analyzeHits.Count -gt 0) { Add-Report 'RESULT: FAIL crash signatures found'; exit 1 }
        Add-Report 'RESULT: OK no crash signatures'
        exit 0
    }
    catch {
        Write-Output ('RESULT: FAIL error=' + $_.Exception.Message)
        exit 1
    }
}

try {
    if (-not $OutFile) { $OutFile = Join-Path (Get-Location) ('logcat-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt') }
    $parent = Split-Path -Parent $OutFile
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $adb = Get-AdbPath
    $deviceSerial = Resolve-DeviceSerial -AdbPath $adb -Requested $Serial
    Add-Report ('ADB: ' + $adb + ' serial=' + $deviceSerial + ' mode=' + $(if ($NoRestart) { 'NoRestart(safe)' } else { 'cold-start x' + $Runs }))

    $times = New-Object System.Collections.ArrayList
    $statuses = New-Object System.Collections.ArrayList

    if (-not $NoRestart) {
        & $adb -s $deviceSerial logcat -c 2>&1 | Out-Null
        for ($run = 1; $run -le $Runs; $run++) {
            & $adb -s $deviceSerial shell am force-stop $AppId 2>&1 | Out-Null
            Start-Sleep -Milliseconds 400
            $startRaw = @(& $adb -s $deviceSerial shell am start -W -n $MainActivity 2>&1)
            $status = ''
            $total = -1
            foreach ($line in $startRaw) {
                $t = [string]$line
                if ($t -match 'Status:\s*(\S+)') { $status = $Matches[1] }
                if ($t -match 'TotalTime:\s*(\d+)') { $total = [int]$Matches[1] }
            }
            [void]$statuses.Add($status)
            [void]$times.Add($total)
            Add-Report ('RUN ' + $run + ': status=' + $(if ($status) { $status } else { '?' }) + ' TotalTime=' + $(if ($total -ge 0) { $total.ToString() + 'ms' } else { 'unparsed' }))
            Start-Sleep -Milliseconds 700
        }
    }

    $dump = @(& $adb -s $deviceSerial logcat -d -v threadtime 2>&1)
    $dump | Out-File -FilePath $OutFile -Encoding utf8
    Add-Report ('LOGCAT: ' + $OutFile + ' lines=' + $dump.Count + ' bytes=' + (Get-Item -LiteralPath $OutFile).Length)

    $hits = @(Find-CrashHits -File $OutFile -PackageId $AppId)
    Format-HitList -Hits $hits | ForEach-Object { Add-Report $_ }
    if ($hits.Count -gt 0) { $failed = $true }
    Add-Report ('CRASHES: ' + $hits.Count)

    if (-not $NoRestart) {
        $displayed = @(Select-String -LiteralPath $OutFile -Pattern ('Displayed ' + [regex]::Escape($AppId)) -SimpleMatch:$false -ErrorAction SilentlyContinue)
        Add-Report ('LAUNCH: Displayed=' + $displayed.Count + ' statuses=' + ($statuses -join ','))
        if ($displayed.Count -eq 0) {
            Add-Report 'LAUNCH GATE FAILED: no "Displayed <appId>" line, app was not observed on screen'
            $failed = $true
        }
        $validTimes = @($times | Where-Object { $_ -ge 0 })
        if ($validTimes.Count -gt 0) {
            $sorted = @($validTimes | Sort-Object)
            $median = $sorted[[int][Math]::Floor($sorted.Count / 2)]
            $minT = $sorted[0]
            $maxT = $sorted[$sorted.Count - 1]
            Add-Report ('STARTUP: runs=' + ($times -join ',') + ' min=' + $minT + 'ms median=' + $median + 'ms max=' + $maxT + 'ms')
            if ($MaxStartMs -gt 0 -and $maxT -gt $MaxStartMs) {
                Add-Report ('STARTUP GATE FAILED: max ' + $maxT + 'ms > ' + $MaxStartMs + 'ms')
                $failed = $true
            }
        }
        elseif ($Runs -gt 0) {
            Add-Report 'STARTUP GATE FAILED: no TotalTime parsed from am start -W'
            $failed = $true
        }
    }
    else {
        Add-Report 'NOTE: NoRestart safe mode - timing not measured, buffer was not cleared'
    }

    if ($ReportFile) {
        $report | Out-File -FilePath $ReportFile -Encoding utf8
        Write-Output ('REPORT: ' + $ReportFile)
    }

    if ($failed) { Add-Report 'RESULT: FAIL'; exit 1 }
    Add-Report 'RESULT: OK'
    exit 0
}
catch {
    Write-Output ('RESULT: FAIL error=' + $_.Exception.Message)
    exit 1
}
