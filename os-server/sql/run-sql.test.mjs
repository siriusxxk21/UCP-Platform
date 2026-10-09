import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 只模拟命令行工具以验证发布编排；不连接任何数据库。
const sqlRoot = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(sqlRoot, 'postgresql/manual/server-upgrade/run-sql.sh');
const fixtureRoot = path.resolve(sqlRoot, '../os-nocode/.work/manual-runner-tests');
const shell = process.env.MANUAL_SQL_TEST_SHELL || 'sh';
mkdirSync(fixtureRoot, { recursive: true });

function runCase(t, mode, options = {}) {
  const folder = mkdtempSync(path.join(fixtureRoot, 'case-'));
  t.after(() => {
    assert.ok(path.resolve(folder).startsWith(fixtureRoot + path.sep));
    rmSync(folder, { recursive: true, force: true });
  });
  const delivery = path.join(folder, 'release with spaces');
  const bin = path.join(folder, 'bin');
  mkdirSync(delivery);
  mkdirSync(bin);
  writeFileSync(path.join(delivery, 'run-sql.sh'), readFileSync(runner));
  const sqlName = options.sqlName || 'upgrade.sql';
  if (!options.missingSql) writeFileSync(path.join(delivery, sqlName), 'SELECT 1;\n');
  if (options.locked) mkdirSync(path.join(delivery, '.run-sql.lock'));

  const common = '#!/bin/sh\nset -eu\n';
  writeFileSync(path.join(bin, 'psql'), common + `
sql_file=''
next_file=0
single=0
stop_on_error=0
ignore_rc=0
for value in "$@"; do
  if [ "$next_file" -eq 1 ]; then sql_file=$value; next_file=0; fi
  case "$value" in
    -f) next_file=1 ;;
    --single-transaction) single=1 ;;
    ON_ERROR_STOP=1) stop_on_error=1 ;;
    -X) ignore_rc=1 ;;
  esac
done
if [ -z "$sql_file" ]; then
  printf 'connect\\n' >> "$TASK_TRACE"
  [ "$TASK_MODE" != connection-failure ] || exit 2
  printf 'connected\\n'
else
  printf 'execute\\n' >> "$TASK_TRACE"
  [ "$single" -eq 1 ] && [ "$stop_on_error" -eq 1 ] && [ "$ignore_rc" -eq 1 ] || exit 90
  [ -s "$(dirname "$sql_file")/database.dump" ] || exit 91
  [ "$TASK_MODE" != sql-failure ] || exit 3
  printf 'sql completed\\n'
fi
`, { mode: 0o755 });
  writeFileSync(path.join(bin, 'pg_dump'), common + `
printf 'backup\\n' >> "$TASK_TRACE"
[ "$TASK_MODE" != backup-failure ] || exit 23
for value in "$@"; do
  case "$value" in --file=*) output=\${value#--file=} ;; esac
done
printf 'mock custom archive\\n' > "$output"
`, { mode: 0o755 });
  writeFileSync(path.join(bin, 'pg_restore'), common + `
printf 'check-backup\\n' >> "$TASK_TRACE"
[ "$TASK_MODE" != bad-backup ] || exit 2
printf 'mock archive contents\\n'
`, { mode: 0o755 });

  const env = { ...process.env };
  for (const name of Object.keys(env)) if (name.startsWith('PG')) delete env[name];
  // Windows 上使用现有 Git 的 sh.exe；命令替身位于 PATH 首位，确保不碰真实数据库。
  const pathKey = Object.keys(env).find(name => name.toUpperCase() === 'PATH') || 'PATH';
  env[pathKey] = [bin, path.dirname(shell), env[pathKey] || ''].join(path.delimiter);
  env.PGPASSWORD = options.noPassword ? '' : 'fixture password with spaces';
  env.TASK_TRACE = path.join(folder, 'events.log').replaceAll('\\', '/');
  env.TASK_MODE = mode;
  const args = [path.join(delivery, 'run-sql.sh')];
  if (options.sqlName) args.push(sqlName);
  const result = spawnSync(shell, args, { cwd: folder, env, encoding: 'utf8', timeout: 20000 });
  assert.ifError(result.error);
  const output = result.stdout + result.stderr;
  assert.ok(!output.includes('fixture password with spaces'), 'password leaked');
  const events = existsSync(env.TASK_TRACE) ? readFileSync(env.TASK_TRACE, 'utf8').trim().split('\n') : [];
  const backups = path.join(delivery, 'backups');
  const runs = existsSync(backups) ? readdirSync(backups).map(name => path.join(backups, name)) : [];
  return { result, output, events, runs, delivery };
}

test('脚本语法检查', () => {
  const result = spawnSync(shell, ['-n', runner], { encoding: 'utf8' });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
});

test('从其他目录执行：先备份后执行，保留快照和日志，使用单事务', t => {
  const { result, output, events, runs, delivery } = runCase(t, 'success');
  assert.equal(result.status, 0, output);
  assert.deepEqual(events, ['connect', 'backup', 'check-backup', 'execute']);
  assert.match(output, /os-newserver0916/);
  assert.match(output, /aios/);
  assert.equal(runs.length, 1);
  assert.equal(readFileSync(path.join(runs[0], 'applied.sql'), 'utf8'), 'SELECT 1;\n');
  assert.ok(existsSync(path.join(runs[0], 'database.dump')));
  assert.ok(existsSync(path.join(runs[0], 'upgrade.log')));
  assert.ok(!existsSync(path.join(delivery, '.run-sql.lock')));
});

for (const [mode, expected] of [
  ['connection-failure', ['connect']],
  ['backup-failure', ['connect', 'backup']],
  ['bad-backup', ['connect', 'backup', 'check-backup']],
  ['sql-failure', ['connect', 'backup', 'check-backup', 'execute']]
]) {
  test(`${mode}：失败停止并释放自身锁`, t => {
    const { result, output, events, delivery } = runCase(t, mode);
    assert.notEqual(result.status, 0, output);
    assert.deepEqual(events, expected);
    assert.ok(!existsSync(path.join(delivery, '.run-sql.lock')));
    if (mode === 'sql-failure') assert.equal(result.status, 3);
  });
}

test('SQL 缺失时不连接数据库', t => {
  const { result, events } = runCase(t, 'success', { missingSql: true });
  assert.notEqual(result.status, 0);
  assert.deepEqual(events, []);
});

test('已有锁时不运行且不删除其他实例的锁', t => {
  const { result, events, delivery } = runCase(t, 'success', { locked: true });
  assert.notEqual(result.status, 0);
  assert.deepEqual(events, []);
  assert.ok(existsSync(path.join(delivery, '.run-sql.lock')));
});

test('非交互缺少密码时不连接数据库', t => {
  const { result, events } = runCase(t, 'success', { noPassword: true });
  assert.notEqual(result.status, 0);
  assert.deepEqual(events, []);
});

test('支持同目录含空格的自定义 SQL 文件名', t => {
  const { result, output, events } = runCase(t, 'success', { sqlName: 'next step.sql' });
  assert.equal(result.status, 0, output);
  assert.equal(events.at(-1), 'execute');
});
