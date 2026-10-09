import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, join, relative } from 'node:path'
import { parse } from 'vue/compiler-sfc'

// 与正式路由发现范围一致：排除只用于迁移参考的 _vben-src，统计源码入口而非虚报为页面数。
const root = resolve('src')
let scanned = 0
const entries = []
function scan(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = join(directory, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== '_vben-src') scan(file)
      continue
    }
    if (!file.endsWith('.vue')) continue
    scanned++
    const template = parse(readFileSync(file, 'utf8')).descriptor.template
    if (!template?.ast) continue
    function visit(node) {
      if (
        node.type === 1 &&
        ['a-select', 'a-tree-select', 'a-cascader', 'MemberSelect', 'GrantFieldSelect'].includes(node.tag)
      ) {
        const attributes = Object.fromEntries(
          node.props.map(p =>
            p.type === 6 ? [p.name, p.value?.content || ''] : [p.arg?.content || p.name, p.exp?.content || '']
          )
        )
        const multi =
          node.tag === 'GrantFieldSelect' ||
          ['multiple', 'tree-checkable'].some(key => key in attributes) ||
          ('mode' in attributes && attributes.mode !== 'single')
        if (multi)
          entries.push({
            file: relative(process.cwd(), file).replaceAll('\\', '/'),
            line: node.loc.start.line,
            component: node.tag,
            mode: attributes.mode ?? 'multiple',
            search:
              attributes['option-filter-prop'] ||
              attributes['tree-node-filter-prop'] ||
              attributes['filter-option'] ||
              '',
            maxTagCount: attributes['max-tag-count'] || ''
          })
      }
      for (const child of node.children || []) visit(child)
    }
    visit(template.ast)
  }
}
scan(root)
const result = {
  scannedVueFiles: scanned,
  sourceFilesWithMultiple: new Set(entries.map(e => e.file)).size,
  multipleCallSites: entries.length,
  entries,
  generatedEntry: 'src/nocode/record-form.ts：recordRules 生成运行端、预览表单中的多选/地区/级联控件'
}
const output = resolve(process.env.FORM_LAYOUT_OUTPUT || 'tests/test-results/form-layout')
mkdirSync(output, { recursive: true })
writeFileSync(join(output, 'inventory.json'), JSON.stringify(result, null, 2))
console.log(
  JSON.stringify({
    scannedVueFiles: scanned,
    sourceFilesWithMultiple: result.sourceFilesWithMultiple,
    multipleCallSites: entries.length,
    output
  })
)
