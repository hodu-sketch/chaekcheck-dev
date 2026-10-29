# Run at logon by the Windows scheduled task "chaekcheck-resume-kept" (user 2026-10-30 "예약 작업 만들어"): starts the
# keeper (start_kept.ps1) of every job in <LogDir>/kept-jobs.json whose keep log ends neither in "done" nor in "gave
# up" and that has no keeper running, so a Windows restart does not stop the long jobs for good. When every job is done
# or given up it removes the scheduled task itself (user 2026-10-30 "완료되면 예약 작업 없애줘"); each keeper runs this
# when it ends, so that happens without waiting for a logon. kept-jobs.json: [{"name", "python", "workDir", "script", "scriptArgs"}, ...]
param([Parameter(Mandatory)] [string] $LogDir, [string] $TaskName = 'chaekcheck-resume-kept', [int] $MaxStarts = 10)
$jobs = Get-Content (Join-Path $LogDir 'kept-jobs.json') -Raw -Encoding utf8 | ConvertFrom-Json
$note = Join-Path $LogDir 'resume.log'
$keepers = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like '*keep_running.ps1*' }
$open = 0
foreach ($job in $jobs) {
    $log = Join-Path $LogDir "$($job.name).keep.log"
    if ((Test-Path $log) -and ((Get-Content $log -Tail 1 -Encoding utf8) -match ' (done|gave up after \d+ starts)$')) {
        continue
    }
    # the starts of every keeper so far, restarts after logons included: at most $MaxStarts in all
    $starts = if (Test-Path $log) { @(Select-String -Path $log -Pattern ' start \d+: pid ' -Encoding utf8).Count } else { 0 }
    if ($starts -ge $MaxStarts) {
        Add-Content -Path $log -Value ("{0:s} gave up after {1} starts" -f (Get-Date), $starts) -Encoding utf8
        continue
    }
    $open++
    if ($keepers | Where-Object { $_.CommandLine -like "*-Name `"$($job.name)`"*" }) {
        continue
    }
    $id = & (Join-Path $PSScriptRoot 'start_kept.ps1') -Name $job.name -Python $job.python -WorkDir $job.workDir `
        -Script $job.script -ScriptArgs $job.scriptArgs -LogDir $LogDir -TaskName $TaskName
    Add-Content -Path $note -Value ("{0:s} started the keeper of {1}: pid {2}" -f (Get-Date), $job.name, $id) -Encoding utf8
}
if ($open -eq 0) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Add-Content -Path $note -Value ("{0:s} every job done or given up; task {1} removed" -f (Get-Date), $TaskName) -Encoding utf8
    }
}
