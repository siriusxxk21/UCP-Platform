import { describe, expect, it } from 'vitest'
import { isBlankNickname, NICKNAME_REQUIRED_MESSAGE, userFormRules } from './user-form-rules'

/** 跑一遍某个字段的全部规则（与 ant-design-vue 表单一样：validator 返回被拒的 Promise 即为不通过）。 */
async function check(rules: ReturnType<typeof userFormRules>, field: string, value: unknown) {
  for (const rule of rules[field] || []) {
    if (!rule.validator)
      continue
    try {
      await rule.validator(rule, value)
    }
    catch (message) {
      return message
    }
  }
  return null
}

describe('用户弹窗：昵称必填（新建用户失败「系统异常」的修复）', () => {
  for (const [label, isEdit] of [['新增', false], ['编辑', true]] as const) {
    it(`${label}：空昵称被拦下并提示「请填写用户昵称」`, async () => {
      const rules = userFormRules(isEdit)
      for (const value of ['', '   ', null, undefined])
        expect(await check(rules, 'nickname', value)).toBe(NICKNAME_REQUIRED_MESSAGE)
      expect(NICKNAME_REQUIRED_MESSAGE).toBe('请填写用户昵称')
    })
    it(`${label}：填了昵称就放行`, async () => {
      expect(await check(userFormRules(isEdit), 'nickname', '山田')).toBeNull()
      expect(await check(userFormRules(isEdit), 'nickname', ' 山田 ')).toBeNull()
    })
  }
  it('昵称显示必填星号；原有规则不变（用户名必填、密码只在新增时必填）', () => {
    expect(userFormRules(false).nickname[0].required).toBe(true)
    expect(Object.keys(userFormRules(false))).toEqual(['username', 'nickname', 'password'])
    expect(Object.keys(userFormRules(true))).toEqual(['username', 'nickname'])
    expect(userFormRules(false).password).toEqual([{ required: true, message: '请输入密码', trigger: 'blur' }])
    expect(userFormRules(true).username).toEqual([{ required: true, message: '请输入用户名', trigger: 'blur' }])
  })
  it('空白判定', () => {
    expect(isBlankNickname('\t\n ')).toBe(true)
    expect(isBlankNickname('a')).toBe(false)
  })
})
