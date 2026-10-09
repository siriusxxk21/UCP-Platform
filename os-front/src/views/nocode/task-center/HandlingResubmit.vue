<script setup lang="ts">
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { provide, ref } from 'vue'
import request from '@/utils/request'
import type { HandlingReopen } from '@/types/nocode/handling'
import type { WorkDraft } from '@/types/nocode/work'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { nocodePlatformKey, useNocodePlatform } from '@/nocode/platform'
import { taskEntryRuntime } from '@/nocode/task-entry'
import { taskEntrySessionKey } from '@/nocode/task-entry-context'
import { editorCloseKey } from '@/nocode/edit-boundary'
import RecordEditor from '../application/components/RecordEditor.vue'
const { confirmDiscard } = useTaskConfirmation()

const props = defineProps<{ applicationId: string; context: HandlingReopen }>()
const emit = defineEmits<{ close: [] }>()
const checks = new Set<() => Promise<boolean>>()
provide(editorCloseKey, checks)
const platform = useNocodePlatform(),
  api = createTaskEntryApi(request),
  draft = ref<WorkDraft | null>(null)
const entry = props.context.entry
if (entry) {
  provide(nocodePlatformKey, {
    ...platform,
    runtime: taskEntryRuntime(platform.runtime, api, entry, () =>
      draft.value ? { id: draft.value.id, revision: draft.value.revision } : null
    )
  })
  provide(taskEntrySessionKey, {
    key: `${entry.entry.applicationId}:${entry.entry.entryId}:${entry.entry.version}`,
    saveDraft: async record =>
      (draft.value = await api.saveDraft(
        entry.entry,
        record,
        draft.value ? { id: draft.value.id, revision: draft.value.revision } : null
      )),
    loadDraft: async () => (draft.value = await api.draft(entry.entry)),
    checkDraft: () => api.draft(entry.entry)
  })
}
defineExpose({
  canClose: async () => {
    for (const check of checks) if (!(await check())) return false
    return true
  }
})
</script>
<template>
  <a-alert
    type="info"
    show-icon
    message="已恢复原申请材料。修改后按当前办理规则重新提交，旧申请及审批记录保留。"
    style="margin-bottom: 16px"
  />
  <RecordEditor
    :confirm-leave="confirmDiscard"
    interaction-display-mode="drawer"
    :application-id="applicationId"
    :model="context.model"
    :form="context.form || undefined"
    :form-id="context.formId || undefined"
    :record="context.initial"
    @saved="emit('close')"
    @cancel="emit('close')"
  />
</template>
