<script setup lang="ts">
import { computed } from 'vue'
import { datasetKey, datasetScopeFields } from '@/nocode/report-dataset-editor'
import { emptyScope } from '@/nocode/data-scope'
import type { DatasetAnalysis, DatasetMetric, DatasetSource, ReportObjectVersion } from '@/types/nocode/report-center'
import DataScopeEditor from '../../components/DataScopeEditor.vue'
const props = defineProps<{
  source: DatasetSource | null
  catalog: Record<string, ReportObjectVersion>
  readonly?: boolean
}>()
const model = defineModel<DatasetAnalysis>({ required: true })
const fields = computed(() => datasetScopeFields(props.source, props.catalog))
const operations = [
  { label: '记录数', value: 'COUNT' },
  { label: '非空计数', value: 'COUNT_FIELD' },
  { label: '去重计数', value: 'COUNT_DISTINCT' },
  { label: '求和', value: 'SUM' },
  { label: '平均值', value: 'AVG' },
  { label: '最小值', value: 'MIN' },
  { label: '最大值', value: 'MAX' },
  { label: '聚合后四则', value: 'FORMULA' }
]
function metricFields(operation: string) {
  return (props.source?.fields || [])
    .filter(f => !f.path.length && (['COUNT_FIELD', 'COUNT_DISTINCT'].includes(operation) || f.role === 'MEASURE'))
    .map(f => ({ value: f.id, label: f.name }))
}
function changeOperation(metric: DatasetMetric) {
  metric.fieldId = null
  metric.conditions = null
  metric.formula = metric.operation === 'FORMULA' ? { operator: 'DIVIDE', left: '', right: '' } : null
}
function operands(id: string) {
  return model.value.metrics.filter(m => m.id !== id).map(m => ({ value: m.id, label: m.name }))
}
function referenced(id: string) {
  return model.value.metrics.some(m => m.formula?.left === id || m.formula?.right === id)
}
</script>
<template>
  <a-form layout="vertical">
    <a-form-item label="统计时区" extra="日期分桶按照此时区计算。">
      <a-select
        v-model:value="model.timeZone"
        aria-label="统计时区"
        :disabled="readonly"
        :options="[...new Set(['Asia/Shanghai', 'UTC', model.timeZone])].map(value => ({ value, label: value }))"
      />
    </a-form-item>
    <a-table
      row-key="id"
      :scroll="{ x: 'max-content' }"
      :data-source="model.metrics"
      :pagination="false"
      size="small"
      :columns="[
        { key: 'name', title: '指标名称', width: 200 },
        { key: 'operation', title: '聚合方式', width: 150 },
        { key: 'field', title: '统计字段', width: 280 },
        { key: 'action', title: '操作', width: 80, fixed: 'right' }
      ]"
    >
      <template #bodyCell="{ column, record }">
        <a-input
          v-if="column.key === 'name'"
          v-model:value="record.name"
          aria-label="指标名称"
          :disabled="readonly"
          :maxlength="80"
        />
        <a-select
          v-else-if="column.key === 'operation'"
          v-model:value="record.operation"
          aria-label="聚合方式"
          :disabled="readonly"
          :options="operations"
          @change="changeOperation(record)"
        />
        <template v-else-if="column.key === 'field'">
          <a-space v-if="record.operation === 'FORMULA' && record.formula" direction="vertical" class="formula-fields">
            <a-select
              v-model:value="record.formula.left"
              aria-label="左侧指标"
              :disabled="readonly"
              :options="operands(record.id)"
              placeholder="左侧指标"
            />
            <a-select
              v-model:value="record.formula.operator"
              aria-label="四则运算"
              :disabled="readonly"
              :options="[
                { label: '＋ 加', value: 'ADD' },
                { label: '－ 减', value: 'SUBTRACT' },
                { label: '× 乘', value: 'MULTIPLY' },
                { label: '÷ 除', value: 'DIVIDE' }
              ]"
            />
            <a-select
              v-model:value="record.formula.right"
              aria-label="右侧指标"
              :disabled="readonly"
              :options="operands(record.id)"
              placeholder="右侧指标"
            />
          </a-space>
          <span v-else-if="record.operation === 'COUNT'">全部记录</span>
          <a-select
            v-else
            v-model:value="record.fieldId"
            aria-label="统计字段"
            :disabled="readonly"
            :options="metricFields(record.operation)"
          />
        </template>
        <a-button
          v-else-if="column.key === 'action' && !readonly"
          type="link"
          danger
          :disabled="referenced(record.id)"
          :title="referenced(record.id) ? '请先移除引用此指标的公式' : undefined"
          @click="model.metrics = model.metrics.filter(m => m.id !== record.id)"
        >
          移除
        </a-button>
      </template>
    </a-table>
    <a-button
      v-if="!readonly"
      class="add-metric"
      :disabled="model.metrics.length >= 10"
      @click="model.metrics.push({ id: datasetKey('metric'), name: '记录数', operation: 'COUNT', fieldId: null })"
    >
      添加指标
    </a-button>
    <a-alert
      class="metric-help"
      type="info"
      show-icon
      message="四则运算在聚合后计算；合计按全部匹配记录重新计算，除数为零时显示空值。被公式引用的指标需先解除引用才能删除。"
    />
    <a-collapse class="metric-conditions">
      <a-collapse-panel
        v-for="metric in model.metrics.filter(m => m.operation !== 'FORMULA')"
        :key="metric.id"
        :header="metric.name + ' · 指标条件'"
      >
        <a-form-item label="仅收窄此指标" extra="与固定筛选、临时筛选和数据权限取交集，不影响其他指标。">
          <a-switch
            :checked="!!metric.conditions"
            :aria-label="metric.name + '指标条件'"
            :disabled="readonly"
            @change="
              (value: boolean) => {
                metric.conditions = value ? emptyScope() : null
              }
            "
          />
        </a-form-item>
        <DataScopeEditor v-if="metric.conditions" v-model="metric.conditions" :fields="fields" :readonly="readonly" />
      </a-collapse-panel>
    </a-collapse>
    <a-divider />
    <a-form-item label="固定筛选条件" extra="预览和所有已发布查询都会叠加此条件。">
      <a-switch
        :checked="!!model.fixedConditions"
        :disabled="readonly"
        checked-children="已开启"
        un-checked-children="未开启"
        @change="
          (value: boolean) => {
            model.fixedConditions = value ? emptyScope() : null
          }
        "
      />
    </a-form-item>
    <DataScopeEditor
      v-if="model.fixedConditions"
      v-model="model.fixedConditions"
      :fields="fields"
      :readonly="readonly"
    />
  </a-form>
</template>
<style scoped>
.ant-select {
  width: 100%;
}
.add-metric {
  margin-top: var(--spacing-md);
}
.formula-fields {
  width: 100%;
}
.metric-help,
.metric-conditions {
  margin-top: var(--spacing-md);
}
</style>
