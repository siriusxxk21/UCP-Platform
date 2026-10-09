<script setup lang="ts">
import { useRecordEditorProjection, type PublishedDetail } from '@/nocode/record-editor-projection'
import { createRequestSession } from '@/nocode/request-session'
import { v4 as uuidv4 } from 'uuid'
import { relationFieldId, isRelationFieldId } from '@/nocode/business-fields'
import { computed, ref, watch, inject, onBeforeUnmount, onMounted, nextTick, provide, h } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import {
  documentProblems,
  isDocumentRejection,
  newRowKey,
  pendingDocumentKey,
  loadPendingDocument,
  storePendingDocument
} from '@/nocode/document-save'
import type { DocumentProblem } from '@/types/nocode/document-policy'
import { formatDateTime } from '@/utils/format'
import { message, Drawer, Modal } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { recordPayload, recordDefaults, writableField } from '@/nocode/record-form'
import {
  createFieldRuleCoordinator,
  EMPTY_RULE_STATES,
  fieldRuleNamesKey,
  ruleReadOnly,
  type FieldRuleCoordinator,
  type RuleFieldName,
  type RuleStates
} from '@/nocode/field-rule-runtime'
import { fieldRuleView, RULE_ERROR_PREFIX } from '@/nocode/business-field-rules'
import { moveDetailFocus, pasteableTypes, previewDetailPaste } from '@/nocode/detail-grid'
import { boundFields } from '@/nocode/application-ui'
import { formLayoutNodes } from '@/nocode/form-detail-layout'
import { defaultFormNodes } from '@/nocode/form-presentation'
import { formFieldLabel } from '@/nocode/form-field-projection'
import { editSignature } from '@/nocode/edit-signature'
import { isRecordConflict, isRecordMissing, rebaseSave } from '@/nocode/save-rebase'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { editorCloseKey } from '@/nocode/edit-boundary'
import { workEntryKey } from '@/nocode/work-context'
import { taskEntrySessionKey } from '@/nocode/task-entry-context'
import { taskFormAccessKey } from '@/nocode/task-form-access'
import { FieldType } from '@/types/nocode/enums'
import { businessFileField } from '@/nocode/business-file'
import type { Aggregate, BusinessRow, RecordModel, RecordContext, SaveRecord } from '@/types/nocode/runtime'
import type { FormConfig } from '@/types/nocode/application-ui'
import RecordForm from './RecordForm.vue'
import RecordReadView from './RecordReadView.vue'
import RelatedFormEditor from './RelatedFormEditor.vue'
import RecordFolderPanel from './RecordFolderPanel.vue'
import FormDetailProvider from './FormDetailProvider.vue'

const props = defineProps<{
  applicationId: string
  model: RecordModel
  record?: Aggregate
  initialRelatedRecords?: SaveRecord['relatedRecords']
  form?: FormConfig
  formId?: string
  readOnly?: boolean
  hideFooter?: boolean
  submitText?: string
  confirmLeave?: (changed: boolean, title?: string) => Promise<boolean>
  interactionDisplayMode?: 'modal' | 'drawer'
  /** 同对象不同任务/反馈入口的未确认保存请求分别恢复；普通业务页保持原命名。 */
  pendingScope?: string
  context?: RecordContext
  lockedValues?: Record<string, unknown>
  /**
   * 保存遇到「记录已被修改」时自动以最新内容为底再存。只有 record 就是刚从服务端取来的这条记录时宿主才能打开：
   * 像「修改后重新提交」那样把上次申请的内容当作 record 传进来的，自动合并会把申请内容当成「没改过」而换成最新值。
   */
  mergeOnConflict?: boolean
}>()
const emit = defineEmits<{ saved: [value: Aggregate]; cancel: [] }>()
const platform = useNocodePlatform(),
  api = platform.runtime,
  router = useRouter()
// 宿主会在普通重渲染时重建关联属性；按内容区分真实切换，保留等价属性下的在途输入。
const contextInputKey = computed(() =>
  JSON.stringify([props.context, props.lockedValues], (_key, value: unknown) =>
    value && typeof value === 'object' && !Array.isArray(value)
      ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))
      : value
  )
)
const editorSession = createRequestSession()
let lifetime = editorSession.begin()
function currentEditor() {
  const current = lifetime,
    record = props.record,
    model = props.model,
    form = props.form,
    applicationId = props.applicationId,
    formId = props.formId,
    readOnly = props.readOnly,
    contextInput = contextInputKey.value,
    pendingScope = props.pendingScope
  return () =>
    current() &&
    record === props.record &&
    model === props.model &&
    form === props.form &&
    applicationId === props.applicationId &&
    formId === props.formId &&
    readOnly === props.readOnly &&
    contextInput === contextInputKey.value &&
    pendingScope === props.pendingScope
}
onBeforeUnmount(() => editorSession.invalidate())
const workEntry = inject(workEntryKey, undefined)
const taskEntry = inject(taskEntrySessionKey, undefined)
const taskAccess = inject(taskFormAccessKey, undefined)
const taskDraftAvailable = ref(false),
  taskDraftCheckError = ref('')
