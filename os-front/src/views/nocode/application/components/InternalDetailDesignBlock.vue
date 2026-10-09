<script setup lang="ts">
import type { ObjectDetail } from '@/types/nocode/data-center'
import type { UiNode } from '@/types/nocode/application-ui'
import { computed } from 'vue'
import { MemberState } from '@/types/nocode/enums'
const props = defineProps<{
  definition?: ObjectDetail
  title?: string
  mode?: string
  nodes?: UiNode[]
  selectedFieldId?: string
}>()
const emit = defineEmits<{ select: [fieldId?: string] }>()
const columns = computed(() =>
  props.nodes
    ? props.nodes.map(node => ({
        id: node.fieldId || '',
        name:
          node.presentation?.label || props.definition?.fields.find(f => f.id === node.fieldId)?.name || '已失效字段'
      }))
    : props.definition?.fields
        .filter(f => props.definition?.fieldOptions[f.id!]?.state !== MemberState.INACTIVE)
        .map(f => ({ id: f.id!, name: f.name })) || []
)
</script>
<template>
  <section class="internal-detail-design" :data-design-detail-id="definition?.id">
    <!-- 点击同时冒泡给引擎框选，首次点击无需等待大纲索引完成。 -->
    <button type="button" class="internal-detail-title" @click="emit('select')">
      <strong>{{ title || definition?.name || '已失效内部明细' }}</strong>
      <span>内部明细 · {{ mode === 'CARDS' ? '卡片' : '表格' }}</span>
    </button>
    <div class="internal-detail-columns" :class="{ 'internal-detail-cards': mode === 'CARDS' }">
      <button
        v-for="column in columns"
        :key="column.id"
        type="button"
        :class="{ selected: selectedFieldId === column.id }"
        :aria-pressed="selectedFieldId === column.id"
        :aria-label="`配置${definition?.name}的${column.name}列`"
        @click="emit('select', column.id)"
      >
        <strong>{{ column.name }}</strong>
        <span>示例内容</span>
      </button>
    </div>
    <p>点击表头设置明细，点击列配置联动、带入等属性；填写时可添加多行。</p>
  </section>
</template>
<style scoped>
.internal-detail-design {
  width: 100%;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-container, #fff);
}
.internal-detail-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px;
  border: 0;
  background: var(--bg-layout, #f8fafc);
  cursor: pointer;
  text-align: left;
}
.internal-detail-title span,
p {
  color: var(--text-secondary, #64748b);
  font-size: 12px;
}
.internal-detail-columns {
  display: flex;
  overflow-x: auto;
}
.internal-detail-columns button {
  min-width: 110px;
  flex: 1;
  padding: 0;
  background: transparent;
  border: 0;
  border-right: 1px solid var(--border-color, #e5e7eb);
  text-align: left;
  cursor: pointer;
}
.internal-detail-columns strong,
.internal-detail-columns span {
  display: block;
  padding: 10px 12px;
  white-space: nowrap;
}
.internal-detail-columns strong {
  font-size: 13px;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}
.internal-detail-columns span {
  color: var(--text-tertiary, #94a3b8);
  font-size: 12px;
}
.internal-detail-columns button:hover,
.internal-detail-columns button.selected {
  background: var(--color-primary-bg, #e6f4ff);
}
.internal-detail-columns button:focus-visible {
  outline: 2px solid var(--color-primary, #1677ff);
  outline-offset: -2px;
}
.internal-detail-cards {
  flex-wrap: wrap;
}
.internal-detail-cards button {
  flex: 1 0 40%;
}
p {
  margin: 0;
  padding: 8px 12px;
  border-top: 1px solid var(--border-color, #e5e7eb);
}
</style>
