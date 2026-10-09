<script setup lang="ts">
import type { DisplayMode } from '@/components/ucp-modal-form/types'

import * as NC from '@/types/nocode/enums'
import DocumentPolicyDesigner from '../components/DocumentPolicyDesigner.vue'
import BusinessFilePolicyDesigner from '../components/BusinessFilePolicyDesigner.vue'
import RecordFolderDesigner from '../components/RecordFolderDesigner.vue'
import ObjectRelationOverview from '../components/ObjectRelationOverview.vue'
import RelationTypeExample from '../components/RelationTypeExample.vue'
import { computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  SaveOutlined,
  SendOutlined,
  UpOutlined
} from '@ant-design/icons-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import '../management-tables.css'
import CategoryInput from '../components/CategoryInput.vue'
import type { ObjectField } from '@/types/nocode/object'
import SelectionMigrationDialog from '../components/SelectionMigrationDialog.vue'
import FieldConversionReview from '../components/FieldConversionReview.vue'
import FieldDesigner from '../components/FieldDesigner.vue'
import ObjectOperationReview from '../components/ObjectOperationReview.vue'
import SystemFieldsPanel from '../components/SystemFieldsPanel.vue'
import ObjectDataGrid from '../components/ObjectDataGrid.vue'
import { changeFieldType, clearMultilinePatterns, copyFieldOptions, designFieldsError } from '@/nocode/field-editing'
import { legacyAutoNumberFields } from '@/nocode/auto-number'
import {
  applyRelation,
  designRelationsError,
  newFieldRelation,
  relationFieldLabel,
  relationForField,
  relationReferenceFields
} from '@/nocode/relation-editing'
import ObjectSharingPanel from '../components/ObjectSharingPanel.vue'
import TableBindingPanel from '../components/TableBindingPanel.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { editDesign, errorMessage, label, newDesign, generatedBinding } from '@/nocode/data-center'
import { fieldTypes, newField, recordTitleFields, reconcileRecordTitle, validateDraft } from '@/nocode/object-draft'
import { useResourceCode, suggestedTableName, suggestedDetailTableName } from '@/nocode/resource-code'
import type { DirectoryNode } from '@/nocode/platform'
import type * as DC from '@/types/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import { createRequestSession } from '@/nocode/request-session'
import { announceFollowResult } from '@/nocode/application-object-follow'
import { synchronizeDesignLifecycle } from '@/nocode/document-lifecycle'
import { businessFileIssues } from '@/nocode/business-file-policy'
import {
  conversionClearsColumn,
  conversionClearFieldIds,
  conversionConfirmationError,
  conversionPublishLabel
} from '@/nocode/field-conversion'
import { objectDependencyPresentation } from '@/nocode/object-dependency-presentation'
import { applicationUpgradeConfirmationError } from '@/nocode/application-upgrade'

const route = useRoute(),
  router = useRouter(),
  platform = useNocodePlatform(),
  api = platform.dataCenter
