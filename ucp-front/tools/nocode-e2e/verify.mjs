import { spawn } from 'node:child_process'
import { createWriteStream, existsSync } from 'node:fs'
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { parseArgs } from 'node:util'

// 复用已有真实 HTTP/Chrome 夹具；串行运行，避免并行配置与页面 HMR 干扰验收。
const root = fileURLToPath(new URL('../../', import.meta.url))
const { values } = parseArgs({
  options: { only: { type: 'string' }, output: { type: 'string' }, label: { type: 'string', default: 'verification' } }
})
const suites = [
  { name: 'reference', script: 'tools/three-center-regression/verify-reference-ui.mjs' },
  { name: 'load-recovery', script: 'tools/three-center-regression/verify-design-load-recovery-ui.mjs' },
  { name: 'editing', script: 'tools/nocode-e2e/verify-editing.mjs' },
  { name: 'table-calculation', script: 'tools/nocode-e2e/verify-table-calculation.mjs' },
  { name: 'report', script: 'tools/nocode-e2e/verify-report.mjs' },
  { name: 'designer', script: 'tools/nocode-e2e/verify-designer.mjs' },
  { name: 'resource-config', script: 'tools/nocode-e2e/verify-resource-config.mjs' },
  { name: 'related-form', script: 'tools/related-form-regression/verify.mjs' },
  { name: 'task-document', script: 'tools/task-entry-regression/verify-document.mjs' },
  { name: 'data-view', script: 'tools/four-center-regression/verify-data-view.mjs' }
]
const selected = values.only?.split(',') || suites.map(suite => suite.name)
if (selected.some(name => !suites.some(suite => suite.name === name))) throw new Error('未知的 nocode 回归场景')
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const api = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const browser = process.env.NOCODE_VERIFY_BROWSER || 'chrome'
const output = resolve(root, values.output || `.work/nocode-e2e/${new Date().toISOString().replace(/[:.]/g, '-')}`)
if (existsSync(resolve(output, 'run.json'))) throw new Error('输出目录已有验收记录，请使用新目录保留前后对照')
await mkdir(output, { recursive: true })
const result = { label: values.label, started: new Date().toISOString(), origin, api, browser, suites: [] }
const persist = () => writeFile(resolve(output, 'run.json'), JSON.stringify(result, null, 2))
await persist()

// 只检查服务可达，不从浏览器配置推断数据源，也不自动切换到历史远程测试地址。
try {
  for (const url of [origin, api + '/system/auth/get-permission-info']) {
    const response = await fetch(url, { signal: AbortSignal.timeout(10000) })
    if (response.status >= 500) throw new Error(`开发服务未就绪：${url} HTTP ${response.status}`)
  }
} catch (error) {
  result.passed = false
  result.failure = error.message
  result.finished = new Date().toISOString()
  await persist()
  throw error
}

async function reports(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const results = []
  for (const entry of entries.filter(entry => entry.isDirectory())) {
    const folder = resolve(directory, entry.name)
    const files = await readdir(folder)
    const report = files.includes('result.json')
      ? 'result.json'
      : files.includes('http-result.json')
        ? 'http-result.json'
        : null
    if (!report) continue
    const detail = JSON.parse(await readFile(resolve(folder, report), 'utf8'))
    results.push({
      path: resolve(folder, report),
      checks: detail.checks?.length || 0,
      failure: detail.failure || detail.checks?.find(check => check?.passed === false)?.error,
      errors: detail.errors || [],
      manifest: files.includes('http-result.json') ? resolve(folder, 'http-result.json') : null
    })
  }
  return results
}

for (const suite of suites.filter(suite => selected.includes(suite.name))) {
  const directory = resolve(output, suite.name)
  await mkdir(directory, { recursive: true })
  console.log(`RUN ${suite.name}`)
  const started = Date.now()
  const log = createWriteStream(resolve(directory, 'process.log'))
  const child = spawn(process.execPath, [suite.script], {
    cwd: root,
    env: {
      ...process.env,
      NOCODE_VERIFY_PREFIX: '',
      NOCODE_VERIFY_URL: origin,
      NOCODE_VERIFY_API: api,
      NOCODE_VERIFY_BROWSER: browser,
      NOCODE_VERIFY_OUTPUT: directory,
      TASK_ENTRY_URL: origin,
      TASK_ENTRY_API: api,
      TASK_ENTRY_BROWSER: browser,
      TASK_ENTRY_OUTPUT: directory,
      FOUR_CENTER_URL: origin,
      FOUR_CENTER_API: api,
      FOUR_CENTER_BROWSER: browser,
      FOUR_CENTER_OUTPUT: directory
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout.pipe(log, { end: false })
  child.stderr.pipe(log, { end: false })
  const code = await new Promise((resolveCode, reject) => {
    child.once('error', reject)
    child.once('close', resolveCode)
  })
  await new Promise(resolveLog => log.end(resolveLog))
  const details = await reports(directory)
  const passed =
    code === 0 &&
    details.length > 0 &&
    details.every(detail => !detail.failure && !detail.errors.length && detail.checks > 0)
  result.suites.push({
    name: suite.name,
    script: suite.script,
    passed,
    code,
    durationMs: Date.now() - started,
    details
  })
  await persist()
  console.log(
    `${passed ? 'PASS' : 'FAIL'} ${suite.name}: ${details.reduce((sum, detail) => sum + detail.checks, 0)} checks; ${directory}`
  )
}
result.finished = new Date().toISOString()
result.passed = result.suites.every(suite => suite.passed)
await persist()
console.log(`Report: ${resolve(output, 'run.json')}`)
if (!result.passed) process.exitCode = 1
