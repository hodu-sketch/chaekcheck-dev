# Keeps a long resumable job going (user 2026-10-30 "작업들 중간에 끊어지게 되는 경우 많은데 이거 안 끊어지게 해"):
# - while this script runs the PC does not idle-sleep (SetThreadExecutionState, the same call video players use; no
#   power setting is changed, and a closed lid or choosing Sleep still sleeps),
# - when the job exits with an error it is started again after a minute (the jobs skip the parts they finished),
# - with -WaitPid it first waits for a copy of the job already running, then starts it again until it exits 0.
# Start it through WMI (scripts/start_kept.ps1) so it does not belong to the app's or a terminal's process tree.
# A Windows restart stops it; the logon task of resume_kept.ps1 starts it again. At most -MaxStarts starts (then
# "gave up"), and when it ends it runs resume_kept.ps1, which removes that task once every job is done or given up
# (user 2026-10-30 "무한히 돌게 하지 말고 완료되면 예약 작업 없애줘").
param(
    [Parameter(Mandatory)] [string] $Name,
    [Parameter(Mandatory)] [string] $Python,
    [Parameter(Mandatory)] [string] $WorkDir,
    [Parameter(Mandatory)] [string] $Script,
    [string] $ScriptArgs = '',
    [Parameter(Mandatory)] [string] $LogDir,
    [int] $WaitPid = 0,
    [int] $MaxStarts = 10,
    [string] $TaskName = 'chaekcheck-resume-kept'
)
Add-Type -Namespace KeepRunning -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED: held by this thread until the script ends
[void][KeepRunning.Power]::SetThreadExecutionState([uint32]'0x80000001')
$log = Join-Path $LogDir "$Name.keep.log"
function Note([string] $text) { Add-Content -Path $log -Value ("{0:s} {1}" -f (Get-Date), $text) -Encoding utf8 }

Note "keeping $Name (pid $PID): $Script $ScriptArgs"
if ($WaitPid -gt 0) {
    Note "waiting for the running copy, pid $WaitPid"
    Wait-Process -Id $WaitPid -ErrorAction SilentlyContinue
    Note "pid $WaitPid ended; starting again to finish or to confirm it finished"
}
# at most $MaxStarts starts in all, counting the keepers before this one (a restart after a logon goes on counting)
$before = if (Test-Path $log) { @(Select-String -Path $log -Pattern ' start \d+: pid ' -Encoding utf8).Count } else { 0 }
for ($start = $before + 1; $start -le $MaxStarts; $start++) {
    $arguments = @('-X', 'utf8', $Script) + ($ScriptArgs -split ' ' | Where-Object { $_ })
    $p = Start-Process -FilePath $Python -ArgumentList $arguments -WorkingDirectory $WorkDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir "$Name.$start.log") -RedirectStandardError (Join-Path $LogDir "$Name.$start.err") `
        -PassThru
    $null = $p.Handle  # without an open handle ExitCode stays empty after the exit
    Note "start ${start}: pid $($p.Id)"
    $p.WaitForExit()
    Note "start ${start} ended with exit code $($p.ExitCode)"
    if ($p.ExitCode -eq 0) {
        Note "done"
        break
    }
    if ($start -ge $MaxStarts) {
        Note "gave up after $start starts"
        break
    }
    Start-Sleep -Seconds 60
}
# removes the logon task when every job is done or given up (starts nothing here: this keeper's job is finished)
& (Join-Path $PSScriptRoot 'resume_kept.ps1') -LogDir $LogDir -TaskName $TaskName -MaxStarts $MaxStarts
