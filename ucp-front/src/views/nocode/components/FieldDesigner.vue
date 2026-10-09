<script setup lang="ts">
import FormulaConfigurationModal from './FormulaConfigurationModal.vue'
import AutoNumberEditor from './AutoNumberEditor.vue'
import InactiveFieldsDrawer from './InactiveFieldsDrawer.vue'
import {
  fieldRestoreError,
  inactiveFieldCodeError,
  restoredFieldValue,
  type InactiveFieldCandidate
} from '@/nocode/field-restoration'
import FieldValueEditor from './FieldValueEditor.vue'
import { fieldDefaultError } from '@/nocode/field-defaults'
import SummaryExpressionEditor from './SummaryExpressionEditor.vue'
import { calculationDescription, calculationUpdateDescription } from '@/nocode/calculation-presentation'
import SelectionSourceEditor from './SelectionSourceEditor.vue'
import FieldSwitchReview from './FieldSwitchReview.vue'
import LocalOptionsEditor from './LocalOptionsEditor.vue'
import {
  fieldSwitchFingerprint,
  fieldSwitchSnapshot,
  restoreFieldSwitch,
  type FieldSwitchSnapshot
} from '@/nocode/field-switch-session'
import { fieldConfigurationChanges } from '@/nocode/field-change-summary'
import { selectionTypes } from '@/nocode/selection'
import FieldValueSourceSection from './FieldValueSourceSection.vue'
import { cleanFieldRules, fieldRuleTags, fieldRulesError, valueSourceModesFor } from '@/nocode/field-rules'

import * as NC from '@/types/nocode/enums'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  PlusOutlined,
  UpOutlined,
  DownOutlined,
  EditOutlined,
  EyeOutlined,
  InfoCircleOutlined,
  StopOutlined,
  DeleteOutlined
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { fieldTypes, newField } from '@/nocode/object-draft'
import {
  changeFieldType,
  copyFieldOptions,
  clearMultilinePatterns,
  fieldBasicsError,
  fieldConfigurationError,
  fieldNumericConstraintError,
  fieldPatternError,
  fieldNamePatch,
  fieldStructureLocked
} from '@/nocode/field-editing'
import { defaultFieldOptions } from '@/nocode/data-center'
import {
  fieldRelationConversionError,
  isRelationField,
  relationFieldLabel,
  relationFieldRows,
  relationForField,
  relationManagedField
} from '@/nocode/relation-editing'
import type { FieldType, ObjectField } from '@/types/nocode/object'
import type {
  AutoNumberOptions,
  FieldSwitchPreview,
  FieldOptions,
  InactiveField,
  ObjectRelation,
  ObjectDetail,
  PublishedFieldBaseline
} from '@/types/nocode/data-center'

const props = defineProps<{
  modelValue: ObjectField[]
  options: Record<string, FieldOptions>
  relations?: ObjectRelation[]
  readOnly?: boolean
  adopted?: boolean
  detail?: boolean
  details?: ObjectDetail[]
  legacyAutoNumberIds?: string[] | null
  publishedBaselines?: Record<string, PublishedFieldBaseline>
  loadInactive?: () => Promise<InactiveField[]>
  revision?: string
  reviewSwitch?: (source: ObjectField, target: ObjectField, options: FieldOptions) => Promise<boolean>
  previewSwitch?: (
    source: ObjectField,
    target: ObjectField,
    options: FieldOptions,
    targetObjectId?: string | null
  ) => Promise<FieldSwitchPreview>
  relationTargets?: { label: string; value: string }[]
  loadRelationTargets?: (name?: string) => Promise<void>
  previewRows?: (
    source: ObjectField,
    target: ObjectField,
    options: FieldOptions,
    targetObjectId: string | null,
    pageNo: number
  ) => Promise<import('@/types/nocode/data-center').FieldConversionRows>
  canViewConversionRows?: boolean
  canClearColumn?: boolean
  canMaintainData?: boolean
  reviewOperation?: (field: ObjectField, operation: 'disable_field' | 'restore_field') => Promise<boolean>
  applyConversion?: (
    source: ObjectField,
    target: ObjectField,
    options: FieldOptions,
    targetObjectId: string | null
  ) => string | null
  /** 明细字段的规则可引用主表字段：条件「当前字段」与公式插入面板分「本行 / 主表」两组。 */
  master?: { fields: ObjectField[]; relations?: ObjectRelation[] } | null
  /** 当前对象 ID（未保存的新对象为空）：数据联动据此认出「来源字段挑的是当前字段」。 */
  objectId?: string | null
  /** 对象关系保存后要回到抽屉的关系（按编码）；属于本设计器的关系时打开其引用字段的抽屉。 */
  focusRelation?: { code: string; seq: number } | null
}>()
const emit = defineEmits<{
  'update:modelValue': [ObjectField[]]
  'update:options': [Record<string, FieldOptions>]
  remove: [string]
  /** origin 为 drawer 表示从字段抽屉进入：抽屉保持打开，关系确定并保存后回到抽屉。 */
  relation: [field: ObjectField, origin?: 'drawer']
  /** 未保存关系的占位行：先保存对象草稿，再打开该关系引用字段的抽屉。 */
  'configure-relation': [code: string]
  /** focusRelation 已处理（打开了抽屉或确认无可配置字段）。 */
  'relation-focused': []
  newRelation: []
  navigate: [route: string]
  maintainData: [context: { fieldId: string }]
  restore: [id: string, persisted: boolean]
}>()
const inactiveOpen = ref(false),
  inactiveLoading = ref(false),
  inactiveError = ref('')
const inactiveSaved = ref<InactiveFieldCandidate[]>([]),
  inactiveLocal = ref<InactiveFieldCandidate[]>([])
const inactiveCandidates = computed(() => {
  const candidates = new Map(inactiveSaved.value.map(item => [item.field.id, item]))
  for (const item of inactiveLocal.value) candidates.set(item.field.id, item)
  return [...candidates.values()].filter(item => !props.modelValue.some(field => field.id === item.field.id))
})
let inactiveRequest = 0
async function loadInactiveFields() {
  const request = ++inactiveRequest
  inactiveError.value = ''
  if (!props.loadInactive) {
    inactiveSaved.value = []
    inactiveLoading.value = false
    return
  }
  inactiveLoading.value = true
  try {
    const result = await props.loadInactive()
    if (request === inactiveRequest) inactiveSaved.value = result.map(item => ({ ...item, persisted: true }))
  } catch (cause) {
    if (request === inactiveRequest)
      inactiveError.value = cause instanceof Error ? cause.message : '已停用字段加载失败，请重试'
  } finally {
    if (request === inactiveRequest) inactiveLoading.value = false
  }
}
watch(
  () => props.revision,
  () => {
    inactiveLocal.value = []
    inactiveSaved.value = []
    inactiveOpen.value = false
    void loadInactiveFields()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  inactiveRequest++
})
async function restoreInactive(candidate: InactiveFieldCandidate) {
  if (props.readOnly || inactiveLoading.value || !candidate.field.id) return
  const issue = fieldRestoreError(candidate, props.modelValue)
  if (issue) {
    inactiveError.value = issue
    return
  }
  const revision = props.revision
  if (candidate.persisted && props.reviewOperation && !(await props.reviewOperation(candidate.field, 'restore_field')))
    return
  if (revision !== props.revision || props.readOnly || fieldRestoreError(candidate, props.modelValue)) return
  const value = restoredFieldValue(candidate)
  emit(
    'update:modelValue',
    [...props.modelValue, value.field].sort((a, b) => a.sort - b.sort)
  )
  emit('update:options', { ...props.options, [value.field.key]: value.options })
  emit('restore', candidate.field.id, candidate.persisted)
  inactiveError.value = ''
  message.success(`“${value.field.name}”已恢复到当前草稿，请保存并发布`)
}
function basicsError(item: ObjectField) {
  return (
    inactiveFieldCodeError(item, inactiveCandidates.value) || fieldBasicsError(item, props.modelValue, props.adopted)
  )
}
defineExpose({
  reservedCodeError: () =>
    props.modelValue.map(field => inactiveFieldCodeError(field, inactiveCandidates.value)).find(Boolean),
  openField: (item: ObjectField, proposedType?: FieldType) => show(item, proposedType)
})
const open = ref(false)
const error = ref('')
const switching = ref(false)
const key = ref<string | null>(null)
const field = reactive(newField(0))
const option = reactive(defaultFieldOptions())
const sourceField = ref<ObjectField>()
const sourceOptions = ref<FieldOptions>()
const originalRelation = ref<ObjectRelation>()
const relationTargetId = ref<string | null>(null)
const switchPreview = ref<FieldSwitchPreview>()
const switchPreviewError = ref('')
const switchPreviewLoading = ref(false)
const clearChoice = ref(false)
const previewRows = ref<import('@/types/nocode/data-center').FieldConversionRows>()
const previewRowsLoading = ref(false)
const previewRowsError = ref('')
const previewRefresh = ref(0)
let previewGeneration = 0
let previewTimer: ReturnType<typeof setTimeout> | undefined
const switchReviewOpen = ref(false)
const reviewedFingerprint = ref('')
let reviewSnapshot: FieldSwitchSnapshot | undefined
let acceptedSnapshot: FieldSwitchSnapshot | undefined
let applyAfterReview = false
const candidateFingerprint = computed(() => fieldSwitchFingerprint(field, option, relationTargetId.value))
const switchReviewed = computed(() => reviewedFingerprint.value === candidateFingerprint.value)