onMounted(async () => {
  if (!taskEntry || props.readOnly || props.record?.record.id) return
  const current = currentEditor()
  try {
    const available = await taskEntry.checkDraft()
    if (current()) taskDraftAvailable.value = !!available
  } catch (e) {
    if (current()) taskDraftCheckError.value = errorMessage(e)
  }
})
const closeApproved = ref(false)
const savingDraft = ref(false)
const problems = ref<DocumentProblem[]>([])
const pending = ref<SaveRecord | null>(null)
// 当前内容是从「尚未确认的保存请求」恢复出来的：它与打开时的记录对不上，保存冲突时不能自动合并。
let restoredInput = false
const user = useUserStore()
const pendingKey = computed(
  () =>
    pendingDocumentKey(
      String(user.userInfo?.id || 'anonymous'),
      props.applicationId,
      props.model.object.objectId,
      props.record?.record.id || null
    ) +
    (taskEntry ? ':entry:' + taskEntry.key : '') +
    (props.pendingScope ? ':scope:' + encodeURIComponent(props.pendingScope) : '')
)
const {
  lifecycle,
  currentState,
  formReadOnly,
  relatedBindings,
  permissions,
  fields,
  details,
  relations,
  formNodes,
  effectiveModel,
  mainOptions: writableMainOptions,
  detailFields,
  detailRelations,
  detailOptions: writableDetailOptions,
  detailModel
} = useRecordEditorProjection(props)
const layoutNodes = computed(() =>
  formLayoutNodes(
    formNodes.value || defaultFormNodes(fields.value),
    details.value.map(detail => detail.id)
  )
)
const handlingRule = computed(
  () => props.model.object.settings.documentPolicy?.handling?.[props.record?.record.id ? 'update' : 'create']
)
// 业务文件策略随对象发布版本冻结；未配置时表单附件保持底座上传行为。
const businessPolicy = computed(() => props.model.object.settings.businessFilePolicy || null)
const submitLabel = computed(
  () =>
    props.submitText ||
    (handlingRule.value?.mode === 'APPROVAL'
      ? '提交审批'
      : handlingRule.value?.mode === 'CONDITIONAL'
        ? '提交办理'
        : props.form?.options?.submitText || '保存记录')
)
const stateActions = computed(() =>
  taskEntry
    ? []
    : lifecycle.value?.actions.filter(
        a => a.fromStates.includes(String(currentState.value)) && permissions.value.actions.includes(a.permission)
      ) || []
)
const editorRoot = ref<HTMLElement>()
const processStates: Record<string, string> = {
  RUNNING: '审批中',
  APPROVED: '审批通过',
  REJECTED: '审批不通过',
  CANCELED: '已取消'
}
const state = ref<Aggregate>({ record: { id: null, revision: null, values: {} }, details: {} }),
  generation = ref(0),
  busy = ref(false),
  error = ref('')
const relatedEditors = ref<Array<InstanceType<typeof RelatedFormEditor>>>([])
const pasteDetail = ref<PublishedDetail>()
const detailModes = ref<Record<string, 'GRID' | 'CARDS'>>({})
const detailMode = (id: string, preferred?: 'GRID' | 'CARDS') => detailModes.value[id] || preferred || 'GRID'
let initialInput = ''
const signature = () =>
  editSignature({ values: state.value.record.values, details: state.value.details, relations: state.value.relations })
const isDirty = () =>
  !closeApproved.value &&
  !formReadOnly.value &&
  (signature() !== initialInput || relatedEditors.value.some(editor => editor.dirty()))
useUnsavedNavigation(() => !closeApproved.value && isDirty(), {
  confirm: (changed, title) => (props.confirmLeave || confirmDiscard)(changed, title)
})
async function requestClose() {
  if (busy.value || pending.value) {
    message.info(pending.value ? '保存结果尚未确认，请先查询结果或重试原请求' : '正在保存，请稍候')
    return false
  }
  closeApproved.value = await (props.confirmLeave || confirmDiscard)(isDirty())
  return closeApproved.value
}
async function cancel() {
  if (await requestClose()) emit('cancel')
}
const closeScope = inject(editorCloseKey, undefined)
closeScope?.add(requestClose)
onBeforeUnmount(() => closeScope?.delete(requestClose))
const mainForm = ref<InstanceType<typeof RecordForm>>(),
  detailForms = ref<Array<InstanceType<typeof RecordForm>>>([])
const relatedDraftRows = ref<SaveRecord['relatedRecords']>()
const draftUnavailable = computed(() => {
  if (relatedBindings.value.length && !taskEntry)
    return '包含独立关联数据的表单暂不支持草稿，请填写后一次保存；关闭前会提醒保留未保存修改'
  if (taskEntry && props.record?.record.id) return '任务入口暂存目前支持新增记录，已有记录请直接保存'
  if (!props.formId || !props.form) return '请先为当前入口配置并发布业务表单'
  if (props.context || Object.keys(props.lockedValues || {}).length) return '关联页面上下文的草稿将在后续阶段支持'
  if (!taskEntry && (details.value.length || props.form.detailIds.length)) return '当前来源的内部明细草稿尚未接入'
  if (relations.value.length) return '包含多对多关系的表单暂不支持草稿'
  if (
    !taskEntry &&
    fields.value.some(f => {
      const value = state.value.record.values[f.id]
      return (f.type === FieldType.ATTACHMENT || f.type === FieldType.IMAGE) && Array.isArray(value) && value.length
    })
  )
    return '含附件的填写请使用保存记录；附件草稿将在后续阶段支持'
  return ''
})
async function saveWorkDraft() {
  if (busy.value || draftUnavailable.value) return
  if (taskEntry) {
    await saveTaskDraft()
    return
  }
  const release = workEntry?.application.value
  if (!release || !props.formId) return
  const current = currentEditor()
  busy.value = true
  savingDraft.value = true
  error.value = ''
  try {
    mainForm.value?.validateUploads()
    // 暂存允许缺少必填项；只有正式保存/提交执行完整表单校验。
    const draft = await platform.work.saveDraft({
      id: null,
      expectedRevision: null,
      resource: {
        applicationId: props.applicationId,
        applicationVersion: release.versionNo,
        applicationChecksum: release.checksum,
        resourceId: props.formId,
        resourceKind: 'FORM'
      },
      objectId: props.model.object.objectId,
      recordId: state.value.record.id,
      baseRecordRevision: state.value.record.revision,
      values: recordPayload(
        fields.value.filter(f => boundFields(formNodes.value || []).includes(f.id)),
        writableMainOptions.value,
        effectiveModel.value,
        !state.value.record.id,
        state.value.record.values
      )
    })
    if (!current()) return
    initialInput = signature()
    message.success('已暂存草稿，可继续填写后正式提交')
    emit('cancel')
    await nextTick()
    workEntry.openDraft(draft.id)
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) {
      busy.value = false
      savingDraft.value = false
    }
  }
}
/** 入口草稿必须通过专用 API 保存/恢复，不能转到普通应用草稿页。 */
async function saveTaskDraft() {
  if (!taskEntry || busy.value) return
  const current = currentEditor()
  busy.value = true
  try {
    mainForm.value?.validateUploads()
    for (const form of detailForms.value) form.validateUploads()
    const relatedRecords = await relatedPayload(false)
    if (!current()) return
    await taskEntry.saveDraft({
      applicationId: props.applicationId,
      objectId: props.model.object.objectId,
      id: null,
      expectedRevision: null,
      values: recordPayload(
        fields.value.filter(f => boundFields(formNodes.value || []).includes(f.id)),
        writableMainOptions.value,
        effectiveModel.value,
        true,
        state.value.record.values
      ),
      details: detailPayload(false),
      relatedRecords
    })
    if (!current()) return
    for (const editor of relatedEditors.value) editor.markClean()
    taskDraftAvailable.value = true
    taskDraftCheckError.value = ''
    initialInput = signature()
    message.success('已暂存；下次从这个入口新建时可恢复，不计入业务更新')
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) busy.value = false
  }
}
async function restoreTaskDraft() {
  const current = currentEditor()
  if (
    !taskEntry ||
    busy.value ||
    pending.value ||
    !(await (props.confirmLeave || confirmDiscard)(isDirty(), '恢复暂存将替换当前未保存输入，是否继续？')) ||
    !current()
  )
    return
  busy.value = true
  try {
    const draft = await taskEntry.loadDraft()
    if (!current()) return
    if (!draft) {
      message.info('此入口暂无未提交的新增草稿')
      return
    }
    state.value.record.values = { ...initialValues(), ...draft.values }
    relatedDraftRows.value = draft.relatedRecords
    state.value.details = Object.fromEntries(
      details.value.map(detail => [
        detail.id,
        (draft.details?.[detail.id] || []).map(row => ({
          ...row,
          values: { ...row.values },
          clientRowKey: row.clientRowKey || newRowKey()
        }))
      ])
    )
    initialInput = signature()
    generation.value++
    message.success('已恢复上次暂存，请检查后保存')
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) busy.value = false
  }
}
// 主表加全部明细共用一个规则协调器（设计稿 15.4.6）；各行结果按 rowKey 下发给对应 RecordForm。
const ruleStates = ref<RuleStates>({ master: {}, rows: {} }),
  ruleError = ref('')
