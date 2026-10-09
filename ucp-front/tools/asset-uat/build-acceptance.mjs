import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'
import { scenarios, boundaries } from './scenarios.mjs'

const [sourceFile, outputArg, docsArg, runtimeArg] = process.argv.slice(2)
assert.ok(sourceFile && outputArg && docsArg && runtimeArg, '需要初始数据、交付目录、文档目录和专用 runtime 目录')
const data = JSON.parse(await fs.readFile(resolve(sourceFile), 'utf8'))
const output = resolve(outputArg),
  docs = resolve(docsArg)
const requireArtifact = createRequire(resolve(runtimeArg, 'package.json'))
const { Workbook, SpreadsheetFile } = requireArtifact('@oai/artifact-tool')
await fs.mkdir(output, { recursive: true })
await fs.mkdir(docs, { recursive: true })
const appUrl = `http://localhost:5173/nocode-app/runtime?id=${data.application.id}&menu=ledger_menu`
const wb = Workbook.create()
const cases = wb.worksheets.add('验收场景')
const assets = wb.worksheets.add('初始资产')
const guide = wb.worksheets.add('使用说明与对象')
const dark = '#3D326B',
  ink = '#282739',
  pale = '#FFF5CE'
function base(sheet, range) {
  sheet.showGridLines = false
  sheet.getRange(range).format.font = { name: 'Arial', size: 10, color: ink }
  sheet.getRange(range).format.verticalAlignment = 'center'
}
function title(sheet, text) {
  sheet.getRange('A2').values = [[text]]
  sheet.getRange('A2').format.font = { name: 'Arial', size: 16, bold: true, color: dark }
  sheet.getRange('A2').format.rowHeight = 28
}
function header(sheet, range) {
  sheet.getRange(range).format = {
    fill: dark,
    font: { name: 'Arial', size: 10, bold: true, color: '#FFFFFF' },
    horizontalAlignment: 'center',
    verticalAlignment: 'center',
    rowHeight: 29,
    wrapText: true
  }
}
function units(text) {
  return [...String(text)].reduce((n, c) => n + (c.charCodeAt(0) > 255 ? 2 : 1), 0)
}
const end = scenarios.length + 6
base(cases, `A1:I${end}`)
cases.tabColor = dark
title(cases, '公司资产管理业务验收')
cases.getRange('A3:H3').values = [['场景总数', scenarios.length, '待验收', null, '通过', null, '未通过/阻塞', null]]
cases.getRange('D3').formulas = [[`=COUNTIFS(F7:F${end},"待验收")`]]
cases.getRange('F3').formulas = [[`=COUNTIFS(F7:F${end},"通过")`]]
cases.getRange('H3').formulas = [[`=COUNTIFS(F7:F${end},"未通过")+COUNTIFS(F7:F${end},"阻塞")`]]
cases.getRange('A4').values = [['按编号顺序执行。黄色列填写结果与实测。账号、初始数据和人工办理边界见后两个工作表。']]
cases.getRange('A6:I6').values = [
  [
    '编号',
    '验收场景',
    '账号与前置条件',
    '操作步骤',
    '预期结果',
    '验收结果',
    '实际结果/问题',
    '截图或问题编号',
    '执行日期'
  ]
]
cases.getRange(`A7:I${end}`).values = scenarios.map(s => [
  s.id,
  s.name,
  s.prerequisite,
  s.steps,
  s.expected,
  s.result,
  '',
  '',
  null
])
const table = cases.tables.add(`A6:I${end}`, true, 'AssetAcceptanceCases')
table.showFilterButton = true
table.style = 'TableStyleLight1'
header(cases, 'A6:I6')
cases.getRange(`A7:I${end}`).format.wrapText = true
cases.getRange(`C7:I${end}`).format.verticalAlignment = 'top'
const widths = { A: 9, B: 24, C: 29, D: 58, E: 58, F: 13, G: 42, H: 26, I: 14 }
for (const [col, width] of Object.entries(widths)) cases.getRange(`${col}1:${col}${end}`).format.columnWidth = width
scenarios.forEach((s, i) => {
  const lines = Math.max(
    Math.ceil(units(s.prerequisite) / 26),
    Math.ceil(units(s.steps) / 51),
    Math.ceil(units(s.expected) / 51),
    3
  )
  cases.getRange(`A${i + 7}:I${i + 7}`).format.rowHeight = 14 * lines + 12
})
cases.getRange(`F7:I${end}`).format.fill = pale
cases.getRange(`F7:F${end}`).dataValidation = {
  rule: { type: 'list', values: ['待验收', '通过', '未通过', '阻塞', '不适用'] }
}
cases.getRange(`F7:F${end}`).conditionalFormats.add('containsText', {
  text: '未通过',
  format: { fill: '#FCE4E4', font: { color: '#9C2020', bold: true } }
})
cases.getRange(`F7:F${end}`).conditionalFormats.add('containsText', {
  text: '阻塞',
  format: { fill: '#FCE4E4', font: { color: '#9C2020', bold: true } }
})
cases.getRange(`I7:I${end}`).setNumberFormat('yyyy-mm-dd')
for (const cell of ['B3', 'D3', 'F3', 'H3']) cases.getRange(cell).format.horizontalAlignment = 'center'
cases.freezePanes.freezeRows(6)
cases.freezePanes.freezeColumns(2)