function beginSwitchReview(snapshot?: FieldSwitchSnapshot, apply = false) {
  reviewSnapshot = snapshot ?? acceptedSnapshot
  applyAfterReview = apply
  switchReviewOpen.value = true
  previewRefresh.value++
}
function cancelSwitchReview() {
  switchReviewOpen.value = false
  applyAfterReview = false
  if (reviewSnapshot) {
    restoreFieldSwitch(field, option, reviewSnapshot)
    relationTargetId.value = reviewSnapshot.targetObjectId
  }
}

const patternError = computed(() => fieldPatternError(field, option))
const numericIssue = computed(() => fieldNumericConstraintError(field, option))
const patternHelp = computed(() => {
  if (!option.pattern?.trim()) return '例如 ^[A-Z0-9]+$；全角括号会被当作普通文字。'
  if (/[（）［］｛｝【】]/u.test(option.pattern))
    return '未发现语法错误。全角括号会被当作普通文字；需要分组、字符集或数量范围时，请使用半角 ()、[]、{}。'
  return '未发现语法错误。这里只检查语法，不判断是否符合预期格式；保存对象草稿时还会由服务端校验。'
})
watch(
  () => option.pattern,
  () => {
    if (error.value.startsWith('正则表达式') || error.value.startsWith('正则校验'))
      error.value = patternError.value || ''
  }
)
const editingOptions = computed({
  get: () => option,
  set: (value: FieldOptions) => {
    if (props.readOnly || value === option) return
    const next = copyFieldOptions(value)
    for (const name of Object.keys(option)) delete (option as unknown as Record<string, unknown>)[name]
    Object.assign(option, next)
  }
})
const editSession = ref(0)
const previewValue = ref<string | null>(null)
const previewMode = ref('form')
const defaultUpload = ref({ pending: false, failed: false })
// 监听函数捕获具体字段和开窗代次，旧文件控件的异步状态不能污染下一字段。
const defaultUploadListener = computed(() => {
  const session = editSession.value,
    fieldKey = field.key,
    fieldType = field.type
  return (status: { pending: boolean; failed: boolean }) => {
    if (open.value && session === editSession.value && fieldKey === field.key && fieldType === field.type)
      defaultUpload.value = status
  }
})
watch(
  () => field.type,
  () => {
    defaultUpload.value = { pending: false, failed: false }
  },
  { flush: 'sync' }
)
watch(
  () => [field.type, option.defaultValue, editSession.value],
  () => {
    previewValue.value = option.defaultValue ?? null
  }
)
const expressionEditor = ref<{ validate: () => string }>()
const calculationEditor = ref<{ validate: () => string; openEditor: () => void }>()
const valueSourceSection = ref<{ validate: () => string }>()
const formulaSummary = computed(() => {
  const calculation = option.calculation
  return `${calculationDescription(calculation)} · ${calculationUpdateDescription(calculation)}`
})
function relationFor(item: ObjectField) {
  return relationForField(item, props.relations)
}
const rows = computed(() => relationFieldRows(props.modelValue, props.relations, props.options))
/**
 * 抽屉里的字段此刻承载的对象关系。
 * 引用列不一定是「对象引用」类型：关系生成的列、映射到关系上的已有列按目标主键落成整数 / 文本 / UUID 列，
 * 类型没动就仍由关系承载（后端按关系的引用列认，不看字段类型）。只有在抽屉里把字段改成了别的类型
 * （显式单值引用解除引用）时，关系才不再适用。
 */
const currentRelation = computed(() =>
  field.type === NC.FieldType.REFERENCE || isRelationField(field) || field.type === sourceField.value?.type
    ? relationFor(field)
    : undefined
)
const protectedField = computed(
  () => !!currentRelation.value || isRelationField(field) || fieldStructureLocked(field, option, props.adopted)
)
const explicitSingleRelation = (item: ObjectField) => {
  const relation = relationFor(item)
  return (
    !!item.id &&
    item.type === NC.FieldType.REFERENCE &&
    !!relation &&
    !props.options[item.key]?.generated &&
    (relation.kind === NC.RelationType.REFERENCE || relation.kind === NC.RelationType.ONE_TO_ONE)
  )
}
const ordinarySwitchTypes = new Set<string>([
  NC.FieldType.TEXT,
  NC.FieldType.TEXTAREA,
  NC.FieldType.RICH_TEXT,
  NC.FieldType.URL,
  NC.FieldType.INTEGER,
  NC.FieldType.DECIMAL,
  NC.FieldType.MONEY,
  NC.FieldType.PERCENT,
  NC.FieldType.BOOLEAN,
  NC.FieldType.DATE,
  NC.FieldType.DATETIME,
  NC.FieldType.TIME,
  NC.FieldType.UUID,
  NC.FieldType.SELECT,
  NC.FieldType.MULTI_SELECT
])
function switchTypes(item: ObjectField) {
  if (!item.id) return fieldTypes
  if (!ordinarySwitchTypes.has(item.type) && !explicitSingleRelation(item))
    return fieldTypes.filter(type => type.value === item.type)
  return [
    ...fieldTypes.filter(type => ordinarySwitchTypes.has(type.value)),
    { value: NC.FieldType.REFERENCE, label: '单选（对象引用）' }
  ]
}
const types = computed(() => fieldTypes.filter(t => !props.detail || t.value !== NC.FieldType.SUMMARY))
const fieldSwitchTypes = computed(() => (sourceField.value ? switchTypes(sourceField.value) : types.value))
// 关系生成列可使用 INTEGER/TEXT 存储；只有真正解除引用时，比较目标才应为空。
const configurationTargetId = computed(() =>
  field.type === NC.FieldType.REFERENCE
    ? relationTargetId.value
    : sourceField.value?.type === field.type
      ? (originalRelation.value?.targetObjectId ?? null)
      : null
)
function differsFromPublished(item: ObjectField, options: FieldOptions, targetId: string | null): boolean {
  const baseline = item.id ? props.publishedBaselines?.[item.id] : undefined
  if (!baseline) return false
  return (
    baseline.type !== item.type ||
    baseline.length !== (item.length ?? null) ||
    baseline.precision !== (item.precision ?? null) ||
    baseline.scale !== (item.scale ?? null) ||
    baseline.required !== !!item.required ||
    baseline.unique !== !!item.unique ||
    baseline.minimum !== (options.minimum ?? null) ||
    baseline.maximum !== (options.maximum ?? null) ||
    baseline.pattern !== (options.pattern ?? null) ||
    (baseline.defaultValue ?? null) !== (options.defaultValue ?? null) ||
    JSON.stringify(baseline.options ?? []) !== JSON.stringify(options.options ?? []) ||
    JSON.stringify(baseline.selection) !== JSON.stringify(options.selection ?? null) ||
    baseline.targetObjectId !== targetId
  )
}
const switchChanged = computed(() => {
  const original = sourceField.value
  const before = sourceOptions.value
  // 新增行的默认类型不是已保存配置，选型和补全参数不进入旧字段转换流程。
  if (!original?.id || !before) return false
  return (
    original.type !== field.type ||
    original.length !== field.length ||
    original.precision !== field.precision ||
    original.scale !== field.scale ||
    original.required !== field.required ||
    original.unique !== field.unique ||
    (before.minimum ?? null) !== (option.minimum ?? null) ||
    (before.maximum ?? null) !== (option.maximum ?? null) ||
    (before.pattern ?? null) !== (option.pattern ?? null) ||
    (before.defaultValue ?? null) !== (option.defaultValue ?? null) ||
    JSON.stringify(before.options ?? []) !== JSON.stringify(option.options ?? []) ||
    JSON.stringify(before.selection ?? null) !== JSON.stringify(option.selection ?? null) ||
    (field.type === NC.FieldType.REFERENCE &&
      (originalRelation.value?.targetObjectId ?? null) !== relationTargetId.value) ||
    differsFromPublished(field, option, configurationTargetId.value)
  )
})
const configurationChanges = computed(() =>
  sourceField.value && sourceOptions.value
    ? fieldConfigurationChanges(
        sourceField.value,
        sourceOptions.value,
        field,
        option,
        sourceField.value.id ? props.publishedBaselines?.[sourceField.value.id] : undefined,
        originalRelation.value?.targetObjectId,
        configurationTargetId.value
      )
    : []
)
const previewInput = computed(() =>
  JSON.stringify({
    open: open.value,
    changed: switchChanged.value,
    type: field.type,
    length: field.length,
    precision: field.precision,
    scale: field.scale,
    selection: option.selection,
    options: option.options,
    required: field.required,
    unique: field.unique,
    minimum: option.minimum,
    maximum: option.maximum,
    pattern: option.pattern,
    defaultValue: option.defaultValue,
    targetObjectId: relationTargetId.value,
    refresh: previewRefresh.value
  })
)
const pendingChange = (item: ObjectField) =>
  differsFromPublished(
    item,
    props.options[item.key] ?? defaultFieldOptions(),
    relationFor(item)?.targetObjectId ?? null
  )
