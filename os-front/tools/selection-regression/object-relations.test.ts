import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { control, empty, event, find, findAll, flush, modal, mount, table, text, type TestNode } from './renderer'
import { defaultFieldOptions, generatedBinding } from '@/nocode/data-center'
import { newField } from '@/nocode/object-draft'
import type { ObjectDesign } from '@/types/nocode/data-center'

const mocks = vi.hoisted(() => ({
  design: vi.fn(),
  objects: vi.fn(),
  save: vi.fn(),
  history: vi.fn(),
  version: vi.fn(),
  plan: vi.fn(),
  execute: vi.fn(),
  conversionRows: vi.fn(),
  fieldSwitchPreview: vi.fn(),
  fieldSwitchPreviewRows: vi.fn(),
  modalConfirm: vi.fn(),
  modalError: vi.fn(),
  replace: vi.fn(),
  dictionary: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    hasPermission: () => true,
    dataCenter: mocks,
    directory: { users: async () => [], departments: async () => [] }
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { id: '10' }, path: '/nocode/object/editor' }),
  useRouter: () => ({ replace: mocks.replace }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() },
  Modal: { confirm: mocks.modalConfirm, error: mocks.modalError }
}))
vi.mock('@ant-design/icons-vue', () =>
  Object.fromEntries(
    [
      'PlusOutlined',
      'UpOutlined',
      'DownOutlined',
      'EditOutlined',
      'EyeOutlined',
      'StopOutlined',
      'DeleteOutlined',
      'ArrowLeftOutlined',
      'SaveOutlined',
      'SendOutlined'
    ].map(name => [name, () => h('icon')])
  )
)
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: modal }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/components/SystemFieldsPanel.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/components/ObjectSharingPanel.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/components/TableBindingPanel.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/components/SelectionMigrationDialog.vue', () => ({ default: empty }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: async () => [] }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: async () => [] }))
vi.mock('@/utils/request', () => ({ default: { get: mocks.dictionary } }))

import Editor from '@/views/nocode/object/editor.vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'

