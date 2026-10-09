<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import request from '@/utils/request'

import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { nocodePlatformKey, useNocodePlatform } from '@/nocode/platform'
import { taskEntryRuntime } from '@/nocode/task-entry'
import { errorMessage } from '@/nocode/data-center'
import type { TaskBinding, TaskCreate, TaskDetail, TaskFormContext, TaskRow } from '@/types/nocode/task-center'
import type { SaveRecord } from '@/types/nocode/runtime'
import type { HandlingResult } from '@/types/nocode/handling'
import RecordEditor from '../application/components/RecordEditor.vue'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { taskEntrySessionKey } from '@/nocode/task-entry-context'
import type { WorkDraft } from '@/types/nocode/work'
import { taskFormAccessKey, taskFormRuntime } from '@/nocode/task-form-access'
const { confirmDiscard } = useTaskConfirmation()

const props = defineProps<{
  task?: TaskRow
  binding?: TaskBinding
  create?: (record: SaveRecord) => TaskCreate
  publish?: (record: SaveRecord) => Promise<TaskDetail>
  saveDraft?: (record: SaveRecord) => Promise<void>
  initialBusiness?: SaveRecord | null
  draftKey?: string
  readonly?: boolean
}>()
const emit = defineEmits<{ saved: []; created: [detail: TaskDetail]; cancel: [] }>()
const platform = useNocodePlatform(),
  api = platform.taskCenter,
  entryApi = createTaskEntryApi(request)
const context = ref<TaskFormContext>(),
  loading = ref(false),
  error = ref(''),
  entryRuntime = ref(platform.runtime),
  created = ref<TaskDetail>(),
  revision = ref(props.task?.revision || 0)
const receipts = new Map<string, HandlingResult>()
const savedContext = ref<TaskFormContext>()
const editor = ref<InstanceType<typeof RecordEditor>>()
const businessDraft = ref(props.initialBusiness || null)
const submissionLocation = computed(() => {
  const requestId = context.value?.handling?.request?.id
  if (!requestId) return undefined
  const taskId = props.task?.id || created.value?.task.id
  return {
    path: '/nocode-app/process-record',
    query: { id: `nocode-handling:${requestId}`, ...(taskId ? { taskId } : {}) }
  }
})
function draftView(record: SaveRecord): WorkDraft {
  if (!context.value) throw new Error('业务表单尚未就绪')
  return {
    id: props.draftKey || 'task-create',
    revision: 0,
    state: 'DRAFT',
    updatedAt: Date.now(),
    resource: { ...context.value.binding.resource, resourceKind: 'FORM' },
    objectId: record.objectId,
    recordId: record.id,
    baseRecordRevision: record.expectedRevision,
    values: record.values,
    details: record.details,
    relatedRecords: record.relatedRecords
  }
}
// 复用业务编辑器的草稿能力，存储仍是整份任务草稿，不创建另一份业务办理草稿。
if (props.saveDraft)
  provide(taskEntrySessionKey, {
    key: `task-create:${props.draftKey}`,
    saveDraft: async record => {
      await props.saveDraft!(record)
      businessDraft.value = record
      return draftView(record)
    },
    loadDraft: async () => (businessDraft.value ? draftView(businessDraft.value) : null),
    checkDraft: async () => null
  })
