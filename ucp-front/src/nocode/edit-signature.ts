/** 表单引擎会补空值；只比较业务输入，忽略属性顺序和等价的空控件值。 */
export function editSignature(value: unknown): string {
  const normalize = (input: unknown): unknown => {
    if (Array.isArray(input)) return input.map(normalize)
    if (input && typeof input === 'object')
      return Object.fromEntries(
        Object.entries(input)
          .filter(([, v]) => v != null && v !== '')
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([k, v]) => [k, normalize(v)])
      )
    return input === '' || input == null ? null : input
  }
  return JSON.stringify(normalize(value))
}
