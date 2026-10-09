<script setup lang="ts">
import { ref } from 'vue'
import CalculationEditor from '../../src/views/nocode/components/CalculationEditor.vue'
import FormulaExpressionEditor from '../../src/views/nocode/components/FormulaExpressionEditor.vue'
import SummaryExpressionEditor from '../../src/views/nocode/components/SummaryExpressionEditor.vue'
import DataScopeEditor from '../../src/views/nocode/components/DataScopeEditor.vue'
import ViewQueryEditor from '../../src/views/nocode/application/components/ViewQueryEditor.vue'
import RecordQueryField from '../../src/views/nocode/application/components/RecordQueryField.vue'
import HyperlinkField from '../../src/views/nocode/application/components/HyperlinkField.vue'
import type { CalculationOptions, ObjectDetail } from '../../src/types/nocode/data-center'
import type { ObjectField } from '../../src/types/nocode/object'
import type { ViewQueryOptions, DataScope } from '../../src/types/nocode/data-scope'
const tab = ref('calculation')
const fields = [
  { id: 'name', key: 'name', code: 'name', name: '名称', type: 'TEXT' },
  { id: 'amount', key: 'amount', code: 'amount', name: '金额', type: 'DECIMAL' },
  { id: 'tags', key: 'tags', code: 'tags', name: '资产类型', type: 'MULTI_SELECT' },
  { id: 'department', key: 'department', code: 'department', name: '所属部门', type: 'INTEGER' }
] as ObjectField[]
const calculation = ref<CalculationOptions | null>(null)
const expression = ref<string | null>(null)
const formulaReadOnly = ref(false)
const summaryExpression = ref<string | null>(null)
const formulaFields = [
  ...fields,
  { id: 'quantity', key: 'quantity', code: 'quantity', name: '数量', type: 'INTEGER' },
  { id: 'price', key: 'price', code: 'price', name: '单价', type: 'DECIMAL' }
] as ObjectField[]
const details = [
  { id: 'items', code: 'items', name: '订单明细', state: 'ACTIVE', fields: formulaFields, fieldOptions: {} }
] as ObjectDetail[]
const scope = ref<DataScope>({
  logic: 'AND',
  conditions: [{ fieldId: 'department', operator: 'in', value: null, valueSource: 'CURRENT_DEPARTMENT_TREE' }],
  groups: []
})
const query = ref<ViewQueryOptions>({
  fixed: [{ fieldId: 'tags', operator: 'containsAny', value: ['A', 'B'] }],
  defaults: {},
  candidates: {}
})
const choices = (id: string) =>
  id === 'tags'
    ? [
        { label: '办公资产', value: 'A' },
        { label: '生产资产', value: 'B' },
        { label: '其他资产', value: 'C' }
      ]
    : []
const filter = ref<string | string[]>('A')
const hyperlink = ref({ link: 'https://example.com/manual', text: '资产使用说明' })
</script>
<template>
  <main>
    <h1>数据表单增强 · 组件验收</h1>
    <p>使用正式组件；此页只验证交互，数据库计算和权限由集成测试覆盖。</p>
    <a-radio-group v-model:value="tab" button-style="solid">
      <a-radio-button value="calculation">公式配置</a-radio-button>
      <a-radio-button value="scope">数据权限</a-radio-button>
      <a-radio-button value="query">字典范围</a-radio-button>
      <a-radio-button value="link">超链接</a-radio-button>
    </a-radio-group>
    <section v-if="tab === 'calculation'">
      <a-form layout="vertical">
        <CalculationEditor v-model="calculation" :fields="fields" :relations="[]" />
        <a-form-item v-if="!calculation || calculation.mode === 'LOCAL'" label="计算规则">
          <a-checkbox v-model:checked="formulaReadOnly">只读预览</a-checkbox>
          <FormulaExpressionEditor
            v-model="expression"
            :disabled="formulaReadOnly"
            :fields="formulaFields.filter(field => !['MULTI_SELECT'].includes(field.type))"
          />
          <pre data-testid="expression">{{ expression }}</pre>
        </a-form-item>
      </a-form>
      <pre data-testid="calculation">{{ JSON.stringify(calculation) }}</pre>
      <h2>内部明细汇总</h2>
      <SummaryExpressionEditor v-model="summaryExpression" :details="details" />
      <pre data-testid="summary-expression">{{ summaryExpression }}</pre>
    </section>
    <section v-if="tab === 'scope'">
      <DataScopeEditor v-model="scope" :fields="fields" dynamic />
      <pre data-testid="scope">{{ JSON.stringify(scope) }}</pre>
    </section>
    <section v-if="tab === 'query'">
      <ViewQueryEditor v-model="query" :fields="fields" :choices="choices" />
      <h2>运行端筛选（固定范围 A、B）</h2>
      <RecordQueryField
        v-model="filter"
        :field="fields[0]!"
        :choices="choices('tags').slice(0, 2)"
        :allowed-values="['A', 'B']"
      />
      <pre data-testid="filter-value">{{ JSON.stringify(filter) }}</pre>
      <pre data-testid="query">{{ JSON.stringify(query) }}</pre>
    </section>
    <section v-if="tab === 'link'">
      <HyperlinkField v-model="hyperlink" />
      <h2>只读显示</h2>
      <HyperlinkField :model-value="hyperlink" read-only />
    </section>
  </main>
</template>
<style scoped>
main {
  max-width: 980px;
  margin: 24px auto;
  padding: 24px;
  background: white;
}
h1 {
  font-size: 24px;
}
h2 {
  font-size: 18px;
  margin-top: 24px;
}
section {
  margin-top: 24px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 12px;
  background: #f5f5f5;
}
@media (max-width: 600px) {
  main {
    margin: 0;
    padding: 16px;
  }
}
</style>
