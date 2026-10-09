import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import { businessFieldRules } from './business-field-rules'
import { formDesignModel } from './form-design'
import { cloneFormRule } from './form-rule'
import { nodesToRules, rulesToNodes } from './application-ui'
import { FieldType } from '@/types/nocode/enums'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'
import type { Rule } from '@form-create/ant-design-vue'

const validatorOf = (rule: Rule) =>
  (rule.validate?.[0] as { validator: (...args: unknown[]) => Promise<void> }).validator

describe('字段物料的运行时复制', () => {
  it('URL 字段拖入保留校验函数，各次生成的嵌套规则互不污染', async () => {
    const field = { id: 'url', key: 'url', name: '网址', code: 'url', type: FieldType.URL } as ObjectField
    const source = reactive(businessFieldRules([field], {}, formDesignModel, true, { mode: 'design' })[0]!) as Rule
    const first = cloneFormRule(source)
    const second = cloneFormRule(source)
    expect(validatorOf(first)).toBe(validatorOf(source))
    expect(first.validate).not.toBe(second.validate)
    first.props!.placeholder = '本表单提示'
    expect(second.props!.placeholder).not.toBe('本表单提示')
    const validator = validatorOf(first)
    await expect(validator({}, { link: 'https://example.com', text: '官网' })).resolves.toBeUndefined()
    await expect(validator({}, { link: 'javascript:alert(1)' })).rejects.toThrow()
    const nodes = rulesToNodes(nodesToRules([uiNode(NodeKind.FIELD, { fieldId: 'url' })], [first], true))
    expect(nodes[0]!.fieldId).toBe('url')
    expect(JSON.stringify(nodes)).not.toMatch(/validator|validate|nocodeHyperlink/)
  })
  it('保留所有既有字段控件映射、事件回调和未定义值', () => {
    const fields = Object.values(FieldType).map((type, i) => ({
      id: String(i),
      key: String(i),
      name: type,
      code: type.toLowerCase(),
      type
    })) as ObjectField[]
    const upload = () => undefined
    for (const rule of businessFieldRules(fields, {}, formDesignModel, true, {
      mode: 'design',
      onUploadStatus: upload
    })) {
      const clone = cloneFormRule(reactive(rule) as Rule)
      expect(clone).toEqual(rule)
      expect(clone).not.toBe(rule)
    }
  })
})