const targetConfigurationError = computed(
  () =>
    basicsError(field) ||
    (!props.adopted && fieldConfigurationError(field, option)) ||
    (field.type === NC.FieldType.REFERENCE && !relationTargetId.value ? '请选择关联的目标业务数据对象' : null)
)
const canApplySwitch = computed(() => {
  if (props.readOnly) return false
  if ((!switchChanged.value && !switchReviewOpen.value) || !props.previewSwitch) return true
  if (targetConfigurationError.value) return false
  const preview = switchPreview.value
  return (
    !switchPreviewLoading.value &&
    !!preview &&
    !switchPreviewError.value &&
    preview.decision !== 'BLOCKED' &&
    preview.deploymentState !== 'MISSING_COLUMN' &&
    !preview.impacts.some(impact => impact.blocking) &&
    (preview.decision !== 'CLEAR_COLUMN' || (clearChoice.value && !!props.canClearColumn))
  )
})
function confirmSwitchReview() {
  if (props.readOnly || !canApplySwitch.value) return
  reviewedFingerprint.value = candidateFingerprint.value
  acceptedSnapshot = fieldSwitchSnapshot(field, option, relationTargetId.value)
  switchReviewOpen.value = false
  if (applyAfterReview) save()
  applyAfterReview = false
}
async function loadPreviewRows(pageNo = 1, request = previewGeneration) {
  if (!props.previewRows || !props.canViewConversionRows || !sourceField.value || previewRowsLoading.value) return
  previewRowsLoading.value = true
  previewRowsError.value = ''
  try {
    const result = await props.previewRows(
      { ...sourceField.value },
      { ...field },
      copyFieldOptions(option),
      relationTargetId.value,
      pageNo
    )
    if (request === previewGeneration) previewRows.value = result
  } catch (cause) {
    if (request === previewGeneration)
      previewRowsError.value = cause instanceof Error ? cause.message : '记录明细获取失败，请重试'
  } finally {
    if (request === previewGeneration) previewRowsLoading.value = false
  }
}
watch(previewInput, () => {
  previewGeneration++
  if (previewTimer) clearTimeout(previewTimer)
  switchPreview.value = undefined
  switchPreviewError.value = ''
  clearChoice.value = false
  switchPreviewLoading.value = false
  previewRows.value = undefined
  previewRowsError.value = ''
  previewRowsLoading.value = false
  const runPreview = props.previewSwitch
  if (
    props.readOnly ||
    !open.value ||
    (!switchChanged.value && !switchReviewOpen.value) ||
    !runPreview ||
    !sourceField.value?.id
  )
    return
  if (field.type === NC.FieldType.REFERENCE && !relationTargetId.value) return
  const request = previewGeneration
  const source = { ...sourceField.value }
  const target = { ...field }
  const options = copyFieldOptions(option)
  const targetId = relationTargetId.value
  switchPreviewLoading.value = true
  previewTimer = setTimeout(async () => {
    try {
      const preview = await runPreview(source, target, options, targetId)
      if (request === previewGeneration) {
        switchPreview.value = preview
        if ((preview.failedRows ?? 0) > 0 || ((preview.valueRows ?? 0) > 0 && preview.decision === 'CLEAR_COLUMN'))
          void loadPreviewRows(1, request)
      }
    } catch (cause) {
      if (request === previewGeneration)
        switchPreviewError.value = cause instanceof Error ? cause.message : '变更影响检查失败，请重试'
    } finally {
      if (request === previewGeneration) switchPreviewLoading.value = false
    }
  }, 240)
})
onBeforeUnmount(() => {
  previewGeneration++
  if (previewTimer) clearTimeout(previewTimer)
})
const grid = ref<HTMLElement>()
const optionsType = computed(
  () =>
    !currentRelation.value &&
    (
      [NC.FieldType.SELECT, NC.FieldType.MULTI_SELECT, NC.FieldType.REGION, NC.FieldType.CASCADE] as readonly string[]
    ).includes(field.type) &&
    (!option.selection || option.selection.kind === 'LOCAL_OPTIONS')
)
const calculated = computed(() =>
  ([NC.FieldType.FORMULA, NC.FieldType.SUMMARY] as readonly string[]).includes(field.type)
)
const autoNumberDisabledReason = computed(() => {
  if (props.adopted) return '已有表纳管字段沿用原表的编号方式。'
  if (!field.id || option.autoNumber) return ''
  if (props.legacyAutoNumberIds === null) return '尚未取得已发布编号定义，请稍后重试或刷新页面。'
  if (props.legacyAutoNumberIds?.includes(field.id))
    return '此字段已按整数自增方式发布。为保留已有数据和应用，请新增自动编号字段配置格式规则。'
  return ''
})
const numeric = computed(() =>
  (
    [NC.FieldType.INTEGER, NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]
  ).includes(field.type)
)
watch(
  () => [field.type, field.precision, field.scale, option.defaultValue, option.minimum, option.maximum],
  () => {
    if (numeric.value && error.value) error.value = basicsError(field) || fieldConfigurationError(field, option) || ''
  }
)
const columns = [
  { title: '字段名称', key: 'name', dataIndex: 'name', width: 170 },
  { title: '字段编码', key: 'code', dataIndex: 'code', width: 180 },
  { title: '类型', key: 'type', width: 190 },
  { title: '长度 / 精度', key: 'size', width: 170 },
  { title: '属性', key: 'properties', width: 220 },
  { title: '操作', key: 'actions', width: 260, fixed: 'right' as const }
]
function locked(item: ObjectField) {
  return (
    !!relationFor(item) || isRelationField(item) || fieldStructureLocked(item, props.options[item.key], props.adopted)
  )
}
function managedByRelation(item: ObjectField) {
  return relationManagedField(item, props.options, props.relations)
}
function protectionReason(item: ObjectField) {
  const options = props.options[item.key]
  if (props.adopted) return '此表由外部系统维护结构。请在原表完成结构调整，再通过表绑定检查和同步；这里保留原列映射。'
  if (options?.primaryKey)
    return '主键用于识别记录和关联关系，不能在字段配置中替换类型或约束。可新增普通字段承载新的业务含义。'
  if (options?.generated) return '此字段由系统或对象关系自动维护，请从对应关系配置入口调整。'
  if (fieldStructureLocked(item, options)) return '此字段承担系统审计或主从关联职责，不能作为普通业务字段调整结构。'
  if (item.id && !ordinarySwitchTypes.has(item.type) && !explicitSingleRelation(item))
    return '此类字段使用专用的生成或存储规则，当前不支持与普通字段互转。可在配置中调整允许修改的规则，或新增目标类型字段。'
  return ''
}
function updateConstraint(item: ObjectField, patch: Pick<Partial<ObjectField>, 'required' | 'unique'>) {
  if (props.readOnly || locked(item) || item.type === NC.FieldType.SUMMARY) return
  if (props.previewSwitch && item.id) {
    show(item)
    Object.assign(field, patch)
    return
  }
  update(item, patch)
}
/** 关系生成的引用列也在抽屉里配置显示名字段、引用筛选与数据联动；关系本体仍从抽屉内按钮进入。 */
function ruleConfigurable(item: ObjectField) {
  return !isRelationField(item) && valueSourceModesFor(item.type, relationFor(item)).block !== 'NONE'
}
/**
 * 换类型后按新类型清理本字段规则：不再适用的档位与取整方式一并清掉（例如非金额字段不保留取整方式）。
 * 原地转成对象引用时关系要到确定后才建立，先按单值关系处理，保留仍然适用的数据联动。
 */