const historyPagination = reactive({
  current: 1,
  pageSize: 10,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`
})
const input = reactive(newDesign())
const mainFieldDesigner = ref<InstanceType<typeof FieldDesigner>>()
const operationReview = ref<InstanceType<typeof ObjectOperationReview>>()
const detailFieldDesigners = ref<InstanceType<typeof FieldDesigner>[]>([])
const design = ref<DC.ObjectDesign>()
const legacyAutoNumberIds = ref<string[] | null>([])
const publishedMembers = ref<{ details: string[]; relations: string[]; indexes: string[] } | null>({
  details: [],
  relations: [],
  indexes: []
})
const publishedFieldBaselines = ref<Record<string, DC.PublishedFieldBaseline>>({})
watch(
  () => [design.value?.draft.id, design.value?.publishedVersion] as const,
  async ([id, version], _, onCleanup) => {
    let active = true
    onCleanup(() => {
      active = false
    })
    legacyAutoNumberIds.value = id && version ? null : []
    publishedMembers.value = id && version ? null : { details: [], relations: [], indexes: [] }
    publishedFieldBaselines.value = {}
    if (!id || !version) return
    try {
      const snapshot = await api.version(id, version)
      if (active) {
        legacyAutoNumberIds.value = legacyAutoNumberFields(snapshot)
        const definitionFields = Array.isArray(snapshot.fields) ? (snapshot.fields as ObjectField[]) : []
        const definitions = Array.isArray(snapshot.details)
          ? (snapshot.details as Array<{ fields?: ObjectField[]; fieldOptions?: Record<string, DC.FieldOptions> }>)
          : []
        const relationships = Array.isArray(snapshot.relations) ? (snapshot.relations as DC.ObjectRelation[]) : []
        const baselines: Record<string, DC.PublishedFieldBaseline> = {}
        const addBaselines = (fields: ObjectField[], options: Record<string, DC.FieldOptions>) => {
          for (const field of fields) {
            if (!field.id) continue
            const configuration = options[field.id] ?? options[field.key]
            baselines[field.id] = {
              type: field.type,
              length: field.length ?? null,
              precision: field.precision ?? null,
              scale: field.scale ?? null,
              required: !!field.required,
              unique: !!field.unique,
              selection: configuration?.selection ?? null,
              targetObjectId: relationships.find(item => item.fieldId === field.id)?.targetObjectId ?? null,
              minimum: configuration?.minimum ?? null,
              maximum: configuration?.maximum ?? null,
              pattern: configuration?.pattern ?? null,
              defaultValue: configuration?.defaultValue ?? null,
              options: configuration?.options ?? []
            }
          }
        }
        addBaselines(definitionFields, (snapshot.fieldOptions ?? {}) as Record<string, DC.FieldOptions>)
        definitions.forEach(detail => addBaselines(detail.fields ?? [], detail.fieldOptions ?? {}))
        publishedFieldBaselines.value = baselines
        const ids = (key: string) =>
          Array.isArray(snapshot[key])
            ? (snapshot[key] as { id?: string }[]).flatMap(member => (member.id ? [member.id] : []))
            : []
        publishedMembers.value = { details: ids('details'), relations: ids('relations'), indexes: ids('indexes') }
      }
    } catch {
      // 查询失败时不推断旧字段可转换；配置区域提示重试，其他字段继续可编辑。
    }
  }
)
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  loaded = ref(false)
const tab = ref(route.query.tab === 'data' ? 'data' : 'fields'),
  baseline = ref('')
const dataEditing = ref(false)
const dataFieldId = computed(() => (typeof route.query.fieldId === 'string' ? route.query.fieldId : undefined))
const dataDetailId = computed(() => (typeof route.query.detailId === 'string' ? route.query.detailId : undefined))
const dataRecordIds = computed(() =>
  typeof route.query.recordIds === 'string' ? route.query.recordIds.split(',').filter(Boolean) : []
)
watch(
  () => route.query.tab,
  value => {
    if (value === 'data') tab.value = 'data'
  }
)
const basicExpanded = ref(false)
const titleFieldName = computed(() => {
  const field = input.draft.fields.find(item => item.key === input.draft.titleFieldKey)
  return field?.name || field?.code || '未设置'
})
const histories = ref<DC.PublishExecution[]>([]),
  targets = ref<DC.ObjectRow[]>([])
const targetNames = ref<Record<string, string>>({})
let relationNamesGeneration = 0
watch(
  () => [...new Set(input.relations.map(r => r.targetObjectId))].filter(Boolean).sort().join(','),
  async ids => {
    const generation = ++relationNamesGeneration
    const missing = ids.split(',').filter(id => id && !targetNames.value[id])
    for (let offset = 0; offset < missing.length; offset += 8) {
      const batch = missing.slice(offset, offset + 8)
      const results = await Promise.allSettled(batch.map(id => api.design(id)))
      if (generation !== relationNamesGeneration) return
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') targetNames.value[batch[index]!] = result.value.draft.objectName
      })
    }
  }
)
const users = ref<{ label: string; value: string }[]>([]),
  departments = ref<DirectoryNode[]>([])
const dirty = computed(() => loaded.value && JSON.stringify(input) !== baseline.value)
const canQuery = computed(() => platform.hasPermission('nocode:object:query'))
const canEdit = computed(
  () =>
    canQuery.value &&
    loaded.value &&
    !loading.value &&
    !saving.value &&
    platform.hasPermission(input.draft.id ? 'nocode:object:update' : 'nocode:object:create') &&
    (!design.value ||
      (design.value.draft.state === NC.VersionState.DRAFT && design.value.status !== NC.ObjectStatus.DISABLED))
)
const titleFields = computed(() => recordTitleFields(input.draft, input.settings.titleTemplate))
watch(titleFields, () => {
  if (!canEdit.value) return
  const previous = input.draft.titleFieldKey
  if (reconcileRecordTitle(input.draft, input.settings.titleTemplate) && previous) {
    basicExpanded.value = true
    message.warning(
      input.draft.titleFieldKey
        ? `原标题字段已不适用，记录标题已改为“${titleFieldName.value}”`
        : '原标题字段已不适用，请重新选择记录标题'
    )
  }
})
const adopted = computed(() => design.value?.source === NC.ObjectSource.ADOPTED)
const nameLocked = computed(() => !!design.value?.publishedVersion || adopted.value)
const codeSuggestion = useResourceCode({
  name: () => input.draft.objectName,
  kind: () => 'OBJECT',
  enabled: () => !input.draft.id,
  setCode: changeCode
})
const allFields = computed(() => [
  ...input.draft.fields.map(f => ({ label: `主表 · ${f.name}`, value: f.key })),
  ...input.details
    .filter(d => d.state === NC.MemberState.ACTIVE)
    .flatMap(d => d.fields.map(f => ({ label: `${d.name} · ${f.name}`, value: f.key })))
])
function dependencyFieldNames(ids: string[]) {
  return ids.map(id => allFields.value.find(field => field.value === id)?.label ?? `非当前草稿字段（${id}）`).join('、')
}
const plan = ref<DC.PublishPlan>(),
  planOpen = ref(false),
  reason = ref(''),
  publishing = ref(false)
const publishError = ref('')
const publishNeedsRecheck = ref(false)
const suspendApplicationsConfirmed = ref(false)
const applicationUpgrades = computed(() => plan.value?.applicationUpgrades ?? [])
const publishChecks = computed(() => {
  const appBlockers = new Set(
    applicationUpgrades.value.flatMap(app => app.blockers.map(reason => `${app.applicationName}：${reason}`))
  )
  return (plan.value?.checks ?? []).filter(
    (check, index, all) =>
      !appBlockers.has(check.message) &&
      all.findIndex(other => other.code === check.code && other.message === check.message) === index
  )
})
const clearTargets = computed(() =>
  (plan.value?.conversions ?? []).filter(item => conversionClearsColumn(item) && item.affectedRows > 0)
)
const clearValueCount = computed(() => clearTargets.value.reduce((count, item) => count + item.affectedRows, 0))
const publishActionLabel = computed(() =>
  publishNeedsRecheck.value
    ? '重新检查发布影响'
    : conversionPublishLabel(plan.value?.conversions ?? [], applicationUpgrades.value.length)
)
const identityLocked = (kind: 'details' | 'relations' | 'indexes', id: string | null) =>
  !!id && (publishedMembers.value === null || publishedMembers.value[kind].includes(id))
const reconcile = ref<DC.ReconcilePreview>(),
  reconcileOpen = ref(false)
const versionOpen = ref(false),
  versionBody = ref('')
const selectionMigrationOpen = ref(false)
const relationOpen = ref(false),
  relationError = ref(''),
  relationPosition = ref(-1)
const relation = reactive<DC.ObjectRelation>({
  id: null,
  code: '',
  name: '',
  kind: NC.RelationType.REFERENCE,
  targetObjectId: '',
  fieldId: null,
  targetFieldId: null,
  required: false,
  onDelete: NC.DeletePolicy.RESTRICT
})
const indexOpen = ref(false),
  indexPosition = ref(-1),
  indexError = ref('')
const index = reactive<DC.ObjectIndex>({ id: null, code: '', name: '', unique: false, fieldIds: [] })
const targetOptions = computed(() =>
  targets.value
    .filter(t => t.publishedVersion && t.status === NC.ObjectStatus.ACTIVE)
    .map(t => ({ label: `${t.objectName} · ${t.objectCode}`, value: t.id }))
)

function accept(result: DC.ObjectDesign) {
  design.value = result
  Object.assign(input, editDesign(result))
  baseline.value = JSON.stringify(input)
  loaded.value = true
}
let loadGeneration = 0
const saveSession = createRequestSession()
async function load() {
  if (!canQuery.value) return
  const generation = ++loadGeneration
  const id = typeof route.query.id === 'string' ? route.query.id : undefined
  loading.value = true
  loaded.value = !!id && design.value?.draft.id === id
  error.value = ''
  try {
    // 已有对象优先展示字段；新建时直接提供必填的基本信息。
    basicExpanded.value = !id
    if (id) {
      const result = await api.design(id)
      if (generation !== loadGeneration) return
      accept(result)
      const history = await api.history(id)
      if (generation === loadGeneration) histories.value = history
    } else {
      codeSuggestion.reset()
      Object.assign(input, newDesign())
      input.draft.category = typeof route.query.category === 'string' ? route.query.category : ''
      design.value = undefined
      baseline.value = JSON.stringify(input)
      loaded.value = true
    }
  } catch (cause) {
    if (generation === loadGeneration) error.value = errorMessage(cause)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
/** 保存对象草稿（不发布）；返回是否保存成功，失败原因留在 error。 */
async function save(): Promise<boolean> {
  if (!canEdit.value || saving.value) return false
  const mainConflict = mainFieldDesigner.value?.reservedCodeError()
  const detailConflict = detailFieldDesigners.value.map(editor => editor.reservedCodeError()).find(Boolean)
  if (mainConflict || detailConflict) {
    error.value = mainConflict || detailConflict || ''
    tab.value = mainConflict ? 'fields' : 'details'
    return false
  }
  input.draft.titleTemplate = input.settings.titleTemplate
  if (!adopted.value) clearMultilinePatterns(input.draft.fields, input.fieldOptions)
  for (const detail of input.details)
    if (detail.binding?.source !== NC.ObjectSource.ADOPTED) clearMultilinePatterns(detail.fields, detail.fieldOptions)
  const lifecycleIssue = synchronizeDesignLifecycle(input)
  if (lifecycleIssue) {
    error.value = lifecycleIssue
    tab.value = 'document-policy'
    return false
  }
  const fieldError = designFieldsError(input)
  if (fieldError) {
    error.value = fieldError.message
    tab.value = fieldError.tab
    return false
  }
  const relationIssue = designRelationsError(input)
  if (relationIssue) {
    error.value = relationIssue
    tab.value = 'relations'
    return false
  }
  const businessFileIssue = businessFileIssues(input)[0]
  if (businessFileIssue) {
    error.value = businessFileIssue
    tab.value = 'business-file'
    return false
  }
  error.value = validateDraft(input.draft, adopted.value, design.value?.draft.tableName) ?? ''
  if (error.value) {
    basicExpanded.value = true
    return false
  }
  const generation = loadGeneration
  const current = saveSession.begin()
  const submitted = JSON.parse(JSON.stringify(input)) as DC.SaveDesign
  saving.value = true
  try {
    const result = await api.save(submitted)
    if (!current() || generation !== loadGeneration) return false
    accept(result)
    await router.replace({
      path: '/nocode/object/editor',
      query: { id: result.draft.id, ...(route.query.returnApp ? { returnApp: route.query.returnApp } : {}) }
    })
    message.success('对象草稿已保存')
    return true
  } catch (cause) {
    if (current() && generation === loadGeneration) error.value = errorMessage(cause)
    return false
  } finally {
    if (current()) saving.value = false
  }
}
async function editPublished() {
  if (!design.value) return
  try {
    accept(await api.edit({ id: design.value.draft.id, expectedLockVersion: design.value.draft.lockVersion }))
    message.success('已创建新草稿，原发布版本保持不变')
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
function changeCode(code: string) {
  const previous = input.draft.objectCode
  input.draft.objectCode = code
  input.draft.tableName = suggestedTableName(code, previous, input.draft.tableName)
}
function removeField(id: string) {
  if (!input.restoredFieldIds?.includes(id) && !input.draft.removedFieldIds.includes(id))
    input.draft.removedFieldIds.push(id)
  cancelFieldRestore(id)
  if (input.draft.titleFieldKey === id) input.draft.titleFieldKey = ''
}
function cancelFieldRestore(id: string) {
  input.restoredFieldIds = (input.restoredFieldIds ?? []).filter(fieldId => fieldId !== id)
}
function restoreField(id: string, persisted: boolean) {
  input.draft.removedFieldIds = input.draft.removedFieldIds.filter(fieldId => fieldId !== id)
  if (persisted && !input.restoredFieldIds?.includes(id))
    input.restoredFieldIds = [...(input.restoredFieldIds ?? []), id]
}
function addDetail() {
  const number = input.details.length + 1,
    code = `items_${number}`
  input.details.push({
    id: null,
    code,
    name: `明细 ${number}`,
    tableName: suggestedDetailTableName(input.draft.tableName, code),
    state: NC.MemberState.ACTIVE,
    fields: [newField(0, '名称')],
    fieldOptions: {},
    indexes: [],
    binding: generatedBinding(design.value?.schemaName, true)
  })
}
function detailIdentityLocked(detail: DC.ObjectDetail) {
  return identityLocked('details', detail.id) || detail.binding?.source === NC.ObjectSource.ADOPTED
}
function changeDetailCode(detail: DC.ObjectDetail, code: string) {
  const previous = detail.code
  if (detail.tableName === suggestedDetailTableName(input.draft.tableName, previous))
    detail.tableName = suggestedDetailTableName(input.draft.tableName, code)
  detail.code = code
}
const bindDetailOpen = ref(false),
  bindDetailError = ref(''),
  bindingLoading = ref(false)
const bindingTables = ref<DC.TableRow[]>([]),
  bindingSchemas = ref<string[]>(['public'])
const bindingPreflight = ref<DC.AdoptionPreflight>()
const detailCandidate = reactive({ code: '', name: '', tableName: '', binding: generatedBinding('public', true) })
const bindingSession = createRequestSession()
const bindDisplayMode = ref<DisplayMode>('modal')
let bindingSearch = 0
async function lookupBindingTables(name = '') {
  const request = ++bindingSearch
  try {
    const result = await api.tables({
      schema: detailCandidate.binding.schemaName,
      name,
      pageNo: 1,
      pageSize: 100,
      management: NC.TableManagement.UNMANAGED
    })
    if (request === bindingSearch) bindingTables.value = result.list.filter(t => !t.system)
  } catch (cause) {
    if (request === bindingSearch) bindDetailError.value = errorMessage(cause)
  }
}
async function showBindDetail() {
  const current = bindingSession.begin()
  bindingLoading.value = false
  const number = input.details.length + 1
  Object.assign(detailCandidate, {
    code: `items_${number}`,
    name: `明细 ${number}`,
    tableName: '',
    binding: {
      ...generatedBinding(design.value?.schemaName, true),
      source: NC.ObjectSource.ADOPTED,
      structureMode: NC.StructureMode.RETAIN
    }
  })
  bindingPreflight.value = undefined
  bindDetailError.value = ''
  bindDetailOpen.value = true
  try {
    const schemas = await api.schemas()
    if (!current()) return
    bindingSchemas.value = schemas
    await lookupBindingTables()
  } catch (cause) {
    if (current()) bindDetailError.value = errorMessage(cause)
  }
}
async function changeBindingSchema() {
  bindingSession.invalidate()
  bindingLoading.value = false
  detailCandidate.tableName = ''
  bindingPreflight.value = undefined
  await lookupBindingTables()
}
async function selectBindingTable(name: string) {
  const request = bindingSession.begin()
  const schema = detailCandidate.binding.schemaName
  const current = () =>
    request() &&
    bindDetailOpen.value &&
    schema === detailCandidate.binding.schemaName &&
    name === detailCandidate.tableName
  bindingLoading.value = true
  bindingPreflight.value = undefined
  bindDetailError.value = ''
  try {
    const result = await api.preflight(schema, name)
    if (!current()) return
    bindingPreflight.value = result
    const key = result.structure.columns.find(c => c.primaryKeyPosition > 0)
    detailCandidate.binding.keyColumn = key?.name ?? ''
    detailCandidate.binding.fingerprint = result.fingerprint
    detailCandidate.binding.readOnly = result.readOnly
    detailCandidate.binding.parentColumn = result.structure.columns.some(c => c.name === 'parent_id') ? 'parent_id' : ''
  } catch (cause) {
    if (current()) bindDetailError.value = errorMessage(cause)
  } finally {
    if (current()) bindingLoading.value = false
  }
}
function closeBindDetail() {
  bindDetailOpen.value = false
  bindingSession.invalidate()
  bindingSearch++
  bindingLoading.value = false
}
function addBoundDetail() {
  if (!canEdit.value) return
  if (
    !bindingPreflight.value?.allowed ||
    bindingLoading.value ||
    bindingPreflight.value.schemaName !== detailCandidate.binding.schemaName ||
    bindingPreflight.value.tableName !== detailCandidate.tableName
  ) {
    bindDetailError.value = '请选择可绑定的已有表并完成预检'
    return
  }
  if (
    !detailCandidate.name.trim() ||
    !/^[a-z][a-z0-9_]{0,62}$/.test(detailCandidate.code) ||
    !detailCandidate.binding.parentColumn?.trim()
  ) {
    bindDetailError.value = '请填写明细名称、编码和父键列'
    return
  }
  if (
    input.details.some(
      d =>
        d.code === detailCandidate.code ||
        (d.tableName === detailCandidate.tableName &&
          (d.binding?.schemaName ?? design.value?.schemaName ?? 'public') === detailCandidate.binding.schemaName)
    )
  ) {
    bindDetailError.value = '明细编码或物理表重复'
    return
  }
  input.details.push({
    id: null,
    code: detailCandidate.code,
    name: detailCandidate.name,
    tableName: detailCandidate.tableName,
    state: NC.MemberState.ACTIVE,
    fields: [],
    fieldOptions: {},
    indexes: [],
    binding: { ...detailCandidate.binding }
  })
  closeBindDetail()
}
const relationColumns = computed(() =>
  relationReferenceFields(input, relationPosition.value, pendingRelationField.value, relation.sourceDetailId).map(
    f => ({
      label: `${f.name} · ${input.fieldOptions[f.key]?.columnName ?? f.code}`,
      value: f.key
    })
  )
)
// 字段入口只配置业务语义；保存前再次编辑也不能切回手工映射引用列。
const fieldRelationContext = ref(false)

const detailKey = (detail: DC.ObjectDetail) => detail.id || `detail:${detail.code}`
const sourceRelations = (detail?: DC.ObjectDetail) =>
  input.relations.filter(r => (r.sourceDetailId || null) === (detail ? detailKey(detail) : null))
const fieldTypeName = (type: string) =>
  type === NC.FieldType.REFERENCE ? '单选（对象引用）' : (fieldTypes.find(item => item.value === type)?.label ?? type)
function fieldSwitchRequest(
  source: ObjectField,
  target: ObjectField,
  options: DC.FieldOptions,
  detailId?: string | null,
  targetObjectId?: string | null
): DC.FieldSwitchPreviewRequest {
  const objectId = input.draft.id
  const fieldId = source.id
  if (!objectId || !fieldId) throw new Error('该字段尚未保存，无法查询物理列影响')
  return {
    objectId,
    detailId: detailId || undefined,
    fieldId,
    targetType: target.type,
    length: target.length,
    precision: target.precision,
    scale: target.scale,
    selection: options.selection,
    targetObjectId: target.type === NC.FieldType.REFERENCE ? targetObjectId : null,
    detachRelation: source.type === NC.FieldType.REFERENCE && target.type !== NC.FieldType.REFERENCE,
    targetRequired: target.required,
    targetUnique: target.unique,
    targetMinimum: options.minimum,
    targetMaximum: options.maximum,
    targetPattern: options.pattern,
    targetDefaultValue: options.defaultValue,
    targetOptions: options.options
  }
}
async function previewFieldSwitch(
  source: ObjectField,
  target: ObjectField,
  options: DC.FieldOptions,
  detailId?: string | null,
  targetObjectId?: string | null
): Promise<DC.FieldSwitchPreview> {
  if (!canEdit.value) throw new Error('当前对象不可编辑')
  if (input.draft.id && source.id)
    return api.fieldSwitchPreview(fieldSwitchRequest(source, target, options, detailId, targetObjectId))
  return {
    objectId: input.draft.id ?? '',
    detailId: detailId ?? null,
    fieldId: source.id ?? '',
    fieldName: source.name,
    sourceType: source.type,
    targetType: target.type,
    deploymentState: 'UNPUBLISHED',
    totalRows: null,
    valueRows: null,
    decision: 'UNPUBLISHED',
    explanation: '该字段尚未保存；草稿只记录配置，发布时仍会检查结构与依赖。',
    impacts: []
  }
}
async function previewFieldSwitchRows(
  source: ObjectField,
  target: ObjectField,
  options: DC.FieldOptions,
  targetObjectId: string | null,
  pageNo: number,
  detailId?: string | null
): Promise<DC.FieldConversionRows> {
  if (!input.draft.id || !source.id) return { rows: [], total: 0, pageNo, pageSize: 20 }
  return api.fieldSwitchPreviewRows({
    ...fieldSwitchRequest(source, target, options, detailId, targetObjectId),
    pageNo,
    pageSize: 20
  })
}
function applyFieldConversion(
  source: ObjectField,
  target: ObjectField,
  options: DC.FieldOptions,
  targetObjectId: string | null,
  detail?: DC.ObjectDetail
): string | null {
  if (!canEdit.value) return '当前对象不可编辑'
  const scope = detail?.fields ?? input.draft.fields
  const fieldIndex = scope.findIndex(item => item.key === source.key && item.id === source.id)
  if (fieldIndex < 0) return '原字段已变化，请重新打开配置'
  const linked = relationForField(source, sourceRelations(detail))
  if (source.type === NC.FieldType.REFERENCE && !linked) return '当前对象关系已变化，请重新打开配置'
  if (target.type === NC.FieldType.REFERENCE) {
    if (!targetObjectId) return '请选择目标业务数据对象'
    const value: DC.ObjectRelation = linked
      ? { ...linked }
      : { ...newFieldRelation(source), sourceDetailId: detail ? detailKey(detail) : null }
    value.name = target.name
    value.code = target.code
    value.targetObjectId = targetObjectId
    value.kind = target.unique ? NC.RelationType.ONE_TO_ONE : NC.RelationType.REFERENCE
    value.required = target.required
    value.fieldId = source.id
    const issue = applyRelation(input, value, linked ? input.relations.indexOf(linked) : -1, source)
    if (issue) return issue
  } else if (linked) {
    const relationIndex = input.relations.indexOf(linked)
    if (relationIndex < 0) return '当前对象关系已变化，请重新打开配置'
    input.relations.splice(relationIndex, 1)
  }
  scope[fieldIndex] = { ...target }
  const fieldOptions = detail?.fieldOptions ?? input.fieldOptions
  fieldOptions[source.key] = copyFieldOptions(options)
  return null
}
/** 编辑阶段只读检查旧列；草稿确认不清数据，正式发布仍重新检查并逐列确认。 */
async function reviewFieldSwitch(
  source: ObjectField,
  target: ObjectField,
  options: DC.FieldOptions,
  detailId?: string | null,
  targetObjectId?: string | null,
  detachRelation = false
): Promise<boolean> {
  if (!canEdit.value) return false
  let preview: DC.FieldSwitchPreview | null = null
  if (input.draft.id && source.id) {
    try {
      preview = await api.fieldSwitchPreview({
        objectId: input.draft.id,
        detailId: detailId || undefined,
        fieldId: source.id,
        targetType: target.type,
        length: target.length,
        precision: target.precision,
        scale: target.scale,
        selection: options.selection,
        targetObjectId,
        detachRelation
      })
    } catch (cause) {
      message.error(`字段变更影响检查失败：${errorMessage(cause)}`)
      return false
    }
  }
  const title = `${fieldTypeName(source.type)} → ${fieldTypeName(target.type)}：变更影响`
  const rows =
    preview?.totalRows == null || preview.valueRows == null
      ? '该字段尚未部署，没有可检查的历史物理列。'
      : `当前表共 ${preview.totalRows} 条记录，本列 ${preview.valueRows} 条有值（包含已逻辑删除的记录）。`
  const explanation = preview?.explanation ?? '新字段尚未保存和发布，没有历史列值；后续发布仍会检查目标配置。'
  const treatment =
    preview?.decision === 'CLEAR_COLUMN'
      ? '如继续，草稿先记录目标配置；正式发布时还要预览记录并确认只清空本列，记录及其他列保留。'
      : preview?.decision === 'PRESERVE'
        ? '旧值按上述兼容方式保留。保存草稿不会修改业务记录。'
        : '继续只调整草稿；发布时仍会校验目标结构和依赖。'
  const details = preview?.impacts?.map(impact => `${impact.sourceName} → ${impact.location}：${impact.message}`) ?? []
  const content = h('div', { class: 'field-switch-impact' }, [
    h('p', rows),
    h('p', explanation),
    h('p', treatment),
    ...details.map(item => h('p', item))
  ])
  if (preview?.decision === 'BLOCKED' || preview?.deploymentState === 'MISSING_COLUMN') {
    Modal.error({ title, content, okText: '知道了', width: 620 })
    return false
  }
  return new Promise(resolve => {
    Modal.confirm({
      title,
      content,
      width: 620,
      okText: '继续调整草稿',
      cancelText: '取消切换',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  })
}
const sourceOptions = computed(() => [
  { label: '主表', value: '' },
  ...input.details
    .filter(d => d.state === NC.MemberState.ACTIVE)
    .map(d => ({ label: `${d.name} · ${d.code}`, value: detailKey(d) }))
])
function sourceChanged() {
  relation.fieldId = null
  if (relation.sourceDetailId) {
    relation.kind = NC.RelationType.REFERENCE
    relation.onDelete = NC.DeletePolicy.RESTRICT
  }
}
/** 对象关系窗口是否从字段抽屉进入：是则确定时自动保存草稿并回到抽屉。 */
const relationFromDrawer = ref(false),
  relationSaving = ref(false)
/** 保存后要回到抽屉的关系；对应的字段设计器处理后清空。 */
const relationFocus = ref<{ code: string; seq: number } | null>(null)
let relationFocusSeq = 0
/** 内部明细面板受控展开：新明细保存后换了 key，要回到它的字段抽屉须保持展开。 */
const detailPanels = ref<Array<string | number>>([])
function focusRelation(code: string) {
  const target = input.relations.find(item => item.code === code)
  const position = target?.sourceDetailId
    ? input.details.findIndex(detail => detailKey(detail) === target.sourceDetailId)
    : -1
  const panel = position >= 0 ? input.details[position]?.id || position : null
  if (panel !== null && !detailPanels.value.includes(panel)) detailPanels.value = [...detailPanels.value, panel]
  relationFocus.value = { code, seq: ++relationFocusSeq }
}
/** 字段列表里未保存关系的占位行：先保存草稿，成功后打开该关系引用字段的抽屉。 */
async function saveThenConfigureRelation(code: string) {
  if (await save()) focusRelation(code)
}
function showRelation(position = -1, detail?: DC.ObjectDetail) {
  fieldRelationContext.value = false
  relationFromDrawer.value = false
  pendingRelationField.value = undefined
  relationPosition.value = position
  relationError.value = ''
  Object.assign(
    relation,
    position < 0
      ? {
          id: null,
          sourceDetailId: detail ? detailKey(detail) : '',
          code: '',
          name: '',
          kind: NC.RelationType.REFERENCE,
          targetObjectId: '',
          fieldId: null,
          targetFieldId: null,
          required: false,
          onDelete: NC.DeletePolicy.RESTRICT
        }
      : { ...input.relations[position], sourceDetailId: input.relations[position].sourceDetailId || '' }
  )
  relationOpen.value = true
  void lookupTargets()
}
function startSeparateRelation(detail?: DC.ObjectDetail) {
  tab.value = 'relations'
  showRelation(-1, detail)
}
const pendingRelationField = ref<ObjectField>()
function fieldRelation(field: ObjectField, detail?: DC.ObjectDetail, origin?: 'drawer') {
  const existing = relationForField(field, input.relations)
  const position = existing ? input.relations.indexOf(existing) : -1
  if (
    existing &&
    field.id &&
    field.type === NC.FieldType.REFERENCE &&
    !(detail?.fieldOptions ?? input.fieldOptions)[field.key]?.generated
  ) {
    void openSingleRelationField(field, detail)
    return
  }
  if (position >= 0) {
    showRelation(position)
    fieldRelationContext.value = true
    relationFromDrawer.value = origin === 'drawer'
    return
  }
  showRelation(-1, detail)
  fieldRelationContext.value = true
  relationFromDrawer.value = origin === 'drawer'
  Object.assign(relation, newFieldRelation(field))
  if (detail) relation.kind = NC.RelationType.REFERENCE
  pendingRelationField.value = field
}
async function openSingleRelationField(field: ObjectField, detail?: DC.ObjectDetail, targetType?: ObjectField['type']) {
  tab.value = detail ? 'details' : 'fields'
  await nextTick()
  const eligibleDetails = input.details.filter(item => item.binding?.source !== NC.ObjectSource.ADOPTED)
  const detailIndex = detail ? eligibleDetails.findIndex(item => detailKey(item) === detailKey(detail)) : -1
  const editor = detail ? detailFieldDesigners.value[detailIndex] : mainFieldDesigner.value
  if (!editor) {
    message.error('字段配置尚未加载，请重试')
    return
  }
  editor.openField(field, targetType)
}
function configureRelationRow(position: number) {
  const linked = input.relations[position]
  if (!linked) return
  const detail = linked.sourceDetailId
    ? input.details.find(item => detailKey(item) === linked.sourceDetailId)
    : undefined
  const field = (detail?.fields ?? input.draft.fields).find(
    item => !!linked.fieldId && (item.id === linked.fieldId || item.key === linked.fieldId)
  )
  if (
    field?.id &&
    field.type === NC.FieldType.REFERENCE &&
    !(detail?.fieldOptions ?? input.fieldOptions)[field.key]?.generated
  ) {
    void openSingleRelationField(field, detail)
    return
  }
  showRelation(position)
}
let targetRequest = 0
async function lookupTargets(name = '') {
  const request = ++targetRequest
  try {
    const result = await api.objects({ pageNo: 1, pageSize: 100, status: NC.ObjectStatus.ACTIVE, name })
    if (request === targetRequest) {
      targets.value = result.list
      result.list.forEach(row => {
        targetNames.value[row.id] = row.objectName
      })
    }
  } catch (cause) {
    if (request === targetRequest) relationError.value = errorMessage(cause)
  }
}
async function saveRelation() {
  if (!canEdit.value || relationSaving.value) return
  relationSaving.value = true
  try {
    // ① 转换复核：已有字段改成对象关系前，先展示旧列值与依赖影响，取消则不动草稿。
    if (pendingRelationField.value) {
      const source = pendingRelationField.value
      const next = { ...source }
      const scope = relation.sourceDetailId
        ? input.details.find(item => detailKey(item) === relation.sourceDetailId)
        : undefined
      const nextOptions = copyFieldOptions((scope?.fieldOptions ?? input.fieldOptions)[source.key])
      if (relation.kind !== NC.RelationType.MANY_TO_MANY) changeFieldType(next, nextOptions, NC.FieldType.REFERENCE)
      if (!(await reviewFieldSwitch(source, next, nextOptions, relation.sourceDetailId, relation.targetObjectId)))
        return
    }
    // ② 应用关系。从抽屉进入时先留快照：自动保存失败要撤回本次关系改动，窗口内容保留供用户修改后重试。
    const snapshot = relationFromDrawer.value ? (JSON.parse(JSON.stringify(input)) as DC.SaveDesign) : null
    relationError.value = applyRelation(input, relation, relationPosition.value, pendingRelationField.value) ?? ''
    if (relationError.value) return
    if (fieldRelationContext.value) tab.value = relation.sourceDetailId ? 'details' : 'fields'
    if (snapshot) {
      // ③ 关系保存后才生成真实引用列：自动保存一次对象草稿（只保存、不发布），④ 成功后回到该引用字段的抽屉。
      const previous = { error: error.value, tab: tab.value, expanded: basicExpanded.value }
      const saved = await save()
      if (!saved) {
        const reason = error.value || '保存未完成，请稍后重试'
        Object.assign(input, snapshot)
        error.value = previous.error
        tab.value = previous.tab
        basicExpanded.value = previous.expanded
        relationError.value = `自动保存对象草稿失败：${reason}`
        return
      }
      focusRelation(relation.code)
    }
  } finally {
    relationSaving.value = false
  }
  relationFromDrawer.value = false
  pendingRelationField.value = undefined
  relationOpen.value = false
}
function removeRelation(position: number) {
  if (!canEdit.value) return
  const linked = input.relations[position]
  if (!linked) return
  const detail = linked.sourceDetailId
    ? input.details.find(item => detailKey(item) === linked.sourceDetailId)
    : undefined
  const field = (detail?.fields ?? input.draft.fields).find(
    item => !!linked.fieldId && (item.id === linked.fieldId || item.key === linked.fieldId)
  )
  if (field?.type === NC.FieldType.REFERENCE && !(detail?.fieldOptions ?? input.fieldOptions)[field.key]?.generated) {
    void openSingleRelationField(field, detail, NC.FieldType.TEXT)
    return
  }
  Modal.confirm({
    title: `移除对象关系“${linked.name}”`,
    content: '这会从草稿移除关系。保存草稿不会更改业务数据；发布时仍会检查引用、索引及物理数据影响。',
    okText: '移除关系',
    cancelText: '取消',
    onOk: () => {
      if (input.relations[position] === linked) input.relations.splice(position, 1)
    }
  })
}
function showIndex(position = -1) {
  indexPosition.value = position
  indexError.value = ''
  Object.assign(
    index,
    position < 0
      ? { id: null, code: '', name: '', unique: false, fieldIds: [], parentScoped: false }
      : JSON.parse(JSON.stringify(input.indexes[position]))
  )
  indexOpen.value = true
}
function saveIndex() {
  if (!canEdit.value) return
  if (!index.name.trim() || !/^[a-z][a-z0-9_]{0,62}$/.test(index.code) || !index.fieldIds.length) {
    indexError.value = '请填写索引名称、编码并选择同一张表的字段'
    return
  }
  const value = { ...index, fieldIds: [...index.fieldIds] }
  if (indexPosition.value < 0) input.indexes.push(value)
  else input.indexes[indexPosition.value] = value
  indexOpen.value = false
}
async function planPublish() {
  if (!input.draft.id || input.draft.expectedLockVersion == null) return
  if (dirty.value) {
    message.warning('请先保存当前修改，再发布')
    return
  }
  try {
    plan.value = await api.plan({ id: input.draft.id, expectedLockVersion: input.draft.expectedLockVersion })
    if (!planOpen.value) reason.value = ''
    publishNeedsRecheck.value = false
    suspendApplicationsConfirmed.value = false
    publishError.value = ''
    planOpen.value = true
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
async function publish() {
  if (!plan.value || publishing.value) return
  if (publishNeedsRecheck.value) {
    await planPublish()
    return
  }
  if (plan.value.state !== NC.PublishState.PENDING) return
  // 仅点击含清空后果的最终发布按钮才生成本计划的清空授权；保存草稿不进入此路径。
  const clearFieldIds = conversionClearFieldIds(plan.value.conversions ?? [])
  publishError.value =
    conversionConfirmationError(
      plan.value.conversions || [],
      clearFieldIds,
      platform.hasPermission('nocode:object:manage') && platform.hasPermission('nocode:object:update')
    ) || ''
  if (!publishError.value)
    publishError.value =
      applicationUpgradeConfirmationError(
        applicationUpgrades.value,
        suspendApplicationsConfirmed.value,
        platform.hasPermission('nocode:app:manage')
      ) || ''
  if (publishError.value) return
  if (!reason.value.trim()) {
    message.warning('请填写发布原因')
    return
  }
  publishing.value = true
  const clearedColumns = clearTargets.value.length
  const clearedValues = clearValueCount.value
  try {
    const paused = [...applicationUpgrades.value]
    const execution = await api.execute(
      plan.value.id,
      reason.value.trim(),
      clearFieldIds,
      paused.map(app => app.applicationId)
    )
    if (execution.state === NC.PublishState.FAILED) throw new Error(execution.error || '发布失败，请重新检查后再发布')
    planOpen.value = false
    await load()
    // 应用自动跟随的结果只作提示：查不到、查询失败都不影响「对象已发布」的结论。
    const followPlanId = plan.value?.id
    if (followPlanId) await announceFollowResult(() => api.followResult(followPlanId), message)
    if (paused.length)
      Modal.success({
        title: '对象已发布，受影响应用已停用',
        content: h('div', [
          h('p', '请分别同步对象版本、调整配置并发布启用；不需要等待其他应用。'),
          ...paused.map(app =>
            h('p', { key: app.applicationId }, [
              h(
                'a',
                {
                  onClick: () => window.open(router.resolve(app.route).href, '_blank', 'noopener')
                },
                app.applicationName
              )
            ])
          )
        ])
      })
    message.success(
      clearedColumns ? `对象结构已发布，已清空 ${clearedColumns} 列共 ${clearedValues} 个字段值` : '对象结构已发布'
    )
  } catch (cause) {
    publishError.value = errorMessage(cause)
    publishNeedsRecheck.value = true
    suspendApplicationsConfirmed.value = false
    if (input.draft.id) histories.value = await api.history(input.draft.id)
  } finally {
    publishing.value = false
  }
}
function handleConversionImpact(route: string) {
  planOpen.value = false
  void router.push(route)
}
function handleDraftImpact(route: string) {
  window.open(route, '_blank', 'noopener')
}
function maintainObjectData(value: { fieldId: string; recordIds?: string[] }, detailId?: string | null) {
  if (!input.draft.id) return
  const destination = router.resolve({
    path: '/nocode/object/editor',
    query: {
      id: input.draft.id,
      tab: 'data',
      fieldId: value.fieldId,
      recordIds: value.recordIds?.join(',') || undefined,
      detailId: detailId || undefined
    }
  })
  window.open(destination.href, '_blank', 'noopener')
}
async function reviewFieldOperation(
  field: ObjectField,
  operation: 'disable_field' | 'restore_field',
  detailId?: string | null
) {
  if (
    !canEdit.value ||
    !field.id ||
    !input.draft.id ||
    input.draft.expectedLockVersion == null ||
    !operationReview.value
  )
    return false
  return operationReview.value.review(
    {
      objectId: input.draft.id,
      expectedLockVersion: input.draft.expectedLockVersion,
      operation,
      fieldId: field.id,
      detailId,
      proposed: JSON.parse(JSON.stringify(input)) as DC.SaveDesign
    },
    field.name
  )
}
function showPublishDependencies() {
  planOpen.value = false
  tab.value = 'dependencies'
}
function publishCheckHelp(check: DC.StructureCheck) {
  if (check.code === 'APPLICATION_CONTRACT')
    return {
      tab: 'dependencies',
      label: '查看应用使用来源',
      message: '应用中心负责显式同步对象版本和调整资源，再单独发布应用。这里不会自动修改已发布应用。'
    }
  if (check.code === 'INDEX_FIELDS' || (check.code === 'DUPLICATES' && check.message.includes('组合唯一索引')))
    return {
      tab: 'indexes',
      label: '查看组合索引配置',
      message: '根据上方索引名称检查字段及唯一范围；重复记录需通过授权业务入口修正。调整唯一范围不会自动删除重复记录。'
    }
  if (['TARGET_KEY', 'TARGET_UNPUBLISHED', 'RELATION_TABLE'].includes(check.code))
    return {
      tab: 'relations',
      label: '查看对象关系配置',
      message: '根据上方关系名称核对目标对象、主键与历史引用。关系配置不会自动创建缺失的目标业务记录。'
    }
  if (['STRUCTURE_DRIFT', 'TABLE_MISSING', 'COLUMN_REMOVED', 'ADOPTION_CHANGED'].includes(check.code))
    return {
      tab: 'versions',
      label: '查看结构核对与同步',
      message: '先核对实际表与已发布基线。仅受支持且通过预检的差异可同步；其他变化需由原表负责人处理后重新检查。'
    }
  if (['BUSINESS_FILE_DEFAULT', 'BUSINESS_FILE_SESSIONS', 'BUSINESS_FILE_SCOPE'].includes(check.code))
    return {
      tab: 'business-file',
      label: '查看业务文件配置',
      message:
        check.code === 'BUSINESS_FILE_DEFAULT'
          ? '参与字段配置了文件默认值，不能接入业务网盘：请改用普通附件，或移除默认附件后重新检查。'
          : '在途上传会随发布按新规则绑定；规则变更只影响新增记录及首次建立归属的旧记录。'
    }
  return null
}
function handlePublishCheck(check: DC.StructureCheck) {
  const help = publishCheckHelp(check)
  if (!help) return
  planOpen.value = false
  tab.value = help.tab
}
async function showVersion(version: number) {
  if (!input.draft.id) return
  try {
    versionBody.value = JSON.stringify(await api.version(input.draft.id, version), null, 2)
    versionOpen.value = true
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
async function verify() {
  if (!input.draft.id) return
  try {
    const checks = await api.verify(input.draft.id)
    if (!checks.length) message.success('当前物理结构与发布基线一致')
    else {
      error.value = checks.map(c => c.message).join('；')
      tab.value = 'versions'
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
async function showReconcile() {
  if (!input.draft.id || dirty.value) {
    message.warning('请先保存修改')
    return
  }
  try {
    reconcile.value = await api.reconcilePreview(input.draft.id)
    reason.value = ''
    reconcileOpen.value = true
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
async function applyReconcile() {
  if (!reconcile.value) return
  if (!reason.value.trim()) {
    message.warning('请填写结构同步原因')
    return
  }
  publishing.value = true
  try {
    accept(
      await api.reconcile({
        id: reconcile.value.id,
        expectedLockVersion: reconcile.value.revision,
        fingerprint: reconcile.value.fingerprint,
        reason: reason.value
      })
    )
    reconcileOpen.value = false
    message.success('差异已纳入草稿，请核对后发布')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    publishing.value = false
  }
}
async function lookupUsers(value = '') {
  try {
    users.value = await platform.directory.users(value)
  } catch {
    /* 底座客户端已提示权限或网络问题。 */
  }
}
function leaveWarning(event: BeforeUnloadEvent) {
  if (dirty.value || dataEditing.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
function confirmLeave() {
  if (saving.value && dirty.value) {
    message.info('正在保存对象草稿，请稍候再离开')
    return false
  }
  if (!dirty.value && !dataEditing.value) return true
  return new Promise<boolean>(resolve =>
    Modal.confirm({
      title: '离开对象设计？',
      content: dataEditing.value ? '对象数据中尚未保存的行编辑将丢失。' : '尚未保存的修改将丢失。',
      okText: '离开',
      cancelText: '继续编辑',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  )
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => (to.query.id !== from.query.id ? confirmLeave() : true))
watch(
  () => route.query.id,
  id => {
    if (route.path === '/nocode/object/editor' && id !== input.draft.id) void load()
  }
)
onMounted(() => {
  void load()
  window.addEventListener('beforeunload', leaveWarning)
})
onBeforeUnmount(() => {
  loadGeneration++
  saveSession.invalidate()
  bindingSession.invalidate()
  bindingSearch++
  window.removeEventListener('beforeunload', leaveWarning)
})
async function loadSettings() {
  void lookupUsers()
  try {
    departments.value = await platform.directory.departments()
  } catch {
    /* 由底座客户端提示。 */
  }
}
watch(tab, value => {
  if (value === 'settings') void loadSettings()
})
// 每个弹窗独立保留底座的弹窗/抽屉/全屏展示状态。
const modalModes = ref<DisplayMode[]>(Array(5).fill('modal'))
</script>

<template>
  <section class="object-editor">
    <header class="editor-header">
      <div class="editor-toolbar">
        <div class="editor-identity">
          <a-button
            :aria-label="route.query.returnApp ? '返回应用配置' : '返回对象列表'"
            @click="
              router.push(
                route.query.returnApp
                  ? { path: '/nocode-app/workspace', query: { id: String(route.query.returnApp) } }
                  : '/nocode/object'
              )
            "
          >
            <ArrowLeftOutlined />
            {{ route.query.returnApp ? '返回应用' : '' }}
          </a-button>
          <div class="editor-title">
            <h2 :title="loaded ? input.draft.objectName : undefined">
              {{
                loaded
                  ? input.draft.objectName || '新建数据对象'
                  : route.query.id
                    ? loading
                      ? '正在加载数据对象'
                      : '数据对象未加载成功'
                    : '新建数据对象'
              }}
            </h2>
            <span v-if="loaded && design" class="object-source">{{ label(design.source) }}</span>
            <a-tag v-if="loaded && design" :color="design.draft.state === NC.VersionState.PUBLISHED ? 'green' : 'blue'">
              V{{ design.draft.versionNo }} {{ label(design.draft.state) }}
            </a-tag>
          </div>
        </div>
        <a-space class="editor-actions" wrap>
          <a-tag v-if="loaded && dirty" color="orange">未保存</a-tag>
          <a-button
            v-if="canEdit"
            type="text"
            :aria-expanded="basicExpanded"
            aria-controls="object-basic-form"
            @click="basicExpanded = !basicExpanded"
          >
            <UpOutlined v-if="basicExpanded" />
            <EditOutlined v-else />
            {{ basicExpanded ? '收起基本信息' : '编辑基本信息' }}
          </a-button>
          <a-button
            v-if="
              loaded &&
              !loading &&
              design?.draft.state === NC.VersionState.PUBLISHED &&
              design.status === NC.ObjectStatus.ACTIVE &&
              platform.hasPermission('nocode:object:update')
            "
            @click="editPublished"
          >
            编辑新草稿
          </a-button>
          <a-button v-if="canEdit || saving" type="primary" :loading="saving" @click="save">
            <SaveOutlined />
            保存草稿
          </a-button>
          <a-button v-if="canEdit && input.draft.id" :disabled="dirty" @click="selectionMigrationOpen = true">
            检查选择字段转换
          </a-button>
          <a-button
            v-if="
              loaded &&
              !loading &&
              design?.draft.state === NC.VersionState.DRAFT &&
              design.status !== NC.ObjectStatus.DISABLED &&
              platform.hasPermission('nocode:object:publish')
            "
            :disabled="dirty"
            @click="planPublish"
          >
            <SendOutlined />
            发布
          </a-button>
        </a-space>
      </div>
      <dl v-if="loaded && !(canEdit && basicExpanded)" class="object-summary">
        <div>
          <dt>对象编码</dt>
          <dd :title="input.draft.objectCode">{{ input.draft.objectCode || '未设置' }}</dd>
        </div>
        <div>
          <dt>主表</dt>
          <dd :title="input.draft.tableName">{{ input.draft.tableName || '未设置' }}</dd>
        </div>
        <div>
          <dt>记录标题</dt>
          <dd :title="titleFieldName">{{ titleFieldName }}</dd>
        </div>
      </dl>
    </header>
    <a-result v-if="!canQuery" status="403" title="暂无数据对象访问权限" />
    <template v-else>
      <a-alert
        v-if="error && loaded"
        type="error"
        show-icon
        :message="error"
        closable
        class="notice"
        @close="error = ''"
      />
      <a-result
        v-if="!loaded && !loading"
        status="warning"
        title="数据对象未加载成功"
        :sub-title="error || '请重新加载后再配置对象。'"
      >
        <template #extra><a-button type="primary" @click="load">重新加载</a-button></template>
      </a-result>
      <a-spin v-if="!loaded && loading" :spinning="true" tip="正在读取对象配置" class="object-load-spinner" />
      <a-alert
        v-if="loaded && design?.readOnly"
        type="warning"
        show-icon
        message="该已有表按只读能力纳管，发布会保留原有表结构。"
        class="notice"
      />
      <a-spin
        v-if="loaded"
        :spinning="loading || saving"
        :tip="saving ? '正在保存对象草稿，请稍候继续编辑' : undefined"
        wrapper-class-name="editor-body"
      >
        <a-card v-if="canEdit && basicExpanded" id="object-basic-form" class="basic-card">
          <a-form layout="vertical" :disabled="!canEdit">
            <a-row :gutter="20">
              <a-col :xs="24" :sm="12" :xl="6">
                <a-form-item label="对象名称" required>
                  <a-input v-model:value="input.draft.objectName" aria-label="对象名称" :maxlength="128" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12" :xl="6">
                <a-form-item label="对象编码" required>
                  <a-input
                    :value="input.draft.objectCode"
                    aria-label="对象编码"
                    :disabled="nameLocked"
                    :maxlength="64"
                    placeholder="例如：公司 → object_gs"
                    @update:value="codeSuggestion.changeCode"
                  />
                  <p v-if="nameLocked" class="field-hint">
                    对象编码用于稳定引用；已发布或纳管后保留。对象名称仍可调整。
                  </p>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12" :xl="8">
                <a-form-item label="主表名称" required>
                  <a-input
                    v-model:value="input.draft.tableName"
                    aria-label="主表名称"
                    :disabled="nameLocked"
                    :maxlength="63"
                  />
                  <p v-if="nameLocked" class="field-hint">
                    主表身份已固定。纳管结构变化请通过“对象设置”的表绑定检查及结构核对处理。
                  </p>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12" :xl="4">
                <a-form-item label="记录标题" required extra="用于标识本对象的记录，如资产名称；对象引用单独配置。">
                  <a-select
                    v-model:value="input.draft.titleFieldKey"
                    aria-label="记录标题"
                    placeholder="请选择记录标题字段"
                    :options="titleFields.map(f => ({ label: f.name || f.code, value: f.key }))"
                  />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12" :xl="6">
                <a-form-item label="数据对象分类">
                  <CategoryInput
                    v-model="input.draft.category"
                    label="数据对象分类"
                    :load-categories="api.categories"
                    :disabled="!canEdit"
                  />
                </a-form-item>
              </a-col>
            </a-row>
          </a-form>
        </a-card>
        <a-card class="object-content-card">
          <a-tabs v-model:active-key="tab" class="object-tabs">
            <a-tab-pane key="fields" tab="主表字段">
              <FieldDesigner
                ref="mainFieldDesigner"
                v-model="input.draft.fields"
                v-model:options="input.fieldOptions"
                :relations="sourceRelations()"
                :details="input.details"
                :object-id="input.draft.id"
                :legacy-auto-number-ids="legacyAutoNumberIds"
                :published-baselines="publishedFieldBaselines"
                :read-only="!canEdit"
                :adopted="adopted"
                :revision="`${design?.draft.id}:${design?.draft.lockVersion}:${design?.draft.versionNo}`"
                :load-inactive="input.draft.id ? () => api.inactiveFields(input.draft.id!) : undefined"
                :review-operation="(field, operation) => reviewFieldOperation(field, operation)"
                :preview-switch="
                  (source, target, options, targetId) =>
                    previewFieldSwitch(source, target, options, undefined, targetId)
                "
                :preview-rows="
                  (source, target, options, targetId, page) =>
                    previewFieldSwitchRows(source, target, options, targetId, page)
                "
                :relation-targets="targetOptions"
                :load-relation-targets="lookupTargets"
                :can-view-conversion-rows="
                  platform.hasPermission('nocode:table:query') && platform.hasPermission('nocode:table:preview')
                "
                :can-maintain-data="canQuery && platform.hasPermission('nocode:object:manage')"
                :can-clear-column="
                  platform.hasPermission('nocode:object:manage') && platform.hasPermission('nocode:object:update')
                "
                :apply-conversion="
                  (source, target, options, targetId) => applyFieldConversion(source, target, options, targetId)
                "
                :focus-relation="relationFocus"
                @remove="removeField"
                @restore="restoreField"
                @relation="(field, origin) => fieldRelation(field, undefined, origin)"
                @configure-relation="saveThenConfigureRelation"
                @relation-focused="relationFocus = null"
                @new-relation="startSeparateRelation()"
                @navigate="handleDraftImpact"
                @maintain-data="maintainObjectData"
              />
            </a-tab-pane>
            <a-tab-pane key="data" tab="对象数据">
              <ObjectDataGrid
                v-if="input.draft.id"
                :object-id="input.draft.id"
                :published-version="design?.publishedVersion"
                :field-id="dataFieldId"
                :detail-id="dataDetailId"
                :record-ids="dataRecordIds"
                @editing="dataEditing = $event"
              />
              <a-empty v-else description="保存并首次发布对象后，即可查看和维护对象数据" />
            </a-tab-pane>
            <a-tab-pane key="system-fields" tab="系统字段">
              <SystemFieldsPanel :design="input" />
            </a-tab-pane>
            <a-tab-pane key="details" :tab="`内部明细（${input.details.length}）`" class="object-tab-scroll">
              <div class="section-toolbar">
                <p>在对象内直接设计明细，与主表使用同一版本，统一发布。</p>
                <a-space v-if="canEdit">
                  <a-button @click="addDetail">
                    <PlusOutlined />
                    新建明细表
                  </a-button>
                  <a-button
                    v-if="
                      platform.hasPermission('nocode:table:query') &&
                      platform.hasPermission('nocode:object:adopt') &&
                      platform.hasPermission('nocode:object:create')
                    "
                    @click="showBindDetail"
                  >
                    <PlusOutlined />
                    绑定已有明细表
                  </a-button>
                </a-space>
              </div>
              <a-empty v-if="!input.details.length" description="尚未配置内部明细" />
              <a-collapse v-else v-model:active-key="detailPanels">
                <a-collapse-panel
                  v-for="(detail, position) in input.details"
                  :key="detail.id || position"
                  :header="`${detail.name} · ${detail.code}`"
                >
                  <a-form layout="vertical" :disabled="!canEdit">
                    <a-row :gutter="16">
                      <a-col :span="6">
                        <a-form-item label="明细名称"><a-input v-model:value="detail.name" /></a-form-item>
                      </a-col>
                      <a-col :span="5">
                        <a-form-item label="明细编码">
                          <a-input
                            :value="detail.code"
                            :disabled="detailIdentityLocked(detail)"
                            @update:value="changeDetailCode(detail, $event)"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="9">
                        <a-form-item label="明细表名">
                          <a-input
                            v-model:value="detail.tableName"
                            :disabled="detailIdentityLocked(detail)"
                            :maxlength="63"
                          />
                          <p v-if="!detailIdentityLocked(detail)" class="field-hint">
                            首次发布前仅调整草稿候选表名，不创建数据表。
                          </p>
                        </a-form-item>
                      </a-col>
                      <a-col :span="4">
                        <a-form-item label="状态">
                          <a-select
                            v-model:value="detail.state"
                            :options="[
                              { value: NC.MemberState.ACTIVE, label: '启用' },
                              { value: NC.MemberState.INACTIVE, label: '停用（保留原数据）' }
                            ]"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>
                  </a-form>
                  <a-button
                    v-if="canEdit && detail.state === NC.MemberState.ACTIVE"
                    class="notice"
                    @click="showRelation(-1, detail)"
                  >
                    为每行添加关联选择（如商品）
                  </a-button>
                  <p class="muted">
                    明细属于本单据；关联选择使用已有独立资料，不会把资料变成明细。删除本单据会处理明细，删除被引用的商品则按关系规则检查。
                  </p>
                  <TableBindingPanel
                    v-if="detail.binding?.source === NC.ObjectSource.ADOPTED"
                    v-model="detail.binding"
                    :table-name="detail.tableName"
                    :disabled="!canEdit"
                  />
                  <a-alert
                    v-if="!detail.id && detail.binding?.source === NC.ObjectSource.ADOPTED"
                    type="info"
                    show-icon
                    class="notice"
                    message="保存草稿后从实际表导入字段映射。主键和父键在发布预览时再次核验。"
                  />
                  <FieldDesigner
                    v-else
                    ref="detailFieldDesigners"
                    v-model="detail.fields"
                    v-model:options="detail.fieldOptions"
                    :legacy-auto-number-ids="legacyAutoNumberIds"
                    :published-baselines="publishedFieldBaselines"
                    :read-only="!canEdit || detail.state !== NC.MemberState.ACTIVE"
                    :adopted="detail.binding?.source === NC.ObjectSource.ADOPTED"
                    :relations="sourceRelations(detail)"
                    :master="{ fields: input.draft.fields, relations: sourceRelations() }"
                    :object-id="input.draft.id"
                    :revision="`${design?.draft.id}:${design?.draft.lockVersion}:${design?.draft.versionNo}:${detail.id}`"
                    :review-operation="(field, operation) => reviewFieldOperation(field, operation, detail.id)"
                    :load-inactive="
                      input.draft.id && detail.id ? () => api.inactiveFields(input.draft.id!, detail.id!) : undefined
                    "
                    :preview-switch="
                      (source, target, options, targetId) =>
                        previewFieldSwitch(source, target, options, detail.id, targetId)
                    "
                    :preview-rows="
                      (source, target, options, targetId, page) =>
                        previewFieldSwitchRows(source, target, options, targetId, page, detail.id)
                    "
                    :relation-targets="targetOptions"
                    :load-relation-targets="lookupTargets"
                    :can-view-conversion-rows="
                      platform.hasPermission('nocode:table:query') && platform.hasPermission('nocode:table:preview')
                    "
                    :can-maintain-data="canQuery && platform.hasPermission('nocode:object:manage')"
                    :can-clear-column="
                      platform.hasPermission('nocode:object:manage') && platform.hasPermission('nocode:object:update')
                    "
                    :apply-conversion="
                      (source, target, options, targetId) =>
                        applyFieldConversion(source, target, options, targetId, detail)
                    "
                    :focus-relation="relationFocus"
                    @remove="cancelFieldRestore"
                    @restore="restoreField"
                    @relation="(field, origin) => fieldRelation(field, detail, origin)"
                    @configure-relation="saveThenConfigureRelation"
                    @relation-focused="relationFocus = null"
                    @new-relation="startSeparateRelation(detail)"
                    @navigate="handleDraftImpact"
                    @maintain-data="value => maintainObjectData(value, detail.id)"
                    detail
                  />
                </a-collapse-panel>
              </a-collapse>
            </a-tab-pane>
            <a-tab-pane key="relations" :tab="`对象关系（${input.relations.length}）`" class="object-tab-scroll">
              <OsTablePage
                class="nocode-embedded-table"
                show-column-settings
                column-settings-key="nocode-object-relations"
                resizable
                :scroll="{ x: 'max-content', y: '100%' }"
                :data-source="input.relations"
                :pagination="false"
                row-key="code"
                :columns="[
                  { title: '关系名称', key: 'name', dataIndex: 'name', width: 200, ellipsis: true },
                  { title: '关系类型', key: 'kind', width: 150 },
                  { title: '来源', key: 'source', width: 160 },
                  { title: '目标对象', key: 'targetObjectId', dataIndex: 'targetObjectId', width: 180 },
                  { title: '删除规则', key: 'delete', width: 180 },
                  { title: '操作', key: 'actions', width: 220, fixed: 'right' }
                ]"
              >
                <template #actions>
                  <a-button v-if="canEdit" @click="showRelation()">
                    <PlusOutlined />
                    新增关系
                  </a-button>
                </template>
                <template #bodyCell="{ column, record, index: rowIndex }">
                  <template v-if="column.key === 'source'">
                    {{ sourceOptions.find(s => s.value === (record.sourceDetailId || ''))?.label || '来源已移除' }}
                  </template>
                  <template v-if="column.key === 'targetObjectId'">
                    {{ targetNames[record.targetObjectId] || `目标对象 ${record.targetObjectId}` }}
                  </template>
                  <template v-if="column.key === 'kind'">{{ label(record.kind) }}</template>
                  <template v-if="column.key === 'delete'">
                    {{
                      { RESTRICT: '阻止删除', CASCADE: '级联删除明细', SET_NULL: '清空引用' }[
                        record.onDelete as string
                      ] || record.onDelete
                    }}
                  </template>
                  <template v-if="column.key === 'actions'">
                    <div class="nocode-table-actions">
                      <a-button type="link" v-if="canEdit" @click="configureRelationRow(rowIndex)">
                        <EditOutlined />
                        配置
                      </a-button>
                      <a-button type="link" danger v-if="canEdit" @click="removeRelation(rowIndex)">
                        <DeleteOutlined />
                        移除关系
                      </a-button>
                    </div>
                  </template>
                </template>
              </OsTablePage>
              <ObjectRelationOverview
                :name="input.draft.objectName"
                :details="input.details"
                :relations="input.relations"
                :target-names="targetNames"
              />
            </a-tab-pane>
            <a-tab-pane key="indexes" :tab="`索引（${input.indexes.length}）`">
              <OsTablePage
                class="nocode-embedded-table"
                show-column-settings
                column-settings-key="nocode-object-indexes"
                resizable
                :scroll="{ x: 'max-content', y: '100%' }"
                :data-source="input.indexes"
                :pagination="false"
                row-key="code"
                :columns="[
                  { title: '名称', key: 'name', dataIndex: 'name', width: 180, ellipsis: true },
                  { title: '编码', key: 'code', dataIndex: 'code', width: 200, ellipsis: true },
                  { title: '字段', key: 'fields', width: 200, ellipsis: true },
                  { title: '唯一', key: 'unique', width: 90 },
                  { title: '范围', key: 'scope', width: 130 },
                  { title: '操作', key: 'actions', width: 180, fixed: 'right' }
                ]"
              >
                <template #actions>
                  <a-button v-if="canEdit" @click="showIndex()">
                    <PlusOutlined />
                    新增索引
                  </a-button>
                </template>
                <template #bodyCell="{ column, record, index: rowIndex }">
                  <template v-if="column.key === 'fields'">
                    {{
                      record.fieldIds.map((id: string) => allFields.find(f => f.value === id)?.label || id).join('、')
                    }}
                  </template>
                  <template v-if="column.key === 'unique'">{{ record.unique ? '是' : '否' }}</template>
                  <template v-if="column.key === 'scope'">{{ record.parentScoped ? '同一主记录' : '整张表' }}</template>
                  <template v-if="column.key === 'actions' && canEdit">
                    <div class="nocode-table-actions">
                      <a-button type="link" @click="showIndex(rowIndex)">
                        <EditOutlined />
                        配置
                      </a-button>
                      <a-button type="link" danger @click="input.indexes.splice(rowIndex, 1)">
                        <DeleteOutlined />
                        移除
                      </a-button>
                    </div>
                  </template>
                </template>
              </OsTablePage>
            </a-tab-pane>
            <a-tab-pane key="document-policy" tab="整单规则与状态" class="object-tab-scroll">
              <DocumentPolicyDesigner v-model="input.settings.documentPolicy" :design="input" :disabled="!canEdit" />
            </a-tab-pane>
            <a-tab-pane key="business-file" tab="业务文件" class="object-tab-scroll">
              <BusinessFilePolicyDesigner
                v-model="input.settings.businessFilePolicy"
                :design="input"
                :disabled="!canEdit"
              />
              <RecordFolderDesigner
                :object-id="input.draft.id ?? undefined"
                :can-manage="canQuery && platform.hasPermission('nocode:object:manage')"
              />
            </a-tab-pane>
            <a-tab-pane key="settings" tab="对象设置" class="object-tab-scroll">
              <TableBindingPanel
                v-if="adopted && input.mainBinding"
                v-model="input.mainBinding"
                :table-name="input.draft.tableName"
                :disabled="!canEdit"
              />
              <a-form layout="vertical" :disabled="!canEdit">
                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="负责人">
                      <a-select
                        v-model:value="input.settings.ownerId"
                        show-search
                        allow-clear
                        :filter-option="false"
                        :options="users"
                        placeholder="选择底座用户"
                        @search="lookupUsers"
                      />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="所属部门">
                      <a-tree-select
                        v-model:value="input.settings.organizationId"
                        allow-clear
                        :tree-data="departments"
                        :field-names="{ label: 'deptName', value: 'id', children: 'children' }"
                        placeholder="选择底座部门"
                      />
                    </a-form-item>
                  </a-col>
                </a-row>
                <a-form-item label="标题模板">
                  <a-input
                    v-model:value="input.settings.titleTemplate"
                    allow-clear
                    :maxlength="512"
                    placeholder="留空使用记录标题字段；使用字段编码，如 {{c_name}} · {{c_code}}"
                  />
                </a-form-item>
                <a-form-item label="图标">
                  <a-input v-model:value="input.settings.icon" placeholder="底座图标名称，可留空" :maxlength="80" />
                </a-form-item>
                <a-form-item label="说明">
                  <a-textarea v-model:value="input.draft.description" :rows="3" :maxlength="1000" />
                </a-form-item>
              </a-form>
            </a-tab-pane>
            <a-tab-pane key="versions" tab="版本与发布" class="object-tab-scroll">
              <div class="section-toolbar">
                <span>当前发布版本：{{ design?.publishedVersion ? `V${design.publishedVersion}` : '尚未发布' }}</span>
                <a-space>
                  <a-button v-if="design?.publishedVersion" @click="verify">重新核验结构</a-button>
                  <a-button
                    v-if="design?.publishedVersion && platform.hasPermission('nocode:object:publish')"
                    @click="showReconcile"
                  >
                    同步物理差异
                  </a-button>
                </a-space>
              </div>
              <OsTablePage
                title="对象版本"
                class="nocode-embedded-table"
                show-column-settings
                column-settings-key="nocode-object-versions"
                resizable
                :scroll="{ x: 'max-content', y: '100%' }"
                :data-source="design?.versions || []"
                :pagination="false"
                row-key="versionNo"
                :columns="[
                  { title: '版本', key: 'versionNo', dataIndex: 'versionNo', width: 90 },
                  { title: '状态', key: 'state', width: 130 },
                  { title: '创建时间', key: 'createdAt', dataIndex: 'createdAt', width: 180 },
                  { title: '发布时间', key: 'publishedAt', dataIndex: 'publishedAt', width: 180 },
                  { title: '操作', key: 'actions', width: 180, fixed: 'right' }
                ]"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">{{ label(record.state) }}</template>
                  <template v-if="column.key === 'createdAt' || column.key === 'publishedAt'">
                    {{ formatDateTime(record[column.key]) }}
                  </template>
                  <div v-if="column.key === 'actions'" class="nocode-table-actions">
                    <a-button type="link" @click="showVersion(record.versionNo)">
                      <EyeOutlined />
                      查看版本快照
                    </a-button>
                  </div>
                </template>
              </OsTablePage>
              <OsTablePage
                title="发布记录"
                class="nocode-embedded-table publication-history"
                show-column-settings
                column-settings-key="nocode-object-publications"
                resizable
                :scroll="{ x: 'max-content', y: '100%' }"
                :data-source="histories"
                :pagination="historyPagination"
                @change="(page: { current: number; pageSize: number }) => Object.assign(historyPagination, page)"
                row-key="id"
                :columns="[
                  { title: '版本', key: 'versionNo', dataIndex: 'versionNo', width: 90 },
                  { title: '结果', key: 'state', width: 130 },
                  { title: '原因', key: 'reason', dataIndex: 'reason', width: 240, ellipsis: true },
                  { title: '错误说明', key: 'error', dataIndex: 'error', width: 240, ellipsis: true },
                  { title: '执行时间', key: 'executedAt', dataIndex: 'executedAt', width: 180 }
                ]"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'executedAt'">{{ formatDateTime(record.executedAt) }}</template>
                  <a-tag
                    v-if="column.key === 'state'"
                    :color="
                      record.state === NC.PublishState.SUCCEEDED
                        ? 'green'
                        : record.state === NC.PublishState.FAILED
                          ? 'red'
                          : 'default'
                    "
                  >
                    {{ label(record.state) }}
                  </a-tag>
                </template>
              </OsTablePage>
            </a-tab-pane>
            <a-tab-pane
              v-if="design?.publishedVersion && platform.hasPermission('nocode:object:share')"
              key="sharing"
              tab="应用共享授权"
              class="object-tab-scroll"
            >
              <ObjectSharingPanel :object-id="design.draft.id" compact />
            </a-tab-pane>
            <a-tab-pane key="dependencies" tab="被引用情况" class="object-tab-scroll">
              <a-alert
                type="info"
                show-icon
                class="notice"
                message="引用登记与具体阻断会分别展示"
                description="这里列出对象的使用来源。字段变更、停用和发布会按本次操作检查具体影响；存在应用引用不会一概禁止兼容变更。应用配置由应用中心显式调整和发布。"
              />
              <a-empty v-if="!design?.dependencies.length" description="当前没有其他对象或已登记资源引用" />
              <a-list v-else :data-source="design.dependencies">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta :title="item.sourceName" :description="objectDependencyPresentation(item).label">
                      <template #description>
                        <p>{{ objectDependencyPresentation(item).label }}</p>
                        <p>{{ objectDependencyPresentation(item).description }}</p>
                        <p>
                          登记字段（{{ item.fieldIds.length }} 个）：{{
                            dependencyFieldNames(item.fieldIds) || '对象级引用'
                          }}
                        </p>
                      </template>
                    </a-list-item-meta>
                    <template #actions>
                      <a-button
                        v-if="objectDependencyPresentation(item).route"
                        type="link"
                        @click="handleDraftImpact(objectDependencyPresentation(item).route!)"
                      >
                        新标签页打开应用配置
                      </a-button>
                    </template>
                  </a-list-item>
                </template>
              </a-list>
            </a-tab-pane>
          </a-tabs>
        </a-card>
      </a-spin>
    </template>
    <OsModalForm
      :open="bindDetailOpen"
      title="绑定已有明细表"
      :width="760"
      :loading="bindingLoading"
      :disabled="!canEdit"
      :display-mode="bindDisplayMode"
      @display-mode-change="bindDisplayMode = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      @ok="addBoundDetail"
      @cancel="closeBindDetail"
    >
      <template #formItems>
        <a-alert v-if="bindDetailError" type="error" :message="bindDetailError" class="notice" />
        <a-form-item label="明细名称" required><a-input v-model:value="detailCandidate.name" /></a-form-item>
        <a-form-item label="明细编码" required><a-input v-model:value="detailCandidate.code" /></a-form-item>
        <a-form-item label="Schema" required>
          <a-select
            v-model:value="detailCandidate.binding.schemaName"
            :options="bindingSchemas.map(value => ({ value, label: value }))"
            @change="changeBindingSchema"
          />
        </a-form-item>
        <a-form-item label="已有明细表" required>
          <a-select
            v-model:value="detailCandidate.tableName"
            show-search
            :filter-option="false"
            :options="
              bindingTables.map(t => ({
                value: t.tableName,
                label: `${t.tableName}${t.comment ? ' · ' + t.comment : ''}`
              }))
            "
            @search="lookupBindingTables"
            @change="selectBindingTable"
            placeholder="搜索当前数据库中尚未绑定的业务表"
          />
        </a-form-item>
        <a-spin :spinning="bindingLoading">
          <template v-if="bindingPreflight">
            <a-alert
              v-for="(check, position) in bindingPreflight.checks"
              :key="position"
              :type="check.blocking ? 'error' : 'warning'"
              :message="check.message"
              class="notice"
            />
            <a-form-item label="关联主表的父键列" required>
              <a-auto-complete
                v-model:value="detailCandidate.binding.parentColumn"
                :options="
                  bindingPreflight.structure.columns
                    .filter(
                      c =>
                        !c.primaryKeyPosition &&
                        !['creator', 'create_time', 'updater', 'update_time', 'deleted'].includes(c.name)
                    )
                    .map(c => ({ value: c.name, label: `${c.name} · ${c.nativeType}` }))
                "
                placeholder="选择已有列；管理结构时也可输入待新增列名"
              />
              <p class="muted">父键保存主表的真实主键。已有明细必须具有明确归属，平台不会猜测历史数据的父记录。</p>
            </a-form-item>
            <TableBindingPanel v-model="detailCandidate.binding" :table-name="detailCandidate.tableName" />
          </template>
        </a-spin>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="relationOpen"
      :disabled="!canEdit"
      :loading="relationSaving"
      title="对象关系"
      :width="680"
      @ok="saveRelation"
      @cancel="relationOpen = false"
      :display-mode="modalModes[0]"
      @display-mode-change="modalModes[0] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-alert v-if="relationError" type="error" :message="relationError" class="notice" />
        <a-alert
          v-if="identityLocked('relations', relation.id)"
          type="info"
          show-icon
          class="notice"
          message="关系的已部署身份保持稳定"
          description="编码、所属表和关系结构用于现有数据关联。单值对象引用可从字段配置调整类型或目标并检查影响；其他结构迁移请新增目标关系，再处理原引用。名称及允许编辑的规则仍可调整。"
        />

        <a-form-item label="关系名称" required>
          <a-input v-model:value="relation.name" />
        </a-form-item>
        <a-form-item label="关系编码" required>
          <a-input v-model:value="relation.code" :disabled="identityLocked('relations', relation.id)" />
        </a-form-item>

        <a-form-item label="在哪里选择关联记录" required>
          <a-select
            v-model:value="relation.sourceDetailId"
            :disabled="identityLocked('relations', relation.id) || fieldRelationContext"
            :options="sourceOptions"
            @change="sourceChanged"
          />
        </a-form-item>
        <a-form-item v-if="fieldRelationContext" label="字段类型">
          <a-tag color="blue">{{ relationFieldLabel(relation) }}</a-tag>
        </a-form-item>
        <a-form-item v-else label="这些记录如何关联">
          <a-select
            v-model:value="relation.kind"
            :disabled="identityLocked('relations', relation.id) || !!pendingRelationField || !!relation.sourceDetailId"
            :options="
              [
                NC.RelationType.REFERENCE,
                NC.RelationType.MASTER_DETAIL,
                NC.RelationType.ONE_TO_ONE,
                NC.RelationType.MANY_TO_MANY
              ].map(value => ({
                value,
                label: label(value)
              }))
            "
          >
            <template #option="item">
              <div class="relation-kind-option">
                <span>{{ item.label }}</span>
                <a-popover placement="right" trigger="hover">
                  <template #content><RelationTypeExample :kind="item.value" /></template>
                  <QuestionCircleOutlined
                    :aria-label="`了解${item.label}`"
                    @mousedown.prevent.stop
                    @click.prevent.stop
                  />
                </a-popover>
              </div>
            </template>
          </a-select>
        </a-form-item>
        <a-form-item label="从哪份资料选择" required>
          <a-select
            v-model:value="relation.targetObjectId"
            :disabled="identityLocked('relations', relation.id)"
            :options="targetOptions"
            show-search
            :filter-option="false"
            placeholder="输入对象名称搜索"
            @search="lookupTargets"
          />
        </a-form-item>

        <a-alert
          v-if="fieldRelationContext"
          type="info"
          show-icon
          :message="relationFieldLabel(relation)"
          description="确认后显示在来源表的字段中。选择目标记录时显示记录标题，引用由对象关系维护。"
          class="notice"
        />
        <a-collapse v-if="!fieldRelationContext && relation.kind !== NC.RelationType.MANY_TO_MANY" class="notice">
          <a-collapse-panel key="mapping" header="高级设置：使用已有字段保存关联（通常无需修改）">
            <a-form-item label="保存关联的字段">
              <a-select
                v-model:value="relation.fieldId"
                :disabled="identityLocked('relations', relation.id)"
                allow-clear
                :options="relationColumns"
                placeholder="自动新建引用列，也可选择已有字段"
              />
              <p class="muted">
                留空时，保存对象草稿后生成引用列并显示在来源表字段中；选择时显示目标对象的记录标题，保存目标记录 ID。
                映射已有字段时保留原列，类型须与目标主键一致；保留结构的主表必须选择已有字段。
              </p>
            </a-form-item>
          </a-collapse-panel>
        </a-collapse>
        <a-form-item :label="`删除被关联的${targetNames[relation.targetObjectId] || '资料'}时`">
          <a-select
            v-model:value="relation.onDelete"
            :options="[
              { value: NC.DeletePolicy.RESTRICT, label: '仍被使用时禁止删除该资料（推荐）' },
              ...(relation.kind === NC.RelationType.MASTER_DETAIL
                ? [{ value: NC.DeletePolicy.CASCADE, label: '同时删除依附于该资料的从记录' }]
                : []),
              ...(!relation.sourceDetailId && relation.kind !== NC.RelationType.MANY_TO_MANY
                ? [{ value: NC.DeletePolicy.SET_NULL, label: '保留当前记录，并清空这个关联选择' }]
                : [])
            ]"
          />
        </a-form-item>
        <a-checkbox v-model:checked="relation.required" :disabled="relation.kind === NC.RelationType.MASTER_DETAIL">
          保存时必须选择一条关联记录
        </a-checkbox>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="indexOpen"
      :disabled="!canEdit"
      title="组合索引"
      :width="650"
      @ok="saveIndex"
      @cancel="indexOpen = false"
      :display-mode="modalModes[1]"
      @display-mode-change="modalModes[1] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-alert v-if="indexError" type="error" :message="indexError" class="notice" />
        <a-alert
          v-if="identityLocked('indexes', index.id)"
          type="info"
          show-icon
          class="notice"
          message="索引编码用于识别已部署索引，发布后保持稳定"
          description="可以调整允许编辑的字段和唯一规则。唯一约束会在发布前检查现有数据；若有重复值，需通过授权业务入口修正后重新检查。"
        />

        <a-form-item label="索引名称" required>
          <a-input v-model:value="index.name" />
        </a-form-item>
        <a-form-item label="索引编码" required>
          <a-input v-model:value="index.code" :disabled="identityLocked('indexes', index.id)" />
        </a-form-item>

        <a-form-item label="字段（按选择顺序）" required>
          <a-select
            v-model:value="index.fieldIds"
            mode="multiple"
            show-search
            option-filter-prop="label"
            :options="allFields"
          />
        </a-form-item>
        <a-space direction="vertical">
          <a-checkbox v-model:checked="index.unique">唯一索引</a-checkbox>
          <a-checkbox v-model:checked="index.parentScoped">按同一主记录限定范围（仅内部明细）</a-checkbox>
        </a-space>
      </template>
    </OsModalForm>
    <ObjectOperationReview ref="operationReview" @navigate="handleDraftImpact" />
    <SelectionMigrationDialog
      v-model:open="selectionMigrationOpen"
      :design="input"
      :dependencies="design?.dependencies || []"
      @applied="message.info('映射已应用到当前草稿，请保存后重新核对发布变更')"
    />
    <OsModalForm
      :open="planOpen"
      title="发布确认"
      :width="960"
      :loading="publishing"
      :show-footer="plan?.state === NC.PublishState.PENDING"
      :ok-text="publishActionLabel"
      cancel-text="返回修改"
      @ok="publish"
      @cancel="planOpen = false"
      :display-mode="modalModes[2]"
      @display-mode-change="modalModes[2] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <template v-if="plan">
          <a-alert
            :type="
              publishNeedsRecheck || plan.state !== NC.PublishState.PENDING
                ? 'error'
                : applicationUpgrades.length
                  ? 'warning'
                  : 'info'
            "
            show-icon
            :message="
              publishNeedsRecheck
                ? '上次发布未完成，请重新检查'
                : plan.state !== NC.PublishState.PENDING
                  ? '请先处理以下影响'
                  : applicationUpgrades.length
                    ? `发布 V${plan.versionNo} 将暂停 ${applicationUpgrades.length} 个应用`
                    : `可以发布 V${plan.versionNo}`
            "
            class="notice"
          />
          <a-list v-if="publishChecks.length" :data-source="publishChecks" size="small" class="notice">
            <template #renderItem="{ item: check }">
              <a-list-item>
                <a-space direction="vertical" :size="0">
                  <a-typography-text :type="check.blocking ? 'danger' : 'secondary'">
                    {{ check.message }}
                  </a-typography-text>
                  <a-button v-if="publishCheckHelp(check)" type="link" @click="handlePublishCheck(check)">
                    {{ publishCheckHelp(check)!.label }}
                  </a-button>
                </a-space>
              </a-list-item>
            </template>
          </a-list>
          <section v-if="applicationUpgrades.length" class="notice">
            <p>暂停整个应用，包括其业务入口。适配后可分别发布启用，其他应用可继续运行。</p>
            <a-list :data-source="applicationUpgrades" size="small" bordered>
              <template #renderItem="{ item: app }">
                <a-list-item>
                  <a-space direction="vertical" :size="4">
                    <strong>
                      {{ app.applicationName }} · 应用 V{{ app.applicationVersion }} · 引用对象 V{{ app.objectVersion }}
                    </strong>
                    <div v-for="text in app.reasons" :key="text">{{ text }}</div>
                    <a-typography-text v-for="text in app.blockers" :key="text" type="danger">
                      {{ text }}
                    </a-typography-text>
                    <a-button type="link" @click="handleConversionImpact(app.route)">前往应用处理</a-button>
                  </a-space>
                </a-list-item>
              </template>
            </a-list>
            <a-checkbox
              v-if="plan.state === NC.PublishState.PENDING"
              v-model:checked="suspendApplicationsConfirmed"
              :disabled="!platform.hasPermission('nocode:app:manage') || publishing"
            >
              我确认暂停以上 {{ applicationUpgrades.length }} 个应用，并在适配后分别发布启用
            </a-checkbox>
          </section>
          <a-collapse v-if="plan.changes.length" ghost class="notice">
            <a-collapse-panel key="changes" :header="`字段与数据库变更（${plan.changes.length} 项）`">
              <a-list :data-source="plan.changes" size="small">
                <template #renderItem="{ item }">
                  <a-list-item>{{ item.message }}</a-list-item>
                </template>
              </a-list>
            </a-collapse-panel>
          </a-collapse>

          <FieldConversionReview
            :plan-id="plan.id"
            :object-id="plan.objectId"
            :reviewed-application-ids="applicationUpgrades.map(app => app.applicationId)"
            :conversions="plan.conversions || []"
            :blocked="plan.state !== NC.PublishState.PENDING"
            @navigate="handleConversionImpact"
          />
          <a-alert v-if="publishError" type="error" show-icon :message="publishError" class="notice" />

          <a-form-item v-if="plan.state === NC.PublishState.PENDING" label="操作原因" required>
            <a-textarea v-model:value="reason" :maxlength="1000" :rows="3" />
          </a-form-item>
          <a-space class="notice">
            <a-button v-if="plan.state !== NC.PublishState.PENDING" @click="planOpen = false">返回修改</a-button>
            <a-button :disabled="publishing" @click="planPublish">重新检查影响</a-button>
            <a-button v-if="plan.dependencies.length" @click="showPublishDependencies">查看被引用情况</a-button>
          </a-space>
        </template>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="reconcileOpen"
      title="同步物理差异"
      :width="780"
      :show-footer="reconcile?.allowed"
      :loading="publishing"
      ok-text="纳入新草稿"
      @ok="applyReconcile"
      @cancel="reconcileOpen = false"
      :display-mode="modalModes[3]"
      @display-mode-change="modalModes[3] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <template v-if="reconcile">
          <a-alert
            v-for="check in reconcile.checks"
            :key="check.code + check.message"
            :type="check.blocking ? 'error' : 'warning'"
            :message="check.message"
            class="notice"
          />
          <a-list :data-source="reconcile.changes">
            <template #renderItem="{ item }">
              <a-list-item>{{ item.message }}</a-list-item>
            </template>
          </a-list>

          <a-form-item label="操作原因" required>
            <a-textarea v-model:value="reason" :rows="3" :maxlength="1000" />
          </a-form-item>
          <p class="sub-title">核对后纳入草稿，再发布形成新的对象版本。此操作保留现有数据。</p>
        </template>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="versionOpen"
      title="版本快照"
      :width="950"
      :show-footer="false"
      @cancel="versionOpen = false"
      :display-mode="modalModes[4]"
      @display-mode-change="modalModes[4] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <pre class="version-snapshot">{{ versionBody }}</pre>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.publication-history {
  margin-top: 16px;
}
.object-editor {
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}
.editor-header {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.editor-toolbar,
.editor-identity,
.editor-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.editor-toolbar {
  justify-content: space-between;
}
.editor-identity {
  flex: 1;
}
.editor-identity > .ant-btn,
.editor-actions {
  flex-shrink: 0;
}
.editor-title {
  flex-wrap: wrap;
  gap: 6px 12px;
}
.editor-title h2 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360px;
  margin: 0;
  font-size: 18px;
  line-height: 28px;
}
.object-source,
.object-summary dt {
  color: var(--ant-color-text-secondary, #7b8492);
  font-size: 12px;
}
.object-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 24px;
  margin: 0;
  line-height: 22px;
  font-size: 13px;
}
.object-summary > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}
.object-summary dt {
  flex-shrink: 0;
}
.object-summary dd {
  margin: 0;
  min-width: 0;
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.object-editor :deep(> .editor-body),
.object-editor :deep(> .editor-body > .ant-spin-container),
.object-content-card,
.object-content-card :deep(> .ant-card-body),
.object-tabs {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}
.object-content-card :deep(> .ant-card-body) {
  padding: 0 16px 16px;
  overflow: hidden;
}
.object-tabs :deep(> .ant-tabs-nav) {
  flex-shrink: 0;
  margin-bottom: 12px;
}
.object-tabs :deep(> .ant-tabs-content-holder) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  /* 复合页签内的每张表以实际剩余设计区高度为上限，跟随窗口与基本信息区变化。 */
  container-type: size;
}
.object-tabs :deep(> .ant-tabs-content-holder > .ant-tabs-content) {
  height: 100%;
}
.object-tabs :deep(.ant-tabs-tabpane-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.object-tabs :deep(.object-tab-scroll) {
  overflow-y: auto;
  overscroll-behavior-y: contain;
}
.object-tabs :deep(.object-tab-scroll > *) {
  flex-shrink: 0;
}
.section-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}
h3 {
  margin-top: 24px;
}
.section-toolbar p {
  color: var(--ant-color-text-secondary, #7b8492);
  margin: 0;
}
.notice,
.basic-card {
  flex-shrink: 0;
  margin-bottom: 16px;
}
.basic-card {
  max-height: 40%;
  overflow-y: auto;
}
.basic-card :deep(.ant-form-item) {
  margin-bottom: 0;
}
.basic-card :deep(.ant-card-body) {
  padding: 12px 16px;
}
.basic-card :deep(.ant-row) {
  row-gap: 12px;
}
.danger {
  color: var(--ant-color-error, #ff4d4f);
}
.model-graph {
  display: flex;
  align-items: center;
  gap: 40px;
  padding: 28px;
  background: var(--ant-color-fill-quaternary, #f6f8fb);
  margin-top: 24px;
  border-radius: 8px;
}
.model-node {
  padding: 12px 18px;
  border: 1px solid var(--ant-color-border, #dce2ec);
  background: var(--ant-color-bg-container, white);
  border-radius: 6px;
}
.main-node {
  border-color: var(--ant-color-primary, #1677ff);
}
.model-edges {
  display: grid;
  gap: 12px;
}
.model-edge {
  display: flex;
  align-items: center;
  gap: 16px;
}
.model-edge span {
  min-width: 125px;
  color: var(--ant-color-text-secondary, #7b8492);
}
.version-snapshot {
  max-height: 65vh;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 1100px) {
  .editor-toolbar {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .editor-title h2 {
    max-width: 260px;
  }
  .editor-actions {
    flex-shrink: 1;
  }
}
</style>
