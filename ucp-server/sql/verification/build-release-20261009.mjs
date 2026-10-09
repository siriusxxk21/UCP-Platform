import { readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

// 本次发布从真实 PostgreSQL 临时表导出的结构契约装配，原迁移正文不做改写。
const root = new URL('../', import.meta.url)
const catalog = JSON.parse(readFileSync(new URL('../../ucp-nocode/.work/release-20261009/catalog.json', import.meta.url), 'utf8'))
const quoted = value => '$json$' + JSON.stringify(value, null, 2) + '$json$::jsonb'
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
const narrow = contracts => contracts.map(t => {
  const names = { drive_entry: ['managed_biz'], drive_space: ['owner_id'], infra_job: ['powerjob_job_id'] }[t.table]
  if (!names) return t
  return { ...t, columns: t.columns.filter(c => names.includes(c.name)), constraints: [],
    indexes: t.indexes.filter(i => t.table === 'drive_space' && i.name === 'drive_space_biz_name_uk') }
})
const final = narrow(catalog.stages[79])
// 状态/操作约束在本区间内有多次扩展，基线核对须识别每个真实中间版本。
const alternativesByTable = new Map()
for (const stage of Object.values(catalog.stages)) {
  for (const table of narrow(stage)) {
    let target = alternativesByTable.get(table.table)
    if (!target) {
      target = { table: table.table, columns: [], constraints: [], indexes: [] }
      alternativesByTable.set(table.table, target)
    }
    for (const key of ['columns', 'constraints', 'indexes']) {
      for (const entry of table[key]) {
        if (!target[key].some(old => JSON.stringify(old) === JSON.stringify(entry))) target[key].push(entry)
      }
    }
  }
}
// 后续版本已替换/删除的旧索引约束不作为重跑前提；最终仍严格检查 V079 的全部定义。
const contract = tables => narrow(tables).map(t => {
  const last = final.find(f => f.table === t.table)
  return { ...t, constraints: t.constraints.filter(x => last.constraints.some(y => y.name === x.name)),
    indexes: t.indexes.filter(x => last.indexes.some(y => y.name === x.name)) }
})
const relation = name => ({ relation: name })
const column = (table, name) => ({ table, column: name })
const menu = path => ({ menu: path })
const message = code => ({ message: code })
const check = (table, name, token) => ({ table, constraint: name, token })
const specific = {
  52: [{ navigation: '/nocode-app/task-center/launch' }],
  54: [column('nocode_task_instance', 'planned_start'), { nullable: 'assignee_id', table: 'nocode_task_instance' },
    relation('nocode_task_launch_draft'), relation('nocode_task_claimable_idx'), message('nocode-task-assignment')],
  56: [check('nocode_task_entry_record', 'nocode_task_entry_record_operation_check', "'DELETED'")],
  57: [check('nocode_task_entry_record', 'nocode_task_entry_record_operation_check', "'UNCHANGED'")],
  59: [check('nocode_task_instance', 'nocode_task_state_ck', "'PENDING_ACCEPTANCE'"), relation('nocode_task_acceptor_idx')],
  60: [message('nocode-task-acceptance')],
  63: [relation('nocode_application_object_follow'), relation('nocode_application_object_follow_log'), { job: 'applicationFollowRetryJob' }],
  68: [{ dependencies: true }],
  69: [menu('/nocode/report-center'), menu('/nocode/report-center/datasets'),
    ...['query', 'create', 'update', 'publish', 'manage', 'authorize'].map(p => ({ permission: 'nocode:report:' + p }))],
  71: [menu('/nocode/report-center/data-authorization')],
  73: [relation('nocode_report_dashboard'), relation('nocode_report_dashboard_version'), menu('/nocode/report-center/dashboards')],
  74: [column('nocode_report_dashboard', 'status'), column('nocode_report_dashboard', 'folder_id'),
    relation('nocode_report_preference'), menu('/nocode/report-center/home')],
  75: [column('nocode_task_template', 'primary_version'), check('nocode_task_template', 'nocode_task_template_primary_version_ck', 'primary_version')],
  76: [check('nocode_task_instance', 'nocode_task_state_ck', "'PAUSED'")],
  78: [menu('/nocode-app/task-center/efficiency')]
}
const plans = []
for (const m of catalog.migrations.filter(m => m.version >= 52 && m.version <= 79)) {
  const bytes = readFileSync(new URL('postgresql/migrations/' + m.script, root))
  if (bytes.toString('utf8') !== m.sql) throw new Error('结构目录过期：' + m.script)
  const tables = [...m.sql.matchAll(/CREATE TABLE public\.(\w+)/g)].map(x => x[1])
  const alters = [...m.sql.matchAll(/ALTER TABLE public\.(\w+)\s+([\s\S]*?);/g)]
  const columns = alters.flatMap(x => [...x[2].matchAll(/ADD COLUMN (\w+)/g)].map(c => column(x[1], c[1])))
  const indexes = [...m.sql.matchAll(/CREATE (?:UNIQUE )?INDEX (\w+)[\s\S]*?ON public\.(\w+)/g)].map(x => ({ name: x[1], table: x[2] }))
  const affected = new Set([...tables, ...alters.map(x => x[1]), ...indexes.map(x => x.table)])
  let markers = [...tables.map(relation), ...columns]
  if (!markers.length) markers = indexes.map(x => relation(x.name))
  markers = specific[m.version] || markers
  if (!markers.length) throw new Error('缺少迁移判断标记：' + m.script)
  plans.push(`-- ${m.script}；原文件 SHA-256: ${sha(bytes)}\nINSERT INTO nocode_release_plan VALUES\n(${m.version},${quoted(markers)},${quoted(contract(catalog.stages[m.version].filter(t => affected.has(t.table))))},$v${m.version}$${m.sql}$v${m.version}$);`)
}
if (plans.length !== 28) throw new Error('迁移范围不完整')
const history = catalog.migrations.map(({ sql, ...identity }) => identity)
// 核对用户提供旧发布文件中的 V001-V051 身份，不能把当前不同分支误当成同一基线。
if (process.argv[2]) {
  const previous = readFileSync(process.argv[2], 'utf8')
  const match = previous.match(/jsonb_to_recordset\(\$json\$([\s\S]*?)\$json\$::jsonb\)/)
  if (!match) throw new Error('旧文件缺少可核对的原迁移身份')
  for (const old of JSON.parse(match[1])) {
    const now = history.find(h => h.version === old.version)
    if (!now || now.script !== old.script || now.checksum !== old.checksum) throw new Error('旧发布基线冲突 V' + old.version)
  }
  console.log('旧发布文件 V001-V051 的文件身份与 Flyway 校验和全部一致')
}
const oldTemplate = readFileSync(new URL('release-20261001.sql.in', import.meta.url), 'utf8')
const helper = oldTemplate.slice(oldTemplate.indexOf('-- 所有校验辅助对象'), oldTemplate.indexOf('DO $release$'))
  .replace('(expected jsonb) RETURNS', '(expected jsonb, exact boolean DEFAULT false) RETURNS')
  .replace('FROM nocode_release_final,', 'FROM nocode_release_alternatives,')
  .replaceAll("WHERE value->>'name'=x->>'name';", "WHERE value->>'name'=x->>'name' AND NOT exact;")
let template = readFileSync(new URL('release-20261009.sql.in', import.meta.url), 'utf8')
for (const [key, value] of Object.entries({ HISTORY: quoted(history), PLANS: plans.join('\n\n'),
  BASELINE: quoted(contract(catalog.stages[51])), FINAL: quoted(final),
  ALTERNATIVES: quoted([...alternativesByTable.values()]), HELPER: helper })) {
  template = template.replace('/* ' + key + ' */', () => value)
}
if (/\/\* (HISTORY|PLANS|BASELINE|FINAL|ALTERNATIVES|HELPER) \*\//.test(template)) throw new Error('模板装配不完整')
const target = new URL('postgresql/manual/upgrade_20261009_v052_v079.sql', root)
writeFileSync(target, template.replaceAll('\r\n', '\n'), 'utf8')
console.log('已生成 ' + target.pathname + '；SHA-256 ' + sha(readFileSync(target)))
