import { execFileSync } from 'node:child_process'
import { readFile, writeFile, unlink } from 'node:fs/promises'
import { delimiter, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 无删除入口的测试数据按台账精确清理；工具自身不解析数据源配置。
const directory = fileURLToPath(new URL('.', import.meta.url))
const server = resolve(directory, '../../../os-server')
export async function cleanupFixtures(ledger) {
  const dependencies = (await readFile(resolve(server, 'os-nocode/.work/classpath.txt'), 'utf8')).trim()
  const classpath = [
    resolve(server, 'os-server/src/main/resources'),
    resolve(server, 'os-nocode/os-nocode-tools/target/classes'),
    dependencies
  ].join(delimiter)
  const argumentFile = resolve(ledger) + '.cleanup.args'
  const args = ['--class-path', classpath, resolve(directory, 'FixtureCleanup.java'), resolve(ledger)]
  await writeFile(argumentFile, args.map(value => JSON.stringify(value.replaceAll('\\', '/'))).join('\n'))
  try {
    const output = execFileSync('java', ['@' + argumentFile], { cwd: server, encoding: 'utf8', timeout: 60000 })
    await writeFile(resolve(ledger) + '.cleanup.log', output)
  } finally {
    await unlink(argumentFile)
  }
}
if (process.argv[2]) await cleanupFixtures(process.argv[2])