function rulesAfterSwitch(type: string, relation: { kind: string } | null | undefined, target: FieldOptions) {
  const effective = relation ?? (type === NC.FieldType.REFERENCE ? { kind: NC.RelationType.REFERENCE } : undefined)
  return cleanFieldRules(type, effective, target).rules
}
function rowError(item: ObjectField) {
  if (isRelationField(item)) return null
  return (
    fieldBasicsError(item, props.modelValue, props.adopted) ||
    (!props.adopted && fieldConfigurationError(item, props.options[item.key]))
  )
}
function update(item: ObjectField, patch: Partial<ObjectField>) {
  if (props.readOnly || managedByRelation(item)) return
  if (locked(item) && Object.keys(patch).some(name => name !== 'name')) return
  emit(
    'update:modelValue',
    props.modelValue.map(f => (f.key === item.key ? { ...f, ...patch } : f))
  )
}
async function updateType(item: ObjectField, value: unknown) {
  if (
    switching.value ||
    props.readOnly ||
    locked(item) ||
    typeof value !== 'string' ||
    item.type === value ||
    !switchTypes(item).some(t => t.value === value)
  )
    return
  if (props.previewSwitch) {
    show(item, value as FieldType)
    return
  }
  const next = { ...item }
  const nextOptions = copyFieldOptions(props.options[item.key])
  changeFieldType(next, nextOptions, value as FieldType)
  nextOptions.rules = rulesAfterSwitch(next.type, relationFor(item), nextOptions)
  switching.value = true
  try {
    if (item.id && props.reviewSwitch && !(await props.reviewSwitch(item, next, nextOptions))) return
    if (!props.modelValue.some(f => f.key === item.key && f.type === item.type)) return
  } finally {
    switching.value = false
  }
  emit(
    'update:modelValue',
    props.modelValue.map(f => (f.key === item.key ? next : f))
  )
  emit('update:options', { ...props.options, [item.key]: nextOptions })
}
async function add() {
  if (props.readOnly || props.adopted) return
  const item = newField(props.modelValue.length)
  emit('update:modelValue', [...props.modelValue, item])
  emit('update:options', { ...props.options, [item.key]: defaultFieldOptions() })
  await nextTick()
  const input = grid.value?.querySelector<HTMLInputElement>(`[data-field-key="${item.key}"] input`)
  const body = input?.closest<HTMLElement>('.ant-table-body')
  // 只定位设计区内的新增行，避免浏览器聚焦时连带滚动整个页面。
  input?.focus({ preventScroll: true })
  body?.scrollTo({ top: body.scrollHeight, left: 0 })
  const pane = grid.value?.closest<HTMLElement>('.object-tab-scroll')
  if (pane && grid.value) {
    const hiddenBottom = grid.value.getBoundingClientRect().bottom - pane.getBoundingClientRect().bottom
    if (hiddenBottom > 0) pane.scrollTop += hiddenBottom
  }
}
/** 未保存的单值关系只是占位行：引用字段要保存后才生成，显示名字段与引用筛选须保存后再配。 */
function unsavedReference(item: ObjectField) {
  const relation = relationFor(item)
  return isRelationField(item) && !!relation && !relation.id && relation.kind !== NC.RelationType.MANY_TO_MANY
    ? relation
    : null
}
function show(item?: ObjectField, proposedType?: FieldType) {
  if (item && managedByRelation(item) && !props.readOnly && !explicitSingleRelation(item) && !ruleConfigurable(item)) {
    const pending = unsavedReference(item)
    if (pending) {
      Modal.confirm({
        title: '请先保存，再配置显示名字段和引用筛选',
        content: '对象关系保存后才会生成引用字段。点「保存」保存对象草稿（不发布），完成后直接打开该字段的配置。',
        okText: '保存',
        cancelText: '取消',
        onOk: () => emit('configure-relation', pending.code)
      })
      return
    }
    emit('relation', item)
    return
  }
  key.value = item?.key ?? null
  sourceField.value = item ? { ...item } : undefined
  sourceOptions.value = item ? copyFieldOptions(props.options[item.key]) : undefined
  originalRelation.value = item ? relationFor(item) : undefined
  relationTargetId.value = originalRelation.value?.targetObjectId ?? null
  Object.assign(field, item ? { ...item } : newField(props.modelValue.length))
  // 删除上一次编辑遗留的可选属性，再装载本字段配置。
  for (const name of Object.keys(option)) delete (option as unknown as Record<string, unknown>)[name]
  Object.assign(option, defaultFieldOptions(), JSON.parse(JSON.stringify(item ? (props.options[item.key] ?? {}) : {})))
  if (!props.adopted) clearMultilinePatterns([field], { [field.key]: option })
  // 同一字段再次打开也重建公式编辑器，恢复操作以本次打开的配置为准。
  editSession.value++
  defaultUpload.value = { pending: false, failed: false }
  error.value = ''
  switchReviewOpen.value = false
  reviewedFingerprint.value = ''
  acceptedSnapshot = fieldSwitchSnapshot(field, option, relationTargetId.value)
  reviewSnapshot = undefined
  open.value = true
  if (proposedType && proposedType !== field.type) void changeType(proposedType)
  if (item && explicitSingleRelation(item)) void props.loadRelationTargets?.()
}
function closeField() {
  open.value = false
  switchReviewOpen.value = false
  previewGeneration++
  if (previewTimer) clearTimeout(previewTimer)
  editSession.value++
  defaultUpload.value = { pending: false, failed: false }
}
async function changeType(value: unknown) {
  if (typeof value !== 'string' || !fieldSwitchTypes.value.some(t => t.value === value) || field.type === value) return
  if (
    switching.value ||
    props.readOnly ||
    (protectedField.value && !explicitSingleRelation(sourceField.value ?? field))
  )
    return
  if (props.previewSwitch && sourceField.value?.id) {
    const snapshot = fieldSwitchSnapshot(field, option, relationTargetId.value)
    changeFieldType(field, option, value as FieldType)
    option.rules = rulesAfterSwitch(field.type, currentRelation.value, option)
    if (sourceField.value?.type === NC.FieldType.REFERENCE && value !== NC.FieldType.REFERENCE) option.nativeType = null
    if (value === NC.FieldType.REFERENCE) void props.loadRelationTargets?.()
    beginSwitchReview(snapshot)
    return
  }
  const session = editSession.value
  const next = { ...field }
  const nextOptions = copyFieldOptions(option)
  changeFieldType(next, nextOptions, value as FieldType)
  switching.value = true
  try {
    if (field.id && props.reviewSwitch && !(await props.reviewSwitch({ ...field }, next, nextOptions))) return
    if (!open.value || session !== editSession.value) return
  } finally {
    switching.value = false
  }
  changeFieldType(field, option, value as FieldType)
  option.rules = rulesAfterSwitch(field.type, currentRelation.value, option)
}
async function reviewSelectionSwitch(next: ObjectField, nextOptions: FieldOptions): Promise<boolean> {
  if (!field.id) return !props.readOnly
  if (props.previewSwitch) {
    const snapshot = fieldSwitchSnapshot(field, option, relationTargetId.value)
    Object.assign(field, next)
    Object.assign(option, copyFieldOptions(nextOptions))
    beginSwitchReview(snapshot)
    return false
  }
  if (switching.value) return false
  const session = editSession.value
  switching.value = true
  try {
    return (
      (!props.reviewSwitch || (await props.reviewSwitch({ ...field }, next, nextOptions))) &&
      open.value &&
      session === editSession.value
    )
  } finally {
    switching.value = false
  }
}
function applySelectionField(next: ObjectField) {
  Object.assign(field, next)
}
function changeName(name: string) {
  if (props.readOnly) return
  Object.assign(field, fieldNamePatch(field, name, option, props.adopted))
}
function updateAutoNumber(rule: AutoNumberOptions) {
  if (props.readOnly || props.adopted || autoNumberDisabledReason.value) return
  option.autoNumber = rule
  // 提交失败后继续修改规则时同步刷新错误，避免已修正的周期仍显示旧提示。
  if (error.value) error.value = basicsError(field) || fieldConfigurationError(field, option) || ''
}
function save() {
  if (props.readOnly) return
  if (!props.adopted) clearMultilinePatterns([field], { [field.key]: option })
  // 已保存的单值引用字段在抽屉里改了类型或目标对象：走转换复核与关系应用流程，不按「只保存规则」处理。
  const converting =
    !!sourceField.value &&
    explicitSingleRelation(sourceField.value) &&
    (sourceField.value.type !== field.type ||
      (originalRelation.value?.targetObjectId ?? null) !== relationTargetId.value)
  if (managedByRelation(field) && !converting) {
    // 关系承载的引用列只在抽屉里维护规则；名称、必填等仍以对象关系为准，不回写字段本身。
    error.value = valueSourceSection.value?.validate() || fieldRulesError(option) || ''
    if (error.value) return
    const rules = cleanFieldRules(field.type, currentRelation.value, option).rules
    emit('update:options', {
      ...props.options,
      [field.key]: {
        ...copyFieldOptions(props.options[field.key]),
        // 数据分类是这类字段在抽屉里唯一还能改的非规则项，随规则一起写回，不悄悄丢掉。
        classification: option.classification,
        rules: rules ? JSON.parse(JSON.stringify(rules)) : null
      }
    })
    closeField()
    return
  }
  error.value =
    basicsError(field) ||
    (!props.adopted && calculationEditor.value?.validate()) ||
    (!props.adopted && expressionEditor.value?.validate()) ||
    valueSourceSection.value?.validate() ||
    fieldRulesError(option) ||
    (!props.adopted && fieldConfigurationError(field, option)) ||
    (!props.adopted && fieldDefaultError(field, option)) ||
    ([NC.FieldType.IMAGE, NC.FieldType.ATTACHMENT].some(type => type === field.type) && defaultUpload.value.pending
      ? '默认文件正在上传，请稍候再保存'
      : [NC.FieldType.IMAGE, NC.FieldType.ATTACHMENT].some(type => type === field.type) && defaultUpload.value.failed
        ? '默认文件上传失败，请重试或移除失败项'
        : '') ||
    ''
  if (error.value) return
  if (props.previewSwitch && switchChanged.value) {
    if (!switchReviewed.value) {
      beginSwitchReview(undefined, true)
      return
    }
    if (field.type === NC.FieldType.REFERENCE && !relationTargetId.value) {
      error.value = '请选择关联的目标业务数据对象'
      return
    }
    if (switchPreviewLoading.value || !switchPreview.value) {
      error.value = switchPreviewError.value || '变更影响尚未检查完成，请稍候或重新检查'
      return
    }
    if (
      switchPreview.value.decision === 'BLOCKED' ||
      switchPreview.value.deploymentState === 'MISSING_COLUMN' ||
      switchPreview.value.impacts.some(impact => impact.blocking)
    ) {
      error.value = '存在尚未处理的转换影响，请打开变更影响弹窗处理后重新检查'
      return
    }
    if (switchPreview.value.decision === 'CLEAR_COLUMN' && (!clearChoice.value || !props.canClearColumn)) {
      error.value = '请先查看记录并选择清空本列后转换'
      return
    }
  }
  for (const key of ['minimum', 'maximum', 'pattern', 'expression'] as const) {
    if (!option[key]?.trim()) option[key] = null
  }
  if (
    sourceField.value &&
    props.applyConversion &&
    (sourceField.value.type === NC.FieldType.REFERENCE || field.type === NC.FieldType.REFERENCE)
  ) {
    // 规则随字段一起转换：按新类型清理后原样带过去，不在转换时丢掉。
    const converted = copyFieldOptions(option)
    converted.rules = rulesAfterSwitch(field.type, currentRelation.value, converted) ?? null
    error.value = props.applyConversion(sourceField.value, { ...field }, converted, relationTargetId.value) ?? ''
    if (error.value) return
    closeField()
    return
  }
  const fields = props.modelValue.map(f => ({ ...f }))
  const position = fields.findIndex(f => f.key === key.value)
  if (position < 0) fields.push({ ...field })
  else fields[position] = { ...field }
  emit('update:modelValue', fields)
  // 同一时刻只落一档：选项类不保存默认值，取整方式只随数据联动或公式默认值保存。
  const cleaned = cleanFieldRules(field.type, currentRelation.value, option)
  emit('update:options', { ...props.options, [field.key]: JSON.parse(JSON.stringify(cleaned)) })
  closeField()
}
// 从抽屉进入对象关系：抽屉保持打开（取消即回到原状态）；确定后由对象编辑器保存草稿并经 focusRelation 回到抽屉。
function configureRelation() {
  if (props.readOnly) return
  error.value = basicsError(field) || fieldRelationConversionError(field) || ''
  if (error.value) return
  if (props.previewSwitch && field.type === NC.FieldType.SELECT && field.id) {
    // 已保存单选原地转为对象引用：在抽屉内选目标对象并复核转换影响，确定时由对象编辑器建立关系。
    const snapshot = fieldSwitchSnapshot(field, option, relationTargetId.value)
    changeFieldType(field, option, NC.FieldType.REFERENCE)
    option.rules = rulesAfterSwitch(field.type, currentRelation.value, option)
    relationTargetId.value = null
    void props.loadRelationTargets?.()
    beginSwitchReview(snapshot)
    return
  }
  emit('relation', { ...field }, 'drawer')
}
function addSeparateRelation() {
  if (props.readOnly) return
  closeField()
  emit('newRelation')
}
function editRelation() {
  if (props.readOnly || !currentRelation.value) return
  emit('relation', { ...field }, 'drawer')
}
let focusedSeq = 0
watch(
  () => [props.focusRelation, props.relations] as const,
  ([request]) => {
    if (!request || request.seq === focusedSeq) return
    const relation = props.relations?.find(item => item.code === request.code)
    if (!relation) return
    focusedSeq = request.seq
    const row = rows.value.find(item => relationFor(item) === relation && ruleConfigurable(item))
    // 多选关系没有引用列、没有可配的规则：关系保存即完成，关掉抽屉。
    if (row) show(row)
    else closeField()
    emit('relation-focused')
  },
  { immediate: true }
)
async function remove(item: ObjectField) {
  if (props.readOnly || locked(item)) return
  if (!item.id) {
    discard(item)
    return
  }
  const revision = props.revision
  const apply = () => {
    if (props.readOnly || revision !== props.revision || !props.modelValue.some(field => field.id === item.id)) return
    const persisted = inactiveSaved.value.some(candidate => candidate.field.id === item.id)
    inactiveLocal.value = [
      ...inactiveLocal.value.filter(candidate => candidate.field.id !== item.id),
      {
        field: { ...item },
        options: copyFieldOptions(props.options[item.key]),
        persisted,
        restorable: true,
        blockedReason: null
      }
    ]
    emit(
      'update:modelValue',
      props.modelValue.filter(f => f.key !== item.key)
    )
    const next = { ...props.options }
    delete next[item.key]
    emit('update:options', next)
    if (item.id) emit('remove', item.id)
  }
  // 撤回尚未保存的恢复时，服务端仍将此字段视为停用；只撤回本地草稿即可。
  const undoPendingRestore = inactiveSaved.value.some(candidate => candidate.field.id === item.id)
  if (props.reviewOperation && !undoPendingRestore) {
    if (await props.reviewOperation(item, 'disable_field')) apply()
    return
  }
  Modal.confirm({
    title: `停用字段“${item.name}”？`,
    content: undoPendingRestore
      ? '本次仅撤回当前草稿中尚未保存的恢复，原列和历史数据保持不变。'
      : '已发布字段的原列和历史数据会保留。保存时会检查标题、索引和其他引用。',
    onOk: apply
  })
}
function discard(item: ObjectField) {
  emit(
    'update:modelValue',
    props.modelValue.filter(f => f.key !== item.key)
  )
  const next = { ...props.options }
  delete next[item.key]
  emit('update:options', next)
}
function move(index: number, direction: number) {
  if (props.readOnly) return
  const target = index + direction
  if (target < 0 || target >= props.modelValue.length) return
  const fields = [...props.modelValue]
  const [item] = fields.splice(index, 1)
  fields.splice(target, 0, item)
  emit(
    'update:modelValue',
    fields.map((f, sort) => ({ ...f, sort }))
  )
}
</script>

