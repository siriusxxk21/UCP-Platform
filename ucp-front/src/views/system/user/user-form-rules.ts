/**
 * 用户新增 / 编辑弹窗的校验规则。
 *
 * 昵称在库里是非空列（system_users.nickname NOT NULL）：以前前端不拦，空昵称提交后数据库报错，页面只显示「系统异常」。
 * 现在新增与编辑都必须填昵称；只填空格也算没填（提交时空串会被清成 undefined，后端 @NotBlank 同样拒绝）。
 */
export const NICKNAME_REQUIRED_MESSAGE = '请填写用户昵称'

/** 只看有没有可见内容：null / undefined / 空串 / 全是空白都算没填。 */
export function isBlankNickname(value: unknown): boolean {
  return value === null || value === undefined || String(value).trim() === ''
}

/** 与 ant-design-vue 表单规则同形（只列本页用到的键）。 */
export interface UserFormRule {
  required?: boolean
  message?: string
  trigger?: string
  validator?: (rule: unknown, value: unknown) => Promise<void>
}

/** 昵称规则：required 只负责显示必填星号；真正的判定在 validator 里（与后端 @NotBlank 同口径）。 */
export const nicknameRules: UserFormRule[] = [
  {
    required: true,
    trigger: 'blur',
    validator: (_rule: unknown, value: unknown) =>
      isBlankNickname(value) ? Promise.reject(NICKNAME_REQUIRED_MESSAGE) : Promise.resolve(),
  },
]

/** 整个弹窗的规则；编辑时不校验密码（原有口径），昵称新增与编辑都校验。 */
export function userFormRules(isEdit: boolean): Record<string, UserFormRule[]> {
  return {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    nickname: nicknameRules,
    ...(isEdit ? {} : { password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }),
  }
}
