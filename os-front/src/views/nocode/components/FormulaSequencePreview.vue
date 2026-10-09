<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { nocodePlatformKey } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { CalculationOptions, FormulaPreviewResult } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'

const props = defineProps<{
  expression: string
  fields: ObjectField[]
  calculation: CalculationOptions
  invalid?: boolean
}>()
const platform = inject(nocodePlatformKey, null)
const samples = ref<Record<string, string | null>[]>([{}, {}, {}])
const results = ref<NonNullable<FormulaPreviewResult['rows']>>([])
const busy = ref(false)
const error = ref('')
const cumulative = computed(() => props.calculation.sequence?.operation === 'CUMULATIVE')
const columns = computed(() => {
  const required = new Set([
    ...(props.calculation.groupFields ?? []),
    props.calculation.sequence?.orderField,
    props.calculation.sequence?.tieBreakerField
  ])
  return props.fields.filter(
    field =>
      !field.code.startsWith('__previous_') &&
      (required.has(field.code) || new RegExp('\\b(?:__previous_)?' + field.code + '\\b').test(props.expression))
  )
})
const payload = computed(() => ({
  expression: props.expression,
  fieldCodes: props.fields.filter(field => !field.code.startsWith('__previous_')).map(field => field.code),
  fieldTypes: Object.fromEntries(
    props.fields.filter(field => !field.code.startsWith('__previous_')).map(field => [field.code, field.type])
  ),
  values: {},
  rows: samples.value.map(row =>
    Object.fromEntries(
      columns.value.map(field => [field.code, row[field.code] === '' ? null : (row[field.code] ?? null)])
    )
  ),
  sequence: props.calculation.sequence,
  groupFields: props.calculation.groupFields ?? []
}))
const fingerprint = computed(() => JSON.stringify(payload.value))
watch(fingerprint, () => {
  results.value = []
  error.value = ''
})
async function preview() {
  if (props.invalid || busy.value) return
  const current = fingerprint.value
  busy.value = true
  error.value = ''
  try {
    if (!platform) throw new Error('当前页面未连接平台服务，无法试算')
    const result = await platform.dataCenter.formulaPreview(payload.value)
    if (current === fingerprint.value) results.value = result.rows ?? []
  } catch (cause) {
    if (current === fingerprint.value) error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
const resultAt = (index: number) => results.value.find(row => row.index === index)
</script>
<template>
  <section class="sequence-preview" aria-label="多行公式试算">
    <p class="hint">
      填写多条样例，服务端按分组和顺序计算。样例行号用于识别输入，同序时按样例行号排列。不会读取或保存业务数据；引用本行公式时填写该公式的样例结果。
    </p>
    <div class="preview-scroll">
      <table class="preview-table">
        <thead>
          <tr>
            <th>样例行</th>
            <th v-for="field in columns" :key="field.key">{{ field.name }}</th>
            <th>{{ cumulative ? '本笔贡献' : '相邻样例行' }}</th>
            <th>计算结果</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in samples" :key="index">
            <td>{{ index + 1 }}</td>
            <td v-for="field in columns" :key="field.key">
              <a-input
                v-model:value="row[field.code]"
                :aria-label="'样例 ' + (index + 1) + '：' + field.name"
                placeholder="留空表示空值"
                :maxlength="1000"
              />
            </td>
            <td v-if="cumulative">
              {{ resultAt(index) ? (resultAt(index)?.contribution ?? '空（按 0 累计）') : '—' }}
            </td>
            <td v-else>
              {{
                resultAt(index)
                  ? resultAt(index)?.adjacentIndex == null
                    ? '无相邻记录'
                    : resultAt(index)!.adjacentIndex! + 1
                  : '—'
              }}
            </td>
            <td>{{ resultAt(index) ? (resultAt(index)?.value ?? '空值') : '—' }}</td>
            <td>
              <a-button type="link" size="small" :disabled="samples.length <= 1" @click="samples.splice(index, 1)">
                移除
              </a-button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <a-space>
      <a-button :disabled="samples.length >= 20" @click="samples.push({})">添加样例行</a-button>
      <a-button
        type="primary"
        :loading="busy"
        :disabled="invalid || !calculation.sequence?.orderField"
        @click="preview"
      >
        按分组顺序试算
      </a-button>
    </a-space>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <p v-if="results.length" class="hint">
      已计算 {{ results.length }} 条样例。修改金额、顺序或移除历史样例后，可以再次试算后续结果。
    </p>
  </section>
</template>
<style scoped>
.sequence-preview {
  display: grid;
  gap: var(--spacing-md);
  min-width: 0;
}
.hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.preview-scroll {
  overflow-x: auto;
}
.preview-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--table-font-sm);
}
.preview-table th,
.preview-table td {
  border: 1px solid var(--border);
  padding: var(--spacing-sm);
  text-align: left;
  min-width: 100px;
}
.preview-table th {
  background: var(--neutral-bg);
}
.preview-table .ant-input {
  min-width: 130px;
}
</style>