<template>
  <div ref="grid" class="field-designer">
    <InactiveFieldsDrawer
      :open="inactiveOpen"
      :candidates="inactiveCandidates"
      :fields="modelValue"
      :read-only="readOnly"
      :loading="inactiveLoading"
      :error="inactiveError"
      @close="inactiveOpen = false"
      @reload="loadInactiveFields"
      @restore="restoreInactive"
    />
    <OsTablePage
      class="nocode-embedded-table"
      :columns="columns"
      :data-source="rows"
      row-key="key"
      :pagination="false"
      :scroll="{ x: 1210, y: '100%' }"
      size="middle"
      resizable
      show-column-settings
      :column-settings-key="detail ? 'nocode-object-detail-fields' : 'nocode-object-main-fields'"
    >
      <template #actions>
        <a-button v-if="loadInactive || inactiveCandidates.length" @click="inactiveOpen = true">
          已停用字段
          <span v-if="inactiveCandidates.length">（{{ inactiveCandidates.length }}）</span>
        </a-button>
        <a-button v-if="!readOnly && !adopted" @click="add" :disabled="modelValue.length >= 200">
          <PlusOutlined />
          新增字段
        </a-button>
      </template>
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'name'">
          <div v-if="!readOnly" :data-field-key="record.key">
            <a-input
              :value="record.name"
              :disabled="managedByRelation(record)"
              :maxlength="128"
              :aria-label="`第 ${index + 1} 行字段名称`"
              placeholder="字段名称"
              :status="!record.name.trim() ? 'error' : undefined"
              @update:value="update(record, fieldNamePatch(record, $event, options[record.key], adopted))"
            />
          </div>
          <template v-else>{{ record.name }}</template>
        </template>
        <template v-if="column.key === 'code'">
          <!-- 保持单元格根节点和输入实例稳定，提示出现时不卸载正在输入的控件。 -->
          <div v-if="!readOnly">
            <a-input
              :key="record.key"
              :value="record.code"
              :maxlength="63"
              :aria-label="`第 ${index + 1} 行字段编码`"
              placeholder="随名称生成，如 c_zclxbm"
              :disabled="locked(record)"
              :status="!isRelationField(record) && basicsError(record) && record.name.trim() ? 'error' : undefined"
              @update:value="update(record, { code: $event })"
            />
            <p v-show="inactiveFieldCodeError(record, inactiveCandidates)" class="field-hint" role="alert">
              {{ inactiveFieldCodeError(record, inactiveCandidates) }}
            </p>
          </div>
          <template v-else>{{ record.code }}</template>
        </template>
        <template v-if="column.key === 'type'">
          <div class="field-type-cell">
            <div class="field-type-main">
              <a-tag v-if="relationFor(record)" color="blue" class="field-type-tag">
                {{ relationFieldLabel(relationFor(record)!) }}
              </a-tag>
              <a-select
                v-else-if="!readOnly"
                :value="record.type"
                :options="switchTypes(record)"
                :disabled="locked(record)"
                :loading="switching"
                :aria-label="`第 ${index + 1} 行字段类型`"
                class="field-type-select"
                @change="updateType(record, $event)"
              />
              <span v-else>{{ fieldTypes.find(t => t.value === record.type)?.label ?? record.type }}</span>
              <a-tooltip v-if="!readOnly && protectionReason(record)" :title="protectionReason(record)">
                <InfoCircleOutlined class="field-protection-hint" tabindex="0" :aria-label="protectionReason(record)" />
              </a-tooltip>
            </div>
            <div v-if="!readOnly && pendingChange(record)" class="field-type-status">
              <a-tag color="orange" class="field-type-tag">待发布变更</a-tag>
              <a-button type="link" size="small" class="field-impact-link" @click="show(record)">查看影响</a-button>
            </div>
          </div>
        </template>
        <template v-if="column.key === 'size'">
          <template v-if="relationFor(record)">—</template>
          <template v-else-if="record.type === NC.FieldType.TEXT">
            <a-input-number
              v-if="!readOnly"
              :value="record.length"
              :min="1"
              :max="4000"
              :precision="0"
              :disabled="locked(record) || (!!record.id && !!previewSwitch)"
              :aria-label="`第 ${index + 1} 行文本长度`"
              class="field-length"
              @update:value="update(record, { length: $event })"
            />
            <template v-else>{{ record.length ?? '—' }}</template>
          </template>
          <div
            v-else-if="
              ([NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]).includes(
                record.type
              )
            "
            class="field-precision"
          >
            <template v-if="!readOnly">
              <a-input-number
                :value="record.precision"
                :min="1"
                :max="38"
                :precision="0"
                :disabled="locked(record) || (!!record.id && !!previewSwitch)"
                :aria-label="`第 ${index + 1} 行总位数`"
                placeholder="总位数"
                @update:value="update(record, { precision: $event })"
              />
              <span>/</span>
              <a-input-number
                :value="record.scale"
                :min="0"
                :max="record.precision ?? 38"
                :precision="0"
                :disabled="locked(record) || (!!record.id && !!previewSwitch)"
                :aria-label="`第 ${index + 1} 行小数位数`"
                placeholder="小数位"
                @update:value="update(record, { scale: $event })"
              />
            </template>
            <template v-else>{{ record.precision }} / {{ record.scale }}</template>
          </div>
          <template v-else>—</template>
        </template>
        <template v-if="column.key === 'properties'">
          <a-space v-if="!readOnly" size="small">
            <a-checkbox
              :checked="record.required"
              :disabled="locked(record) || record.type === NC.FieldType.SUMMARY"
              :aria-label="`第 ${index + 1} 行必填`"
              @update:checked="updateConstraint(record, { required: $event })"
            >
              必填
            </a-checkbox>
            <a-checkbox
              :checked="record.unique"
              :disabled="locked(record) || record.type === NC.FieldType.SUMMARY"
              :aria-label="`第 ${index + 1} 行唯一`"
              @update:checked="updateConstraint(record, { unique: $event })"
            >
              唯一
            </a-checkbox>
          </a-space>
          <template v-else>
            <a-tag v-if="record.required">必填</a-tag>
            <a-tag v-if="record.unique">唯一</a-tag>
          </template>
          <a-tag v-if="options[record.key]?.generated" color="blue">
            {{ relationFor(record) ? '关系生成' : '自动维护' }}
          </a-tag>
          <a-tag v-if="options[record.key]?.primaryKey" color="purple">主键</a-tag>
          <a-tag v-for="tag in fieldRuleTags(options[record.key])" :key="tag" color="cyan">{{ tag }}</a-tag>
          <a-tag
            v-if="
              options[record.key]?.classification &&
              options[record.key]?.classification !== NC.DataClassification.NORMAL
            "
            color="orange"
          >
            受限预览
          </a-tag>
          <div v-if="!readOnly && rowError(record)" class="field-row-error" role="status">{{ rowError(record) }}</div>
        </template>
        <template v-if="column.key === 'actions'">
          <div class="nocode-table-actions">
            <a-button type="link" @click="show(record)">
              <EyeOutlined v-if="readOnly" />
              <EditOutlined v-else />
              {{ readOnly ? '查看' : '配置' }}
            </a-button>
            <template v-if="!readOnly">
              <a-button
                type="text"
                size="small"
                aria-label="字段上移"
                :disabled="isRelationField(record) || index === 0"
                @click="move(index, -1)"
              >
                <UpOutlined />
              </a-button>
              <a-button
                type="text"
                size="small"
                aria-label="字段下移"
                :disabled="isRelationField(record) || index === modelValue.length - 1"
                @click="move(index, 1)"
              >
                <DownOutlined />
              </a-button>
              <a-button v-if="!locked(record)" type="link" danger @click="remove(record)">
                <StopOutlined v-if="record.id" />
                <DeleteOutlined v-else />
                {{ record.id ? '停用' : '移除' }}
              </a-button>
            </template>
          </div>
        </template>
      </template>
    </OsTablePage>
    <OsModalForm
      :open="open"
      :title="key ? '字段配置' : '新增字段'"
      :width="920"
      :show-footer="!readOnly"
      @ok="save"
      @cancel="closeField"
      display-mode="drawer"
      :allow-switch-display="false"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      :disabled="readOnly"
    >
      <template #footer>
        <a-space>
          <a-button @click="closeField">取消</a-button>
          <a-button type="primary" @click="save">
            {{ switchChanged ? (switchReviewed ? '应用到草稿' : '检查并应用到草稿') : '确定' }}
          </a-button>
        </a-space>
      </template>
      <template #formItems>
        <a-alert v-if="error" type="error" show-icon :message="error" class="field-note" />
        <a-alert
          v-else-if="fieldRulesError(option)"
          type="warning"
          show-icon
          :message="fieldRulesError(option)!"
          class="field-note"
        />

        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="字段名称" required>
              <a-input
                :value="field.name"
                :maxlength="128"
                :disabled="managedByRelation(field)"
                @update:value="changeName"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="字段编码" required>
              <a-input
                v-model:value="field.code"
                :disabled="protectedField || !!originalRelation"
                :maxlength="63"
                placeholder="随名称生成，如 c_zclxbm"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <p class="field-note field-hint">
          数据分类影响数据中心的数据表预览遮蔽及计算依赖检查；业务表单和列表按字段权限显示，不等同于手机号等部分脱敏。
        </p>
        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="字段类型">
              <a-select
                :value="field.type"
                :options="fieldSwitchTypes"
                :disabled="(protectedField && !explicitSingleRelation(sourceField ?? field)) || switching"
                :loading="switching"
                @change="changeType"
              />
              <p v-if="originalRelation && explicitSingleRelation(sourceField ?? field)" class="field-hint">
                选择其他类型会解除当前单值对象关系；弹窗将展示实际旧值与依赖影响。
              </p>
              <p v-if="sourceField && protectionReason(sourceField)" class="field-hint">
                {{ protectionReason(sourceField) }}
              </p>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="数据分类">
              <a-select
                v-model:value="option.classification"
                :options="[
                  { value: NC.DataClassification.NORMAL, label: '普通 · 数据表预览可见原值' },
                  { value: NC.DataClassification.INTERNAL, label: '内部 · 数据表预览隐藏值' },
                  { value: NC.DataClassification.SENSITIVE, label: '敏感 · 数据表预览隐藏值' },
                  { value: NC.DataClassification.SECRET, label: '机密 · 数据表预览隐藏值' }
                ]"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item v-if="field.type === NC.FieldType.REFERENCE" label="从哪份资料选择" required>
          <a-select
            v-model:value="relationTargetId"
            show-search
            option-filter-prop="label"
            :options="relationTargets || []"
            placeholder="请选择已发布的业务数据对象"
            @search="loadRelationTargets?.(String($event))"
          />
          <p v-if="originalRelation" class="field-hint">
            当前关系：{{ originalRelation.name }}。关系类型、删除策略可在下方进入对象关系配置。
          </p>
        </a-form-item>

        <a-alert
          v-if="currentRelation"
          type="info"
          show-icon
          :message="relationFieldLabel(currentRelation)"
          description="候选来自关联对象，显示记录名称。字段类型和目标对象可在上方调整。"
          class="field-note"
        >
          <template #action>
            <a-space v-if="!readOnly">
              <a-button type="link" @click="editRelation">配置关系详细规则</a-button>
            </a-space>
          </template>
        </a-alert>
        <SelectionSourceEditor
          v-else-if="selectionTypes.includes(field.type)"
          :field="field"
          v-model:options="editingOptions"
          :disabled="protectedField || readOnly"
          :detail="detail"
          :review-switch="reviewSelectionSwitch"
          @field-change="applySelectionField"
          @relation="configureRelation"
          @new-relation="addSeparateRelation"
        />
        <AutoNumberEditor
          v-if="field.type === NC.FieldType.AUTO_NUMBER"
          :model-value="option.autoNumber"
          :disabled="readOnly || adopted || !!autoNumberDisabledReason"
          :disabled-reason="autoNumberDisabledReason"
          @update:model-value="updateAutoNumber"
        />
        <a-alert
          v-if="field.type === NC.FieldType.RICH_TEXT"
          type="info"
          show-icon
          message="表单支持格式工具栏；列表和统计明细保留基本排版，长内容可在单元格内滚动，查看记录可阅读完整内容。"
          class="field-note"
        />
        <a-alert
          v-if="field.type === NC.FieldType.REGION || field.type === NC.FieldType.CASCADE"
          type="info"
          show-icon
          message="当前使用平面多选，暂不支持省市区树或逐级联动；高级检索与统计分组暂不支持此类多值字段。"
          class="field-note"
        />
        <a-form-item label="最大长度" v-if="!currentRelation && field.type === NC.FieldType.TEXT">
          <a-input-number v-model:value="field.length" :min="1" :max="4000" :disabled="adopted" />
        </a-form-item>
        <a-row
          v-if="
            ([NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]).includes(field.type)
          "
          :gutter="20"
        >
          <a-col :span="12">
            <a-form-item label="总位数">
              <a-input-number v-model:value="field.precision" :min="1" :max="38" :disabled="adopted" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="小数位数">
              <a-input-number v-model:value="field.scale" :min="0" :max="field.precision ?? 38" :disabled="adopted" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-alert
          v-if="previewSwitch && switchChanged"
          type="info"
          show-icon
          class="field-note"
          :message="
            targetConfigurationError
              ? `待补齐配置：${targetConfigurationError}`
              : switchReviewed
                ? '已确认变更方案，保存并发布后生效'
                : '字段配置有变化，需查看影响并确认'
          "
        >
          <template #action><a-button type="link" @click="beginSwitchReview()">查看变更影响</a-button></template>
        </a-alert>

        <a-form-item label="约束">
          <a-space>
            <a-checkbox
              v-model:checked="field.required"
              :disabled="protectedField || field.type === NC.FieldType.SUMMARY"
            >
              必填
            </a-checkbox>
            <a-checkbox
              v-model:checked="field.unique"
              :disabled="protectedField || field.type === NC.FieldType.SUMMARY"
            >
              唯一
            </a-checkbox>
          </a-space>
        </a-form-item>

        <FieldValueSourceSection
          :key="field.key + ':source:' + editSession + ':' + field.type"
          ref="valueSourceSection"
          v-model:options="editingOptions"
          :field="field"
          :relation="currentRelation"
          :fields="modelValue"
          :relations="relations"
          :field-options="options"
          :master="master"
          :object-id="objectId"
          :disabled="readOnly || (!currentRelation && protectedField)"
        >
          <template #custom>
            <p v-if="option.selection?.directory === NC.FieldType.ORGANIZATION" class="field-hint">
              组织字段的默认值在上方「默认值方式」中设置。
            </p>
            <template v-else>
              <FieldValueEditor
                :key="field.key + ':default:' + editSession + ':' + field.type"
                v-model="option.defaultValue"
                :field="field"
                :options="option"
                :disabled="protectedField || readOnly"
                @upload-status="defaultUploadListener"
              />
              <p v-if="numericIssue?.input === 'defaultValue'" class="field-hint default-value-error" role="alert">
                {{ numericIssue.message }}
              </p>
              <p class="field-hint">仅用于新记录，留空即没有默认值；编辑已有记录和恢复草稿不会重新填入。</p>
            </template>
          </template>
          <template #options>
            <template v-if="optionsType">
              <p class="field-hint">
                可选项（仅当前字段使用）：填写时显示名称，数据保存编码。需多处共用同一套选项时，可将数据来源切换为平台公共字典。
              </p>
              <LocalOptionsEditor v-model="option.options" :disabled="readOnly" />
            </template>
            <p v-else class="field-hint">候选来源见上方「数据来源」。</p>
          </template>
        </FieldValueSourceSection>
        <a-row v-if="!currentRelation && numeric" :gutter="20">
          <a-col :span="12">
            <a-form-item
              label="最小值"
              :validate-status="numericIssue?.input === 'minimum' ? 'error' : undefined"
              :help="numericIssue?.input === 'minimum' ? numericIssue.message : undefined"
            >
              <a-input-number
                v-model:value="option.minimum"
                string-mode
                :precision="field.type === NC.FieldType.INTEGER ? 0 : (field.scale ?? undefined)"
                :disabled="adopted"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item
              label="最大值"
              :validate-status="numericIssue?.input === 'maximum' ? 'error' : undefined"
              :help="numericIssue?.input === 'maximum' ? numericIssue.message : undefined"
            >
              <a-input-number
                v-model:value="option.maximum"
                string-mode
                :precision="field.type === NC.FieldType.INTEGER ? 0 : (field.scale ?? undefined)"
                :disabled="adopted"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item
          label="正则校验"
          :validate-status="patternError ? 'error' : option.pattern?.trim() ? 'success' : undefined"
          :help="patternError || patternHelp"
          v-if="!currentRelation && field.type === NC.FieldType.TEXT"
        >
          <a-input
            v-model:value="option.pattern"
            aria-label="正则校验"
            allow-clear
            :maxlength="200"
            :disabled="adopted"
            placeholder="例如 ^[A-Z0-9]+$"
          />
        </a-form-item>
        <template v-if="calculated">
          <a-form-item v-if="field.type === NC.FieldType.FORMULA" label="计算公式" required>
            <div class="formula-summary">
              <strong>{{ formulaSummary }}</strong>
              <code
                v-if="
                  option.expression && (!option.calculation || ['LOCAL', 'SEQUENCE'].includes(option.calculation.mode))
                "
              >
                {{ option.expression }}
              </code>
              <span v-else-if="!option.calculation">尚未配置计算规则</span>
              <a-button :disabled="false" @click="calculationEditor?.openEditor()">
                {{ readOnly || adopted ? '查看公式配置' : '配置公式' }}
              </a-button>
            </div>
          </a-form-item>
          <a-form-item v-if="field.type === NC.FieldType.SUMMARY" label="计算规则" required>
            <SummaryExpressionEditor
              v-if="field.type === NC.FieldType.SUMMARY"
              :key="field.key + ':summary:' + editSession"
              ref="expressionEditor"
              v-model="option.expression"
              :details="details || []"
              :disabled="readOnly || adopted"
              @result-type="option.resultType = $event"
            />
          </a-form-item>

          <a-form-item v-if="field.type === NC.FieldType.SUMMARY" label="结果类型">
            <a-select
              v-model:value="option.resultType"
              :disabled="readOnly || adopted || field.type === NC.FieldType.SUMMARY"
              :options="[
                { value: NC.FieldType.TEXT, label: '文本' },
                { value: NC.FieldType.INTEGER, label: '整数' },
                { value: NC.FieldType.DECIMAL, label: '小数' }
              ]"
            />
          </a-form-item>
        </template>

        <section class="field-live-preview">
          <div class="field-preview-heading">
            <strong>实时预览</strong>
            <a-radio-group v-model:value="previewMode" size="small" button-style="solid">
              <a-radio-button value="form">表单填写</a-radio-button>
              <a-radio-button value="table">列表显示</a-radio-button>
            </a-radio-group>
          </div>
          <p class="field-hint">
            {{ detail ? '内部明细中的字段' : '主表字段' }} · 试填内容仅用于预览，不保存业务数据。
          </p>
          <template v-if="calculated || field.type === NC.FieldType.AUTO_NUMBER">
            <a-alert
              type="info"
              show-icon
              :message="
                field.type === NC.FieldType.AUTO_NUMBER
                  ? '保存新记录时自动生成编号，用户无需填写。'
                  : '系统按计算规则生成只读结果。'
              "
              :description="option.expression || undefined"
            />
          </template>
          <template v-else-if="previewMode === 'form'">
            <label class="field-preview-label">{{ field.required ? '* ' : '' }}{{ field.name || '未命名字段' }}</label>
            <FieldValueEditor v-model="previewValue" :field="field" :options="option" preview :disabled="readOnly" />
          </template>
          <table v-else class="field-preview-table">
            <thead>
              <tr>
                <th>{{ field.name || '未命名字段' }}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>
                  <FieldValueEditor :model-value="previewValue" :field="field" :options="option" display-only />
                </td>
              </tr>
            </tbody>
          </table>
        </section>
        <a-typography-text v-if="option.columnName" type="secondary">
          对应数据列：{{ option.columnName }}
        </a-typography-text>
      </template>
    </OsModalForm>
    <FieldSwitchReview
      :open="switchReviewOpen && open"
      :field-name="field.name"
      :preview="switchPreview"
      :target-type="field.type"
      :changes="configurationChanges"
      :loading="switchPreviewLoading"
      :error="switchPreviewError"
      :configuration-error="targetConfigurationError"
      :can-confirm="canApplySwitch"
      v-model:confirmed="clearChoice"
      :rows="previewRows"
      :rows-loading="previewRowsLoading"
      :rows-error="previewRowsError"
      :can-view-rows="canViewConversionRows"
      :can-clear="canClearColumn"
      :can-maintain-data="canMaintainData && !!sourceField?.id"
      @page="loadPreviewRows($event)"
      @retry="previewRefresh++"
      @navigate="emit('navigate', $event)"
      @maintain-data="sourceField?.id && emit('maintainData', { fieldId: sourceField.id })"
      @confirm="confirmSwitchReview"
      @cancel="cancelSwitchReview"
      @configure="switchReviewOpen = false"
    >
      <template #target>
        <a-form-item
          v-if="optionsType"
          label="可选项（仅当前字段使用）"
          :label-col="{ span: 24 }"
          :wrapper-col="{ span: 24 }"
          required
        >
          <LocalOptionsEditor v-model="option.options" :disabled="readOnly" />
        </a-form-item>
        <a-form-item v-if="field.type === NC.FieldType.REFERENCE" label="从哪份资料选择" required>
          <a-select
            v-model:value="relationTargetId"
            show-search
            option-filter-prop="label"
            :options="relationTargets || []"
            placeholder="请选择已发布的业务数据对象"
            @search="loadRelationTargets?.(String($event))"
          />
        </a-form-item>
      </template>
    </FieldSwitchReview>
    <FormulaConfigurationModal
      v-if="open && field.type === NC.FieldType.FORMULA"
      :key="field.key + ':formula-configuration:' + editSession"
      ref="calculationEditor"
      :field="field"
      :options="option"
      :fields="modelValue"
      :field-options="options"
      :relations="relations || []"
      :detail="detail"
      :disabled="readOnly || adopted"
      @apply="Object.assign(option, $event)"
    />
  </div>
