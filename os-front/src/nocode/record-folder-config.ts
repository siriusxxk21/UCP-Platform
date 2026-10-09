import type {
  RecordFolderNameTemplate,
  RecordFolderSource,
  RecordFolderSourceInput
} from '@/types/nocode/record-folder'

/**
 * 文件夹配置的前端校验镜像与提交整形。
 *
 * 只镜像不依赖服务端数据就能判的几条（个数、页签名称、重复、名称组合的形状）；
 * 文件夹是否可用、有没有权限、关联是否成立、层数与循环只有服务端判得了。文案与服务端一致。
 */
export const RECORD_FOLDER_MAX_SOURCES = 6
export const RECORD_FOLDER_LABEL_MAX = 20
export const RECORD_FOLDER_NAME_PARTS_MAX = 5
export const RECORD_FOLDER_NAME_TEXT_MAX = 20
export const RECORD_FOLDER_DEFAULT_SEPARATOR = '-'
/** 可选的分隔符：值就是连接各段的文字 */
export const RECORD_FOLDER_SEPARATORS: Array<{ label: string; value: string }> = [
  { label: '-', value: '-' },
  { label: '_', value: '_' },
  { label: '空格', value: ' ' },
  { label: ' · ', value: ' · ' },
  { label: '不分隔', value: '' }
]

const subfolder = (source: Pick<RecordFolderSourceInput, 'placement'>) => source.placement === 'RECORD_SUBFOLDER'

function nameTemplateIssue(template: RecordFolderNameTemplate): string | undefined {
  const parts = template.parts || []
  if (parts.length > RECORD_FOLDER_NAME_PARTS_MAX) return `文件夹名称最多由 ${RECORD_FOLDER_NAME_PARTS_MAX} 段组成`
  if (!RECORD_FOLDER_SEPARATORS.some(option => option.value === (template.separator ?? '')))
    return '文件夹名称的分隔符不支持'
  const fields = new Set<string>()
  for (const part of parts) {
    if (part.kind === 'TEXT') {
      const text = (part.text ?? '').trim()
      if (!text || text.length > RECORD_FOLDER_NAME_TEXT_MAX || /[/\\\r\n\t]/.test(text))
        return '固定文字要在 1 到 20 个字之间，且不能包含 / 或 \\'
      continue
    }
    const fieldId = part.fieldId ?? ''
    if (fields.has(fieldId)) return '文件夹名称里的字段不能重复'
    fields.add(fieldId)
  }
  if (!fields.size) return '文件夹名称里至少要有一个字段'
  return undefined
}

/** 提交前就能发现的问题；空数组表示可以提交 */
export function recordFolderIssues(sources: RecordFolderSourceInput[]): string[] {
  const issues: string[] = []
  if (sources.length > RECORD_FOLDER_MAX_SOURCES) issues.push(`一个对象最多配置 ${RECORD_FOLDER_MAX_SOURCES} 个文件夹`)
  const labels = new Set<string>(),
    shapes = new Set<string>()
  for (const source of sources) {
    const label = (source.label ?? '').trim()
    if (label.length > RECORD_FOLDER_LABEL_MAX) issues.push(`页签名称不能超过 ${RECORD_FOLDER_LABEL_MAX} 个字`)
    else if (label && labels.has(label)) issues.push(`页签名称「${label}」重复`)
    if (label) labels.add(label)
    const shape =
      source.kind === 'FOLDER'
        ? `F|${String(source.entryId ?? '')}|${source.placement}`
        : `R|${source.relationFieldId ?? ''}|${source.targetSourceId ?? ''}|${source.placement}`
    if (shapes.has(shape)) issues.push('同一个文件夹同一种放法不能添加两次')
    shapes.add(shape)
    if (subfolder(source) && source.nameTemplate) {
      const issue = nameTemplateIssue(source.nameTemplate)
      if (issue) issues.push(issue)
    }
  }
  return [...new Set(issues)]
}

/**
 * 整形成提交体：只留服务端接受的字段（回显字段不提交）；
 * 放法不是「子文件夹」时，建立时机与名称组合对它没有意义，一律不带。
 */
export function recordFolderSaveInput(
  sources: Array<RecordFolderSourceInput | RecordFolderSource>
): RecordFolderSourceInput[] {
  return sources.map(source => {
    const own = subfolder(source)
    const template = own ? (source.nameTemplate ?? null) : null
    return {
      id: source.id || null,
      kind: source.kind,
      placement: source.placement,
      label: (source.label ?? '').trim(),
      spaceId: source.kind === 'FOLDER' ? (source.spaceId ?? null) : null,
      entryId: source.kind === 'FOLDER' ? (source.entryId ?? null) : null,
      relationFieldId: source.kind === 'RELATION' ? (source.relationFieldId ?? '') : null,
      targetSourceId: source.kind === 'RELATION' ? (source.targetSourceId ?? null) : null,
      createMode: own ? (source.createMode ?? 'ON_FIRST_WRITE') : null,
      nameTemplate: template
        ? {
            separator: template.separator ?? '',
            parts: template.parts.map(part =>
              part.kind === 'TEXT'
                ? { kind: 'TEXT' as const, text: (part.text ?? '').trim() }
                : { kind: 'FIELD' as const, fieldId: part.fieldId ?? '' }
            )
          }
        : null
    }
  })
}

/** 两份配置提交出去是否相同：用来判断「有未保存的修改」 */
export function recordFolderSameConfig(
  left: Array<RecordFolderSourceInput | RecordFolderSource>,
  right: Array<RecordFolderSourceInput | RecordFolderSource>
): boolean {
  return JSON.stringify(recordFolderSaveInput(left)) === JSON.stringify(recordFolderSaveInput(right))
}
