<script setup lang="ts">
import type { TaskMaterial } from '@/types/nocode/task-center'
import { taskTime } from '@/nocode/task-center'
import RecordEditor from '../application/components/RecordEditor.vue'

defineProps<{ material: TaskMaterial }>()
</script>
<template>
  <RecordEditor
    v-if="material.binding && material.model && material.record"
    :application-id="material.binding.resource.applicationId"
    :model="material.model"
    :record="material.record"
    read-only
    hide-footer
  />
  <section v-for="entry in material.entries || []" :key="entry.entryKey" class="task-submission-material">
    <h4>{{ entry.name }}</h4>
    <template v-if="entry.model && entry.binding">
      <article
        v-for="item in entry.records.filter(
          item => item.record && !entry.submissions.some(s => s.contributionId === item.id)
        )"
        :key="item.id"
      >
        <p
          v-for="source in item.sources"
          :key="`${source.taskId}:${source.revision}:${source.time}`"
          class="task-list__hint"
        >
          {{ source.taskTitle }} · {{ source.actorName }} · {{ taskTime(source.time) }}
        </p>
        <RecordEditor
          v-if="item.record"
          :application-id="entry.binding.resource.applicationId"
          :model="entry.model"
          :record="{ record: item.record, details: {} }"
          read-only
          hide-footer
        />
      </article>
      <article v-for="submission in entry.submissions" :key="submission.contributionId">
        <p
          v-for="source in submission.sources"
          :key="`${source.taskId}:${source.revision}:${source.time}`"
          class="task-list__hint"
        >
          {{ source.taskTitle }} · {{ source.actorName }} · {{ taskTime(source.time) }}
        </p>
        <RecordEditor
          :application-id="submission.binding.resource.applicationId"
          :model="entry.model"
          :record="submission.record"
          read-only
          hide-footer
        />
      </article>
    </template>
    <p v-else class="task-list__hint">当前权限下没有可查看的材料内容。</p>
    <p v-if="!entry.records.some(item => item.record) && !entry.submissions.length" class="task-list__hint">
      本次未提交此项材料。
    </p>
  </section>
  <a-empty
    v-if="!material.record && !material.entries?.length"
    description="本次没有可展示的业务材料，可结合提交说明和任务信息验收。"
  />
</template>
<style scoped>
.task-submission-material {
  margin-top: 20px;
}
.task-submission-material article {
  margin-bottom: 16px;
}
</style>
