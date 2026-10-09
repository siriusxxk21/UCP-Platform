import { readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

// 本批交付的可复现装配入口；SQL 来源仅为已封存迁移，catalog 由真实 PostgreSQL 临时夹具生成。
const root = new URL('../', import.meta.url)
const catalog = JSON.parse(readFileSync(new URL('../../os-nocode/.work/release-20261001/catalog.json', import.meta.url), 'utf8'))
const migrations = catalog.migrations.filter(m => m.version >= 38 && m.version <= 51)
const literal = value => "'" + value.replaceAll("'", "''") + "'"
const quotedJson = value => '$json$' + JSON.stringify(value, null, 2) + '$json$::jsonb'
const history = catalog.migrations.map(({ sql, ...identity }) => identity)
// 既有底座表只核对本次改动列/索引；不把开发库的其他扩展当作线上必须一致的条件。
const narrow = contracts => contracts.map(t => {
  const columns = { drive_entry: ['managed_biz'], drive_space: ['owner_id'], infra_job: ['powerjob_job_id'] }[t.table]
  if (!columns) return t
  return { ...t, columns: t.columns.filter(c => columns.includes(c.name)), constraints: [],
    indexes: t.indexes.filter(i => t.table === 'drive_space' && i.name === 'drive_space_biz_name_uk') }
})
for (const [stage, contracts] of Object.entries(catalog.stages)) {
  for (const table of contracts) {
    for (const item of [...table.indexes, ...table.constraints]) {
      if (/\b(?:public|pg_temp(?:_\d+)?)\./.test(item.definition)) {
        throw new Error(`V${stage} ${table.table}.${item.name} 校验基准包含环境 schema；请重新生成 catalog`)
      }
    }
  }
}
const plans = migrations.map(m => {
  const original = readFileSync(new URL(`postgresql/migrations/${m.script}`, root), 'utf8')
  if (original !== m.sql) throw new Error('catalog 已过期：' + m.script)
  const tables = [...original.matchAll(/CREATE TABLE public\.(\w+)/g)].map(x => x[1])
  const alters = [...original.matchAll(/ALTER TABLE public\.(\w+)\s+([\s\S]*?);/g)]
  const columns = alters.flatMap(x => [...x[2].matchAll(/ADD COLUMN (?!IF NOT EXISTS)(\w+)/g)].map(c => ({ table: x[1], column: c[1] })))
  const indexes = [...original.matchAll(/CREATE (?:UNIQUE )?INDEX (\w+)[\s\S]*?ON public\.(\w+)/g)].map(x => ({ relation: x[1], table: x[2] }))
  const affected = new Set([...tables, ...alters.map(x => x[1]), ...indexes.map(x => x.table)])
  let markers = tables.map(table => ({ relation: table }))
  markers.push(...columns)
  if (!markers.length) markers = indexes.map(({ relation }) => ({ relation }))
  if (m.version === 41) markers = [{ permission: 'nocode:task:manage-all' }]
  if (m.version === 42) markers = [{ topLevel: '/task-center' }]
  if (m.version === 51) markers = [{ relation: 'drive_space_biz_name_uk' }]
  const contract = narrow(catalog.stages[m.version].filter(t => affected.has(t.table)))
  const sha = createHash('sha256').update(readFileSync(new URL(`postgresql/migrations/${m.script}`, root))).digest('hex')
  return `-- V${String(m.version).padStart(3, '0')} 原文件 SHA-256: ${sha}\nINSERT INTO nocode_release_plan(version,markers,contract,body) VALUES\n(${m.version},${quotedJson(markers)},${quotedJson(contract)},$v${m.version}$${original}$v${m.version}$);`
})
let template = readFileSync(new URL('release-20261001.sql.in', import.meta.url), 'utf8')
template = template.replace('/* HISTORY */', () => quotedJson(history)).replace('/* PLANS */', () => plans.join('\n\n'))
template = template.replace('/* FINAL CONTRACT */', () => quotedJson(narrow(catalog.stages[51])))
const target = new URL('postgresql/manual/upgrade_20261001_v038_v051.sql', root)
writeFileSync(target, template.replaceAll('\r\n', '\n'), 'utf8')
console.log('Generated ' + target.pathname)
