<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { taskDate } from '@/nocode/task-center'
import { taskHierarchy } from '@/nocode/task-hierarchy'
import type { TaskNodeInput, TaskSchedulePreview } from '@/types/nocode/task-center'

const props = defineProps<{ preview: TaskSchedulePreview; nodes: TaskNodeInput[] }>()
const hierarchy = computed(() => taskHierarchy(props.nodes))
const columns = [
  { title: '任务 / 子任务', key: 'title', width: 320 },
  { title: '预计开始', key: 'expectedStart', width: 150 },
  { title: '预计完成', key: 'expectedEnd', width: 150 },
  { title: '工期（天）', key: 'durationDays', width: 110 },
  { title: '排期提示', key: 'notice', width: 280 }
]
const rows = computed(() => {
  const lookup = new Map(props.preview.nodes.map(node => [node.id, node]))
  return props.nodes
    .flatMap(node => {
      const dates = lookup.get(node.id)
      return dates ? [{ ...dates, parentId: node.parentId }] : []
    })
    .sort((left, right) =>
      (hierarchy.value.get(left.id)?.outline || '').localeCompare(
        hierarchy.value.get(right.id)?.outline || '',
        undefined,
        { numeric: true }
      )
    )
})
function basis(id: string) {
  const mode = props.nodes.find(node => node.id === id)?.schedule.mode
  if (mode === 'FIXED') return '固定日期'
  if (hierarchy.value.get(id)?.childCount && ['AUTO', 'UNSCHEDULED'].includes(mode || '')) return '汇总下级日期'
  return mode === 'AUTO' ? '跟随任务顺序' : mode === 'UNSCHEDULED' ? '未排期' : '按时间规则计算'
}
function durationDays(start: string | null, end: string | null): number | string {
  if (!start || !end) return '—'
  // 只展示预览日期的自然日跨度，汇总任务不能累加可能并行的子任务工期。
  const days = dayjs(end).startOf('day').diff(dayjs(start).startOf('day'), 'day')
  return Number.isFinite(days) && days >= 0 ? days : '—'
}
</script>

<template>
  <section class="task-schedule-preview" aria-label="整组排期预览">
    <a-alert v-if="preview.warnings.length" type="warning" show-icon message="部分任务需要检查排期，请查看下方提示。" />
    <OsTablePage
      :columns="columns"
      :data-source="rows"
      row-key="id"
      :pagination="false"
      :show-index="false"
      :scroll="{ x: 890, y: 360 }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <div
          v-if="column.key === 'title'"
          class="task-schedule-preview__name"
          :class="{ 'task-schedule-preview__root': !record.parentId }"
          :style="{ paddingInlineStart: `${(hierarchy.get(record.id)?.depth || 0) * 16}px` }"
        >
          <span class="task-schedule-preview__outline">{{ hierarchy.get(record.id)?.outline }}</span>
          {{ record.title }}
        </div>
        <template v-else-if="column.key === 'expectedStart' || column.key === 'expectedEnd'">
          {{ taskDate(record[column.key]) }}
        </template>
        <template v-else-if="column.key === 'durationDays'">
          {{ durationDays(record.expectedStart, record.expectedEnd) }}
        </template>
        <div v-else-if="column.key === 'notice'" class="task-schedule-preview__notice">
          <span>{{ basis(record.id) }}</span>
          <span v-if="record.partial && basis(record.id) !== '未排期'" class="task-schedule-preview__warning">
            部分未排期
          </span>
          <span v-for="warning in record.warnings" :key="warning" class="task-schedule-preview__warning">
            {{ warning }}
          </span>
        </div>
      </template>
    </OsTablePage>
    <details v-if="preview.warnings.length" class="task-schedule-preview__warnings">
      <summary>查看全部排期提醒（{{ preview.warnings.length }}）</summary>
      <ul>
        <li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </details>
    <p class="task-list__hint">工期按自然日计算；标准工时单独计量。预计日期不会代替实际开始与完成时间。</p>
  </section>
</template>

<style scoped>
.task-schedule-preview {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-width: 0;
}
.task-schedule-preview__name {
  overflow-wrap: anywhere;
}
.task-schedule-preview__root {
  font-weight: 600;
  color: var(--brand);
}
.task-schedule-preview__outline {
  margin-right: var(--spacing-sm);
  color: var(--text-secondary);
}
.task-schedule-preview__notice {
  display: flex;
  flex-direction: column;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-schedule-preview__warning {
  color: var(--color-warning, #d48806);
}
.task-schedule-preview__warnings {
  color: var(--text-secondary);
}
.task-schedule-preview__warnings summary {
  cursor: pointer;
}
.task-schedule-preview p {
  margin: 0;
}
</style>
