<script setup lang="ts">
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import FieldSwitchImpact from './FieldSwitchImpact.vue'
import type { FieldConversionRows, FieldSwitchPreview } from '@/types/nocode/data-center'
import type { FieldConfigurationChange } from '@/nocode/field-change-summary'

defineProps<{
  open: boolean
  fieldName: string
  targetType: string
  preview?: FieldSwitchPreview
  loading?: boolean
  error?: string
  configurationError?: string | null
  confirmed: boolean
  canConfirm: boolean
  rows?: FieldConversionRows
  rowsLoading?: boolean
  rowsError?: string
  canViewRows?: boolean
  canClear?: boolean
  canMaintainData?: boolean
  changes?: FieldConfigurationChange[]
}>()
const emit = defineEmits<{
  confirm: []
  cancel: []
  configure: []
  'update:confirmed': [value: boolean]
  page: [pageNo: number]
  retry: []
  navigate: [route: string]
  maintainData: []
}>()
</script>

<template>
  <OsModalForm
    v-if="open"
    :open="open"
    :title="`检查字段变更 · ${fieldName || '未命名字段'}`"
    :width="900"
    display-mode="modal"
    :allow-switch-display="false"
    @ok="emit('confirm')"
    @cancel="emit('cancel')"
  >
    <template #formItems>
      <FieldSwitchImpact
        :configuration-error="configurationError"
        :preview="preview"
        :target-type="targetType"
        :changes="changes"
        :loading="loading"
        :error="error"
        :confirmed="confirmed"
        :rows="rows"
        :rows-loading="rowsLoading"
        :rows-error="rowsError"
        :can-view-rows="canViewRows"
        :can-clear="canClear"
        :can-maintain-data="canMaintainData"
        @update:confirmed="emit('update:confirmed', $event)"
        @page="emit('page', $event)"
        @retry="emit('retry')"
        @navigate="emit('navigate', $event)"
        @maintain-data="emit('maintainData')"
      >
        <template #target><slot name="target" /></template>
      </FieldSwitchImpact>
    </template>
    <template #footer>
      <a-space>
        <a-button @click="emit('cancel')">取消本次变更</a-button>
        <a-button @click="emit('configure')">返回字段配置</a-button>
        <a-button type="primary" :disabled="!canConfirm" @click="emit('confirm')">确认变更方案</a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>
