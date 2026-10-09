import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

export const BATCH = 'CONSTRUCTION_GUIDE_20261002'
export const PREFIX = 'construction_guide'
export const OUTPUT = resolve('.work/construction-experience')
const option = extra => ({ classification: 'NORMAL', state: 'ACTIVE', options: [], resolver: 'NONE', ...extra })
const choices = (values, defaultValue) =>
  option({
    resolver: 'LOCAL_OPTIONS',
    defaultValue,
    options: Object.entries(values).map(([code, label]) => ({ code, label, disabled: false }))
  })
const field = (code, name, type = 'TEXT', required = false, extra = {}) => ({
  key: code,
  id: null,
  code,
  name,
  type,
  required,
  unique: false,
  sort: 0,
  length: type === 'TEXT' ? 200 : null,
  precision: ['MONEY', 'DECIMAL'].includes(type) ? 20 : null,
  scale: ['MONEY', 'DECIMAL'].includes(type) ? 2 : null,
  ...extra
})
const node = (id, type, extra = {}) => ({ id, type, children: [], ...extra })
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
const labels = { project: '施工项目', content: '施工内容', log: '施工日志', cost: '费用登记', inspection: '验收记录' }

/** 用户授权的长期体验配置。只走正式 API；重跑不覆盖发布配置、授权或用户已编辑的数据。 */
export class ConstructionExperience extends FormAcceptance {
  constructor() {
    super(process.env.CONSTRUCTION_API || 'http://127.0.0.1:8080/api', OUTPUT)
    this.prefix = PREFIX
    this.catalog = {}
    this.seeded = {}
  }

  async checkpoint() {
    await mkdir(OUTPUT, { recursive: true })
    await writeFile(
      resolve(OUTPUT, 'manifest.json'),
      JSON.stringify(
        {
          batch: BATCH,
          complete: !!this.complete,
          applicationId: this.app?.application.id,
          applicationCode: this.app?.application.code,
          actorId: this.actor,
          objects: Object.fromEntries(
            Object.entries(this.catalog).map(([key, object]) => [
              key,
              {
                id: object.objectId,
                code: object.definition.objectCode,
                name: object.definition.objectName,
                version: object.versionNo,
                fields: object.ids
              }
            ])
          ),
          seeded: this.seeded,
          checkedAt: new Date().toISOString(),
          taskConfiguration: '未创建任务、任务模板或任务反馈规则；等待用户逐步体验',
          retention: '用户授权的体验数据，保留，不纳入自动测试清理'
        },
        null,
        2
      )
    )
  }

  async version(id) {
    const object = await this.api(`/nocode/application/object-version?id=${id}`)
    object.ids = Object.fromEntries(object.definition.fields.map(item => [item.code, item.id]))
    return object
  }

  async design(key, fields, fieldOptions = {}) {
    const code = `${PREFIX}_${key}`
    const found = (await this.api(`/nocode/object/page?pageNo=1&pageSize=100&code=${code}`)).list.find(
      item => item.objectCode === code
    )
    let saved
    if (found) {
      saved = await this.api(`/nocode/design/get?id=${found.id}`)
      assert.ok(saved.draft.description?.includes(BATCH), `已有对象 ${code} 不属于本次体验，不覆盖`)
    } else {
      saved = await this.api('/nocode/design/save', {
        draft: {
          id: null,
          expectedLockVersion: null,
          objectCode: code,
          objectName: `施工体验·${labels[key]}`,
          description: `${BATCH} 用户授权的施工学习应用；示例数据非真实施工台账，请保留供逐步体验。`,
          tableName: `biz_${code}`,
          titleFieldKey: 'name',
          removedFieldIds: [],
          fields: fields.map((item, index) => ({ ...item, sort: index }))
        },
        settings: {},
        fieldOptions,
        indexes: [],
        details: [],
        relations:
          key === 'project'
            ? []
            : [
                {
                  id: null,
                  code: 'project',
                  name: '所属项目',
                  kind: 'REFERENCE',
                  targetObjectId: this.catalog.project.objectId,
                  fieldId: null,
                  targetFieldId: null,
                  required: true,
                  onDelete: 'RESTRICT',
                  sourceDetailId: null
                }
              ]
      })
    }
    if (!saved.publishedVersion) {
      const plan = await this.api('/nocode/design/plan', {
        id: saved.draft.id,
        expectedLockVersion: saved.draft.lockVersion
      })
      assert.deepEqual(
        plan.checks.filter(item => item.blocking),
        [],
        `${labels[key]} 发布检查`
      )
      const execution = await this.api('/nocode/design/execute', {
        planId: plan.id,
        reason: `${BATCH} 配置施工体验对象`
      })
      assert.equal(execution.state, 'SUCCEEDED')
    }
    this.catalog[key] = await this.version(saved.draft.id)
    await this.checkpoint()
    return this.catalog[key]
  }