let ruleCoordinator: FieldRuleCoordinator | null = null
const ruleNames = computed<Record<string, RuleFieldName>>(() => ({
  ...Object.fromEntries(fields.value.map(f => [f.id, { name: f.name, detailId: null }])),
  ...Object.fromEntries(
    details.value.flatMap(detail =>
      detail.fields.flatMap(f => (f.id ? [[f.id, { name: f.name, detailId: detail.id }] as const] : []))
    )
  )
}))
provide(fieldRuleNamesKey, ruleNames)
const rowKeyOf = (row: BusinessRow) => row.clientRowKey || (row.id ? `row-${row.id}` : '')
function rowRuleStates(detailId: string, row: BusinessRow) {
  return ruleStates.value.rows[detailId]?.[rowKeyOf(row)] || EMPTY_RULE_STATES
}
/** 表格模式格内放不下提示，行首汇总为悬浮提示。 */
function rowRuleNotes(detailId: string, row: BusinessRow) {
  const options = details.value.find(detail => detail.id === detailId)?.fieldOptions || {}
  return Object.values(rowRuleStates(detailId, row)).flatMap(result => {
    const view = fieldRuleView(result, ruleNames.value, detailId, ruleReadOnly(options[result.fieldId]))
    const name = ruleNames.value[result.fieldId]?.name || '字段'
    if (view?.inScope === false) return [`${name}：不符合当前筛选`]
    const note = view?.message || (view?.kind === 'REFERENCE' ? view.pending : null)
    return note ? [`${name}：${note}`] : []
  })
}
function startRules() {
  ruleCoordinator?.dispose()
  ruleCoordinator = null
  ruleStates.value = { master: {}, rows: {} }
  ruleError.value = ''
  if (formReadOnly.value) return
  const current = currentEditor()
  const coordinator = createFieldRuleCoordinator({
    read: () => ({
      creating: !state.value.record.id,
      options: props.model.object.fieldOptions,
      values: state.value.record.values,
      canWrite: id => {
        const field = fields.value.find(f => f.id === id)
        return (
          !!field && writableField(field, writableMainOptions.value[id], effectiveModel.value, !state.value.record.id)
        )
      },
      details: details.value.map(detail => ({
        detailId: detail.id,
        options: detail.fieldOptions,
        fieldIds: detail.fields.flatMap(f => (f.id ? [f.id] : [])),
        rows: (state.value.details[detail.id] || []).map(row => ({
          rowKey: rowKeyOf(row),
          detailRecordId: row.id,
          creating: !row.id,
          values: row.values
        })),
        canWrite: id => {
          const field = detailFields(detail).find(f => f.id === id)
          return !!field && writableField(field, writableDetailOptions(detail)[id], detailModel(detail), false)
        }
      }))
    }),
    evaluate: async part =>
      (
        await api.evaluateFieldRules({
          applicationId: props.applicationId,
          objectId: props.model.object.objectId,
          formId: props.formId,
          recordId: state.value.record.id || undefined,
          ...part
        })
      ).results,
    onStates: states => {
      if (!current()) return
      ruleStates.value = states
      ruleError.value = ''
    },
    onError: e => {
      if (current()) ruleError.value = `${RULE_ERROR_PREFIX}${errorMessage(e)}`
    }
  })
  ruleCoordinator = coordinator
  // 等本轮渲染和字段默认值落定后再记快照：新建时全量求值一次，编辑已有记录打开不求值（依赖变化后照常求值）。
  void nextTick(() => {
    if (ruleCoordinator === coordinator && current()) coordinator.start()
  })
}
watch(
  () => [state.value.record.values, state.value.details],
  () => ruleCoordinator?.sync(),
  { deep: true }
)
onBeforeUnmount(() => ruleCoordinator?.dispose())
function initialValues() {
  // 选择默认值由候选接口按当前范围与权限求值，避免填入发布后失效的值。
  const values = recordDefaults(fields.value, writableMainOptions.value, effectiveModel.value, false)
  if (lifecycle.value) values[lifecycle.value.fieldId] = lifecycle.value.initialState
  return values
}
function reset() {
  lifetime = editorSession.begin()
  busy.value = false
  savingDraft.value = false
  pasteDetail.value = undefined
  detailModes.value = {}
  relatedDraftRows.value = props.initialRelatedRecords
  closeApproved.value = false
  state.value = props.record
    ? JSON.parse(JSON.stringify(props.record))
    : {
        record: {
          id: null,
          revision: null,
          values: {
            ...initialValues(),
            ...props.lockedValues
          }
        },
        details: Object.fromEntries(details.value.map(d => [d.id, []])),
        relations: Object.fromEntries(relations.value.map(r => [r.id, []]))
      }
  for (const relation of relations.value)
    state.value.record.values[relationFieldId(relation.id)] = state.value.relations?.[relation.id] || []
  error.value = ''
  problems.value = []
  pending.value = loadPendingDocument(pendingKey.value)
  if (pending.value) {
    // 恢复原输入后才能以相同请求重试，不能把刷新后的空表单提交成新单据。
    state.value = {
      record: { id: pending.value.id, revision: pending.value.expectedRevision, values: { ...pending.value.values } },
      details: pending.value.details || {},
      relations: pending.value.relations || {}
    }
    error.value = '发现尚未确认的保存请求，请查询结果或重试原请求'
  }
  restoredInput = !!pending.value
  for (const rows of Object.values(state.value.details))
    for (const row of rows) row.clientRowKey ||= row.id ? `row-${row.id}` : newRowKey()
  generation.value++
  initialInput = signature()
  startRules()
}
function addDetail(detail: PublishedDetail) {
  if ((state.value.details[detail.id]?.length || 0) >= 500) {
    message.info('每组明细最多 500 行')
    return
  }
  ;(state.value.details[detail.id] ||= []).push({
    id: null,
    revision: null,
    clientRowKey: newRowKey(),
    values: recordDefaults(detail.fields, writableDetailOptions(detail), detailModel(detail), false)
  })
}
function moveDetail(detail: PublishedDetail, index: number, offset: number) {
  const rows = state.value.details[detail.id] || []
  const target = index + offset
  if (
    !Number.isInteger(index) ||
    !Number.isInteger(target) ||
    index < 0 ||
    index >= rows.length ||
    target < 0 ||
    target >= rows.length
  )
    return
  const [row] = rows.splice(index, 1)
  if (row) rows.splice(target, 0, row)
}
function duplicateDetail(detail: PublishedDetail, row: BusinessRow) {
  if ((state.value.details[detail.id]?.length || 0) >= 500) {
    message.info('每组明细最多 500 行')
    return
  }
  // 复制的是已确定的填写快照；未完成或失败的带入不能通过复制新行丢失状态保护。
  try {
    detailForms.value.find(form => form.rowKey === row.clientRowKey)?.validateUploads()
  } catch (e) {
    error.value = `此行暂时不能复制：${errorMessage(e)}`
    return
  }
  error.value = ''
  const values = recordPayload(detail.fields, writableDetailOptions(detail), detailModel(detail), true, row.values)
  // A20：复制行不携带业务文件附件，避免新行使原上传会话与网盘归属错位；新行逐行重新上传。
  let clearedFiles = false
  for (const field of detail.fields) {
    if (!field.id || !businessFileField(businessPolicy.value, field.id)) continue
    const value = values[field.id]
    if (Array.isArray(value) && value.length) {
      delete values[field.id]
      clearedFiles = true
    }
  }
  ;(state.value.details[detail.id] ||= []).push({
    id: null,
    revision: null,
    clientRowKey: newRowKey(),
    values: JSON.parse(JSON.stringify(values))
  })
  if (clearedFiles) message.info('业务文件附件已按规则不带入复制行，请在新行重新上传')
}
const pasteText = ref('')
const pasteColumns = ref<string[]>([])
const pastePreview = ref<ReturnType<typeof previewDetailPaste>>()
const pasteFields = computed(() => {
  const detail = pasteDetail.value
  if (!detail) return []
  const options = writableDetailOptions(detail),
    model = detailModel(detail)
  return detailFields(detail).filter(f => pasteableTypes.has(f.type) && writableField(f, options[f.id], model, true))
})
watch(
  [pasteText, pasteColumns],
  () => {
    pastePreview.value = undefined
  },
  { deep: true }
)
function openPaste(detail: PublishedDetail) {
  pasteDetail.value = detail
  pasteText.value = ''
  pasteColumns.value = pasteFields.value.map(f => f.id)
  pastePreview.value = undefined
}
function checkPaste() {
  const detail = pasteDetail.value
  if (!detail || !detailModel(detail).writable) return
  try {
    const fields = pasteColumns.value.map(id => {
      const field = pasteFields.value.find(f => f.id === id)
      if (!field) throw new Error('粘贴列已变化，请重新选择')
      return field
    })
    pastePreview.value = previewDetailPaste(
      pasteText.value,
      fields,
      writableDetailOptions(detail),
      state.value.details[detail.id]?.length || 0
    )
  } catch (e) {
    pastePreview.value = { rows: [], count: 0, errors: [errorMessage(e)] }
  }
}
function applyPaste() {
  checkPaste()
  const detail = pasteDetail.value,
    result = pastePreview.value
  if (!detail || !detailModel(detail).writable || !result || result.errors.length || !result.rows.length) return
  ;(state.value.details[detail.id] ||= []).push(
    ...result.rows.map(values => ({
      id: null,
      revision: null,
      clientRowKey: newRowKey(),
      values: { ...recordDefaults(detail.fields, writableDetailOptions(detail), detailModel(detail), false), ...values }
    }))
  )
  pasteDetail.value = undefined
  message.success(`已添加 ${result.rows.length} 行，保存整单后生效`)
}
function acceptSaved(saved: Aggregate) {
  closeApproved.value = true
  pending.value = null
  storePendingDocument(pendingKey.value, null)
  error.value = ''
  problems.value = []
  message.success('整单已保存')
  initialInput = signature()
  // 已收到成功回执，先解除保存锁，再通知外层办理窗口关闭。
  busy.value = false
  emit('saved', saved)
}
function acceptSubmission(result: import('@/types/nocode/handling').HandlingResult) {
  if (result.outcome === 'EFFECTIVE' && result.result) {
    acceptSaved(result.result)
    return
  }
  pending.value = null
  storePendingDocument(pendingKey.value, null)
  error.value = ''
  problems.value = []
  initialInput = signature()
  busy.value = false
  const processInstanceId = result.request?.processInstanceId
  message.success(
    result.outcome === 'SUBMITTED'
      ? processInstanceId
        ? h('span', [
            '申请已提交审批。',
            h(
              'a',
              {
                href: router.resolve({ name: 'TaskInstanceDetail', query: { id: processInstanceId } }).href,
                onClick: (event: MouseEvent) => {
                  event.preventDefault()
                  void router.push({ name: 'TaskInstanceDetail', query: { id: processInstanceId } })
                }
              },
              '查看审批详情'
            )
          ])
        : '申请已提交审批'
      : '已确认保存成功，当前无权读取结果'
  )
  emit('cancel')
}
async function confirmResult() {
  const command = pending.value
  if (command) await queryPendingResult(command, currentEditor())
}
/** 成功回执可能晚于表单换代；仅清理对应请求，不影响新表单或同位置的新请求。 */
function clearConfirmedRequest(storageKey: string, command: SaveRecord) {
  if (command.requestKey && loadPendingDocument(storageKey)?.requestKey === command.requestKey)
    storePendingDocument(storageKey, null)
}
async function queryPendingResult(command: SaveRecord, current: () => boolean, storageKey = pendingKey.value) {
  const requestKey = command.requestKey
  if (!requestKey) {
    error.value = '原保存请求缺少幂等标识，请保留输入并重新打开表单'
    return
  }
  busy.value = true
  try {
    const receipt = await api.submitReceipt(command.applicationId, command.objectId, requestKey)
    if (receipt) clearConfirmedRequest(storageKey, command)
    if (!current()) return
    if (receipt) acceptSubmission(receipt)
    else error.value = '尚未查到提交结果。输入已保留，可再次查询或重试原请求。'
  } catch {
    if (current()) error.value = '暂时无法确认保存结果。请恢复连接后查询，原请求和输入已保留。'
  } finally {
    if (current()) busy.value = false
  }
}
async function sendPending() {
  const command = pending.value,
    current = currentEditor(),
    storageKey = pendingKey.value
  if (!command) return
  if (!command.requestKey) {
    error.value = '原保存请求缺少幂等标识，请保留输入并重新打开表单'
    return
  }
  busy.value = true
  try {
    const result = await api.submit(command)
    clearConfirmedRequest(storageKey, command)
    if (current()) acceptSubmission(result)
  } catch (e) {
    if (!current()) return
    // 记录在这期间被别人改过：以最新内容为底再存（后保存的生效）。返回 false 时按下面原有的方式提示。
    if (await resendRebased(e, command, current, storageKey)) return
    if (isDocumentRejection(e)) {
      pending.value = null
      storePendingDocument(storageKey, null)
      problems.value = documentProblems(e)
      error.value = `本次未保存：${errorMessage(e)}`
      await nextTick()
      if (current() && problems.value[0]) focusProblem(problems.value[0])
    } else {
      error.value = '正在确认保存结果，输入已保留…'
      await queryPendingResult(command, current, storageKey)
    }
  } finally {
    if (current()) busy.value = false
  }
}
/** 首次提交之后，保存冲突最多再自动重试几次。 */
const REBASE_RETRIES = 2
/**
 * 保存被拒是因为记录在这期间被别人改过：取最新记录，我改过的字段用我的、没动的跟最新的走，换一个请求标识再提交。
 * 不打断、不弹窗、不改动用户的输入；只有确实保留了对方的改动时，成功后多提示一句。
 * 返回 true = 这里已经处理完；false = 没有进入（或重试用尽仍是冲突），由调用处按原有方式提示这次冲突。
 * 不进入的：宿主没有声明 mergeOnConflict、新建、任务入口场景、内容是从未确认请求恢复出来的。
 */
