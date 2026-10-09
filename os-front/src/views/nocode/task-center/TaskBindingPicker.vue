<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskBinding } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import TaskEntrySelector from './TaskEntrySelector.vue'

const binding = defineModel<TaskBinding | null>({ required: true })
const props = defineProps<{ entryMode?: 'LIST'; fieldsOnly?: boolean; applicationId?: string | null }>()
const emit = defineEmits<{
  fields: [fields: Array<{ value: string; label: string }>]
  readFields: [fields: Array<{ value: string; label: string }>]
  ruleFields: [fields: ObjectField[]]
  fieldOptions: [options: Record<string, FieldOptions>]
  metadata: [value: { name: string; applicationName: string; objectName: string; viewName?: string }]
}>()
const platform = useNocodePlatform(),
  entriesApi = createTaskEntryApi(request)
const selectorOpen = ref(false),
  error = ref(''),
  busy = ref(false),
  resolved = ref<{ name: string; applicationName: string; objectName: string; viewName?: string }>()
// 共用候选选择和旧入口保护；这个临时配置只供选择器使用，不改变业务绑定的存储结构。
const selectedEntries = computed<TaskWorkEntryConfig[]>(() =>
  binding.value
    ? [
        {
          key: 'business',
          name: resolved.value?.name || '原业务表单（保留原配置）',
          binding: binding.value,
          dataMode: 'INDEPENDENT',
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: null,
          writableFieldIds: null,
          required: false,
          allowAll: false
        }
      ]
    : []
)
let generation = 0
watch(
  () => [
    binding.value?.applicationId,
    binding.value?.viewId,
    binding.value?.formId,
    binding.value?.entryId,
    props.applicationId
  ],
  async () => {
    const token = ++generation
    error.value = ''
    resolved.value = undefined
    busy.value = false
    emit('fields', [])
    emit('readFields', [])
    emit('ruleFields', [])
    emit('fieldOptions', {})
    const current = binding.value
    if (!current) return
    if (props.applicationId !== undefined && current.applicationId !== props.applicationId) {
      error.value = '原业务表单不属于当前应用；原引用已保留，请重新选择。'
      return
    }
    busy.value = true
    try {
      if (current.entryId) {
        // 已有入口保持其授权路径；不能换成直接表单请求来扩大读写权限。
        const context = await entriesApi.context({ applicationId: current.applicationId, entryId: current.entryId })
        if (token !== generation) return
        if (current.formId && context.config.formId !== current.formId)
          throw new Error('原业务配置对应的表单已变更，原配置已保留，请核对后重新选择。')
        const form = context.resources.find(
          resource => resource.kind === 'FORM' && resource.id === context.config.formId
        )
        if (!form) throw new Error('原业务表单已不可用，原配置已保留。')
        resolved.value = {
          name: form.name,
          applicationName: context.entry.applicationName,
          objectName: context.model.object.objectName
        }
        emit('metadata', resolved.value)
        emit('fieldOptions', context.model.object.fieldOptions)
        emit(
          'ruleFields',
          context.model.object.fields.filter(f => context.model.permissions.readFields.includes(f.id!))
        )
        emit(
          'fields',
          context.model.object.fields
            .filter(f => context.model.permissions.writeFields.includes(f.id!))
            .map(f => ({ value: f.id!, label: f.name }))
        )
        emit(
          'readFields',
          context.model.object.fields
            .filter(f => context.model.permissions.readFields.includes(f.id!))
            .map(f => ({ value: f.id!, label: f.name }))
        )
      } else if (current.formId) {
        const app = await platform.runtime.application(current.applicationId)
        if (token !== generation) return
        const form = app.definition.resources.find(r => r.kind === 'FORM' && r.id === current.formId)
        if (!form?.config.objectId) throw new Error('原业务表单已不可用，原配置已保留。')
        const view = current.viewId
          ? app.definition.resources.find(r => r.kind === 'VIEW' && r.id === current.viewId)
          : undefined
        if (current.viewId && (!view || view.config.formId !== form.id || view.config.composition))
          throw new Error('关联视图或对应表单已变更；原配置保留，请重新核对。')
        const model = await platform.runtime.model(current.applicationId, String(form.config.objectId))
        if (token !== generation) return
        resolved.value = {
          name: form.name,
          applicationName: app.application?.name || '原应用',
          objectName: model.object.objectName,
          viewName: view?.name
        }
        emit('metadata', resolved.value)
        emit('fieldOptions', model.object.fieldOptions)
        emit(
          'ruleFields',
          model.object.fields.filter(f => model.permissions.readFields.includes(f.id!))
        )
        emit(
          'fields',
          model.object.fields
            .filter(f => model.permissions.writeFields.includes(f.id!))
            .map(f => ({ value: f.id!, label: f.name }))
        )
        emit(
          'readFields',
          model.object.fields
            .filter(f => model.permissions.readFields.includes(f.id!))
            .map(f => ({ value: f.id!, label: f.name }))
        )
      }
    } catch (e) {
      if (token === generation) error.value = errorMessage(e)
    } finally {
      if (token === generation) busy.value = false
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => generation++)
function confirmSelection(entries: TaskWorkEntryConfig[]) {
  const selected = entries[0]?.binding
  if (
    selected &&
    (selected.applicationId !== binding.value?.applicationId ||
      selected.entryId !== binding.value?.entryId ||
      selected.viewId !== binding.value?.viewId ||
      selected.formId !== binding.value?.formId)
  )
    binding.value = { ...selected }
  selectorOpen.value = false
}
</script>
<template>
  <div class="task-binding-picker">
    <template v-if="!fieldsOnly">
      <div v-if="binding" class="task-binding-picker__summary" aria-label="已选业务表单">
        <strong>
          {{ resolved?.viewName || resolved?.name || (busy ? '正在核对业务视图…' : '原业务关联（保留原配置）') }}
        </strong>
        <span v-if="resolved">数据对象：{{ resolved.objectName }} · 所属应用：{{ resolved.applicationName }}</span>
      </div>
      <a-space wrap>
        <a-button size="small" type="dashed" :disabled="applicationId === null" @click="selectorOpen = true">
          {{ binding ? '更换业务视图' : '选择业务视图' }}
        </a-button>
        <a-button v-if="binding" size="small" type="link" @click="binding = null">移除关联</a-button>
      </a-space>
      <TaskEntrySelector
        :open="selectorOpen"
        :entries="selectedEntries"
        :multiple="false"
        :application-id="applicationId"
        @cancel="selectorOpen = false"
        @confirm="confirmSelection"
      />
    </template>
    <a-alert v-if="error" type="warning" :message="error" />
  </div>
</template>
<style scoped>
.task-binding-picker,
.task-binding-picker__summary {
  display: grid;
  gap: var(--spacing-sm);
}
.task-binding-picker__summary span {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
</style>
