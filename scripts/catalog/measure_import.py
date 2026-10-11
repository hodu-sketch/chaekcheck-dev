"""One measured import run (cycle 11 handoff step 11, ADR-0079): a dry run with result files, or an export followed by
the ids of catalog_next and a discard. Records the six measures of the step: memory, time, database disk, CPU,
search during the import, and the result files for compare_grouping.py.

Usage (from the repository root; WSL with the compose db up; a service on CHAEKCHECK_BASE, default 8080, for the
search probe):
  python scripts/catalog/measure_import.py <out dir> <dryrun|export> <grouping> <xmx> <sqlite path> [extra JVM options]

Writes into <out dir>: import.log, gc.log, probe/ (probe_during_import.py), result/ (dry run: books.tsv.gz,
rows.tsv.gz; export: book-ids.tsv.gz, holding-ids.tsv.gz), discard.log (export), summary.json, and prints one line.
The export file itself is deleted after its ids are read (the catalog_next ids are the result). The PC catalog stays:
every export ends with a discard. The JVM runs with -XX:+ExitOnOutOfMemoryError, and a run still going after 90 minutes
is stopped (exit "timeout").

summary.json: exit code, wall seconds, step_seconds (from the log), heap peak (ImportReport, MB: the heap pools' peaks
summed, an upper bound), live data (largest heap after a full collection; after a remark as an upper bound; MB, from gc.log), Java process CPU seconds (log), database container CPU seconds (cgroup
cpu.stat usage_usec difference), temp_bytes difference (pg_stat_database), database size before and largest (bytes,
probe/db-size.tsv), search p50/p95/max ms and non-2xx count (probe/probe.tsv).
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENV = dict(os.environ, MSYS_NO_PATHCONV='1', PYTHONIOENCODING='utf-8')
COMPOSE = ['wsl', '-d', 'Ubuntu', '--exec', 'docker', 'compose', '-p', 'chaekcheck', 'exec', '-T', 'db']
PG_COMMAND = 'wsl,-d,Ubuntu,--exec,docker,compose,-p,chaekcheck,exec,-T,db'


def env_value(name):
    with open(os.path.join(ROOT, '.env'), encoding='utf-8') as f:
        for line in f:
            if line.startswith(name + '='):
                return line.split('=', 1)[1].strip().strip('"')
    raise SystemExit(f'{name} missing in .env')


def psql(sql):
    user, database = env_value('DB_USERNAME'), env_value('DB_NAME')
    out = subprocess.run(COMPOSE + ['psql', '-U', user, '-d', database, '-X', '-A', '-t', '-c', sql],
                         capture_output=True, text=True, env=ENV, encoding='utf-8')
    return out.stdout.strip()


def container_cpu_usec():
    out = subprocess.run(COMPOSE + ['cat', '/sys/fs/cgroup/cpu.stat'], capture_output=True, text=True, env=ENV)
    match = re.search(r'usage_usec (\d+)', out.stdout)
    return int(match.group(1)) if match else None


def temp_bytes():
    return int(psql('SELECT temp_bytes FROM pg_stat_database WHERE datname = current_database()') or 0)


# a run that has not ended by then is stopped (a heap too small can keep collecting for hours, cycle 08)
TIME_LIMIT_S = 90 * 60


def stop_import_java():
    subprocess.run(['powershell', '-NoProfile', '-Command',
                    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like "
                    "'*collection-import*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"], env=ENV)


def gradle(args, log_path, jvm_args, xmx, limit=None):
    command = [os.path.join(ROOT, 'backend', 'gradlew.bat'), 'bootRun', f'-PbootRunXmx={xmx}',
               '--args=' + ' '.join(args)]
    if jvm_args:
        command.insert(3, f'-PbootRunJvmArgs={jvm_args}')
    with open(log_path, 'w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=os.path.join(ROOT, 'backend'), stdout=log, stderr=subprocess.STDOUT,
                                   env=ENV)
        try:
            return process.wait(timeout=limit)
        except subprocess.TimeoutExpired:
            stop_import_java()
            process.wait(timeout=300)
            return 'timeout'


def parse_log(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    result = {}
    steps = re.findall(r'time: \{([^}]*)\}', text)
    if steps:
        result['steps'] = {k: v for k, v in re.findall(r'(\w+)=PT([0-9.HMS]+)', steps[-1])}
    heap = re.findall(r'heap used up to (\d+) MB', text)
    if heap:
        result['heap_peak_mb'] = int(heap[-1])
    cpu = re.findall(r'process cpu ([0-9.]+) s, uptime ([0-9.]+) s', text)
    if cpu:
        result['java_cpu_s'], result['java_uptime_s'] = float(cpu[-1][0]), float(cpu[-1][1])
    result['out_of_memory'] = 'OutOfMemoryError' in text or 'java.lang.OutOfMemoryError' in text         or 'Terminating due to java.lang.OutOfMemoryError' in text
    return result


def parse_gc(path):
    """From the unified GC log (G1): the largest heap after a full collection (live data), after a remark (an upper
    bound: garbage found by that marking is not freed yet), after any pause (young pauses with an evacuation failure
    report a full heap), and the number of full collections."""
    if not os.path.exists(path):
        return {}
    live, remark, after, full = [], [], [], 0
    for line in open(path, encoding='utf-8', errors='replace'):
        match = re.search(r'Pause (Young|Full|Remark|Cleanup).*?(\d+)M->(\d+)M\((\d+)M\)', line)
        if not match:
            continue
        value = int(match.group(3))
        after.append(value)
        if match.group(1) == 'Full':
            live.append(value)
        elif match.group(1) == 'Remark':
            remark.append(value)
        full += match.group(1) == 'Full'
    return {'gc_full_after_max_mb': max(live) if live else None, 'gc_remark_after_max_mb': max(remark) if remark else None,
            'gc_heap_after_max_mb': max(after) if after else None,
            'gc_full_count': full}


def parse_probe(folder):
    result = {}
    probe = os.path.join(folder, 'probe.tsv')
    if os.path.exists(probe):
        times, bad = [], 0
        for line in open(probe, encoding='utf-8'):
            parts = line.rstrip('\n').split('\t')
            if len(parts) >= 4 and parts[1] == 'search':
                try:
                    times.append(float(parts[3]))
                except ValueError:
                    continue
                bad += not parts[2].startswith('2')
        times.sort()
        if times:
            result.update(search_count=len(times), search_p50_ms=round(times[len(times) // 2]),
                          search_p95_ms=round(times[int(len(times) * 0.95)]), search_max_ms=round(times[-1]),
                          search_non_2xx=bad)
    sizes = os.path.join(folder, 'db-size.tsv')
    if os.path.exists(sizes):
        values = [int(p[1]) for p in (line.split('\t') for line in open(sizes, encoding='utf-8'))
                  if len(p) > 1 and p[1].strip().isdigit()]
        if values:
            result.update(db_size_first=values[0], db_size_max=max(values))
    return result


def main(out_dir, mode, grouping, xmx, sqlite, jvm_extra=''):
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    result_dir = os.path.join(out_dir, 'result')
    probe_dir = os.path.join(out_dir, 'probe')
    gc_log = os.path.join(out_dir, 'gc.log').replace('\\', '/')
    # an OutOfMemoryError ends the run at once instead of leaving a half-dead process
    jvm_args = (jvm_extra + ' ' if jvm_extra else '') + f'-XX:+ExitOnOutOfMemoryError -Xlog:gc*:file={gc_log}'
    args = ['--spring.profiles.active=collection-import', f'--chaekcheck.collection.import.sqlite-path={sqlite}',
            '--chaekcheck.collection.import.ttl-path=../data/nlk/ttl', f'--chaekcheck.collection.import.grouping={grouping}',
            '--chaekcheck.catalog.load.maintenance-work-mem=1GB',
            '--chaekcheck.catalog.load.max-parallel-maintenance-workers=4']
    export_file = os.path.join(out_dir, 'export.dump').replace('\\', '/')
    if mode == 'dryrun':
        args += ['--chaekcheck.collection.import.dry-run=true',
                 f'--chaekcheck.collection.import.dry-run-output={result_dir.replace(os.sep, "/")}']
    else:
        args += ['--chaekcheck.collection.import.mode=export', f'--chaekcheck.collection.import.file={export_file}',
                 f'--chaekcheck.catalog.load.pg-command={PG_COMMAND}']

    summary = {'mode': mode, 'grouping': grouping, 'xmx': xmx, 'jvm_extra': jvm_extra, 'sqlite': sqlite}
    cpu_before, temp_before = container_cpu_usec(), temp_bytes()
    probe = subprocess.Popen([sys.executable, os.path.join(ROOT, 'scripts', 'catalog', 'probe_during_import.py'),
                              probe_dir, '1'], cwd=ROOT, env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.time()
    summary['exit'] = gradle(args, os.path.join(out_dir, 'import.log'), jvm_args, xmx, TIME_LIMIT_S)
    summary['wall_s'] = round(time.time() - started, 1)
    cpu_after, temp_after = container_cpu_usec(), temp_bytes()
    os.makedirs(probe_dir, exist_ok=True)
    open(os.path.join(probe_dir, 'stop'), 'w').close()
    probe.wait(timeout=120)
    if cpu_before is not None and cpu_after is not None:
        summary['db_cpu_s'] = round((cpu_after - cpu_before) / 1e6, 1)
    summary['temp_bytes'] = temp_after - temp_before
    summary.update(parse_log(os.path.join(out_dir, 'import.log')))
    summary.update(parse_gc(gc_log))
    summary.update(parse_probe(probe_dir))
    if mode == 'export' and summary['exit'] == 0:
        subprocess.run([sys.executable, os.path.join(ROOT, 'scripts', 'catalog', 'compare_grouping.py'), 'ids', 'db',
                        result_dir], cwd=ROOT, env=ENV)
        if os.path.exists(export_file):
            summary['dump_bytes'] = os.path.getsize(export_file)
            os.remove(export_file)
    if mode == 'export':
        gradle(['--spring.profiles.active=collection-import', '--chaekcheck.collection.import.mode=discard'],
               os.path.join(out_dir, 'discard.log'), '', '512m')
    json.dump(summary, open(os.path.join(out_dir, 'summary.json'), 'w', encoding='utf-8'), indent=2)
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary['exit'] == 0 else 1


if __name__ == '__main__':
    if len(sys.argv) < 6:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(*sys.argv[1:7]))
