<script setup lang="ts">
import { DownOutlined, RightOutlined } from '@ant-design/icons-vue'
import type { TaskHierarchyItem } from '@/nocode/task-hierarchy'

withDefaults(
  defineProps<{
    item: TaskHierarchyItem
    expandable?: boolean
    expanded?: boolean
    loading?: boolean
    toggleLabel?: string
    compact?: boolean
    showOutline?: boolean
    childCountLabel?: string
    showChildCount?: boolean
    personal?: boolean
  }>(),
  {
    expandable: false,
    expanded: false,
    loading: false,
    toggleLabel: '',
    showOutline: false,
    showChildCount: false,
    personal: true
  }
)
defineEmits<{ toggle: [] }>()
</script>

<template>
  <div
    class="task-hierarchy"
    :class="{
      'task-hierarchy--root': item.label === '总任务',
      'task-hierarchy--branch': item.depth > 0,
      'task-hierarchy--compact': compact,
      'task-hierarchy--personal': personal
    }"
    :data-depth="item.depth"
    :data-hierarchy="showOutline ? item.outline || undefined : undefined"
  >
    <span v-for="level in item.depth" :key="level" class="task-hierarchy__guide" aria-hidden="true">
      <span v-if="level === item.depth" class="task-hierarchy__joint" />
    </span>
    <div class="task-hierarchy__node">
      <a-button
        v-if="expandable"
        class="task-hierarchy__toggle"
        type="text"
        size="small"
        :loading="loading"
        :aria-expanded="expanded"
        :aria-label="toggleLabel || (expanded ? '收起下级任务' : '展开下级任务')"
        @click="$emit('toggle')"
      >
        <DownOutlined v-if="expanded" />
        <RightOutlined v-else />
      </a-button>
      <span v-else class="task-hierarchy__leaf" aria-hidden="true">·</span>
      <slot name="branch-actions" />
      <div class="task-hierarchy__content">
        <div v-if="!personal" class="task-hierarchy__meta">
          <span class="task-hierarchy__label">{{ compact && item.depth ? `${item.depth} 级子任务` : item.label }}</span>
          <span v-if="showOutline && item.outline" class="task-hierarchy__outline" title="当前视图中的层级编号">
            {{ item.outline }}
          </span>
          <span v-if="showChildCount && item.childCount" class="task-hierarchy__count">
            {{ childCountLabel || `${item.childCount} 项下级` }}
          </span>
        </div>
        <div class="task-hierarchy__title"><slot /></div>
        <div v-if="item.parentTitle && !compact" class="task-hierarchy__parent" :title="`上级：${item.parentTitle}`">
          上级：{{ item.parentTitle }}
        </div>
        <slot name="extra" />
      </div>
      <div
        v-if="personal && ((showChildCount && item.childCount) || item.label !== '总任务')"
        class="task-hierarchy__footer"
      >
        <span v-if="showChildCount && item.childCount" class="task-hierarchy__count">
          {{ childCountLabel || `${item.childCount} 项下级` }}
        </span>
        <span v-if="item.label !== '总任务'" class="task-hierarchy__label">{{ item.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.task-hierarchy {
  display: flex;
  align-items: stretch;
  min-width: 0;
  text-align: left;
}
.task-hierarchy__guide {
  position: relative;
  flex: 0 0 var(--spacing-xl);
  border-left: 1px solid var(--border-hover);
  margin-block: calc(-1 * var(--spacing-sm));
}
.task-hierarchy__joint {
  position: absolute;
  top: 50%;
  left: 0;
  width: var(--spacing-lg);
  border-top: 1px solid var(--border-hover);
}
.task-hierarchy__node {
  display: flex;
  align-items: flex-start;
  flex: 1;
  min-width: 0;
  gap: var(--spacing-xs);
  padding-block: var(--spacing-xs);
}
.task-hierarchy--root .task-hierarchy__node {
  background: var(--brand-light);
  border-radius: var(--radius-sm);
  padding-right: var(--spacing-sm);
}
.task-hierarchy__toggle,
.task-hierarchy__leaf {
  flex: 0 0 var(--control-height-sm);
  width: var(--control-height-sm);
  margin-top: var(--spacing-xl);
  text-align: center;
  color: var(--brand);
}
.task-hierarchy__content {
  flex: 1;
  min-width: 0;
}
.task-hierarchy__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
  font-size: var(--table-font-sm);
  color: var(--text-secondary);
}
.task-hierarchy__label {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding-inline: var(--spacing-xs);
  background: var(--neutral-bg);
  white-space: nowrap;
}
.task-hierarchy--root .task-hierarchy__label {
  border-color: var(--brand);
  color: var(--brand);
  font-weight: 600;
  background: var(--brand-light);
}
.task-hierarchy__outline {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.task-hierarchy__title {
  min-width: 0;
  overflow-wrap: anywhere;
}
.task-hierarchy__title :deep(.ant-btn-link) {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: left;
  overflow-wrap: anywhere;
}
.task-hierarchy--root .task-hierarchy__title :deep(.ant-btn-link) {
  font-weight: 600;
}
.task-hierarchy__parent {
  margin-top: var(--spacing-xs);
  font-size: var(--table-font-sm);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-hierarchy--compact .task-hierarchy__guide {
  flex-basis: 20px;
}
.task-hierarchy--compact .task-hierarchy__node {
  padding-block: 0;
}
.task-hierarchy--compact .task-hierarchy__meta {
  margin-bottom: 2px;
}
.task-hierarchy--personal .task-hierarchy__node {
  align-items: center;
  padding-block: var(--spacing-sm);
}
.task-hierarchy--personal .task-hierarchy__toggle,
.task-hierarchy--personal .task-hierarchy__leaf {
  margin-top: 0;
}
.task-hierarchy__footer {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--spacing-sm);
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
</style>