const initialRecord = computed(() =>
  props.initialBusiness
    ? {
        record: {
          id: props.initialBusiness.id,
          revision: props.initialBusiness.expectedRevision,
          values: props.initialBusiness.values
        },
        details: props.initialBusiness.details || {},
        relations: props.initialBusiness.relations || {}
      }
    : undefined
)
defineExpose({ requestClose: () => editor.value?.requestClose() ?? Promise.resolve(true) })
watch(
  () => props.task?.revision,
  value => {
    if (value !== undefined) revision.value = value
  }
)
let generation = 0
watch(
  () => [props.task?.id, props.binding?.applicationId, props.binding?.formId, props.binding?.entryId],
  async () => {
    const token = ++generation
    context.value = undefined
    loading.value = true
    error.value = ''
    created.value = undefined
    savedContext.value = undefined
    receipts.clear()
    try {
      const form = props.task
        ? await api.form(props.task.id)
        : props.binding
          ? await api.formPreview(props.binding)
          : undefined
      if (token !== generation || !form) return
      const binding = props.task?.binding || props.binding
      let runtime = platform.runtime
      if (!props.task && binding?.entryId) {
        const entry = await entryApi.context({
          applicationId: binding.applicationId,
          entryId: binding.entryId,
          version: form.binding.resource.applicationVersion
        })
        runtime = taskEntryRuntime(platform.runtime, entryApi, entry)
      }
      if (token !== generation) return
      context.value = form
      entryRuntime.value = runtime
      revision.value = props.task?.revision || 0
    } catch (e) {
      if (token === generation) error.value = errorMessage(e)
    } finally {
      if (token === generation) loading.value = false
    }
  },
  { immediate: true }
)
async function submit(record: SaveRecord): Promise<HandlingResult> {
  if (props.readonly) throw new Error('当前任务材料只读')
  let form: TaskFormContext
  if (props.task) {
    form = await api.saveBusiness({ taskId: props.task.id, expectedRevision: revision.value, record })
  } else {
    if (!props.create) throw new Error('缺少任务发起命令')
    const detail = props.publish ? await props.publish(record) : await api.create(props.create(record))
    created.value = detail
    form = await api.form(detail.task.id)
  }
  const result = form.handling || { outcome: 'EFFECTIVE', result: form.record, request: null }
  savedContext.value = form
  if (record.requestKey) receipts.set(record.requestKey, result)
  return result
}
async function receipt(_app: string, _object: string, requestKey: string): Promise<HandlingResult | null> {
  const cached = receipts.get(requestKey)
  if (cached) return cached
  const token = generation
  if (props.task) {
    const result = await api.formReceipt(props.task.id, requestKey)
    if (result && token === generation) receipts.set(requestKey, result)
    return result
  }
  const result = await api.createReceipt(requestKey)
  if (!result) return null
  const [detail, form] = await Promise.all([api.detail(result.taskId), api.form(result.taskId)])
  if (token === generation) {
    created.value = detail
    savedContext.value = form
    receipts.set(requestKey, result.handling)
  }
  return result.handling
}
const runtime = taskFormRuntime(platform.runtime, {
  evaluateFieldRules: query =>
    props.task ? api.formFieldRules(props.task.id, query) : entryRuntime.value.evaluateFieldRules(query),
  relatedForm: (...args: Parameters<typeof platform.runtime.relatedForm>) =>
    props.task ? api.relatedForm(props.task.id, ...args) : entryRuntime.value.relatedForm(...args),
  relatedSelection: (...args: Parameters<typeof platform.runtime.relatedSelection>) =>
    props.task ? api.relatedSelection(props.task.id, ...args) : entryRuntime.value.relatedSelection(...args),
  relatedFill: (...args: Parameters<typeof platform.runtime.relatedFill>) =>
    props.task ? api.relatedFill(props.task.id, ...args) : entryRuntime.value.relatedFill(...args),
  formFill: (...args: Parameters<typeof platform.runtime.formFill>) =>
    props.task ? api.formFill(props.task.id, ...args) : entryRuntime.value.formFill(...args),
  selection: (...args: Parameters<typeof platform.runtime.selection>) =>
    props.task ? api.formSelection(props.task.id, ...args) : entryRuntime.value.selection(...args),
  submit,
  submitReceipt: receipt
})
if (props.task) {
  provide(taskFormAccessKey, {
    scope: () => `task:${props.task!.id}:__business`,
    relatedFieldRules: (source, query) => api.relatedFieldRules(props.task!.id, source, query)
  })
}
// 本组件只承载旧业务表单或发起预填；历史任务没有统一办理项，附件沿用普通应用授权。
// 新任务授权附件由 TaskEntryRecordEditor 按服务端真实办理项身份适配，不能在这里伪造 __business。
provide(nocodePlatformKey, { ...platform, runtime })
const canResubmit = computed(() => ['REJECTED', 'CANCELED'].includes(context.value?.handling?.outcome || ''))
const model = computed(() => {
  if (!context.value) return undefined
  const original = context.value.model,
    allowed = context.value.writableFieldIds
  return {
    ...original,
    writeFields: original.writeFields?.filter(id => allowed.includes(id)),
    permissions: {
      ...original.permissions,
      writeFields: original.permissions.writeFields.filter(id => allowed.includes(id))
    }
  }
})
function saved() {
  if (savedContext.value) context.value = savedContext.value
  if (created.value) emit('created', created.value)
  else emit('saved')
}
function cancelled() {
  if (savedContext.value) context.value = savedContext.value
  if (created.value) emit('created', created.value)
  else if (receipts.size) emit('saved')
  else emit('cancel')
}
</script>
<template>
  <a-spin v-if="loading" />
  <a-alert v-else-if="error" type="error" show-icon :message="error" />
  <div v-else-if="context && model">
    <a-alert
      v-if="context.handling?.outcome === 'SUBMITTED'"
      type="info"
      show-icon
      message="业务申请已提交审批，生效后可完成任务。"
      style="margin-bottom: 12px"
    >
      <template #action>
        <RouterLink v-if="submissionLocation" :to="submissionLocation">查看提交记录</RouterLink>
      </template>
    </a-alert>
    <a-alert
      v-if="canResubmit"
      type="warning"
      show-icon
      :message="
        context.handling?.outcome === 'REJECTED'
          ? '申请已驳回，请修改材料后重新提交'
          : '申请已撤回，可修改材料后重新提交'
      "
      :description="
        context.handling?.request?.error || '已恢复原申请输入，新申请保留与本任务的关联，历史申请仍可追溯。'
      "
      style="margin-bottom: 12px"
    />
    <a-alert
      v-if="context.handling?.outcome === 'APPLY_FAILED'"
      type="error"
      show-icon
      message="审批通过但业务数据生效失败"
      :description="context.handling.request?.error || '请查看本次提交记录中的失败原因。'"
      style="margin-bottom: 12px"
    >
      <template #action>
        <RouterLink v-if="submissionLocation" :to="submissionLocation">查看提交记录</RouterLink>
      </template>
    </a-alert>
    <RecordEditor
      :confirm-leave="confirmDiscard"
      interaction-display-mode="drawer"
      :pending-scope="
        task
          ? `task-business:${task.id}`
          : `task-create:${binding?.applicationId}:${binding?.formId}:${binding?.entryId || ''}`
      "
      ref="editor"
      :key="task?.id || context.binding.resource.resourceId"
      :application-id="context.binding.resource.applicationId"
      :model="model"
      :record="context.record || initialRecord"
      :initial-related-records="initialBusiness?.relatedRecords"
      :form="context.form"
      :submit-text="canResubmit ? '重新提交' : create ? '加入任务池' : undefined"
      :form-id="context.binding.resource.resourceId"
      :read-only="
        readonly ||
        (!!task && !task.canExecute) ||
        (!!task && task.status !== 'RUNNING') ||
        ['SUBMITTED', 'APPLY_FAILED'].includes(context.handling?.outcome || '')
      "
      @saved="saved"
      @cancel="cancelled"
    />
  </div>
</template>
