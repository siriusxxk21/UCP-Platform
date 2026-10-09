<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { computed } from 'vue'
import { ResourceKind, type PublishedObject, type ApplicationResource } from '@/types/nocode/application'
import type { ReportFilter, ReportConfig } from '@/types/nocode/report'
import { scalarField, dateField, reportDetailId, reportFieldOptions, reportFields } from '@/nocode/report'
import { sourceFilterProblem } from '@/nocode/report-sources'
const filters = defineModel<ReportFilter[]>({ required: true })
const props = defineProps<{
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
  orderedReadiness?: Record<string, Record<string, string>>
}>()
const objects = computed(() =>
  Object.values(props.objects).map(o => ({ value: o.objectId, label: o.definition.objectName }))
)
const reports = computed(() => props.resources.filter(r => r.kind === ResourceKind.REPORT))
const fields = (f: ReportFilter) => {
  const object = props.objects[f.objectId]?.definition
  return object
    ? reportFields(object, props.orderedReadiness?.[f.objectId]).filter(v =>
        f.dateRange ? dateField(v) : scalarField(v)
      )
    : []
}
function targetsChanged(f: ReportFilter, ids: string[]) {
  f.targets = Object.fromEntries(
    ids.map(id => [id, f.targets[id] || targetFields(f, id).find(v => !v.disabled)?.value || ''])
  )
}
function sourceChanged(f: ReportFilter) {
  f.fieldId = ''
  f.targets = {}
}
function targetFields(f: ReportFilter, id: string) {
  const report = reports.value.find(r => r.id === id)?.config as unknown as ReportConfig | undefined
  const source = fields(f).find(v => v.id === f.fieldId)
  const sourceTarget = props.objects[f.objectId]?.definition.relations.find(
    r => r.fieldId === f.fieldId
  )?.targetObjectId
  // 目标字段按那张统计自己的粒度取（明细粒度的统计可以把粒度明细的字段开放为筛选）。
  const options = reportFieldOptions(
    report?.objectId || '',
    props.objects,
    props.orderedReadiness,
    reportDetailId(report)
  ).filter(
    v =>
      v.field.type === source?.type &&
      props.objects[v.objectId]?.definition.relations.find(r => r.fieldId === v.field.id)?.targetObjectId ===
        sourceTarget &&
      (f.dateRange ? report?.dateFieldId === v.value : report?.filterFieldIds.includes(v.value))
  )
  // 多个数据来源的统计：这个键在某个来源里映射不到的，禁用并写明原因（契约 laneM L14）。
  return options.map((v): (typeof options)[number] & { disabled?: boolean; title?: string } => {
    const problem = report ? sourceFilterProblem(report, v.value, f.dateRange, v.label) : null
    return problem ? { ...v, disabled: true, title: problem } : v
  })
}
/** 联动统计的选项：某张多来源统计的候选字段全都映射不到时，这张统计本身也禁用并写明原因。 */
function reportChoices(f: ReportFilter) {
  return reports.value
    .map(r => ({ r, options: targetFields(f, r.id) }))
    .filter(({ options }) => options.length)
    .map(({ r, options }) =>
      options.every(v => v.disabled)
        ? { value: r.id, label: r.name, disabled: true, title: options[0].title }
        : { value: r.id, label: r.name }
    )
}
function add() {
  filters.value.push({
    id: uuidv4(),
    name: '统一筛选',
    objectId: objects.value[0]?.value || '',
    fieldId: '',
    dateRange: false,
    targets: {}
  })
}
</script>
<template>
  <a-collapse class="page-filter-config">
    <a-collapse-panel key="filters" :header="'页面统一筛选 · ' + filters.length + ' 项'">
      <p class="hint">把一个筛选项映射到多个统计视图，例如用“公司”同时更新流水总额和月度趋势。只联动明确绑定的报表。</p>
      <a-empty v-if="!filters.length" :image="undefined" description="尚未配置统一筛选，各报表可独立筛选" />
      <section v-for="(f, index) in filters" :key="f.id" class="filter-item">
        <div class="config-row">
          <a-input v-model:value="f.name" placeholder="筛选名称" :maxlength="60" />
          <a-select
            v-model:value="f.objectId"
            :options="objects"
            placeholder="字段来源对象"
            @change="sourceChanged(f)"
          />
          <a-checkbox v-model:checked="f.dateRange" @change="sourceChanged(f)">日期范围</a-checkbox>
          <a-select
            v-model:value="f.fieldId"
            :options="fields(f).map(v => ({ value: v.id!, label: v.name }))"
            placeholder="筛选字段"
            @change="f.targets = {}"
          />
          <a-button danger @click="filters.splice(index, 1)">移除</a-button>
        </div>
        <a-select
          :value="Object.keys(f.targets)"
          mode="multiple"
          show-search
          option-filter-prop="label"
          :options="reportChoices(f)"
          placeholder="选择联动的统计视图"
          class="target-select"
          @change="(ids: string[]) => targetsChanged(f, ids)"
        />
        <div v-for="(_, id) in f.targets" :key="id" class="config-row mapping">
          <span>{{ reports.find(r => r.id === id)?.name }}</span>
          <span>使用字段</span>
          <a-select v-model:value="f.targets[id]" :options="targetFields(f, id)" placeholder="目标报表的筛选字段" />
        </div>
      </section>
      <a-button :disabled="filters.length >= 10 || !reports.length" @click="add">添加统一筛选</a-button>
    </a-collapse-panel>
  </a-collapse>
</template>
<style scoped>
.page-filter-config {
  margin-bottom: 16px;
}
.hint {
  color: #64748b;
}
.filter-item {
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin-bottom: 12px;
  background: white;
}
.config-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.config-row > .ant-input,
.config-row > .ant-select {
  flex: 1;
  min-width: 160px;
}
.target-select {
  width: 100%;
  margin-bottom: 12px;
}
.mapping {
  margin: 8px 0 0;
}
</style>