let fixture: ObjectDesign
const mounted: Array<ReturnType<typeof mount>> = []
beforeEach(() => {
  vi.stubGlobal('window', { addEventListener: vi.fn(), removeEventListener: vi.fn() })
  const title = { ...newField(0, '名称'), key: '11', id: '11' }
  fixture = {
    draft: {
      id: '10',
      objectCode: 'review',
      objectName: 'Review',
      description: '',
      tableName: 'biz_review',
      titleFieldId: '11',
      state: 'DRAFT',
      lockVersion: 0,
      versionNo: 1,
      updatedAt: '',
      fields: [title]
    },
    settings: { icon: null, ownerId: null, organizationId: null, titleTemplate: null },
    fieldOptions: {},
    relations: [],
    indexes: [],
    details: [],
    mainBinding: generatedBinding(),
    source: 'GENERATED',
    schemaName: 'public',
    publishedVersion: null,
    status: 'ACTIVE',
    readOnly: false,
    versions: [],
    dependencies: []
  }
  mocks.design.mockImplementation(async () => JSON.parse(JSON.stringify(fixture)))
  mocks.objects.mockResolvedValue({
    list: [{ id: '20', objectName: '分类', objectCode: 'category', publishedVersion: 1, status: 'ACTIVE' }]
  })
  mocks.history.mockResolvedValue([])
  mocks.version.mockResolvedValue({ fields: [], details: [], relations: [], indexes: [] })
  mocks.plan.mockResolvedValue({
    id: 'plan-1',
    objectId: '10',
    revision: 0,
    versionNo: 1,
    state: 'PENDING',
    changes: [],
    checks: [],
    dependencies: [],
    conversions: [
      {
        fieldId: '11',
        detailId: null,
        fieldName: '名称',
        sourceName: 'Review',
        fromType: 'varchar',
        toType: 'bigint',
        affectedRows: 2,
        deletedRows: 0,
        masked: false,
        fingerprint: 'v1',
        clearAllowed: true,
        impacts: []
      }
    ]
  })
  mocks.execute.mockResolvedValue({ state: 'SUCCEEDED' })
  mocks.fieldSwitchPreview.mockImplementation(async (request: { fieldId: string; targetType: string }) => ({
    objectId: '10',
    detailId: null,
    fieldId: request.fieldId,
    fieldName: '分类',
    sourceType: 'SELECT',
    targetType: request.targetType,
    deploymentState: 'DEPLOYED',
    totalRows: 3,
    valueRows: 2,
    decision: 'CLEAR_COLUMN',
    explanation: '旧值不能直接作为新类型使用，需要确认清空本列。',
    impacts: []
  }))
  mocks.fieldSwitchPreviewRows.mockResolvedValue({ rows: [], total: 0, pageNo: 1, pageSize: 20 })
  mocks.modalConfirm.mockImplementation((options: { onOk?: () => void }) => options.onOk?.())
  // 只检查真实组件提交的请求；真实持久化另由开发环境接口回归覆盖。
  mocks.save.mockRejectedValue(new Error('测试已捕获保存请求'))
  mocks.dictionary.mockResolvedValue([{ name: '状态', type: 'sys_common_status' }])
})
afterEach(() => {
  mounted.splice(0).forEach(item => item.unmount())
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

async function editor() {
  const instance = mount(Editor)
  mounted.push(instance)
  await flush()
  return instance.root
}
const dialog = (root: TestNode) => find(root, 'modal')
const button = (root: TestNode, name: string) => find(root, 'a-button', node => text(node).trim() === name)
const row = (root: TestNode, name: string) =>
  find(root, 'row', node => !!node.props.record.type && node.props.record.name === name)
async function settlePreview() {
  await new Promise(resolve => setTimeout(resolve, 280))
  await flush()
}
async function approveClear(root: TestNode) {
  await settlePreview()
  await event(
    find(dialog(root), 'a-checkbox', node => text(node).includes('选择发布时清空本列')),
    'onUpdate:checked',
    true
  )
}
async function addSelection(root: TestNode, type: 'SELECT' | 'MULTI_SELECT', name: string) {
  await event(button(root, '新增字段'), 'onClick')
  const added = find(root, 'row', node => !node.props.record.name)
  await event(
    find(added, 'a-input', node => node.props.placeholder === '字段名称'),
    'onUpdate:value',
    name
  )
  await event(find(row(root, name), 'a-select'), 'onChange', type)
  await event(button(row(root, name), '配置'), 'onClick')
}
async function chooseObject(root: TestNode) {
  await event(control(dialog(root), '数据来源', 'a-select'), 'onChange', 'OBJECT_RELATION')
  await event(control(dialog(root), '从哪份资料选择', 'a-select'), 'onUpdate:value', '20')
}

describe('真实字段与对象编辑组件的选择关系流程', () => {
  it('最终发布统一确认本计划清空列，失败保留窗口与原因，重新检查后才能再次执行', async () => {
    const root = await editor()
    await event(button(root, '发布'), 'onClick')
    await event(find(dialog(root), 'a-textarea'), 'onUpdate:value', '调整字段')
    expect(mocks.execute).not.toHaveBeenCalled()
    expect(dialog(root).props['ok-text']).toBe('清空本列 2 个值并发布')
    expect(findAll(dialog(root), node => node.type === 'a-checkbox')).toHaveLength(0)
    mocks.execute.mockRejectedValueOnce(new Error('数据已变化，请重新检查影响'))
    await event(dialog(root), 'onOk')
    expect(mocks.execute).toHaveBeenLastCalledWith('plan-1', '调整字段', ['11'], [])
    expect(find(dialog(root), 'a-alert', node => String(node.props.message).includes('数据已变化'))).toBeDefined()
    expect(dialog(root).props['ok-text']).toBe('重新检查发布影响')
    await event(dialog(root), 'onOk')
    expect(mocks.execute).toHaveBeenCalledTimes(1)
    expect(find(dialog(root), 'a-textarea').props.value).toBe('调整字段')
    await event(find(dialog(root), 'a-textarea'), 'onUpdate:value', '重新核对')
    await event(dialog(root), 'onOk')
    expect(mocks.execute).toHaveBeenLastCalledWith('plan-1', '重新核对', ['11'], [])
    expect(findAll(root, node => node.type === 'modal')).toHaveLength(0)
  })

  it('已保存单选可配置业务对象，取消保留原配置，确认保留字段身份并交发布检查', async () => {
    fixture.draft.fields.push({ ...newField(1, '已有分类'), id: '12', key: '12', type: 'SELECT' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    const root = await editor()
    await event(button(row(root, '已有分类'), '配置'), 'onClick')
    const source = control(dialog(root), '数据来源', 'a-select')
    expect(source.props.options.find((item: { value: string }) => item.value === 'OBJECT_RELATION').disabled).toBe(
      false
    )
    expect(find(dialog(root), 'a-alert', node => node.props.message === '切换时将检查当前列与转换影响')).toBeDefined()
    await chooseObject(root)
    expect(control(dialog(root), '从哪份资料选择', 'a-select')).toBeDefined()
    expect(row(root, '已有分类').props.record.type).toBe('SELECT')
    await event(dialog(root), 'onCancel')
    expect(row(root, '已有分类').props.record.type).toBe('SELECT')
    expect(mocks.save).not.toHaveBeenCalled()
    await event(button(row(root, '已有分类'), '配置'), 'onClick')
    await chooseObject(root)
    expect(text(dialog(root))).toContain('变更影响')
    await approveClear(root)
    await event(dialog(root), 'onOk')
    expect(text(row(root, '已有分类'))).toContain('单选（对象引用）')
    await event(button(root, '保存草稿'), 'onClick')
    const payload = mocks.save.mock.calls[0]![0]
    expect(payload.draft.fields[1]).toMatchObject({ id: '12', key: '12', type: 'REFERENCE' })
    expect(payload.fieldOptions['12'].columnName).toBe('original_column')
    expect(payload.relations[0].fieldId).toBe('12')
  })

  it('文本切换为整数先展示全表与本列有值数，取消不改草稿，确认后才改类型', async () => {
    fixture.draft.fields.push({ ...newField(1, '旧文本'), id: '12', key: '12', type: 'TEXT' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    const root = await editor()
    await event(find(row(root, '旧文本'), 'a-select'), 'onChange', 'INTEGER')
    await settlePreview()
    expect(row(root, '旧文本').props.record.type).toBe('TEXT')
    expect(mocks.fieldSwitchPreview).toHaveBeenCalledWith(
      expect.objectContaining({ objectId: '10', fieldId: '12', targetType: 'INTEGER' })
    )
    expect(text(dialog(root))).toContain('共 3 条记录，本列 2 条有值')
    await event(dialog(root), 'onCancel')
    expect(row(root, '旧文本').props.record.type).toBe('TEXT')
    await event(find(row(root, '旧文本'), 'a-select'), 'onChange', 'INTEGER')
    await approveClear(root)
    await event(dialog(root), 'onOk')
    expect(row(root, '旧文本').props.record.type).toBe('INTEGER')
    expect(mocks.save).not.toHaveBeenCalled()
  })

  it('兼容的字段类型切换也展示保留方式和有值数', async () => {
    fixture.draft.fields.push({ ...newField(1, '旧文本'), id: '12', key: '12', type: 'TEXT' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    mocks.fieldSwitchPreview.mockResolvedValueOnce({
      objectId: '10',
      detailId: null,
      fieldId: '12',
      fieldName: '旧文本',
      sourceType: 'TEXT',
      targetType: 'TEXTAREA',
      deploymentState: 'DEPLOYED',
      totalRows: 3,
      valueRows: 2,
      decision: 'PRESERVE',
      explanation: '文本改为多行文本，旧列值直接保留。',
      impacts: []
    })
    const root = await editor()
    await event(find(row(root, '旧文本'), 'a-select'), 'onChange', 'TEXTAREA')
    await settlePreview()
    expect(text(dialog(root))).toContain('已发布：单行文本 → 当前草稿目标：多行文本')
    expect(text(dialog(root))).toContain('共 3 条记录，本列 2 条有值')
    expect(
      find(dialog(root), 'a-alert', node => node.props.description === '文本改为多行文本，旧列值直接保留。')
    ).toBeDefined()
    await event(dialog(root), 'onOk')
    expect(row(root, '旧文本').props.record.type).toBe('TEXTAREA')
  })

  it('保存过的转换草稿重新打开仍按已发布类型检查最终目标', async () => {
    fixture.publishedVersion = 1
    fixture.draft.fields.push({
      ...newField(1, '旧文本'),
      id: '12',
      key: '12',
      type: 'DECIMAL',
      length: null,
      precision: 18,
      scale: 2
    })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    mocks.version.mockResolvedValue({
      fields: [{ ...newField(1, '旧文本'), id: '12', key: '12', type: 'TEXT', length: 200 }],
      fieldOptions: { '12': { ...defaultFieldOptions(), columnName: 'original_column' } },
      details: [],
      relations: [],
      indexes: []
    })
    const root = await editor()
    expect(text(row(root, '旧文本'))).toContain('待发布变更')
    await event(button(row(root, '旧文本'), '配置'), 'onClick')
    await settlePreview()
    expect(mocks.fieldSwitchPreview).toHaveBeenCalledWith(
      expect.objectContaining({ fieldId: '12', targetType: 'DECIMAL' })
    )
    expect(text(dialog(root))).toContain('本列 2 条有值')
  })

  it('数据来源切换即使字段类型相同也检查历史值，取消时保留来源', async () => {
    fixture.draft.fields.push({ ...newField(1, '状态'), id: '12', key: '12', type: 'SELECT' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    const root = await editor()
    await event(button(row(root, '状态'), '配置'), 'onClick')
    await event(control(dialog(root), '数据来源', 'a-select'), 'onChange', 'SYSTEM_DICTIONARY')
    await settlePreview()
    expect(mocks.fieldSwitchPreview).toHaveBeenCalledWith(
      expect.objectContaining({
        fieldId: '12',
        targetType: 'SELECT',
        selection: expect.objectContaining({ kind: 'SYSTEM_DICTIONARY' })
      })
    )
    expect(control(dialog(root), '数据来源', 'a-select').props.value).toBe('SYSTEM_DICTIONARY')
    await event(dialog(root), 'onCancel')
    await event(button(row(root, '状态'), '配置'), 'onClick')
    expect(control(dialog(root), '数据来源', 'a-select').props.value).toBe('LOCAL_OPTIONS')
  })

  it('存在其他依赖时展示处理位置并阻止字段切换', async () => {
    fixture.draft.fields.push({ ...newField(1, '旧文本'), id: '12', key: '12', type: 'TEXT' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column' }
    mocks.fieldSwitchPreview.mockResolvedValueOnce({
      objectId: '10',
      detailId: null,
      fieldId: '12',
      fieldName: '旧文本',
      sourceType: 'TEXT',
      targetType: 'SELECT',
      deploymentState: 'DEPLOYED',
      totalRows: 3,
      valueRows: 2,
      decision: 'BLOCKED',
      explanation: '存在必须先处理的依赖',
      impacts: [{ sourceName: '订单统计', location: '公式 / 金额汇总', message: '请先移除字段引用', blocking: true }]
    })
    const root = await editor()
    await event(find(row(root, '旧文本'), 'a-select'), 'onChange', 'INTEGER')
    await settlePreview()
    expect(row(root, '旧文本').props.record.type).toBe('TEXT')
    expect(find(dialog(root), 'a-alert', node => String(node.props.message).includes('公式 / 金额汇总'))).toBeDefined()
    await event(dialog(root), 'onOk')
    expect(row(root, '旧文本').props.record.type).toBe('TEXT')
  })

  it('显式对象引用可经字段抽屉改回普通文本，原字段身份与列名保留', async () => {
    fixture.publishedVersion = 1
    fixture.draft.fields.push({ ...newField(1, '关联资料'), id: '12', key: '12', type: 'REFERENCE' })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), columnName: 'original_column', nativeType: 'bigint' }
    fixture.relations.push({
      id: '30',
      code: 'related',
      name: '关联资料',
      kind: 'REFERENCE',
      targetObjectId: '20',
      fieldId: '12',
      targetFieldId: null,
      required: false,
      onDelete: 'RESTRICT'
    })
    const root = await editor()
    await event(button(root, '移除关系'), 'onClick')
    expect(control(dialog(root), '字段类型', 'a-select').props.value).toBe('TEXT')
    await event(dialog(root), 'onCancel')
    expect(text(row(root, '关联资料'))).toContain('单选（对象引用）')
    await event(button(row(root, '关联资料'), '配置'), 'onClick')
    const relationType = control(dialog(root), '字段类型', 'a-select')
    expect(relationType.props.value).toBe('REFERENCE')
    expect(relationType.props.options).toEqual(expect.arrayContaining([expect.objectContaining({ value: 'TEXT' })]))
    await event(relationType, 'onChange', 'TEXT')
    expect(control(dialog(root), '字段类型', 'a-select').props.value).toBe('TEXT')
    await approveClear(root)
    await event(dialog(root), 'onOk')
    expect(mocks.fieldSwitchPreview).toHaveBeenCalledWith(
      expect.objectContaining({ fieldId: '12', targetType: 'TEXT', detachRelation: true })
    )
    expect(row(root, '关联资料').props.record.type).toBe('TEXT')
    await event(button(root, '保存草稿'), 'onClick')
    const payload = mocks.save.mock.calls[0]![0]
    expect(payload.relations).toEqual([])
    expect(payload.draft.fields[1]).toMatchObject({ id: '12', key: '12', type: 'TEXT' })
    expect(payload.fieldOptions['12']).toMatchObject({ columnName: 'original_column', nativeType: null })
  })

  for (const type of ['SELECT', 'MULTI_SELECT'] as const) {
    it(`${type} 从字段入口配置及再次配置均无需物理类型或引用列`, async () => {
      const root = await editor()
      await addSelection(root, type, '分类')
      await chooseObject(root)
      expect(text(dialog(root))).toContain(type === 'SELECT' ? '单选（对象引用）' : '多选（对象引用）')
      expect(
        findAll(dialog(root), node => node.type === 'a-form-item' && ['引用列', '关系类型'].includes(node.props.label))
      ).toHaveLength(0)
      await event(dialog(root), 'onOk')
      expect(text(row(root, '分类'))).toContain('对象引用')
      await event(button(row(root, '分类'), '配置'), 'onClick')
      expect(findAll(dialog(root), node => node.type === 'a-form-item' && node.props.label === '引用列')).toHaveLength(
        0
      )
      await event(control(dialog(root), '关系名称', 'a-input'), 'onUpdate:value', '取消修改')
      await event(dialog(root), 'onCancel')
      expect(row(root, '分类')).toBeDefined()
      await event(button(root, '新增字段'), 'onClick')
      await event(
        find(
          find(root, 'row', node => !node.props.record.name),
          'a-input',
          node => node.props.placeholder === '字段名称'
        ),
        'onUpdate:value',
        '补充'
      )
      await event(button(root, '保存草稿'), 'onClick')
      const payload = mocks.save.mock.calls[0]![0]
      expect(payload.relations[0]).toMatchObject({
        fieldId: null,
        kind: type === 'SELECT' ? 'REFERENCE' : 'MANY_TO_MANY'
      })
      expect(payload.draft.fields.map((field: any) => field.name)).toEqual(['名称', '补充'])
    })
  }

  it('取消首次对象配置保留原选择字段；缺少目标和策略冲突均留在弹窗', async () => {
    const root = await editor()
    await addSelection(root, 'SELECT', '分类')
    await event(control(dialog(root), '数据来源', 'a-select'), 'onChange', 'OBJECT_RELATION')
    await event(dialog(root), 'onOk')
    expect(find(dialog(root), 'a-alert', node => node.props.type === 'error').props.message).toContain('目标对象')
    await event(control(dialog(root), '从哪份资料选择', 'a-select'), 'onUpdate:value', '20')
    await event(find(dialog(root), 'a-checkbox'), 'onUpdate:checked', true)
    await event(
      find(
        find(dialog(root), 'a-form-item', node => String(node.props.label).startsWith('删除被关联的')),
        'a-select'
      ),
      'onUpdate:value',
      'SET_NULL'
    )
    await event(dialog(root), 'onOk')
    expect(find(dialog(root), 'a-alert', node => node.props.type === 'error').props.message).toContain('必填引用')
    await event(dialog(root), 'onCancel')
    expect(row(root, '分类').props.record.type).toBe('SELECT')
    expect(text(row(root, '分类'))).not.toContain('对象引用')
  })

  it('保存后的单选统一维护关系名称，重新打开不显示引用列映射', async () => {
    fixture.draft.fields.push({ ...newField(1, '原名称'), id: '12', key: '12', type: 'INTEGER', length: null })
    fixture.fieldOptions['12'] = { ...defaultFieldOptions(), generated: true }
    fixture.relations.push({
      id: '30',
      code: 'category',
      name: '原名称',
      kind: 'REFERENCE',
      targetObjectId: '20',
      fieldId: '12',
      targetFieldId: null,
      required: false,
      onDelete: 'RESTRICT'
    })
    const root = await editor()
    expect(find(row(root, '原名称'), 'a-input', node => node.props.placeholder === '字段名称').props.disabled).toBe(
      true
    )
    await event(button(row(root, '原名称'), '配置'), 'onClick')
    expect(findAll(dialog(root), node => node.type === 'a-form-item' && node.props.label === '引用列')).toHaveLength(0)
    await event(control(dialog(root), '关系名称', 'a-input'), 'onUpdate:value', '新名称')
    await event(dialog(root), 'onOk')
    expect(row(root, '新名称')).toBeDefined()
    await event(button(root, '保存草稿'), 'onClick')
    expect(mocks.save.mock.calls[0]![0].relations[0].name).toBe('新名称')
  })

  for (const published of [false, true]) {
    it(`显式单值引用统一从字段抽屉配置：${published ? '已发布' : '草稿关系'}`, async () => {
      fixture.publishedVersion = 1
      fixture.draft.fields.push({ ...newField(1, '分类'), id: '12', key: '12', type: 'REFERENCE', length: null })
      fixture.relations.push({
        id: '30',
        code: 'category',
        name: '分类',
        kind: 'REFERENCE',
        targetObjectId: '20',
        fieldId: '12',
        targetFieldId: null,
        required: false,
        onDelete: 'RESTRICT'
      })
      mocks.version.mockResolvedValue({
        fields: [],
        details: [],
        relations: published ? [{ id: '30' }] : [],
        indexes: []
      })
      const root = await editor()
      await event(button(row(root, '分类'), '配置'), 'onClick')
      expect(control(dialog(root), '字段编码', 'a-input').props.disabled).toBe(true)
      expect(control(dialog(root), '从哪份资料选择', 'a-select').props.disabled).not.toBe(true)
    })
  }

  it('只读查看多选对象引用不会显示单选标签或局部选项', async () => {
    const instance = mount(FieldDesigner, {
      modelValue: [],
      options: {},
      readOnly: true,
      relations: [
        {
          id: '30',
          code: 'tags',
          name: '标签',
          kind: 'MANY_TO_MANY',
          targetObjectId: '20',
          fieldId: null,
          targetFieldId: null,
          required: false,
          onDelete: 'RESTRICT'
        }
      ]
    })
    mounted.push(instance)
    await event(button(instance.root, '查看'), 'onClick')
    expect(find(dialog(instance.root), 'a-alert', node => node.props.message === '多选（对象引用）')).toBeDefined()
    expect(
      findAll(dialog(instance.root), node => node.type === 'a-alert' && node.props.message === '单选（对象引用）')
    ).toHaveLength(0)
    expect(
      findAll(dialog(instance.root), node => node.type === 'a-form-item' && node.props.label === '局部选项')
    ).toHaveLength(0)
  })

  it('普通单选、多选来源配置自动确定逻辑类型，公共字典未选定不能确认', async () => {
    const root = await editor()
    await addSelection(root, 'MULTI_SELECT', '状态')
    await event(control(dialog(root), '数据来源', 'a-select'), 'onChange', 'SYSTEM_DICTIONARY')
    await event(dialog(root), 'onOk')
    expect(find(dialog(root), 'a-alert', node => node.props.type === 'error').props.message).toContain('公共字典')
    await event(control(dialog(root), '公共字典', 'a-select'), 'onChange', 'sys_common_status')
    await event(dialog(root), 'onOk')
    expect(row(root, '状态').props.record.type).toBe('MULTI_SELECT')
    await event(button(row(root, '状态'), '配置'), 'onClick')
    await event(control(dialog(root), '数据来源', 'a-select'), 'onChange', 'DIRECTORY')
    await event(control(dialog(root), '系统目录', 'a-select'), 'onChange', 'USER')
    await event(control(dialog(root), '选择数量', 'a-select'), 'onChange', false)
    await settlePreview()
    await event(dialog(root), 'onOk')
    expect(row(root, '状态').props.record.type).toBe('USER')
    await event(button(root, '保存草稿'), 'onClick')
    const payload = mocks.save.mock.calls[0]![0]
    expect(payload.fieldOptions[payload.draft.fields[1].key].selection).toMatchObject({
      kind: 'DIRECTORY',
      directory: 'USER'
    })
  })
})
