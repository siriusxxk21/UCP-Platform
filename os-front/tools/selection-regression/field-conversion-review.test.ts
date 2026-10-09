import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { event, find, findAll, flush, mount, table, text } from './renderer'
import type { FieldConversion } from '@/types/nocode/data-center'

const mocks = vi.hoisted(() => ({ rows: vi.fn(), permission: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ hasPermission: mocks.permission, dataCenter: { conversionRows: mocks.rows } })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: table }))
import FieldConversionReview from '@/views/nocode/components/FieldConversionReview.vue'

const conversion = (): FieldConversion => ({
  fieldId: '41',
  detailId: null,
  fieldName: '分类',
  sourceName: '订单',
  fromType: 'varchar',
  toType: 'bigint',
  affectedRows: 2,
  deletedRows: 1,
  masked: false,
  fingerprint: 'old',
  clearAllowed: true,
  impacts: []
})
const mounted: ReturnType<typeof mount>[] = []
beforeEach(() => {
  mocks.permission.mockReturnValue(true)
  mocks.rows.mockResolvedValue({
    rows: [
      { id: '7', title: '订单 7', parentId: null, oldValue: '旧分类', deleted: false },
      { id: '8', title: '订单 8', parentId: null, oldValue: '••••', deleted: true }
    ],
    total: 2,
    pageNo: 1,
    pageSize: 20
  })
})
afterEach(() => {
  mounted.splice(0).forEach(item => item.unmount())
  vi.clearAllMocks()
})

describe('转换影响审核组件', () => {
  it('展示仅本列清空范围、保留删除记录及原值；新计划撤销旧确认', async () => {
    const selected = ref<string[]>([])
    const planId = ref('plan-1')
    const instance = mount(
      defineComponent({
        setup: () => () =>
          h(FieldConversionReview, {
            planId: planId.value,
            conversions: [conversion()],
            confirmed: selected.value,
            'onUpdate:confirmed': value => {
              selected.value = value
            }
          })
      })
    )
    mounted.push(instance)
    expect(find(instance.root, 'a-alert').props.description).toContain('整条记录与其他字段保留')
    expect(text(instance.root)).toContain('1 条为已删除')
    await event(find(instance.root, 'a-button'), 'onClick')
    expect(mocks.rows).toHaveBeenCalledWith('plan-1', '41', 1, 20)
    expect(text(instance.root)).toContain('旧分类')
    expect(text(instance.root)).toContain('已删除保留')
    await event(find(instance.root, 'a-checkbox'), 'onChange', { target: { checked: true } })
    expect(selected.value).toEqual(['41'])
    planId.value = 'plan-2'
    await flush()
    expect(selected.value).toEqual([])
    expect(findAll(instance.root, item => item.type === 'table-page')).toHaveLength(0)
  })
  it('无预览或清空权限时提示原因，不查询原值也不提供清空勾选', () => {
    mocks.permission.mockReturnValue(false)
    const instance = mount(FieldConversionReview, { planId: 'p', conversions: [conversion()], confirmed: [] })
    mounted.push(instance)
    expect(text(instance.root)).toContain('数据表查询与预览权限')
    expect(text(instance.root)).toContain('对象管理权限')
    expect(findAll(instance.root, item => item.type === 'a-checkbox')).toHaveLength(0)
    expect(mocks.rows).not.toHaveBeenCalled()
  })
  it('仅有管理权限而无修改权限不能确认清空', () => {
    mocks.permission.mockImplementation((permission: string) => permission !== 'nocode:object:update')
    const instance = mount(FieldConversionReview, { planId: 'p', conversions: [conversion()], confirmed: [] })
    mounted.push(instance)
    expect(text(instance.root)).toContain('对象修改权限')
    expect(findAll(instance.root, item => item.type === 'a-checkbox')).toHaveLength(0)
  })
  it('保留值转换展示规则与原值，不出现清空授权', async () => {
    const item = { ...conversion(), action: 'PRESERVE_VALUES' as const, conversionRule: '精确保留旧数值' }
    const instance = mount(FieldConversionReview, { planId: 'p', conversions: [item], confirmed: [] })
    mounted.push(instance)
    expect(text(instance.root)).toContain('按规则转换并保留')
    expect(text(instance.root)).toContain('精确保留旧数值')
    expect(findAll(instance.root, node => node.type === 'a-checkbox')).toHaveLength(0)
    await event(
      find(instance.root, 'a-button', node => text(node).includes('查看受影响')),
      'onClick'
    )
    expect(mocks.rows).toHaveBeenCalledWith('p', '41', 1, 20)
  })
  it('具体依赖提供定位，阻断不允许清空；原值加载失败可重试', async () => {
    const item = conversion()
    item.clearAllowed = false
    item.impacts = [
      {
        fieldId: '41',
        sourceKind: 'APPLICATION',
        sourceId: '6',
        sourceName: '采购管理',
        location: '订单表单 / 自动赋值',
        message: '旧选项编码不再兼容引用 ID',
        route: '/nocode-app/workspace?id=6',
        blocking: true
      }
    ]
    const navigate = vi.fn()
    const instance = mount(FieldConversionReview, {
      planId: 'p',
      conversions: [item],
      confirmed: [],
      onNavigate: navigate
    })
    mounted.push(instance)
    await event(
      find(instance.root, 'a-button', node => text(node) === '前往处理'),
      'onClick'
    )
    expect(navigate).toHaveBeenCalledWith('/nocode-app/workspace?id=6')
    expect(findAll(instance.root, node => node.type === 'a-checkbox')).toHaveLength(0)
    mocks.rows.mockRejectedValueOnce(new Error('记录已变化，请重新检查'))
    await event(
      find(instance.root, 'a-button', node => text(node).includes('查看受影响')),
      'onClick'
    )
    expect(find(instance.root, 'a-alert', node => String(node.props.message).includes('记录已变化'))).toBeDefined()
    await event(
      find(instance.root, 'a-button', node => text(node).includes('查看受影响')),
      'onClick'
    )
    expect(mocks.rows).toHaveBeenCalledTimes(2)
  })
})
