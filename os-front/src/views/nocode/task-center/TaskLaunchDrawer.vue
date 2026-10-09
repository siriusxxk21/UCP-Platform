<script setup lang="ts">
import type { TaskDetail, TaskDraft } from '@/types/nocode/task-center'
import TaskLaunch from './TaskLaunch.vue'

// 保留入口组件名兼容调用方；主入口已改为内容区工作区，不再叠加抽屉。
defineProps<{ initialTemplateId?: string; initialTemplateVersion?: number; draftId?: string }>()
const emit = defineEmits<{ close: []; created: [detail: TaskDetail]; draftSaved: [draft: TaskDraft] }>()
</script>
<template>
  <TaskLaunch
    class="task-launch-workspace"
    embedded
    workspace
    :initial-template-id="initialTemplateId"
    :initial-template-version="initialTemplateVersion"
    :draft-id="draftId"
    @close="emit('close')"
    @created="emit('created', $event)"
    @draft-saved="emit('draftSaved', $event)"
  />
</template>
