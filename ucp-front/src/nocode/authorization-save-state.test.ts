// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import ApplicationMembers from '@/views/nocode/application/components/ApplicationMembers.vue'
import ObjectGrantFields from '@/views/nocode/components/ObjectGrantFields.vue'
import GrantFieldSelect from '@/views/nocode/components/GrantFieldSelect.vue'
import type { ObjectGrant } from '@/types/nocode/authorization'
import type { PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import type { DataScope } from '@/types/nocode/data-scope'

const mocks = vi.hoisted(() => ({
  authorization: vi.fn(),
  sharing: vi.fn(),
  saveAuthorization: vi.fn(),
  roleGet: vi.fn(),
  success: vi.fn(),
  modal: vi.fn(),
  leaveGuards: [] as Array<() => Promise<boolean>>
}))
Element.prototype.scrollIntoView = vi.fn()
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: mocks }) }))
vi.mock('@/utils/request', () => ({ default: { get: mocks.roleGet } }))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('ant-design-vue', () => ({ message: { success: mocks.success }, Modal: { confirm: mocks.modal } }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (guard: () => Promise<boolean>) => mocks.leaveGuards.push(guard),
  onBeforeRouteUpdate: () => {}
}))
vi.mock('@/components/UserSelectorTrigger.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['modelValue'],
      emits: ['update:modelValue'],
      setup:
        (_, { emit }) =>
        () =>
          h('button', { type: 'button', onClick: () => emit('update:modelValue', ['user']) }, '选择回归用户')
    })
  }
})

// 接口与底座控件替身不联网；权限表单、嵌套条件编辑器和未保存保护使用真实组件。
let app: App | undefined
let host: HTMLDivElement
const renderErrors: unknown[] = []
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const definition = {
  objectName: '权限回归对象',
  fields: [{ id: 'status', name: '状态', type: 'TEXT', state: 'ACTIVE', columnName: 'c_status' }],
  details: [],
  relations: []
} as unknown as PublishedDefinition
const scope = (): DataScope => ({
  logic: 'AND',
  groups: [],
  conditions: [{ fieldId: 'status', operator: 'eq', value: 'ACTIVE', valueSource: 'CONSTANT' }]
})
const grant = (actionScopes?: Record<string, DataScope>): ObjectGrant => ({
  objectId: 'object',
  actions: ['READ'],
  scope: 'ALL',
  readFields: ['status'],
  writeFields: [],
  readDetails: [],
  writeDetails: [],
  readRelations: [],
  writeRelations: [],
  computeFields: [],
  ...(actionScopes === undefined ? {} : { actionScopes })
})
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const dirty = () => host.textContent!.includes('有未保存授权')
const click = async (label: string) => {
  const button = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.trim() === label)
  expect(button, label).toBeDefined()
  button!.click()
  await flush()
}
function controls(instance: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'ASpace',
    'AForm',
    'AFormItem',
    'ACard',
    'APopconfirm',
    'ACollapse',
    'ACollapsePanel',
    'ARadioGroup',
    'ARadio',
    'ACheckboxGroup',
    'ASwitch',
    'AEmpty'
  ])
    instance.component(name, plain)
  instance.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('div', p.message) }))
  instance.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: !!p.disabled || !!p.loading }, slots.default?.())
    })
  )
  instance.component(
    'AInput',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            value: p.value,
            disabled: !!p.disabled,
            onInput: (e: Event) => {
              emit('update:value', (e.target as HTMLInputElement).value)
              emit('change', e)
            }
          })
    })
  )
  instance.component(
    'ACheckbox',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['change'],
      setup:
        (p, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: !!p.checked,
              disabled: !!p.disabled,
              onChange: (e: Event) => emit('change', e)
            }),
            slots.default?.()
          ])
    })
  )
  instance.component(
    'ASelect',
    defineComponent({
      props: ['value', 'disabled', 'options', 'mode'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value,
              disabled: !!p.disabled,
              multiple: p.mode === 'multiple',
              onChange: (e: Event) => {
                const select = e.target as HTMLSelectElement
                const value =
                  p.mode === 'multiple' ? Array.from(select.selectedOptions).map(o => o.value) : select.value
                emit('update:value', value)
                emit('change', value)
              }
            },
            (p.options || []).map((o: { label: string; value: string; disabled?: boolean }) =>
              h('option', { value: o.value, disabled: o.disabled }, o.label)
            )
          )
    })
  )
}
async function mountMembers(actionScopes?: Record<string, DataScope>, emptyMembers = false) {
  let persisted = {
    revision: 1,
    members: emptyMembers ? [] : [{ principalKind: 'ROLE', principalId: 'role', objects: [grant(actionScopes)] }]
  }
  mocks.authorization.mockImplementation(async () => clone(persisted))
  mocks.sharing.mockResolvedValue([{ objectId: 'object', permission: grant() }])
  mocks.roleGet.mockResolvedValue([{ id: 'role', name: '回归角色' }])
  const save = async (request: { members: typeof persisted.members }) => {
    persisted = { revision: persisted.revision + 1, members: clone(request.members) }
    return clone(persisted)
  }
  mocks.saveAuthorization.mockImplementation(save)
  mocks.modal.mockImplementation(options => options.onCancel())
  app = createApp(() =>
    h(ApplicationMembers, {
      applicationId: 'app',
      objects: {
        object: { objectId: 'object', definition, versionNo: 1, checksum: 'fixture' }
      } as Record<string, PublishedObject>
    })
  )
  app.config.errorHandler = error => renderErrors.push(error)
  controls(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return () => clone(persisted)
}
/** 成员卡片默认折叠（业务方 2026-10-04）：编辑前先展开全部卡片。 */
async function expandMembers() {
  for (const button of Array.from(host.querySelectorAll<HTMLButtonElement>('.member-summary[aria-expanded="false"]')))
    button.click()
  await flush()
}
async function inputCondition(value: string) {
  await expandMembers()
  const input = host.querySelector<HTMLInputElement>('input[aria-label="范围值"]:not(:disabled)')!
  expect(input).not.toBeNull()
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.leaveGuards.length = 0
  renderErrors.length = 0
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  app = undefined
})

