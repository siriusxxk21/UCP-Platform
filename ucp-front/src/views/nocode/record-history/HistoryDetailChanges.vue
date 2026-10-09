<script setup lang="ts">
import type { HistoryDetailChange } from '@/types/nocode/record-history'
import { DetailChangeKind, detailRowChanges } from '@/nocode/history-details'
import { historyValue } from '@/nocode/record-history'
defineProps<{ groups: HistoryDetailChange[] }>()
</script>
<template>
  <section v-for="group in groups" :key="group.id" class="history-detail-group">
    <h4>{{ group.name }}</h4>
    <a-alert
      v-if="!group.beforeKnown || !group.afterKnown"
      type="info"
      show-icon
      message="该次操作的明细历史不完整，不能据此判断新增或删除。"
    />
    <article v-for="row in detailRowChanges(group)" :key="row.id" class="history-detail-row">
      <header>
        <a-tag :color="row.kind === 'DELETE' ? 'red' : row.kind === 'CREATE' ? 'green' : 'blue'">
          {{ DetailChangeKind[row.kind] }}
        </a-tag>
        <strong>明细记录 {{ row.id }}</strong>
        <span v-if="row.moved">第 {{ row.beforePosition }} 行 → 第 {{ row.afterPosition }} 行</span>
      </header>
      <div v-for="field in row.fields" :key="field" class="history-detail-field">
        <strong>{{ group.fields.find(f => f.id === field)?.name || field }}</strong>
        <span class="history-before">{{ row.before ? historyValue(row.before[field]) : '该行不存在' }}</span>
        <span aria-label="变为">→</span>
        <span class="history-after">{{ row.after ? historyValue(row.after[field]) : '该行已删除' }}</span>
      </div>
    </article>
  </section>
</template>
<style scoped>
.history-detail-group {
  margin-top: 16px;
  border-top: 1px solid var(--border-color, #e5e7eb);
}
.history-detail-group h4 {
  margin: 14px 0 10px;
}
.history-detail-row {
  margin: 8px 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--bg-secondary, #f8fafc);
}
.history-detail-row header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.history-detail-field {
  display: grid;
  grid-template-columns: minmax(90px, 1fr) minmax(0, 1.4fr) 16px minmax(0, 1.4fr);
  gap: 8px;
  padding: 6px 0;
  overflow-wrap: anywhere;
}
.history-before {
  color: var(--text-secondary, #64748b);
}
.history-after {
  color: var(--primary-color, #4f46e5);
}
@media (max-width: 560px) {
  .history-detail-field {
    grid-template-columns: 1fr 16px 1fr;
  }
  .history-detail-field > strong {
    grid-column: 1 / -1;
  }
}
</style>
