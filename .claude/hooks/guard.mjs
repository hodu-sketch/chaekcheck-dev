// chaekcheck PreToolUse guard: exit 2 blocks the tool call and sends stderr to Claude.
import { readFileSync, existsSync } from 'node:fs';
import { basename } from 'node:path';

const input = JSON.parse(readFileSync(0, 'utf8') || '{}');
const tool = input.tool_name ?? '';
const cmd = input.tool_input?.command ?? '';
const file = (input.tool_input?.file_path ?? '').replace(/\\/g, '/');
const name = basename(file);
const block = (msg) => { process.stderr.write(msg + '\n'); process.exit(2); };

// 1. Maven: this project builds with the Gradle Wrapper only
if (tool === 'Bash' && /(^|[\s;&|(`])(\.\/)?mvnw?(\.cmd)?(\s|$)/.test(cmd))
  block('Blocked: this project uses Gradle only. Run ./gradlew in backend/ instead of Maven.');
if (tool !== 'Bash' && name === 'pom.xml')
  block('Blocked: do not create or edit pom.xml. This project uses Gradle (backend/build.gradle).');

// 2. Secrets: .env files are edited by the user, not by Claude (.env.example is allowed)
if (tool !== 'Bash' && /^\.env(\..+)?$/.test(name) && name !== '.env.example')
  block(`Blocked: ${name} may hold secrets. Ask the user to edit it; you may update .env.example instead.`);

// 3. Liquibase: existing changelogs may already be applied (checksum error if edited).
//    Before the first deployment the marker file changelog/PRE_DEPLOYMENT exists and changelogs may be reorganized
//    (local databases are reset). Delete the marker at the first deployment.
const changelogDir = file.includes('/backend/src/main/resources/db/changelog/')
  ? file.slice(0, file.indexOf('/backend/src/main/resources/db/changelog/')) + '/backend/src/main/resources/db/changelog/'
  : '';
if (tool !== 'Bash' && changelogDir && !existsSync(changelogDir + 'PRE_DEPLOYMENT')
    && name !== 'db.changelog-master.yaml' && existsSync(file)) {
  const body = readFileSync(file, 'utf8');
  if (!/runOnChange\s*:\s*true/.test(body))
    block(`Blocked: ${name} is an existing Liquibase changelog and may already be applied. Add a new changeset file and include it from db.changelog-master.yaml instead.`);
}
process.exit(0);
