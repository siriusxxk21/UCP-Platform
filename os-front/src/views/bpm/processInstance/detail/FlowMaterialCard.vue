<script setup lang="ts">
import type { FlowMaterialDetail, FlowMaterialItem } from '@/api/nocode/flow-material'
import { formatDateTime } from '@/utils/format'
import MaterialFormView from './MaterialFormView.vue'
defineProps<{
  item: FlowMaterialItem
  ordinal: number
  detail?: FlowMaterialDetail
  loading?: boolean
  error?: string
  approvalRequired?: boolean
}>()
defineEmits<{ retry: []; failed: [reason: string] }>()
</script>
<template>
  <article class="material-card" :class="{ 'is-history': item.state === 'HISTORY' }" :data-material-id="item.id">
    <header class="material-card-header">
      <div class="material-heading-row">
        <span class="material-number">{{ String(ordinal).padStart(2, '0') }}</span>
        <span class="material-heading">
          <strong>{{ item.nodeName || item.formName }}</strong>
          <small>{{ item.submitterName || '未记录提交人' }} · {{ formatDateTime(item.submittedAt) }} 提交</small>
        </span>
        <a-tag :color="item.state === 'HISTORY' ? 'default' : item.state === 'UNAVAILABLE' ? 'warning' : undefined">
          {{ item.state === 'HISTORY' ? '历史提交' : item.state === 'UNAVAILABLE' ? '暂不可读' : '只读' }}
        </a-tag>
      </div>
      <div class="material-card-actions">
        <slot name="versions">
          <span>第 {{ item.revision }} 次提交</span>
        </slot>
      </div>
    </header>
    <div class="material-card-body">
      <a-alert
        v-if="item.warning && item.state !== 'UNAVAILABLE' && !error"
        :message="item.warning"
        type="warning"
        show-icon
        class="material-warning"
      />
      <a-alert
        v-if="error || item.state === 'UNAVAILABLE'"
        type="error"
        show-icon
        :message="error || item.warning || '该材料暂不可读取'"
        :description="
          approvalRequired && item.required
            ? '此材料为审批必需材料，恢复可读后才能通过。'
            : '可切换其他步骤查阅已提交材料。'
        "
      >
        <template #action><a-button size="small" @click="$emit('retry')">重新读取</a-button></template>
      </a-alert>
      <a-skeleton v-else-if="loading || !detail" active :paragraph="{ rows: 3 }" />
      <MaterialFormView v-else :detail="detail" @failed="$emit('failed', $event)" />
      <footer>
        <span>表单：{{ item.formName }}</span>
        <span>
          {{
            item.state === 'UNAVAILABLE'
              ? '暂未取得提交内容'
              : item.state === 'HISTORY'
                ? '历史提交内容'
                : '展示提交时内容'
          }}
        </span>
      </footer>
    </div>
  </article>
</template>
<style scoped>
.material-card {
  min-width: 0;
  background: #fff;
}
.material-card.is-history {
  background: #fafbfc;
}
.material-card-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 12px;
}
.material-heading-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
}
.material-number {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--ant-primary-color, #5135cf);
  color: white;
  font-size: 13px;
}
.material-heading {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.material-heading strong {
  font-size: 15px;
  overflow-wrap: anywhere;
}
.material-heading small,
.material-card-actions {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.material-card-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.material-card-body {
  border-top: 1px solid #f0f1f4;
  padding: 16px 12px 0;
}
.material-warning {
  margin-bottom: 16px;
}
.material-card-body footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.material-card-body :deep(.os-read-field) {
  --os-form-field-gap: 10px;
}
.material-card-body :deep(.os-read-horizontal > .os-read-field) {
  border-bottom: 1px solid #f0f1f4;
  padding-bottom: 8px;
}
@media (max-width: 767px) {
  .material-card-header {
    padding: 12px 0;
  }
  .material-card-actions {
    margin-left: 0;
    max-width: 100%;
  }
  .material-heading-row {
    flex-basis: 100%;
  }
}
</style>