base(assets, 'A1:J18')
title(assets, '初始资产基线')
assets.getRange('A3').values = [['来源：本地验收应用初始化数据。仅为虚构业务样例。执行验收前的状态，2026-09-11。']]
assets.getRange('A4').values = [
  ['公司简称：青空＝【模拟】青空科技株式会社；樱桥＝【模拟】樱桥贸易株式会社。金额单位：人民币元。']
]
assets.getRange('A6:J6').values = [
  ['资产编号', '资产名称', '公司', '资产类型', '初始状态', '保管部门', '采购单价', '附加费用', '购置总额', '保管人']
]
assets.getRange('A7:J14').values = data.records.ledger.map(r => [
  r.asset_no.value,
  r.name.value,
  r.company_id.value === '1' ? '青空' : '樱桥',
  r.category_id.display,
  r.status.display,
  r.department.display.replace('资产验收·', ''),
  Number(r.unit_price.value),
  Number(r.extra_cost.value),
  Number(r.original_cost.value),
  r.custodian.display || '未分配'
])
assets.tables.add('A6:J14', true, 'InitialAssetLedger').style = 'TableStyleLight1'
header(assets, 'A6:J6')
assets.getRange('H16').values = [['初始合计']]
assets.getRange('I16').formulas = [['=SUM(I7:I14)']]
assets.getRange('A18').values = [
  ['初始维修：WX-001＝280＋120＝400 元；WX-002＝80 元。盘点任务 PD-202609-01 的 3 条明细均为待盘点。']
]
for (const [col, width] of Object.entries({ A: 13, B: 24, C: 10, D: 12, E: 15, F: 14, G: 15, H: 15, I: 17, J: 28 }))
  assets.getRange(`${col}1:${col}18`).format.columnWidth = width
assets.getRange('A7:J14').format.rowHeight = 31
assets.getRange('G7:I16').setNumberFormat('#,##0.00')
assets.getRange('G7:I16').format.horizontalAlignment = 'right'
assets.getRange('H16:I16').format.font.bold = true
assets.freezePanes.freezeRows(6)

