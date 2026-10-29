# Starts scripts/keep_running.ps1 through WMI (Win32_Process.Create), so the keeper and its job are not children of the
# app, a terminal or this shell and survive them closing. Same parameters as keep_running.ps1; prints the keeper's pid.
#   powershell -File scripts/start_kept.ps1 -Name chunk512 -Python <venv python> -WorkDir <repo> `
#       -Script scripts/aisearch/build_chunk_vectors.py -LogDir <log folder> [-ScriptArgs "..."] [-WaitPid <pid>]
param(
    [Parameter(Mandatory)] [string] $Name,
    [Parameter(Mandatory)] [string] $Python,
    [Parameter(Mandatory)] [string] $WorkDir,
    [Parameter(Mandatory)] [string] $Script,
    [string] $ScriptArgs = '',
    [Parameter(Mandatory)] [string] $LogDir,
    [int] $WaitPid = 0,
    [string] $TaskName = 'chaekcheck-resume-kept'
)
$keeper = Join-Path $PSScriptRoot 'keep_running.ps1'
$line = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$keeper`" -Name `"$Name`" " +
    "-Python `"$Python`" -WorkDir `"$WorkDir`" -Script `"$Script`" -ScriptArgs `"$ScriptArgs`" -LogDir `"$LogDir`" -WaitPid $WaitPid -TaskName `"$TaskName`""
$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $line; CurrentDirectory = $WorkDir }
if ($result.ReturnValue -ne 0) { throw "Win32_Process.Create failed: $($result.ReturnValue)" }
$result.ProcessId