  resources() {
    const resources = []
    const columns = {
      project: ['project_no', 'name', 'owner', 'status', 'start_date', 'end_date', 'budget'],
      content: ['name', 'project_id', 'location', 'trade', 'quantity', 'unit'],
      log: ['name', 'project_id', 'log_date', 'weather', 'workers', 'recorder'],
      cost: ['name', 'project_id', 'cost_date', 'category', 'amount', 'payee', 'status'],
      inspection: ['name', 'project_id', 'inspection_date', 'location', 'result', 'inspector']
    }
    for (const [key, object] of Object.entries(this.catalog)) {
      const fields = [...object.definition.fields].sort((a, b) =>
        a.code === 'project_id' ? -1 : b.code === 'project_id' ? 1 : a.sort - b.sort
      )
      const nodes = []
      let pair = []
      const append = () => {
        if (!pair.length) return
        nodes.push(
          node(`${key}_row_${nodes.length}`, 'ROW', {
            children: pair.map(item =>
              node(`${key}_col_${item.code}`, 'COLUMN', {
                span: pair.length === 1 ? 24 : 12,
                children: [node(`${key}_field_${item.code}`, 'FIELD', { fieldId: item.id })]
              })
            )
          })
        )
        pair = []
      }
      for (const item of fields) {
        if (['TEXTAREA', 'IMAGE', 'ATTACHMENT'].includes(item.type)) {
          append()
          pair = [item]
          append()
        } else {
          pair.push(item)
          if (pair.length === 2) append()
        }
      }
      append()
      resources.push(
        resource(`${key}_form`, 'FORM', key === 'project' ? '项目资料' : labels[key], {
          objectId: object.objectId,
          nodes,
          detailIds: [],
          options: { layout: 'vertical', submitText: `保存${labels[key]}` }
        })
      )
      resources.push(
        resource(`${key}_view`, 'VIEW', key === 'project' ? '项目台账' : labels[key], {
          objectId: object.objectId,
          fieldIds: columns[key].map(code => object.ids[code]),
          equal: {},
          sortFieldId: object.ids.name,
          descending: false,
          pageSize: 10,
          formId: `${key}_form`,
          detailPageId: key === 'project' ? 'project_detail' : null,
          interaction: {
            buttons: ['CREATE', 'VIEW', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT'],
            actionIds: [],
            editMode: 'DRAWER',
            detailMode: 'DRAWER'
          },
          list: {
            queryFieldIds: [object.ids.name],
            advancedFieldIds: columns[key].map(code => object.ids[code]),
            columnWidths: {},
            batchDelete: false
          }
        })
      )
    }
    const tabs = [
      node('project_info_tab', 'TAB', {
        text: '项目资料',
        children: [node('project_info', 'DETAIL', { resourceId: 'project_form' })]
      })
    ]
    tabs.push(
      node('project_tasks_tab', 'TAB', {
        text: '项目任务',
        children: [
          node('project_task_tip', 'ALERT', { text: '这里暂时没有任务。后续一起发起任务，系统会自动关联当前项目。' }),
          node('project_tasks', 'TASKS', {
            resourceId: 'project_form',
            text: '本项目任务',
            taskView: {
              columnKeys: ['title', 'owner', 'status', 'time'],
              sort: { field: 'createdAt', descending: true }
            }
          })
        ]
      })
    )
    for (const key of ['content', 'log', 'cost', 'inspection']) {
      const relation = this.catalog[key].definition.relations.find(item => item.code === 'project')
      assert.ok(relation?.id, `${key} 需要真实所属项目关系`)
      tabs.push(
        node(`${key}_tab`, 'TAB', {
          text: labels[key],
          children: [
            node(`project_${key}`, 'RELATED', {
              text: `本项目${labels[key]}`,
              resourceId: `${key}_view`,
              binding: { relationId: relation.id, direction: 'INCOMING' }
            })
          ]
        })
      )
    }
    tabs.push(
      node('project_files_tab', 'TAB', {
        text: '项目附件',
        children: [node('project_files', 'ATTACHMENTS', { resourceId: 'project_form', text: '项目附件' })]
      })
    )
    resources.push(
      resource('project_detail', 'PAGE', '项目工作台', {
        contextObjectId: this.catalog.project.objectId,
        nodes: [
          node('project_heading', 'HEADING', { text: '项目工作台' }),
          node('project_tabs', 'TABS', { children: tabs })
        ]
      })
    )
    const metric = (id, key, name, operation = 'COUNT', code = null) =>
      resource(id, 'REPORT', name, {
        objectId: this.catalog[key].objectId,
        dimensions: [],
        metrics: [{ id: 'value', name, operation, fieldId: code ? this.catalog[key].ids[code] : null }],
        equal: {},
        filterFieldIds: [],
        dateFieldId: null,
        timeZone: 'Asia/Shanghai',
        display: 'METRIC',
        sortMetricId: null,
        descending: false,
        limit: 20,
        detailViewId: `${key}_view`
      })
    resources.push(
      metric('project_count', 'project', '项目数量'),
      metric('content_count', 'content', '施工内容数量'),
      metric('cost_total', 'cost', '登记费用（元）', 'SUM', 'amount'),
      metric('inspection_count', 'inspection', '验收记录数量')
    )
    resources.push(
      resource('overview', 'PAGE', '施工概览', {
        nodes: [
          node('welcome', 'HEADING', { text: '施工管理 · 体验工作台' }),
          node('guide', 'ALERT', {
            text: '这是专门用于学习的施工应用，全部为示例数据。先查看项目，再一步步配置任务；系统不会预先替你创建任务。'
          }),
          node('metrics', 'ROW', {
            children: ['project_count', 'content_count', 'cost_total', 'inspection_count'].map((id, i) =>
              node(`metric_col_${i}`, 'COLUMN', {
                span: 6,
                children: [node(`metric_${i}`, 'REPORT', { resourceId: id })]
              })
            )
          }),
          node('project_list', 'VIEW', { resourceId: 'project_view', text: '项目台账' })
        ]
      })
    )
    resources.push(
      resource('application_tasks', 'PAGE', '应用任务', {
        nodes: [
          node('app_tasks_tip', 'ALERT', {
            text: '只显示本施工应用中你有权查看的任务。任务配置和发起将在后续体验中一起完成。'
          }),
          node('application_tasks_list', 'TASKS', {
            text: '施工应用任务',
            taskView: { columnKeys: ['title', 'owner', 'status', 'time'] }
          })
        ]
      })
    )
    for (const [id, name, targetId] of [
      ['overview_menu', '施工概览', 'overview'],
      ['project_menu', '项目台账', 'project_view'],
      ['content_menu', '施工内容', 'content_view'],
      ['log_menu', '施工日志', 'log_view'],
      ['cost_menu', '费用登记', 'cost_view'],
      ['inspection_menu', '验收记录', 'inspection_view'],
      ['tasks_menu', '应用任务', 'application_tasks']
    ])
      resources.push(resource(id, 'MENU', name, { targetId }))
    return resources
  }

  async seed(key, tag, values) {
    const object = this.catalog[key]
    if (this.seeded[key]?.[tag]) {
      // 已交给用户的示例按稳定 ID 恢复；用户改名、修改或删除后不自动重置。
      await this.get(object, { id: this.seeded[key][tag] })
      return { id: this.seeded[key][tag] }
    }
    const previous = await this.page(object, undefined, { equal: { [object.ids.name]: values.name } })
    assert.ok(previous.total <= 1, `示例记录名称不唯一：${values.name}`)
    const row = previous.list[0] || (await this.save(object, values))
    this.seeded[key] ||= {}
    this.seeded[key][tag] = row.id
    await this.checkpoint()
    return row
  }

  async initialize() {
    await this.login()
    const info = await this.api('/system/auth/get-permission-info')
    this.actor = String(info.user.id)
    let previous
    try {
      previous = JSON.parse(await readFile(resolve(OUTPUT, 'manifest.json'), 'utf8'))
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
    }
    if (previous) {
      assert.equal(previous.batch, BATCH)
      this.seeded = previous.seeded || {}
    }
    await this.design(
      'project',
      [
        field('name', '项目名称', 'TEXT', true),
        field('project_no', '项目编号', 'TEXT', true, { unique: true }),
        field('location', '施工地点'),
        field('client', '建设单位'),
        field('owner', '项目负责人', 'USER'),
        field('start_date', '计划开工日期', 'DATE'),
        field('end_date', '计划完工日期', 'DATE'),
        field('budget', '项目预算（元）', 'MONEY'),
        field('status', '项目状态', 'SELECT', true),
        field('description', '项目说明', 'TEXTAREA'),
        field('attachments', '项目附件', 'ATTACHMENT')
      ],
      {
        budget: option({ minimum: '0' }),
        status: choices({ PLANNED: '待开工', RUNNING: '施工中', DONE: '已完工' }, 'PLANNED')
      }
    )
    await this.design(
      'content',
      [
        field('name', '施工内容名称', 'TEXT', true),
        field('location', '施工部位'),
        field('trade', '专业工序', 'SELECT'),
        field('quantity', '计划工程量', 'DECIMAL'),
        field('unit', '计量单位', 'SELECT'),
        field('requirement', '质量与技术要求', 'TEXTAREA'),
        field('attachments', '施工图纸或资料', 'ATTACHMENT')
      ],
      {
        quantity: option({ minimum: '0' }),
        trade: choices({ STRUCTURE: '土建结构', ELECTRICAL: '机电安装', FINISHING: '装饰装修' }),
        unit: choices({ M2: '平方米', M3: '立方米', TON: '吨', SET: '项' })
      }
    )
    await this.design(
      'log',
      [
        field('name', '日志标题', 'TEXT', true),
        field('log_date', '施工日期', 'DATE', true),
        field('weather', '天气', 'SELECT'),
        field('workers', '现场人数', 'INTEGER'),
        field('recorder', '记录人', 'USER'),
        field('progress', '今日施工内容与进展', 'TEXTAREA', true),
        field('issues', '问题与待协调事项', 'TEXTAREA'),
        field('photos', '现场照片', 'IMAGE')
      ],
      { weather: choices({ SUNNY: '晴', CLOUDY: '阴', RAINY: '雨' }), workers: option({ minimum: '0' }) }
    )
    await this.design(
      'cost',
      [
        field('name', '费用名称', 'TEXT', true),
        field('cost_date', '发生日期', 'DATE', true),
        field('category', '费用类别', 'SELECT', true),
        field('amount', '金额（元）', 'MONEY', true),
        field('payee', '收款方'),
        field('status', '支付状态', 'SELECT', true),
        field('receipt', '费用凭证', 'ATTACHMENT'),
        field('notes', '费用说明', 'TEXTAREA')
      ],
      {
        amount: option({ minimum: '0' }),
        category: choices({ MATERIAL: '材料费', LABOR: '人工费', EQUIPMENT: '机械费', OTHER: '其他' }),
        status: choices({ UNPAID: '未支付', PAID: '已支付' }, 'UNPAID')
      }
    )
    await this.design(
      'inspection',
      [
        field('name', '验收事项', 'TEXT', true),
        field('inspection_date', '验收日期', 'DATE', true),
        field('location', '验收部位'),
        field('result', '验收结果', 'SELECT', true),
        field('inspector', '验收人', 'USER'),
        field('conclusion', '验收说明与整改要求', 'TEXTAREA', true),
        field('photos', '验收照片', 'IMAGE')
      ],
      { result: choices({ PASS: '通过', RECTIFY: '需整改' }) }
    )
    const appCode = `${PREFIX}_app`
    const apps = await this.api(`/nocode/application/page?pageNo=1&pageSize=100&search=${appCode}`)
    const existing = apps.list.find(item => item.code === appCode)
    this.app = existing
      ? await this.api(`/nocode/application/get?id=${existing.id}`)
      : await this.api('/nocode/application/save', {
          id: null,
          expectedRevision: null,
          code: appCode,
          name: '施工管理（体验）',
          category: '业务体验',
          description: `${BATCH} 用户授权的施工学习应用。项目与业务资料已配置；任务、模板和反馈要求由用户逐步体验配置。示例数据请保留。`,
          definition: {
            objects: Object.values(this.catalog).map(({ objectId, versionNo, checksum }) => ({
              objectId,
              versionNo,
              checksum
            })),
            resources: this.resources()
          }
        })
    assert.ok(this.app.application.description?.includes(BATCH), '同编码应用不属于本轮，不覆盖')
    if (previous?.applicationId) assert.equal(this.app.application.id, previous.applicationId)
    await this.checkpoint()
    if (!this.app.application.publishedVersion) {
      for (const object of Object.values(this.catalog)) {
        const shared = await this.api(`/nocode/object-sharing/list?objectId=${object.objectId}`)
        if (!shared.some(item => item.applicationId === this.app.application.id))
          await this.api('/nocode/object-sharing/save', {
            objectId: object.objectId,
            applicationId: this.app.application.id,
            expectedRevision: 0,
            permission: this.grant(object),
            reason: `${BATCH} 仅授权本施工体验应用使用自有业务对象`
          })
      }
      const policy = await this.api(`/nocode/application/authorization?id=${this.app.application.id}`)
      if (!policy.members.some(item => item.principalKind === 'USER' && item.principalId === this.actor))
        await this.authorize([
          ...policy.members,
          this.member(
            this.actor,
            Object.values(this.catalog).map(object => this.grant(object))
          )
        ])
      this.app = await this.api('/nocode/application/publish', {
        id: this.app.application.id,
        expectedRevision: this.app.application.revision,
        reason: `${BATCH} 发布可长期体验的施工应用`
      })
    }
    const a = await this.seed('project', 'building3', {
      name: '幸福小区 3 号楼',
      project_no: 'SG-TY-003',
      location: '示例工地 · 3 号楼',
      client: '示例建设单位',
      owner: this.actor,
      start_date: '2026-10-01',
      end_date: '2026-10-31',
      budget: 500000,
      status: 'RUNNING',
      description: '体验项目：用于学习关联业务与任务拆分。所有数据均为示例，不代表真实工程。'
    })
    const b = await this.seed('project', 'building5', {
      name: '幸福小区 5 号楼',
      project_no: 'SG-TY-005',
      location: '示例工地 · 5 号楼',
      client: '示例建设单位',
      owner: this.actor,
      start_date: '2026-10-05',
      end_date: '2026-11-15',
      budget: 350000,
      status: 'PLANNED',
      description: '对照项目：用于观察不同项目的业务记录和任务互不混淆。示例数据。'
    })
    await this.seed('content', 'building3', {
      name: '3 号楼二层钢筋绑扎',
      project_id: a.id,
      location: '二层梁板',
      trade: 'STRUCTURE',
      quantity: 12.5,
      unit: 'TON',
      requirement: '示例施工内容；后续可按需作为任务的业务资料，不等同于已创建任务。'
    })
    await this.seed('content', 'building5', {
      name: '5 号楼首层砌筑',
      project_id: b.id,
      location: '首层',
      trade: 'STRUCTURE',
      quantity: 80,
      unit: 'M3',
      requirement: '示例施工内容，待正式开工安排。'
    })
    for (const [tag, project, label] of [
      ['building3', a, '3 号楼'],
      ['building5', b, '5 号楼']
    ]) {
      await this.seed('log', tag, {
        name: `${label}进场准备（示例）`,
        project_id: project.id,
        log_date: '2026-10-02',
        weather: 'SUNNY',
        workers: tag === 'building3' ? 8 : 3,
        recorder: this.actor,
        progress: '示例：场地检查与施工资料准备。此记录不是任务过程反馈，后续由你亲自登记反馈。',
        issues: '仅供学习数据关联，无真实施工结论。'
      })
      await this.seed('cost', tag, {
        name: `${label}材料进场费用（示例）`,
        project_id: project.id,
        cost_date: '2026-10-02',
        category: 'MATERIAL',
        amount: tag === 'building3' ? 12000 : 8000,
        payee: '示例材料供应商',
        status: 'UNPAID',
        notes: '模拟金额，不作为付款或会计凭证。'
      })
      await this.seed('inspection', tag, {
        name: `${label}进场准备检查（示例）`,
        project_id: project.id,
        inspection_date: '2026-10-02',
        location: '施工现场',
        result: 'PASS',
        inspector: this.actor,
        conclusion: '仅为展示验收记录样式的模拟结论，不代表真实工程验收。'
      })
    }
    this.complete = true
    await this.checkpoint()
    return {
      applicationId: this.app.application.id,
      application: this.app.application.name,
      objects: Object.keys(this.catalog).length,
      records: Object.values(this.seeded).reduce((n, items) => n + Object.keys(items).length, 0),
      tasksCreated: 0,
      url: `http://127.0.0.1:5173/nocode-app/runtime?id=${this.app.application.id}&menu=project_menu`
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const experience = new ConstructionExperience()
  console.log(JSON.stringify(await experience.initialize(), null, 2))
}
