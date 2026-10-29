# Cycle 26 step 11 (ADR-0163): the collect PC's covers (covers\<xx>\<sha1>.jpg, 256 folders) to S3 flat as
# covers/<sha1>.jpg. Run in PowerShell (Git Bash hung on aws s3 sync, docs/maintenance/8-covers.md 2절):
#
#   powershell -File scripts/covers/upload-collected.ps1 -Source <data>\covers -Log <folder>
#
# One aws s3 cp --recursive per folder (no listing of the 1.1 million objects); a folder already in done.txt is
# skipped, so a stopped run goes on where it was. Objects already there are written again with the same bytes.
# -Only <regex> takes the folders whose name matches, so several runs can go side by side (8 together about 170 files/s,
# 2026-10-29: one run alone was about 25 files a second).
param(
	[Parameter(Mandatory = $true)][string]$Source,
	[Parameter(Mandatory = $true)][string]$Log,
	[string]$Aws = "$PSScriptRoot\..\..\..\chaekcheck-dev\data\tools\awscli\Amazon\AWSCLIV2\aws.exe",
	[string]$Bucket = 'chaekcheck-covers-135274608857',
	[string]$Profile = 'chaekcheck-covers',
	[string]$Only = '.'
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force $Log | Out-Null
$done = Join-Path $Log 'done.txt'
$skip = @{}
Get-ChildItem $Log -Filter 'done*.txt' | ForEach-Object { Get-Content $_.FullName | ForEach-Object { $skip[$_] = $true } }
$done = Join-Path $Log ("done-{0}.txt" -f ([Math]::Abs($Only.GetHashCode())))
$started = Get-Date
foreach ($folder in Get-ChildItem $Source -Directory | Where-Object { $_.Name -match $Only } | Sort-Object Name) {
	if ($skip.ContainsKey($folder.Name)) { continue }
	$out = Join-Path $Log "$($folder.Name).log"
	& $Aws s3 cp $folder.FullName "s3://$Bucket/covers/" --recursive --exclude '*' --include '*.jpg' `
		--profile $Profile --cache-control 'public, max-age=604800' --content-type image/jpeg --no-progress --only-show-errors *> $out
	if ($LASTEXITCODE -eq 0) {
		Add-Content $done $folder.Name
		$files = (Get-ChildItem $folder.FullName -Filter *.jpg -File).Count
		"{0} {1} files ok, {2:n0} min so far" -f $folder.Name, $files, ((Get-Date) - $started).TotalMinutes
	}
	else {
		"{0} FAILED (exit {1}), see {2}" -f $folder.Name, $LASTEXITCODE, $out
	}
}
"finished in {0:n0} min" -f ((Get-Date) - $started).TotalMinutes
