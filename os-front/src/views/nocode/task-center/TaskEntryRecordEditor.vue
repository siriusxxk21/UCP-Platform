<script setup lang="ts">
import { computed, onBeforeUnmount, provide, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { nocodePlatformKey, useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskWorkFormTarget } from '@/types/nocode/task-work-entries'
import type { TaskFormContext } from '@/types/nocode/task-center'
import type { SaveRecord, Aggregate } from '@/types/nocode/runtime'
import type { HandlingResult } from '@/types/nocode/handling'
import RecordEditor from '../application/components/RecordEditor.vue'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { taskFormAccessKey, taskFormFiles, taskFormRuntime } from '@/nocode/task-form-access'
const props = defineProps<{ target: TaskWorkFormTarget; readonly?: boolean; snapshot?: Aggregate }>()
const emit = defineEmits<{ saved: []; cancel: [] }>()
const platform = useNocodePlatform(),
  api = platform.taskCenter
const { confirmDiscard } = useTaskConfirmation()
const context = ref<TaskFormContext>(),
  loading = ref(false),
  error = ref('')
const editor = ref<InstanceType<typeof RecordEditor>>()
const receipts = new Map<string, HandlingResult>()
const submissionLocation = computed(() => {
  const requestId = context.value?.handling?.request?.id
  return requestId
    ? {
        path: '/nocode-app/process-record',
        query: { id: `nocode-handling:${requestId}`, taskId: props.target.taskId }
      }
    : undefined
})
defineExpose({ requestClose: () => editor.value?.requestClose() ?? Promise.resolve(true) })
let generation = 0
onBeforeUnmount(() => generation++)
async function load() {
  const target = props.target
  const current = ++generation
  loading.value = true
  error.value = ''
  context.value = undefined
  try {
    const response = await api.entryForm(target)
    if (current === generation) context.value = response
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.target, load, { immediate: true })
const runtime = taskFormRuntime(platform.runtime, {
  evaluateFieldRules: query => api.entryFieldRules(props.target, query),
  selection: (...args: Parameters<typeof platform.runtime.selection>) => api.entrySelection(props.target, ...args),
  formFill: (...args: Parameters<typeof platform.runtime.formFill>) => api.entryFill(props.target, ...args),
  relatedForm: (...args: Parameters<typeof platform.runtime.relatedForm>) => api.entryRelated(props.target, ...args),
  relatedSelection: (...args: Parameters<typeof platform.runtime.relatedSelection>) =>
    api.entryRelatedSelection(props.target, ...args),
  relatedFill: (...args: Parameters<typeof platform.runtime.relatedFill>) =>
    api.entryRelatedFill(props.target, ...args),
  submit: async (record: SaveRecord) => {
    if (props.readonly) throw new Error('此入口当前只读')
    const response = await api.entrySave(props.target, record)
    if (record.requestKey) receipts.set(record.requestKey, response.handling)
    return response.handling
  },
  submitReceipt: async (_app: string, _object: string, key: string) =>
    receipts.get(key) || (await api.entryReceipt(props.target, key))?.handling || null
})
provide(taskFormAccessKey, {
  scope: () => `task:${props.target.taskId}:${props.target.entryKey}`,
  relatedFieldRules: (source, query) => api.entryRelatedFieldRules(props.target, source, query)
})
const bizFiles = taskFormFiles(
  platform.bizFiles,
  api,
  () => ({ ...props.target, recordId: context.value?.record?.record.id || props.target.recordId }),
  () =>
    context.value
      ? { applicationId: context.value.binding.resource.applicationId, objectId: context.value.model.object.objectId }
      : undefined
)
provide(nocodePlatformKey, { ...platform, runtime, bizFiles })
</script>
<template>
  <a-spin v-if="loading" />
  <template v-else-if="error">
    <a-alert type="error" show-icon :message="error" />
    <a-button @click="load">重新加载表单</a-button>
  </template>
  <template v-else-if="context">
    <a-alert
      v-if="['REJECTED', 'CANCELED'].includes(context.handling?.outcome || '')"
      type="warning"
      :message="
        context.handling?.outcome === 'REJECTED' ? '审批未通过，可编辑后重新提交。' : '申请已撤回，可编辑后重新提交。'
      "
    />
    <a-alert
      v-if="['SUBMITTED', 'APPLY_FAILED'].includes(context.handling?.outcome || '')"
      :type="context.handling?.outcome === 'APPLY_FAILED' ? 'error' : 'info'"
      :message="
        context.handling?.outcome === 'APPLY_FAILED'
          ? '审批已通过，但数据保存失败。请打开审批详情查看原因并重试。'
          : '本次提交正在审批，审批通过后更新业务数据。'
      "
      :description="context.handling?.outcome === 'APPLY_FAILED' ? context.handling.request?.error : undefined"
    >
      <template #action>
        <RouterLink v-if="submissionLocation" :to="submissionLocation">查看审批详情</RouterLink>
      </template>
    </a-alert>
    <RecordEditor
      ref="editor"
      :application-id="context.binding.resource.applicationId"
      :model="context.model"
      :record="snapshot || context.record || undefined"
      :form="context.form"
      :form-id="context.binding.resource.resourceId"
      :pending-scope="`task-feedback:${target.taskId}:${target.entryKey}:${target.contributionId || ''}`"
      :read-only="!!snapshot || readonly || ['SUBMITTED', 'APPLY_FAILED'].includes(context.handling?.outcome || '')"
      :confirm-leave="confirmDiscard"
      interaction-display-mode="modal"
      @saved="emit('saved')"
      @cancel="emit('cancel')"
    />
  </template>
</template>
