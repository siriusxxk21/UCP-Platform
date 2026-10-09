/** 只读取生产产物，记录 JS 字节、gzip 和 ESM 依赖边；不修改构建配置或加载业务接口。 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { gzipSync } from 'node:zlib'
import { createHash } from 'node:crypto'

const root = fileURLToPath(new URL('../../', import.meta.url))
const options = {}
for (let index = 2; index < process.argv.length; index++) {
  const option = process.argv[index]
  if (!['--dist', '--output', '--compare'].includes(option) || !process.argv[index + 1])
    throw new Error(
      '用法: node tools/nocode-e2e/bundle-report.mjs [--dist dist] [--output report.json] [--compare baseline.json]'
    )
  options[option.slice(2)] = path.resolve(process.cwd(), process.argv[++index])
}
const dist = options.dist || path.join(root, 'dist')
const output = options.output || path.join(root, '.work/nocode-phase2-20260913/bundle-report.json')
const packages = path.join(root, 'node_modules/.pnpm')
const lexerFolder = fs.readdirSync(packages).find(name => name.startsWith('es-module-lexer@'))
if (!lexerFolder) throw new Error('缺少当前 Vite 已安装的 es-module-lexer；请先使用项目既有依赖环境')
const { init, parse } = await import(
  pathToFileURL(path.join(packages, lexerFolder, 'node_modules/es-module-lexer/dist/lexer.js')).href
)
await init

const walk = directory =>
  fs
    .readdirSync(directory, { withFileTypes: true })
    .flatMap(item => (item.isDirectory() ? walk(path.join(directory, item.name)) : [path.join(directory, item.name)]))
const chunks = walk(dist)
  .filter(file => file.endsWith('.js'))
  .map(file => {
    const bytes = fs.readFileSync(file),
      relative = path.relative(dist, file).split(path.sep).join('/')
    const edges = parse(bytes.toString())[0]
      .filter(edge => edge.n?.startsWith('.'))
      .map(edge => ({
        file: path.posix.normalize(path.posix.join(path.posix.dirname(relative), edge.n)),
        dynamic: edge.d >= 0
      }))
    return {
      file: relative,
      bytes: bytes.length,
      gzipBytes: gzipSync(bytes).length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      modified: fs.statSync(file).mtime.toISOString(),
      staticImports: edges.filter(edge => !edge.dynamic).map(edge => edge.file),
      dynamicImports: edges.filter(edge => edge.dynamic).map(edge => edge.file)
    }
  })
const byFile = new Map(chunks.map(chunk => [chunk.file, chunk]))
const total = entries => ({
  files: entries.length,
  bytes: entries.reduce((sum, item) => sum + item.bytes, 0),
  gzipBytes: entries.reduce((sum, item) => sum + item.gzipBytes, 0)
})
function closure(file, index = byFile) {
  const visited = new Set()
  function visit(current) {
    if (visited.has(current) || !index.has(current)) return
    visited.add(current)
    index.get(current).staticImports.forEach(visit)
  }
  visit(file)
  return { file, members: [...visited], ...total([...visited].map(current => index.get(current))) }
}
const htmlEntries = html =>
  Array.from(fs.readFileSync(path.join(dist, html), 'utf8').matchAll(/<script\b[^>]*\bsrc="([^"]+)"[^>]*>/g), match =>
    match[1].replace(/^\//, '')
  )
const entry = htmlEntries('index.html')
const app = chunks.filter(chunk => chunk.file.startsWith('assets/'))
const designer = chunks.filter(chunk => chunk.file.startsWith('nocode-designer/'))
const describe = chunk => ({
  ...chunk,
  staticImporters: chunks.filter(item => item.staticImports.includes(chunk.file)).map(item => item.file),
  dynamicImporters: chunks.filter(item => item.dynamicImports.includes(chunk.file)).map(item => item.file)
})
const top = (entries, limit) =>
  entries
    .toSorted((a, b) => b.bytes - a.bytes)
    .slice(0, limit)
    .map(describe)
const boundaries = [
  'BasicLayout-',
  'workspace-',
  'runtime-',
  'ResourceManager-',
  'BusinessDesigner-',
  'ReportChart-',
  'TiptapEditor-'
]
const result = {
  captured: new Date().toISOString(),
  dist,
  entry,
  app: total(app),
  designer: total(designer),
  entryClosure: entry.map(file => closure(file)),
  selectedClosures: boundaries.flatMap(prefix =>
    app.filter(chunk => chunk.file.startsWith(`assets/${prefix}`)).map(chunk => closure(chunk.file))
  ),
  designerEntryClosures: ['nocode-designer/index.html', 'nocode-designer/canvas.html']
    .filter(html => fs.existsSync(path.join(dist, html)))
    .flatMap(html => htmlEntries(html).map(file => closure(file))),
  topApp: top(app, 15),
  topDesigner: top(designer, 10),
  chunks
}
if (options.compare) {
  const baseline = JSON.parse(fs.readFileSync(options.compare, 'utf8'))
  result.comparison = Object.fromEntries(
    ['app', 'designer'].map(scope => [
      scope,
      {
        bytes: result[scope].bytes - baseline[scope].bytes,
        gzipBytes: result[scope].gzipBytes - baseline[scope].gzipBytes,
        files: result[scope].files - baseline[scope].files
      }
    ])
  )
  const baselineIndex = new Map(baseline.chunks.map(chunk => [chunk.file, chunk]))
  result.closureComparison = boundaries.flatMap(boundary => {
    const prior = baseline.chunks.find(chunk => chunk.file.startsWith(`assets/${boundary}`))
    const current = result.selectedClosures.find(chunk => chunk.file.startsWith(`assets/${boundary}`))
    if (!prior || !current) return []
    const previous = closure(prior.file, baselineIndex)
    const metrics = ({ bytes, gzipBytes, files }) => ({ bytes, gzipBytes, files })
    return [
      {
        boundary,
        before: metrics(previous),
        after: metrics(current),
        delta: {
          bytes: current.bytes - previous.bytes,
          gzipBytes: current.gzipBytes - previous.gzipBytes,
          files: current.files - previous.files
        }
      }
    ]
  })
}
fs.mkdirSync(path.dirname(output), { recursive: true })
fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n')
console.log(
  JSON.stringify(
    {
      output,
      app: result.app,
      designer: result.designer,
      comparison: result.comparison,
      topApp: result.topApp.slice(0, 7).map(({ file, bytes, gzipBytes }) => ({ file, bytes, gzipBytes }))
    },
    null,
    2
  )
)
