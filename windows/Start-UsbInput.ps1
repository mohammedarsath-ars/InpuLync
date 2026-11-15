param(
    [string]$Code,
    [switch]$ResetCode
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$receiver = Join-Path $PSScriptRoot 'usb_input_host.py'

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}

# First run: the helper asks for the pair code shown in InpuLync > SET > USB PAIR CODE.
$arguments = @('--adb', $adb)
if ($Code) { $arguments += @('--code', $Code) }
if ($ResetCode) { $arguments += '--reset-code' }
python $receiver @arguments