</template>

<style scoped>
.field-type-cell {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--spacing-xs);
  min-width: 0;
  text-align: left;
}
.field-type-main,
.field-type-status {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 0;
}
.field-type-status {
  flex-wrap: wrap;
}
.field-type-tag {
  max-width: 100%;
  margin-inline-end: 0;
  white-space: normal;
  overflow-wrap: anywhere;
}
.field-type-main .field-type-select {
  flex: 1;
  min-width: 0;
}
.field-protection-hint {
  flex-shrink: 0;
  color: var(--text-secondary);
  cursor: help;
}
.field-impact-link {
  height: auto;
  padding: 0;
  font-size: inherit;
}
.formula-summary {
  display: grid;
  justify-items: start;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.formula-summary code {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  color: var(--text-secondary);
}
.field-type-select,
.field-length {
  width: 100%;
}
.field-precision {
  display: flex;
  align-items: center;
  gap: 4px;
}
.field-precision .ant-input-number {
  width: 70px;
}
.field-row-error {
  color: var(--ant-color-error, #ff4d4f);
  font-size: 12px;
  line-height: 18px;
  margin-top: 4px;
}
.field-note {
  margin-bottom: 16px;
}
.field-hint {
  color: var(--text-secondary);
  font-size: 12px;
  margin: 8px 0;
}
.default-value-error {
  color: #cf1322;
}
.field-live-preview {
  padding: 16px;
  margin: 16px 0;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  background: var(--bg-page, #fafafa);
}
.field-preview-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.field-preview-label {
  display: block;
  margin: 12px 0 8px;
}
.field-preview-table {
  border-collapse: collapse;
  width: 100%;
}
.field-preview-table th,
.field-preview-table td {
  padding: 12px;
  border: 1px solid var(--border, #e5e7eb);
  text-align: left;
  overflow-wrap: anywhere;
}
</style>
