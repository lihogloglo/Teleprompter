# Pull follow-session traces off the phone and triage the newest one.
#
#   .\tools\pull-traces.ps1              # pull + report on the newest trace
#   .\tools\pull-traces.ps1 -All         # report on every trace pulled
#
# Traces live in the app's external files dir, which is readable over adb
# without root (see FollowTraceWriter). The app keeps the last 5 sessions.

param([switch]$All)

$ErrorActionPreference = "Stop"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$remote = "/sdcard/Android/data/dev.metzook.teleprompter/files/traces"
$local = Join-Path $PSScriptRoot "..\traces"

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    throw "No device. Plug in USB, enable USB debugging, and accept the prompt on the phone."
}

if (-not (Test-Path $local)) { New-Item -ItemType Directory -Path $local | Out-Null }

# `adb pull <dir>/.` copies the contents, not a nested traces/ folder.
& $adb pull -a "$remote/." $local
if ($LASTEXITCODE -ne 0) { throw "adb pull failed - has a follow session ever run on this build?" }

$traces = Get-ChildItem $local -Filter "follow-*.tsv" | Sort-Object Name -Descending
if (-not $traces) { throw "No trace files pulled." }

Write-Output ""
$targets = if ($All) { $traces } else { $traces | Select-Object -First 1 }
foreach ($t in $targets) { python (Join-Path $PSScriptRoot "trace-report.py") $t.FullName }
