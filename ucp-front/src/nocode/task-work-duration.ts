/** 参考工作量使用整数分钟，独立于排期、等待时间与实际起止时间。 */
export const MAX_EFFECTIVE_WORK_MINUTES = 9999 * 60 + 59

export function validEffectiveWorkMinutes(value: number | null | undefined): boolean {
  return value == null || (Number.isInteger(value) && value >= 0 && value <= MAX_EFFECTIVE_WORK_MINUTES)
}

export function formatEffectiveWorkMinutes(value: number | null | undefined): string {
  if (!value || !validEffectiveWorkMinutes(value)) return '未设置'
  const hours = Math.floor(value / 60)
  const minutes = value % 60
  return [hours ? `${hours} 小时` : '', minutes ? `${minutes} 分钟` : ''].filter(Boolean).join(' ')
}
