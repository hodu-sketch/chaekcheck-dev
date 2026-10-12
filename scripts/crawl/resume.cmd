@echo off
rem Resume the e-book catalogue crawl. Safe to run again at any time:
rem finished libraries are skipped, unfinished ones continue from the last saved page.
rem Only one copy may run at a time (two copies would hit the same library sites together). The check also finds a
rem collector started from the old chaekcheck-data\scripts copy.
rem
rem Parallelism is chosen per run:  resume.cmd [WORKERS] [PER_HOST] [GAP]
rem   WORKERS   servers crawled at the same time            (default 40)
rem   PER_HOST  requests at once on one server              (default 1 = agreed politeness rule)
rem   GAP       seconds between requests to one server      (default 1.0)
rem   e.g.  resume.cmd 60 1 1.0
rem
rem The code is in the dev repository (scripts\crawl, ADR-0047 B); the data stays outside git in the data folder:
rem CHAEKCHECK_DATA if set, else the chaekcheck-data folder next to the repository. queue-by-ip.txt and logs\ are read
rem and written there, with the same meaning as before the move.
rem Since cycle 13 (spec R1-6) no request goes to the KYOBO subscription servers (*.dkyobobook.co.kr; they blocked our
rem IP), so --include-big-subscription is gone and host-slots.txt (requests at once on those servers) is not read.
set HERE=%~dp0
if "%CHAEKCHECK_DATA%"=="" set CHAEKCHECK_DATA=%HERE%..\..\..\chaekcheck-data
cd /d "%CHAEKCHECK_DATA%" || exit /b 1
set WORKERS=%1
set PER_HOST=%2
set GAP=%3
if "%WORKERS%"=="" set WORKERS=40
if "%PER_HOST%"=="" set PER_HOST=1
if "%GAP%"=="" set GAP=1.0
rem Queues by IP (user decision 2026-10-04): while the file queue-by-ip.txt exists, every IP gets one queue
rem (shared servers split by IP, domains on one IP share a queue). Delete the file to queue by host name again.
set SPLITARG=
if exist queue-by-ip.txt set SPLITARG=--queue-by-ip
if exist host-slots.txt echo host-slots.txt is ignored since cycle 13: its servers are never requested.
rem A full run or a refill-pubdt run of either copy (this one or the old one) blocks a second start.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object { ($_.CommandLine -like '*crawl_catalog.py*' -and ($_.CommandLine -like '* full *' -or $_.CommandLine -like '* refill-pubdt *')) -or $_.CommandLine -like '*refill_pubdt.py*' }) { exit 0 } else { exit 1 }" && (
  echo crawl_catalog.py is already running. Not starting a second copy.
  exit /b 1
)
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set STAMP=%%d
python -X utf8 "%HERE%crawl_catalog.py" --data-dir "%CHAEKCHECK_DATA%" full --retry-failed --audio-pass --workers %WORKERS% --per-host %PER_HOST% --gap %GAP% %SPLITARG% > logs\crawl-%STAMP%.log 2>&1
python -X utf8 "%HERE%crawl_catalog.py" --data-dir "%CHAEKCHECK_DATA%" status >> logs\crawl-%STAMP%.log 2>&1