const businessPurpose = {
  company: '复用公司主数据，仅显示正式名称、简称。样例使用青空科技、樱桥贸易两家公司。',
  category: '复用资产类型，仅查看。样例使用笔记本和服务器。',
  ledger: '一物一卡。唯一资产编号、序列号；所属公司/类型引用；部门、人员目录；购置总额；状态、位置、附件。',
  movement: '一张单据记录一件资产的入库、领用、调拨、归还或报废。引用资产；保存采购单价快照。',
  repair: '一张维修单引用一件资产，内含费用项目和金额明细。主表自动汇总维修费用。',
  stocktake: '保存盘点批次、公司、部门、日期、负责人和任务状态。',
  count: '分别引用盘点任务与资产。记录账面/实盘数量，自动计算数量差异，人工填写结论。'
}
const objectRows = data.objects.map(o => [o.name, `${businessPurpose[o.key]} 对象 ID ${o.id}，V${o.version}。`])
const notes = [
  ['验收入口', appUrl],
  [
    '操作账号',
    '主流程使用你现有的管理员账号。权限场景使用 assetuatreader，显示名“资产验收·研发查看员”。密码单独在本次对话交付，不写入文件。'
  ],
  [
    '测试顺序',
    '建议先 A01—A05 和 F01—F03 核对基线及权限，再按 B—E 顺序测试新增、流转、维修和盘点。最后执行 G，H 为可选配置体验。完整约 60—90 分钟。'
  ],
  [
    '结果记录',
    '所有场景初始均为待验收。请填写结果、实际表现、截图或问题编号及执行日期。前置场景失败时把依赖场景标记为阻塞，说明原因。'
  ],
  [
    '角色边界',
    '管理员查看和操作本批业务数据。研发查看员仅查看当前部门资产及基础资料，无金额、附件、备注、写入、导出权限。默认可见 3 件资产。'
  ],
  ...boundaries
]
const guideRows = [...objectRows, ['', ''], ...notes]
const guideEnd = guideRows.length + 6
base(guide, `A1:B${guideEnd}`)
title(guide, '使用说明与数据对象')
guide.getRange('A4').values = [['新增 5 个业务对象，复用 2 个基础对象。盘点明细保留待填写，验收结果由你记录。']]
guide.getRange('A6:B6').values = [['项目', '说明']]
guide.getRange(`A7:B${guideEnd}`).values = guideRows
header(guide, 'A6:B6')
guide.getRange(`A1:A${guideEnd}`).format.columnWidth = 28
guide.getRange(`B1:B${guideEnd}`).format.columnWidth = 112
guide.getRange(`A7:B${guideEnd}`).format.wrapText = true
guideRows.forEach((r, i) => {
  guide.getRange(`A${i + 7}:B${i + 7}`).format.rowHeight = r[0]
    ? 14 * Math.max(2, Math.ceil(units(r[1]) / 99)) + 12
    : 12
})
guide.freezePanes.freezeRows(6)

// 验证结果输入驱动统计，并恢复所有用户场景为待验收。
cases.getRange('F7').values = [['通过']]
assert.equal(cases.getRange('F3').values[0][0], 1)
cases.getRange('F7').values = [['待验收']]
wb.recalculate()
assert.equal(cases.getRange('D3').values[0][0], scenarios.length)
assert.equal(cases.getRange('F3').values[0][0], 0)
assert.equal(assets.getRange('I16').values[0][0], 74000)
console.log(
  (
    await wb.inspect({
      kind: 'table',
      range: '验收场景!A3:H3',
      include: 'values,formulas',
      maxChars: 1600,
      tableMaxRows: 1,
      tableMaxCols: 8
    })
  ).ndjson
)
console.log(
  (
    await wb.inspect({
      kind: 'match',
      searchTerm: '#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!',
      options: { useRegex: true, maxResults: 20 },
      maxChars: 1200
    })
  ).ndjson
)
for (const [sheetName, range, filename] of [
  ['验收场景', 'A1:F10', 'scenarios-preview.png'],
  ['验收场景', 'A18:F21', 'flow-preview.png'],
  ['初始资产', 'A1:J18', 'assets-preview.png'],
  ['使用说明与对象', 'A1:B18', 'guide-preview.png'],
  ['使用说明与对象', `A19:B${guideEnd}`, 'boundaries-preview.png']
]) {
  const blob = await wb.render({ sheetName, range, scale: 1.5, format: 'png' })
  await fs.writeFile(resolve(runtimeArg, filename), new Uint8Array(await blob.arrayBuffer()))
}
const xlsx = await SpreadsheetFile.exportXlsx(wb)
await xlsx.save(resolve(output, '公司资产管理-验收清单.xlsx'))

const md = []
md.push(
  '# 公司资产管理业务验收',
  '',
  '初始化日期：2026-09-11。范围：办公固定资产。本手册和 Excel 的业务验收结果均待用户填写。',
  '',
  `[打开验收应用](${appUrl})`,
  '',
  '主流程使用现有管理员账号；权限验收账号为 `assetuatreader`（资产验收·研发查看员）。密码仅在本次对话交付。建议另一浏览器会话登录查看员，避免覆盖管理员会话。',
  '',
  '建议先执行 A、F 核对基线和权限，再按 B、C、D、E、G 顺序测试。H 为可选配置体验。完整约 60—90 分钟。前置失败时将依赖项标为“阻塞”。',
  '',
  '## 数据对象',
  '',
  '| 对象 | ID / 版本 | 初始化情况 | 业务作用 |',
  '|---|---|---|---|'
)
for (const o of data.objects)
  md.push(
    `| ${o.name} | ${o.id} / V${o.version} | ${['company', 'category'].includes(o.key) ? '复用既有对象' : `${data.records[o.key].length} 条记录`} | ${businessPurpose[o.key]} |`
  )
