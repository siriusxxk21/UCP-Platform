<template>
  <div class="pagination-bar">
    <span class="pagination-bar__info">
      共 {{ total }} 条 · 当前显示第 {{ total === 0 ? 0 : (current - 1) * pageSize + 1 }} - {{ Math.min(current * pageSize, total) }} 条
    </span>
    <a-pagination
      :current="current"
      :page-size="pageSize"
      :total="total"
      :show-size-changer="true"
      :page-size-options="pageSizeOptions"
      :size="size"
      @update:current="(val: number) => emit('update:current', val)"
      @update:page-size="(val: number) => emit('update:pageSize', val)"
    />
  </div>
</template>

<script setup lang="ts">
import type { PaginationBarProps } from './types'

const props = withDefaults(defineProps<PaginationBarProps>(), {
  pageSizeOptions: () => ['10', '20', '50', '100'],
  size: 'small',
})

const emit = defineEmits<{
  'update:current': [val: number]
  'update:pageSize': [val: number]
}>()
</script>

<style scoped>
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-top: 1px solid #e5e7eb;
  background: #f9fafb;
}

.pagination-bar__info {
  font-size: 13px;
  color: #6b7280;
}
</style>
