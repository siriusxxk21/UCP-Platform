import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse, compileTemplate } from 'vue/compiler-sfc'

// 模板表达式也需要真实 Vue 编译器检查；vue-tsc 不能替代此检查。
const repository = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim()
const changed = execFileSync(
  'git',
  ['-c', 'core.safecrlf=false', 'diff', '--name-only', '--diff-filter=ACMRTUXB', '--', 'src'],
  { encoding: 'utf8' }
)
const added = execFileSync('git', ['ls-files', '--full-name', '--others', '--exclude-standard', '--', 'src'], {
  encoding: 'utf8'
})
const files = [...new Set((changed + '\n' + added).split(/\r?\n/).filter(file => file.endsWith('.vue')))]
const errors = []
for (const file of files) {
  const filename = resolve(repository, file)
  const result = parse(readFileSync(filename, 'utf8'), { filename })
  for (const error of result.errors) errors.push({ file, error: String(error) })
  if (result.descriptor.template) {
    const compiled = compileTemplate({
      filename,
      id: file,
      source: result.descriptor.template.content,
      compilerOptions: { expressionPlugins: ['typescript'] }
    })
    for (const error of compiled.errors) errors.push({ file, error: String(error) })
  }
}
console.log(JSON.stringify({ checked: files.length, errors }, null, 2))
if (errors.length) process.exitCode = 1
