@echo off
rem Daily check of Kyobo subscription sites for limit/closure notices (user request 2026-10-04).
rem Appends only popups/notices not seen before to other-platforms\kyobo-limits-log.tsv and saves new popup images.
rem Popup text is usually an image: a person reads new images in other-platforms\kyobo-limits-images later.
rem Code in the dev repository (scripts\library, ADR-0047 C); data and logs in the data folder (CHAEKCHECK_DATA, else the
rem chaekcheck-data folder next to the repository).
set HERE=%~dp0
if "%CHAEKCHECK_DATA%"=="" set CHAEKCHECK_DATA=%HERE%..\..\..\chaekcheck-data
cd /d "%CHAEKCHECK_DATA%" || exit /b 1
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd"') do set DAY=%%d
python -X utf8 "%HERE%probe_kyobo_limits.py" --log other-platforms\kyobo-limits-log.tsv other-platforms\kyobo-limits-images >> logs\limits-daily-%DAY%.log 2>&1