async function resendRebased(first: unknown, original: SaveRecord, current: () => boolean, storageKey: string) {
  const base = props.record
  if (!props.mergeOnConflict || !isRecordConflict(first) || !original.id || !base || restoredInput || taskEntry)
    return false
  const settle = (text: string) => {
    pending.value = null
    storePendingDocument(storageKey, null)
    error.value = text
    return true
  }
  let keptTheirs = false
  for (let attempt = 0; attempt < REBASE_RETRIES; attempt++) {
    let latest: Aggregate
    try {
      latest = await api.get(original.applicationId, original.objectId, original.id, { quiet: true })
    } catch (e) {
      if (!current()) return true
      return isRecordMissing(e) ? settle('这条记录已被别人删除，无法保存') : false
    }
    if (!current()) return true
    // 每次都拿原来的提交去套最新的内容；拿上一次合并的结果去套，会把对方新改的值用旧值盖回去。
    const rebased = rebaseSave({ base, latest, command: original })
    // 底座的全量类型检查没开严格空检查，只认显式的 === false 来区分两种结果。
    if (rebased.ok === false) return settle(`本次未保存：${rebased.message}`)
    if (rebased.keptTheirs.length) keptTheirs = true
    const command = rebased.command
    pending.value = command
    storePendingDocument(storageKey, command)
    try {
      const result = await api.submit(command)
      if (!current()) return true
      acceptSubmission(result)
      if (keptTheirs) message.info('这条记录刚被别人改过，对方改的其他内容已保留。')
      return true
    } catch (e) {
      if (!current()) return true
      if (isRecordConflict(e)) continue
      // 重试的那次因别的原因被拒或结果未知：与首次提交同样处理，只是对象换成重试的那次请求。
      if (isDocumentRejection(e)) {
        settle(`本次未保存：${errorMessage(e)}`)
        problems.value = documentProblems(e)
        await nextTick()
        if (current() && problems.value[0]) focusProblem(problems.value[0])
      } else {
        error.value = '正在确认保存结果，输入已保留…'
        await queryPendingResult(command, current)
      }
      return true
    }
  }
  return false
}
async function focusProblem(problem: DocumentProblem) {
  const key = problem.clientRowKey || (problem.recordId ? `row-${problem.recordId}` : null)
  const selector = key
    ? `[data-row-key="${CSS.escape(key)}"]`
    : problem.detailId
      ? `[data-detail-id="${CSS.escape(problem.detailId)}"]`
      : '.os-form-surface'
  const node = editorRoot.value?.querySelector<HTMLElement>(selector)
  const panels: HTMLElement[] = []
  for (let parent = node?.parentElement; parent; parent = parent.parentElement)
    if (parent.classList.contains('ant-tabs-tabpane')) panels.unshift(parent)
  for (const panel of panels) {
    editorRoot.value?.querySelector<HTMLElement>(`[role="tab"][aria-controls="${CSS.escape(panel.id)}"]`)?.click()
    await nextTick()
  }
  node?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  node
    ?.querySelector<HTMLElement>('input:not([disabled]),textarea:not([disabled]),[role="combobox"]')
    ?.focus({ preventScroll: true })
}
/** 暂存和正式保存复用同一明细输入协议；暂存仅跳过必填和整单业务规则。 */
function requireRevision(row: BusinessRow, label: string) {
  if (row.id && !row.revision) throw new Error(`${label}缺少修订号，请保留输入并刷新记录后重试`)
}
function detailPayload(validateRevision = true) {
  const groups: Record<string, BusinessRow[]> = {}
  for (const detail of details.value) {
    const cap = detailModel(detail)
    if (cap.writable)
      groups[detail.id] = (state.value.details[detail.id] || []).map(row => {
        if (validateRevision) requireRevision(row, detail.name + '明细')
        return {
          id: row.id,
          revision: row.revision,
          clientRowKey: row.clientRowKey,
          values: recordPayload(detail.fields, writableDetailOptions(detail), cap, !row.id, row.values)
        }
      })
  }
  return groups
}
const noEditableContent = computed(
  () =>
    !fields.value.some(f => !props.form || boundFields(formNodes.value || []).includes(f.id)) &&
    !details.value.length &&
    !relatedBindings.value.length
)
async function save(actionCode?: string) {
  if (busy.value || pending.value || noEditableContent.value) return
  busy.value = true
  const current = currentEditor()
  error.value = ''
  problems.value = []
  try {
    requireRevision(state.value.record, '当前记录')
    // 防抖中的联动立即算完再校验，避免带着过期的建议值保存。
    await ruleCoordinator?.settle()
    if (!current()) return
    await mainForm.value?.validate()
    if (!current()) return
    for (const form of detailForms.value) {
      await form.validate()
      if (!current()) return
    }
    const groups = detailPayload()
    const relatedRecords = await relatedPayload()
    if (!current()) return
    const selected = props.form
      ? fields.value.filter(f => boundFields(formNodes.value || []).includes(f.id))
      : fields.value
    pending.value = {
      requestKey: uuidv4(),
      actionCode,
      applicationId: props.applicationId,
      objectId: props.model.object.objectId,
      formId: props.formId,
      context: props.context,
      id: state.value.record.id,
      expectedRevision: state.value.record.revision,
      values: recordPayload(
        selected.filter(f => !isRelationFieldId(f.id)),
        writableMainOptions.value,
        effectiveModel.value,
        !state.value.record.id,
        state.value.record.values
      ),
      details: groups,
      ...(relatedBindings.value.length ? { relatedRecords } : {}),
      relations: Object.fromEntries(
        relations.value
          .filter(
            r =>
              effectiveModel.value.writeFields.includes(relationFieldId(r.id)) &&
              selected.some(f => f.id === relationFieldId(r.id))
          )
          .map(r => [r.id, (state.value.record.values[relationFieldId(r.id)] || []) as string[]])
      )
    }
    storePendingDocument(pendingKey.value, pending.value)
    await sendPending()
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) busy.value = false
  }
}
async function relatedPayload(validate = true): Promise<NonNullable<SaveRecord['relatedRecords']>> {
  if (relatedBindings.value.length !== relatedEditors.value.length) throw new Error('关联数据尚未加载，请稍后保存')
  const expected = new Set(relatedBindings.value.map(binding => binding.id))
  const actual = new Set(relatedEditors.value.map(editor => editor.bindingId))
  if (
    expected.size !== actual.size ||
    actual.size !== relatedEditors.value.length ||
    [...actual].some(id => !expected.has(id))
  ) {
    throw new Error('关联区域已变化，请保留当前输入并重新打开表单')
  }
  const output: NonNullable<SaveRecord['relatedRecords']> = {}
  for (const editor of relatedEditors.value) output[editor.bindingId] = await editor.payload(validate)
  return output
}
// 切回只读详情时丢弃尚未保存的编辑值，避免取消后仍把草稿当作已保存资料展示。
watch(
  [
    () => props.applicationId,
    () => props.formId,
    () => props.record,
    () => props.model,
    () => props.form,
    () => props.readOnly,
    contextInputKey,
    () => props.pendingScope
  ],
  reset,
  { immediate: true }
)
defineExpose({ reset, isDirty, requestClose, confirmResult })
</script>
<template>
  <div ref="editorRoot">
    <a-alert
      v-if="noEditableContent && !formReadOnly"
      type="warning"
      show-icon
      class="notice"
      message="暂无可填写字段，请联系管理员检查字段权限及表单配置。"
    />
    <a-alert
      v-if="taskEntry && !readOnly && !record?.record.id"
      type="info"
      show-icon
      :message="
        handlingRule?.mode === 'APPROVAL'
          ? '未填完可暂存；审批通过并生效后更新业务数据。'
          : handlingRule?.mode === 'CONDITIONAL'
            ? '未填完可暂存；提交时按对象规则判断是否需要审批。'
            : '未填完可暂存；保存后立即更新业务数据。'
      "
      class="notice"
    >
      <template #action>
        <a-button
          v-if="taskDraftAvailable"
          size="small"
          :disabled="busy || !!pending || !!draftUnavailable"
          @click="restoreTaskDraft"
        >
          恢复上次暂存
        </a-button>
      </template>
    </a-alert>
    <a-alert
      v-if="taskDraftCheckError"
      type="warning"
      show-icon
      :message="`原草稿暂不可恢复：${taskDraftCheckError}`"
      class="notice"
    />
    <a-alert
      v-if="!formReadOnly && handlingRule && handlingRule.mode !== 'DIRECT'"
      type="info"
      show-icon
      class="notice"
      :message="
        handlingRule.mode === 'APPROVAL'
          ? '本次操作需要审批，审批前原数据保持不变。'
          : '提交时按对象规则判断是否审批；需要审批时原数据保持不变。'
      "
    />
    <a-alert v-if="error" :type="pending ? 'warning' : 'error'" :message="error" show-icon class="notice">
      <template v-if="pending" #action>
        <a-space>
          <a-button :loading="busy" @click="confirmResult">查询保存结果</a-button>
          <a-button :disabled="busy" @click="sendPending">重试原请求</a-button>
        </a-space>
      </template>
    </a-alert>
    <a-alert v-if="ruleError" type="warning" :message="ruleError" show-icon class="notice" />
    <ul v-if="problems.length" class="document-problems">
      <li v-for="(problem, index) in problems" :key="index">
        <a @click="focusProblem(problem)">{{ problem.message }}</a>
      </li>
    </ul>
    <div :inert="savingDraft || busy || pending ? true : undefined">
      <FormDetailProvider :detail-ids="details.map(detail => detail.id)">
        <RecordReadView
          v-if="formReadOnly"
          :fields="fields"
          :values="state.record.values"
          :display-values="record?.record.displayValues"
          :options="model.object.fieldOptions"
          :nodes="layoutNodes"
          :form-id="formId"
          :application-id="applicationId"
          :object-id="model.object.objectId"
          :record-id="record?.record.id || undefined"
          :relations="model.object.relations"
          :layout="form?.options?.layout"
          :business-policy="businessPolicy"
        />
        <RecordForm
          v-else
          :key="generation"
          ref="mainForm"
          v-model="state.record.values"
          :fields="fields"
          :options="model.object.fieldOptions"
          :model="effectiveModel"
          :creating="!state.record.id"
          :nodes="layoutNodes"
          :form-id="formId"
          :application-id="applicationId"
          :object-id="model.object.objectId"
          :record-id="record?.record.id || undefined"
          :relations="model.object.relations"
          :layout="form?.options?.layout"
          :business-policy="businessPolicy"
          :rule-states="ruleStates.master"
        />
        <template #detail="{ detailId, mode: preferredMode, title: detailTitle }">
          <section
            v-for="detail in details.filter(d => d.id === detailId)"
            :key="detail.id"
            :data-detail-id="detail.id"
            class="detail-section"
            :class="{ 'detail-grid': detailMode(detail.id, preferredMode) === 'GRID' && !formReadOnly }"
            @keydown="moveDetailFocus($event, $event.currentTarget as HTMLElement)"
          >
            <div class="detail-heading">
              <h3>
                {{ detailTitle || detail.name }}
                <small>{{ state.details[detail.id]?.length || 0 }} 行</small>
              </h3>
              <a-space wrap>
                <a-radio-group
                  v-if="!formReadOnly"
                  :value="detailMode(detail.id, preferredMode)"
                  @update:value="detailModes[detail.id] = $event"
                  size="small"
                  :options="[
                    { label: '表格', value: 'GRID' },
                    { label: '卡片', value: 'CARDS' }
                  ]"
                  option-type="button"
                />
                <a-button v-if="model.writable && detailModel(detail).writable" size="small" @click="openPaste(detail)">
                  粘贴多行
                </a-button>
                <a-button v-if="model.writable && detailModel(detail).writable" size="small" @click="addDetail(detail)">
                  添加明细
                </a-button>
              </a-space>
            </div>
            <p v-if="detailMode(detail.id, preferredMode) === 'GRID' && !formReadOnly" class="grid-hint">
              Enter 移到下一格，Alt + ↑ / ↓ 同列移动；移动端自动使用卡片。明细随整单保存。
            </p>
            <div class="detail-body">
              <div v-if="detailMode(detail.id, preferredMode) === 'GRID' && !formReadOnly" class="detail-grid-head">
                <span>序号 / 操作</span>
                <span v-for="field in detailFields(detail)" :key="field.id">
                  {{ formFieldLabel(field, form?.detailNodes?.[detail.id]) }}{{ field.required ? ' *' : '' }}
                </span>
              </div>
              <div
                v-for="(row, index) in state.details[detail.id] || []"
                :key="row.clientRowKey || row.id || index"
                :data-row-key="row.clientRowKey"
                class="detail-row"
                :class="{
                  'has-document-error': problems.some(
                    p => p.detailId === detail.id && p.clientRowKey === row.clientRowKey
                  )
                }"
              >
                <div class="detail-heading">
                  <strong>
                    明细 {{ index + 1 }}
                    <a-tooltip
                      v-if="detailMode(detail.id, preferredMode) === 'GRID' && rowRuleNotes(detail.id, row).length"
                      :title="rowRuleNotes(detail.id, row).join('；')"
                    >
                      <span class="row-rule-flag" tabindex="0" :aria-label="rowRuleNotes(detail.id, row).join('；')">
                        !
                      </span>
                    </a-tooltip>
                  </strong>
                  <a-space v-if="model.writable && detailModel(detail).writable">
                    <a-button size="small" :disabled="index === 0" @click="moveDetail(detail, index, -1)">
                      上移
                    </a-button>
                    <a-button
                      size="small"
                      :disabled="index === state.details[detail.id].length - 1"
                      @click="moveDetail(detail, index, 1)"
                    >
                      下移
                    </a-button>
                    <a-button size="small" @click="duplicateDetail(detail, row)">复制</a-button>
                  </a-space>
                  <a
                    v-if="model.writable && detailModel(detail).writable"
                    class="danger"
                    @click="state.details[detail.id].splice(index, 1)"
                  >
                    移除
                  </a>
                </div>
                <RecordForm
                  v-if="!formReadOnly"
                  :compact="detailMode(detail.id, preferredMode) === 'GRID'"
                  :key="generation + '-' + row.clientRowKey"
                  ref="detailForms"
                  :client-row-key="row.clientRowKey || undefined"
                  v-model="row.values"
                  :nodes="form?.detailNodes?.[detail.id]"
                  :form-id="formId"
                  :parent-values="state.record.values"
                  :fields="detailFields(detail)"
                  :options="detail.fieldOptions"
                  :model="detailModel(detail)"
                  :creating="!row.id"
                  :application-id="applicationId"
                  :object-id="model.object.objectId"
                  :detail-id="detail.id"
                  :detail-record-id="row.id || undefined"
                  :relations="detailRelations(detail)"
                  :record-id="record?.record.id || undefined"
                  :business-policy="businessPolicy"
                  :rule-states="rowRuleStates(detail.id, row)"
                />
                <RecordReadView
                  v-else
                  :fields="detailFields(detail)"
                  :nodes="form?.detailNodes?.[detail.id]"
                  :values="row.values"
                  :display-values="row.displayValues"
                  :options="detail.fieldOptions"
                  :application-id="applicationId"
                  :object-id="model.object.objectId"
                  :detail-id="detail.id"
                  :detail-record-id="row.id || undefined"
                  :record-id="record?.record.id || undefined"
                  :relations="detailRelations(detail)"
                  :business-policy="businessPolicy"
                />
              </div>
            </div>
            <a-empty
              v-if="!state.details[detail.id]?.length"
              description="暂无明细"
              :image-style="{ height: '32px' }"
            />
          </section>
        </template>
      </FormDetailProvider>
      <section v-if="record?.processes?.length" class="detail-section">
        <h3>流程记录</h3>
        <a-list :data-source="record.processes">
          <template #renderItem="{ item }">
            <a-list-item>
              <a @click="router.push({ name: 'TaskInstanceDetail', query: { id: item.instanceId } })">
                {{ item.name }}
              </a>
              <a-space>
                <a-tag>{{ processStates[item.status] || item.status }}</a-tag>
                <span>{{ formatDateTime(item.createTime) }}</span>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </section>
    </div>
  </div>
  <component
    :is="interactionDisplayMode === 'drawer' ? Drawer : Modal"
    :open="!!pasteDetail"
    title="从表格粘贴明细"
    :width="760"
    :footer="null"
    @cancel="pasteDetail = undefined"
    @close="pasteDetail = undefined"
  >
    <a-alert
      type="info"
      show-icon
      message="先选列、粘贴并检查，再添加到整单。关联和附件请在添加后填写；保存时仍会执行完整业务校验。"
    />
    <a-form layout="vertical" style="margin-top: 16px">
      <a-form-item label="粘贴列（与复制的列顺序一致，不含表头）">
        <a-select
          v-model:value="pasteColumns"
          mode="multiple"
          :options="pasteFields.map(f => ({ value: f.id, label: f.name }))"
        />
      </a-form-item>
      <a-form-item label="粘贴内容">
        <a-textarea v-model:value="pasteText" :rows="7" placeholder="从电子表格复制多行后粘贴到这里" />
      </a-form-item>
    </a-form>
    <a-alert
      v-if="pastePreview"
      :type="pastePreview.errors.length ? 'error' : 'success'"
      :message="
        pastePreview.errors.length
          ? `发现 ${pastePreview.errors.length} 个问题，本次尚未添加`
          : `检查通过，可添加 ${pastePreview.count} 行`
      "
    />
    <ul v-if="pastePreview?.errors.length" class="paste-errors">
      <li v-for="(item, index) in pastePreview.errors.slice(0, 100)" :key="index">{{ item }}</li>
    </ul>
    <a-space style="margin-top: 16px">
      <a-button @click="checkPaste">检查内容</a-button>
      <a-button type="primary" :disabled="!pastePreview || !!pastePreview.errors.length" @click="applyPaste">
        添加到整单
      </a-button>
    </a-space>
  </component>
  <a-alert
    v-if="relatedBindings.length && !formId"
    type="error"
    show-icon
    message="关联表单入口缺少发布标识，请检查当前表单配置后重试"
  />
  <template v-if="formId">
    <RelatedFormEditor
      :interaction-display-mode="interactionDisplayMode"
      v-for="binding in relatedBindings"
      :key="`${generation}:${binding.id}`"
      ref="relatedEditors"
      :application-id="applicationId"
      :object-id="model.object.objectId"
      :form-id="formId"
      :binding="binding"
      :record-id="record?.record.id"
      :read-only="formReadOnly || !!pending"
      :pending-rows="pending?.relatedRecords?.[binding.id] || relatedDraftRows?.[binding.id]"
    />
  </template>
  <RecordFolderPanel
    v-if="record?.record.id && !taskEntry && !taskAccess"
    :application-id="applicationId"
    :object-id="model.object.objectId"
    :record-id="record.record.id"
    :revision="record.record.revision"
  />
  <div v-if="!hideFooter" class="editor-footer">
    <a-button
      v-for="action in !formReadOnly ? stateActions : []"
      :key="action.code"
      :disabled="busy || !!pending || !effectiveModel.writable"
      @click="save(action.code)"
    >
      {{ action.name }}
    </a-button>
    <a-button :disabled="busy || !!pending" @click="cancel">{{ formReadOnly ? '关闭' : '取消' }}</a-button>
    <a-tooltip
      v-if="(workEntry || taskEntry) && effectiveModel.writable"
      :title="draftUnavailable || '保存未完成的填写，不写入业务记录'"
    >
      <span>
        <a-button :disabled="busy || !!draftUnavailable" :loading="savingDraft" @click="saveWorkDraft">
          暂存草稿
        </a-button>
      </span>
    </a-tooltip>
    <a-button
      v-if="effectiveModel.writable"
      type="primary"
      :loading="busy"
      :disabled="noEditableContent"
      @click="save()"
    >
      {{ submitLabel }}
    </a-button>
  </div>