md.push(
  '',
  '组织、部门、用户继续使用平台底座目录。本次在既有 LY 组织内增加“资产验收·行政部”和“资产验收·研发部”。',
  '',
  '```mermaid',
  'erDiagram',
  '  公司 ||--o{ 资产台账 : 所属公司',
  '  资产类型 ||--o{ 资产台账 : 类型',
  '  资产台账 ||--o{ 资产流转 : 关联资产',
  '  资产台账 ||--o{ 维修记录 : 关联资产',
  '  维修记录 ||--o{ 维修费用明细 : 内部明细',
  '  公司 ||--o{ 盘点任务 : 所属公司',
  '  盘点任务 ||--o{ 盘点明细 : 任务',
  '  资产台账 ||--o{ 盘点明细 : 盘点资产',
  '```',
  '',
  '全部对象引用为必填且删除受限。资产编号、非空设备序列号、各类单据号唯一；数量固定为 1；金额非负；实盘数量只能为 0 或 1。盘点任务与资产的组合尚无去重约束，需人工核对。',
  '',
  '## 初始资产',
  '',
  '| 编号 | 名称 | 公司 | 状态 | 部门 | 采购单价 | 附加费用 | 购置总额 |',
  '|---|---|---|---|---|---:|---:|---:|'
)
for (const r of data.records.ledger)
  md.push(
    `| ${r.asset_no.value} | ${r.name.value} | ${r.company_id.value === '1' ? '青空科技' : '樱桥贸易'} | ${r.status.display} | ${r.department.display} | ${Number(r.unit_price.value).toFixed(2)} | ${Number(r.extra_cost.value).toFixed(2)} | ${Number(r.original_cost.value).toFixed(2)} |`
  )
md.push(
  '',
  '合计 8 件、74,000 元；闲置在库 3、使用中 3、维修中 1、已报废 1。公司、分类、金额均为模拟业务内容。',
  '',
  '流转 LC-001—005 分别示例入库、领用、调拨、归还、报废。WX-001 有 280＋120 两条费用明细，合计 400；WX-002 一条 80。PD-202609-01 含 PDMX-001—003，分别关联 ZC-002、003、005，全部待盘点。',
  '',
  '## 当前配置边界',
  ''
)
for (const [name, description] of boundaries) md.push(`- **${name}**：${description}`)
md.push(
  '',
  '## 手工验收场景',
  '',
  '每项填写：结果（待验收／通过／未通过／阻塞／不适用）、实际结果、截图或问题编号、执行日期。'
)
for (const s of scenarios)
  md.push(
    '',
    `### ${s.id} ${s.name}`,
    '',
    `前置：${s.prerequisite}`,
    '',
    `操作：${s.steps}`,
    '',
    `预期：${s.expected}`,
    '',
    '结果：**待验收**。实际结果／问题编号：________。'
  )
md.push(
  '',
  '## 初始化核对（不代替用户验收）',
  '',
  `- 应用 ${data.application.id} 已发布 V${data.application.publishedVersion}，对象和表单使用正式保存、发布及运行接口。`,
  '- 实际业务记录数为 8 / 5 / 2 / 1 / 3；维修费用内部明细 3 条。',
  '- 初始购置总额、维修合计、关系标题、查看员本部门范围及金额不可见已做环境就绪核对。',
  '- 初始化工具按固定批次识别对象，完成后重跑只读取环境，不覆盖验收过程中新建或修改的记录。',
  '- 本批保留原应用、原资产类型明细和基础资料配置，不新增平台迁移脚本，不清理既有数据。',
  '',
  '## 重复执行',
  '',
  '在 `ucp-front` 下运行 `node tools/asset-uat/initialize.mjs`。使用现有 `.env.test` 认证和既有后端，不写数据库连接或登录令牌。执行清单位于忽略目录 `tests/test-results/asset-uat-20260911/`。不要删除清单后以“重置”方式重跑；重测先记录现有测试结果，再明确准备新一批数据。',
  ''
)
await fs.writeFile(resolve(docs, '验收手册.md'), md.join('\n'))
await fs.writeFile(resolve(docs, '初始化数据.json'), JSON.stringify(data, null, 2))
console.log(
  JSON.stringify({
    scenarios: scenarios.length,
    workbook: resolve(output, '公司资产管理-验收清单.xlsx'),
    handbook: resolve(docs, '验收手册.md')
  })
)
