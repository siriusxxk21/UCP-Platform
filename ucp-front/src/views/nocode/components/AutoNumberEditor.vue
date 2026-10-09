<script setup lang="ts">
import { computed } from 'vue'
import type { AutoNumberOptions } from '@/types/nocode/data-center'
import { autoNumberError, autoNumberPreview, defaultAutoNumber } from '@/nocode/auto-number'

const props = defineProps<{
  modelValue?: AutoNumberOptions | null
  disabled?: boolean
  disabledReason?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [AutoNumberOptions] }>()
const error = computed(() => autoNumberError(props.modelValue))
function patch(value: Partial<AutoNumberOptions>) {
  if (!props.disabled) emit('update:modelValue', { ...(props.modelValue ?? defaultAutoNumber()), ...value })
}
</script>

<template>
  <section class="auto-number-editor" aria-label="自动编号规则">
    <h4>编号规则</h4>
    <a-alert v-if="disabledReason" type="info" show-icon :message="disabledReason" class="auto-number-note" />
    <template v-if="modelValue">
      <a-form-item label="固定前缀" extra="可留空；分隔符可直接写在前缀末尾，例如 HT-。">
        <a-input
          :value="modelValue.prefix"
          :maxlength="40"
          :disabled="disabled"
          placeholder="例如 HT-"
          aria-label="编号固定前缀"
          @update:value="patch({ prefix: $event })"
        />
      </a-form-item>
      <a-row :gutter="20">
        <a-col :span="12">
          <a-form-item label="日期格式">
            <a-select
              :value="modelValue.dateFormat"
              :disabled="disabled"
              aria-label="编号日期格式"
              :options="[
                { value: '', label: '不含日期' },
                { value: 'yyyy', label: '年 · 2026' },
                { value: 'yyyyMM', label: '年月 · 202609' },
                { value: 'yyyyMMdd', label: '年月日 · 20260914' }
              ]"
              @update:value="patch({ dateFormat: $event })"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="流水号重置">
            <a-select
              :value="modelValue.resetCycle"
              :disabled="disabled"
              aria-label="流水号重置周期"
              :options="[
                { value: 'NONE', label: '不重置 · 持续递增' },
                { value: 'YEAR', label: '每年重新计数' },
                { value: 'MONTH', label: '每月重新计数' },
                { value: 'DAY', label: '每天重新计数' }
              ]"
              @update:value="patch({ resetCycle: $event })"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="流水号位数" extra="不足位数补零，超出后保留完整数字。">
            <a-input-number
              :value="modelValue.sequenceLength"
              :min="1"
              :max="12"
              :precision="0"
              :disabled="disabled"
              aria-label="流水号位数"
              @update:value="patch({ sequenceLength: $event as number })"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="起始值" extra="每个新周期从此值开始，已有计数不会回退。">
            <a-input-number
              :value="modelValue.startValue"
              :min="1"
              :max="999999999999"
              :precision="0"
              :disabled="disabled"
              aria-label="流水号起始值"
              @update:value="patch({ startValue: $event as number })"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <div v-else class="auto-number-preview" role="status">
        <span>编号示例</span>
        <strong>{{ autoNumberPreview(modelValue) }}</strong>
        <span>下一条：{{ autoNumberPreview(modelValue, undefined, 1) }}</span>
      </div>
      <p class="auto-number-hint">按“前缀 + 日期 + 流水号”组合，日期按北京时间。示例从起始值展示，不占用真实编号。</p>
      <p class="auto-number-hint">
        保存并发布对象后，新记录自动生成编号；已有记录的编号保持不变。同一字段在不同应用中共享计数。
      </p>
    </template>
    <template v-else>
      <a-alert
        type="info"
        show-icon
        message="当前沿用整数自增编号"
        description="未配置格式规则时，由系统生成连续递增整数。"
      />
      <a-button v-if="!disabled" class="auto-number-enable" @click="patch({})">配置编号规则</a-button>
    </template>
  </section>
</template>

<style scoped>
.auto-number-editor {
  margin: 16px 0;
}
.auto-number-editor h4 {
  margin-bottom: 16px;
}
.auto-number-editor .ant-input-number {
  width: 100%;
}
.auto-number-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  background: var(--bg-page, #fafafa);
  overflow-wrap: anywhere;
}
.auto-number-preview strong {
  font-size: 20px;
  color: var(--ant-color-primary, #6150e8);
}
.auto-number-hint {
  color: var(--text-secondary);
  font-size: 12px;
  margin: 8px 0;
}
.auto-number-enable {
  margin-top: 12px;
}
.auto-number-note {
  margin-bottom: 12px;
}
</style>