</template>
<style scoped>
.notice {
  margin-bottom: 16px;
}
.grid-hint {
  margin: 8px 0;
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.detail-grid-head {
  display: none;
}
.paste-errors {
  max-height: 220px;
  overflow: auto;
  color: #cf1322;
}
.detail-heading small {
  color: var(--text-secondary, #6b7280);
  font-weight: normal;
}
@media (min-width: 769px) {
  .detail-grid .detail-body {
    overflow-x: auto;
    border: 1px solid #e5e7eb;
    border-radius: 6px;
  }
  .detail-grid-head {
    display: flex;
    width: max-content;
    min-width: 100%;
    background: #fafafa;
    border-bottom: 1px solid #e5e7eb;
  }
  .detail-grid-head > span {
    width: 200px;
    padding: 10px 8px;
    flex: none;
    box-sizing: border-box;
  }
  .detail-grid-head > span:first-child {
    width: 310px;
  }
  .detail-grid .detail-row {
    display: flex;
    width: max-content;
    min-width: 100%;
    border: 0;
    border-bottom: 1px solid #f0f0f0;
    border-radius: 0;
    padding: 0;
    margin: 0;
  }
  .detail-grid .detail-row > .detail-heading {
    flex: none;
    width: 310px;
    padding: 8px;
    margin: 0;
    gap: 8px;
    box-sizing: border-box;
  }
  .detail-grid .detail-row > .detail-heading strong {
    white-space: nowrap;
    font-size: 12px;
  }
}
@media (max-width: 768px) {
  .detail-heading {
    flex-wrap: wrap;
  }
  .detail-grid-head {
    display: none;
  }
}
.detail-section {
  border-top: 1px solid #eef0f3;
  padding-top: 24px;
  margin-top: 24px;
}
.detail-heading,
.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.detail-heading h3 {
  margin: 0;
  font-size: 15px;
  line-height: 24px;
  font-weight: 600;
}
.detail-section > h3 {
  margin: 0 0 16px;
  font-size: 15px;
  line-height: 24px;
}
.detail-row {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 20px;
  margin-top: 16px;
}
.detail-row .detail-heading {
  margin-bottom: 16px;
}
.danger {
  color: #dc2626;
}
.editor-footer {
  justify-content: flex-end;
  margin-top: 24px;
  position: sticky;
  bottom: -24px;
  background: white;
  padding: 16px 0;
  border-top: 1px solid #e5e7eb;
  z-index: 2;
}
</style>

<style scoped>
.row-rule-flag {
  display: inline-block;
  width: 16px;
  height: 16px;
  margin-left: 4px;
  border-radius: 50%;
  background: #faad14;
  color: white;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  cursor: help;
}
.document-problems {
  margin: 0 0 16px;
  padding-left: 24px;
}
.has-document-error {
  outline: 1px solid var(--os-color-error, #ff4d4f);
  outline-offset: 3px;
}
</style>
