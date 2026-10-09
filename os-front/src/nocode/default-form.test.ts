import { describe, expect, it } from 'vitest'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { isDefaultForm, resolveViewForm } from './default-form'

function form(id: string, objectId = 'object', defaultForObject?: boolean): ApplicationResource {
  return {
    id,
    code: id,
    name: id,
    kind: ResourceKind.FORM,
    config: { objectId, nodes: [], detailIds: [], options: { defaultForObject } }
  }
}

describe('应用内对象默认表单解析', () => {
  it('旧发布没有默认标记时继续使用自动表单，不将第一份普通表单隐式改为默认', () => {
    expect(isDefaultForm(form('ordinary'))).toBe(false)
    expect(resolveViewForm([form('ordinary'), form('not-default', 'object', false)], 'object')).toBeUndefined()
  })
  it('只继承当前资源集合中同对象的默认表单', () => {
    const defaultForm = form('default', 'object', true)
    expect(resolveViewForm([form('other', 'another-object', true), defaultForm], 'object')).toBe(defaultForm)
    expect(resolveViewForm([form('other', 'another-object', true)], 'object')).toBeUndefined()
  })
  it('显式绑定优先，替换默认不会改变已经指定的表单', () => {
    const explicit = form('explicit')
    expect(resolveViewForm([form('default', 'object', true), explicit], 'object', explicit.id)).toBe(explicit)
    expect(resolveViewForm([form('new-default', 'object', true), explicit], 'object', explicit.id)).toBe(explicit)
  })
  it.each([null, undefined, ''])('无显式绑定 %s 时使用默认', id => {
    const defaultForm = form('default', 'object', true)
    expect(resolveViewForm([defaultForm], 'object', id)).toBe(defaultForm)
  })
  it('错误显式绑定不回退到默认或自动表单', () => {
    const resources = [form('default', 'object', true), form('other', 'other-object')]
    expect(() => resolveViewForm(resources, 'object', 'missing')).toThrow('指定的业务表单不存在')
    expect(() => resolveViewForm(resources, 'object', 'other')).toThrow('不属于当前数据对象')
    expect(() =>
      resolveViewForm([{ ...form('page'), kind: ResourceKind.PAGE }, ...resources], 'object', 'page')
    ).toThrow('指定的业务表单不存在')
  })
  it('异常重复默认不任意选择一份，其他资源上的同名选项不参与解析', () => {
    expect(() => resolveViewForm([form('first', 'object', true), form('second', 'object', true)], 'object')).toThrow(
      '多个默认表单'
    )
    const page = { ...form('page', 'object', true), kind: ResourceKind.PAGE }
    expect(isDefaultForm(page)).toBe(false)
    expect(resolveViewForm([page], 'object')).toBeUndefined()
  })
})