describe('授权保存后的未保存保护', () => {
  it.each<{ name: string; scopes: Record<string, DataScope> | undefined }>([
    { name: '空条件', scopes: {} },
    { name: '已有条件', scopes: { READ: scope() } },
    { name: '省略条件', scopes: undefined }
  ])('$name保存成功后不再误报', async ({ scopes }) => {
    const persisted = await mountMembers(scopes)
    expect(dirty()).toBe(false)
    await click('保存授权')
    expect(mocks.success).toHaveBeenCalledWith('应用授权已生效')
    expect(persisted().revision).toBe(2)
    expect(dirty()).toBe(false)
    expect(await mocks.leaveGuards[0]!()).toBe(true)
    expect(mocks.modal).not.toHaveBeenCalled()
  })
  it('连续保存、重新加载后保持已保存状态及权限内容', async () => {
    const persisted = await mountMembers({})
    const before = persisted().members
    await click('保存授权')
    await click('保存授权')
    expect(persisted().revision).toBe(3)
    expect(dirty()).toBe(false)
    await click('重新加载')
    expect(persisted().members).toEqual(before)
    expect(dirty()).toBe(false)
    expect(await mocks.leaveGuards[0]!()).toBe(true)
  })
  it.each(['USER', 'ROLE'])('应用成员可添加 %s 并选择对象、保存和重新加载', async kind => {
    const persisted = await mountMembers({}, true)
    expect(renderErrors).toEqual([])
    await click('添加成员或角色')
    expect(renderErrors).toEqual([])
    const principalKind = host.querySelector<HTMLSelectElement>('select:not([multiple])')!
    expect(principalKind).not.toBeNull()
    if (kind === 'ROLE') {
      principalKind.value = 'ROLE'
      principalKind.dispatchEvent(new Event('change', { bubbles: true }))
      await flush()
      const role = host.querySelectorAll<HTMLSelectElement>('select:not([multiple])')[1]!
      role.value = 'role'
      role.dispatchEvent(new Event('change', { bubbles: true }))
      await flush()
    } else await click('选择回归用户')
    const objects = host.querySelector<HTMLSelectElement>('select[multiple]')!
    expect(objects).not.toBeNull()
    expect(objects.options[0]!.disabled).toBe(false)
    objects.options[0]!.selected = true
    objects.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    expect(renderErrors).toEqual([])
    expect(dirty()).toBe(true)
    await click('保存授权')
    expect(persisted().members).toEqual([
      {
        principalKind: kind,
        principalId: kind === 'ROLE' ? 'role' : 'user',
        objects: [
          expect.objectContaining({ objectId: 'object', actions: ['READ'], readFields: ['*'], writeFields: [] })
        ]
      }
    ])
    expect(dirty()).toBe(false)
    await click('重新加载')
    expect(renderErrors).toEqual([])
    await expandMembers()
    expect(host.querySelector<HTMLSelectElement>('select[multiple]')!.selectedOptions[0]!.value).toBe('object')
    expect(dirty()).toBe(false)
  })
  it('条件输入是真实修改，成功保存后再次输入仍受到保护', async () => {
    const persisted = await mountMembers({ READ: scope() })
    await inputCondition('FIRST')
    expect(dirty()).toBe(true)
    expect(await mocks.leaveGuards[0]!()).toBe(false)
    await click('保存授权')
    expect(persisted().members[0]!.objects[0]!.actionScopes!.READ!.conditions[0]!.value).toBe('FIRST')
    expect(dirty()).toBe(false)
    await inputCondition('SECOND')
    expect(dirty()).toBe(true)
    expect(await mocks.leaveGuards[0]!()).toBe(false)
  })
  it('嵌套条件的值与条件组删除均会标记修改', async () => {
    await mountMembers({ READ: { logic: 'AND', conditions: [], groups: [scope()] } })
    await inputCondition('NESTED')
    expect(dirty()).toBe(true)
    await click('保存授权')
    expect(dirty()).toBe(false)
    await click('移除条件组')
    expect(dirty()).toBe(true)
  })
  it('修改后保存失败保留未保存状态与输入', async () => {
    await mountMembers({ READ: scope() })
    await inputCondition('KEEP')
    mocks.saveAuthorization.mockRejectedValueOnce(new Error('模拟保存失败'))
    await click('保存授权')
    expect(mocks.success).not.toHaveBeenCalled()
    expect(dirty()).toBe(true)
    expect(host.textContent).toContain('模拟保存失败')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="范围值"]')!.value).toBe('KEEP')
    expect(await mocks.leaveGuards[0]!()).toBe(false)
  })
  it('新增成员仍触发未保存保护', async () => {
    await mountMembers({})
    await click('添加成员或角色')
    expect(dirty()).toBe(true)
    expect(await mocks.leaveGuards[0]!()).toBe(false)
  })
  it('相同或服务器规范化后的回填都不当作用户编辑', async () => {
    const model = ref(grant({ READ: scope() }))
    const onChange = vi.fn()
    app = createApp(() => h(ObjectGrantFields, { modelValue: model.value, definition, onChange }))
    controls(app)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
    model.value = clone(model.value)
    await flush()
    model.value = grant({})
    await flush()
    expect(onChange).not.toHaveBeenCalled()
    const checkbox = host.querySelector<HTMLInputElement>('input[type="checkbox"]')!
    checkbox.click()
    await flush()
    expect(onChange).toHaveBeenCalled()
  })
  it('批量选择只加入可用字段，保留旧授权项直至用户明确清空', async () => {
    const model = ref(['legacy'])
    const onChange = vi.fn()
    app = createApp(() =>
      h(GrantFieldSelect, {
        modelValue: model.value,
        'onUpdate:modelValue': value => (model.value = value),
        label: '可查看字段',
        options: [
          { value: 'allowed', label: '允许' },
          { value: 'blocked', label: '禁止', disabled: true }
        ],
        onChange
      })
    )
    controls(app)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
    await click('全选可用项')
    expect(model.value).toEqual(['legacy', 'allowed'])
    await click('全选可用项')
    expect(model.value).toEqual(['legacy', 'allowed'])
    await click('清空')
    expect(model.value).toEqual([])
    expect(onChange).toHaveBeenCalledTimes(3)
  })
  it('继续编辑定位当前授权实例的保存按钮', async () => {
    await mountMembers({})
    await click('添加成员或角色')
    expect(await mocks.leaveGuards[0]!()).toBe(false)
    await flush()
    expect(document.activeElement).toBe(host.querySelector('[data-authorization-save]'))
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })
})

