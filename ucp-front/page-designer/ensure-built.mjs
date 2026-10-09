import { existsSync, readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const entries = ['index.html', 'canvas.html']
const entryUrl = new URL('../public/nocode-designer/index.html', import.meta.url)

// iframe 产物不入 Git；首次启动或切分支后自动补齐，防止 Vite 回退到主站首页。
if (
  entries.some(entry => !existsSync(new URL(`../public/nocode-designer/${entry}`, import.meta.url))) ||
  !readFileSync(entryUrl, 'utf8').includes('name="os-page-designer" content="editor"')
) {
  console.log('页面设计器尚未构建，正在安装锁定依赖并生成静态资源…')
  const result = spawnSync(process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm', ['run', 'build:designer'], {
    cwd: root,
    stdio: 'inherit',
    shell: process.platform === 'win32'
  })
  if (result.error || result.status !== 0) {
    console.error('页面设计器构建失败，请修复上述错误后重新启动。')
    process.exit(1)
  }
}