describe('成员授权的「全部」默认值与收紧提示', () => {
  /** 模拟服务端按上一层收紧：把第一个成员第一个对象的可查看字段清空后返回。 */
  const tightenOnce = (revision: number) =>
    mocks.saveAuthorization.mockImplementationOnce(async (request: { members: unknown }) => {
      const members = clone(request.members) as Array<{ objects: ObjectGrant[] }>
      for (const member of members.slice(0, 1)) for (const item of member.objects.slice(0, 1)) item.readFields = []
      return { revision, members }
    })
  const canLeave = async () => {
    const [guard] = mocks.leaveGuards
    if (!guard) throw new Error('离开保护未注册')
    return guard()
  }
  async function addMemberWithObject() {
    await click('添加成员或角色')
    await click('选择回归用户')
    const objects = host.querySelector<HTMLSelectElement>('select[multiple]')
    const first = objects?.options[0]
    if (!objects || !first) throw new Error('可访问对象的选择框不存在')
    first.selected = true
    objects.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
  }
  it.each([['应用成员', false]])(
    '给%s新选对象：可查看的三个清单默认全部，可修改的三个为空，仅查看、本人创建',
    async () => {
      const persisted = await mountMembers({}, true)
      await addMemberWithObject()
      expect(renderErrors).toEqual([])
      await click('保存授权')
      expect(persisted().members.map(member => member.objects)).toEqual([
        [
          {
            objectId: 'object',
            actions: ['READ'],
            scope: 'OWN',
            readFields: ['*'],
            writeFields: [],
            readDetails: ['*'],
            writeDetails: [],
            readRelations: ['*'],
            writeRelations: []
          }
        ]
      ])
    }
  )
  it.each([['应用成员', false, '随应用数据权限自动同步']])('%s的「全部」写明跟随哪一层', async (_, _entry, upper) => {
    await mountMembers({}, true)
    await addMemberWithObject()
    expect(host.textContent).toContain(upper)
  })
  it('保存后返回的授权比提交的少了项：提示已按上一层收紧几项，且不标成未保存', async () => {
    await mountMembers({})
    expect(host.textContent).not.toContain('已按上一层收紧')
    tightenOnce(2)
    await click('保存授权')
    expect(mocks.success).toHaveBeenCalledWith('应用授权已生效')
    expect(host.textContent).toContain('已按上一层收紧 1 项')
    expect(dirty()).toBe(false)
    expect(await canLeave()).toBe(true)
  })
  it('保存后没有被收紧时不出提示；上一次的提示在重新保存或重新加载后消失', async () => {
    await mountMembers({})
    tightenOnce(2)
    await click('保存授权')
    expect(host.textContent).toContain('已按上一层收紧 1 项')
    await click('保存授权')
    expect(host.textContent).not.toContain('已按上一层收紧')
    tightenOnce(9)
    await click('保存授权')
    await click('重新加载')
    expect(host.textContent).not.toContain('已按上一层收紧')
  })
})

// 业务方 2026-10-04：「权限设置这块看起来太乱了。要能折叠起来。默认折叠。然后展开编辑。」
describe('成员与数据权限 · 卡片折叠', () => {
  const summaries = () => Array.from(host.querySelectorAll<HTMLButtonElement>('.member-summary'))
  it('默认折叠：标题行看得出是谁、能访问哪些对象、记录范围与允许操作', async () => {
    await mountMembers({ READ: scope() })
    const [first] = summaries()
    expect(first!.getAttribute('aria-expanded')).toBe('false')
    expect(first!.textContent).toContain('角色 · 回归角色')
    expect(first!.textContent).toContain('权限回归对象')
    expect(first!.textContent).toContain('全部记录 · 有记录条件')
    expect(first!.textContent).toContain('查看')
    expect(host.querySelector('select[multiple]')).toBeNull()
    expect(host.querySelector('input[aria-label="范围值"]')).toBeNull()
  })
  it('点开才显示可访问对象与授权，再点收起', async () => {
    await mountMembers({})
    summaries()[0]!.click()
    await flush()
    expect(summaries()[0]!.getAttribute('aria-expanded')).toBe('true')
    expect(host.querySelector('select[multiple]')).not.toBeNull()
    summaries()[0]!.click()
    await flush()
    expect(host.querySelector('select[multiple]')).toBeNull()
  })
  it('新加的成员默认展开，原有的保持折叠；有未保存授权照旧提示', async () => {
    await mountMembers({})
    await click('添加成员或角色')
    expect(summaries().map(s => s.getAttribute('aria-expanded'))).toEqual(['false', 'true'])
    expect(summaries()[1]!.textContent).toContain('未选择成员')
    expect(host.textContent).toContain('有未保存授权')
  })
  it('保存前发现问题：不提交，自动展开有问题的卡片并定位', async () => {
    await mountMembers({})
    await click('添加成员或角色')
    summaries()[1]!.click()
    await flush()
    expect(summaries()[1]!.getAttribute('aria-expanded')).toBe('false')
    const scroll = vi.mocked(Element.prototype.scrollIntoView)
    scroll.mockClear()
    await click('保存授权')
    expect(mocks.saveAuthorization).not.toHaveBeenCalled()
    expect(summaries()[1]!.getAttribute('aria-expanded')).toBe('true')
    expect(host.querySelector('[data-member-index="1"] [role="alert"]')?.textContent).toBe('第 2 张授权还没有选择成员')
    expect(host.querySelector('[data-member-index="1"]')!.classList.contains('member-card--problem')).toBe(true)
    expect(scroll.mock.contexts.some(context => (context as Element).getAttribute?.('data-member-index') === '1')).toBe(
      true
    )
    expect(host.textContent).toContain('有未保存授权')
  })
  it('同一个角色出现两次：点名是哪两张', async () => {
    await mountMembers({})
    await click('添加成员或角色')
    const kinds = host.querySelectorAll<HTMLSelectElement>('[data-member-index="1"] select:not([multiple])')
    kinds[0]!.value = 'ROLE'
    kinds[0]!.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    const role = host.querySelectorAll<HTMLSelectElement>('[data-member-index="1"] select:not([multiple])')[1]!
    role.value = 'role'
    role.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    await click('保存授权')
    expect(mocks.saveAuthorization).not.toHaveBeenCalled()
    expect(host.querySelector('[data-member-index="1"] [role="alert"]')?.textContent).toBe(
      '第 2 张授权与第 1 张是同一个角色，请合并'
    )
  })
  it('保存后保留展开状态，不打断正在编辑的卡片', async () => {
    await mountMembers({})
    summaries()[0]!.click()
    await flush()
    await click('保存授权')
    expect(mocks.saveAuthorization).toHaveBeenCalledTimes(1)
    expect(summaries()[0]!.getAttribute('aria-expanded')).toBe('true')
    await click('重新加载')
    expect(summaries()[0]!.getAttribute('aria-expanded')).toBe('false')
  })
})
