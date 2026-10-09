<script setup lang="ts">
import { v4 as uuid } from 'uuid'
import { computed, ref } from 'vue'
import type { DataViewComposition, DataViewSection, DataViewColumn } from '@/types/nocode/data-view'
import type { PublishedObject, ApplicationResource } from '@/types/nocode/application'
import { MemberState, RelationType } from '@/types/nocode/enums'
import { supportsAdvancedQuery, dynamicQueryField } from '@/nocode/runtime-list'
import DynamicSearch from '@/components/ucp-table-page/OsDynamicSearch.vue'

const props = defineProps<{
  objectId: string
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
}>()
const value = defineModel<DataViewComposition | null>()
const filtering = ref<DataViewSection>()
const root = computed(() => props.objects[props.objectId]?.definition)
const details = computed(() => root.value?.details.filter(d => d.state !== MemberState.INACTIVE) || [])
const sources = computed(() => {
  const choices = details.value.map(d => ({
    value: `detail:${d.id}`,
    label: `内部明细 · ${d.name}`,
    detailId: d.id,
    objectId: null as string | null,
    binding: null as DataViewSection['binding']
  }))
  for (const object of Object.values(props.objects))
    for (const relation of object.definition.relations) {
      if (relation.sourceDetailId) continue
      if (object.objectId === props.objectId && relation.targetObjectId !== props.objectId)
        choices.push({
          value: `OUTGOING:${relation.id}`,
          label: `关联对象 · ${relation.name} → ${props.objects[relation.targetObjectId]?.definition.objectName || ''}`,
          detailId: null as any,
          objectId: relation.targetObjectId,
          binding: { relationId: relation.id!, direction: 'OUTGOING' }
        })
      if (relation.targetObjectId === props.objectId && object.objectId !== props.objectId)
        choices.push({
          value: `INCOMING:${relation.id}`,
          label: `关联对象 · ${object.definition.objectName} → 当前对象`,
          detailId: null as any,
          objectId: object.objectId,
          binding: { relationId: relation.id!, direction: 'INCOMING' }
        })
    }
  return choices
})
function enable(on: boolean | string | number) {
  value.value = on ? { grain: 'ROOT', detailId: null, sections: [], columns: [] } : null
}
const sourceKey = (s: DataViewSection) =>
  s.detailId ? `detail:${s.detailId}` : `${s.binding?.direction}:${s.binding?.relationId}`
function fields(s: DataViewSection) {
  return (
    (s.detailId
      ? details.value.find(d => d.id === s.detailId)?.fields
      : props.objects[s.objectId || '']?.definition.fields
    )?.filter(f => f.id) || []
  )
}
function selectSource(s: DataViewSection, key: string) {
  const source = sources.value.find(o => o.value === key)
  if (!source) return
  Object.assign(s, {
    detailId: source.detailId,
    objectId: source.objectId,
    binding: source.binding,
    viewId: null,
    conditions: null
  })
  s.fieldIds = fields(s).map(f => f.id!)
  s.name = source.label.replace(/^(内部明细|关联对象) · /, '')
  value.value!.columns = value.value!.columns.filter(c => c.sectionId !== s.id)
}
function addSection() {
  if (!value.value || !sources.value.length) return
  const s: DataViewSection = {
    id: `s${uuid().replaceAll('-', '')}`,
    name: '子表',
    detailId: null,
    objectId: null,
    viewId: null,
    binding: null,
    fieldIds: [],
    conditions: null,
    pageSize: 10,
    showTable: true
  }
  value.value.sections.push(s)
  selectSource(s, sources.value[0]!.value)
}
function removeSection(s: DataViewSection) {
  value.value!.sections = value.value!.sections.filter(item => item.id !== s.id)
  value.value!.columns = value.value!.columns.filter(c => c.sectionId !== s.id)
}
function changeGrain() {
  if (!value.value) return
  value.value.columns = value.value.columns.filter(c => c.kind !== 'DETAIL')
  if (value.value.grain === 'ROOT') {
    value.value.detailId = null
    return
  }
  value.value.detailId ||= details.value[0]?.id || null
  if (value.value.detailId && !value.value.sections.some(s => s.detailId === value.value!.detailId)) {
    addSection()
    const s = value.value.sections.at(-1)
    if (s) selectSource(s, `detail:${value.value.detailId}`)
  }
}
function addColumn() {
  const s = value.value?.sections[0]
  if (!s) return
  value.value!.columns.push({
    id: `view_c${uuid().replaceAll('-', '')}`,
    name: `${s.name}条数`,
    sectionId: s.id,
    fieldId: null,
    kind: 'COUNT'
  })
}
function kinds(c: DataViewColumn) {
  const s = value.value?.sections.find(s => s.id === c.sectionId)
  const output = [
    { value: 'COUNT', label: '条数' },
    { value: 'SUM', label: '求和' },
    { value: 'MIN', label: '最小值' },
    { value: 'MAX', label: '最大值' }
  ]
  if (s?.detailId === value.value?.detailId && value.value?.grain === 'DETAIL')
    output.unshift({ value: 'DETAIL', label: '当前明细字段' })
  const relation = root.value?.relations.find(r => r.id === s?.binding?.relationId)
  if (s?.binding?.direction === 'OUTGOING' && relation?.kind !== RelationType.MANY_TO_MANY)
    output.unshift({ value: 'LOOKUP', label: '关联记录字段' })
  return output
}
function columnFields(c: DataViewColumn) {
  const s = value.value?.sections.find(s => s.id === c.sectionId)
  return (s ? fields(s) : [])
    .filter(
      f => supportsAdvancedQuery(f) && (c.kind !== 'SUM' || ['INTEGER', 'DECIMAL', 'MONEY', 'PERCENT'].includes(f.type))
    )
    .map(f => ({ label: f.name, value: f.id }))
}
function resetColumn(c: DataViewColumn) {
  c.kind = 'COUNT'
  c.fieldId = null
}
</script>
<template>
  <a-form-item label="多对象视图"><a-switch :checked="!!value" @change="enable" /></a-form-item>
  <template v-if="value">
    <a-alert
      type="info"
      show-icon
      message="内部明细随主记录整单保存；独立关联对象通过已配置关系连接。每组子表独立分页，汇总仅计算其可见范围。"
    />
    <a-form-item label="一行表示" style="margin-top: 16px">
      <a-radio-group v-model:value="value.grain" @change="changeGrain">
        <a-radio value="ROOT">一条主记录，可展开子表</a-radio>
        <a-radio value="DETAIL" :disabled="!details.length">一条内部明细，带出主表信息</a-radio>
      </a-radio-group>
    </a-form-item>
    <a-form-item v-if="value.grain === 'DETAIL'" label="明细来源">
      <a-select
        v-model:value="value.detailId"
        :options="details.map(d => ({ label: d.name, value: d.id }))"
        @change="changeGrain"
      />
    </a-form-item>
    <a-collapse>
      <a-collapse-panel v-for="s in value.sections" :key="s.id" :header="s.name || '未命名子表'">
        <a-form-item label="子表来源">
          <a-select :value="sourceKey(s)" :options="sources" @change="(key: unknown) => selectSource(s, String(key))" />
        </a-form-item>
        <a-form-item label="显示名称"><a-input v-model:value="s.name" :maxlength="80" /></a-form-item>
        <a-form-item v-if="s.objectId" label="沿用目标视图的范围和表单">
          <a-select
            v-model:value="s.viewId"
            allow-clear
            placeholder="可选，不选则使用对象默认表单"
            :options="
              resources
                .filter(r => r.kind === 'VIEW' && r.config.objectId === s.objectId && !r.config.composition)
                .map(r => ({ label: r.name, value: r.id }))
            "
          />
        </a-form-item>
        <a-form-item label="展示字段">
          <a-select
            v-model:value="s.fieldIds"
            mode="multiple"
            :options="fields(s).map(f => ({ label: f.name, value: f.id }))"
          />
        </a-form-item>
        <a-form-item label="每页条数"><a-input-number v-model:value="s.pageSize" :min="1" :max="100" /></a-form-item>
        <a-form-item label="在主记录下显示子表"><a-switch v-model:checked="s.showTable" /></a-form-item>
        <a-form-item label="子表固定范围">
          <a-space>
            <a-button @click="filtering = s">{{ s.conditions ? '编辑已配置的范围' : '配置固定范围' }}</a-button>
            <a-button v-if="s.conditions" @click="s.conditions = null">清除</a-button>
          </a-space>
        </a-form-item>
        <a-button danger @click="removeSection(s)">移除此子表及其关联列</a-button>
      </a-collapse-panel>
    </a-collapse>
    <a-button :disabled="!sources.length || value.sections.length >= 12" class="space" @click="addSection">
      添加子表或关联对象
    </a-button>
    <a-empty
      v-if="!sources.length"
      description="当前对象暂无内部明细或已引用对象之间的关系，请先在数据中心配置并更新应用引用。"
    />
    <a-divider>主列表关联列</a-divider>
    <div v-for="c in value.columns" :key="c.id" class="column-config">
      <a-input v-model:value="c.name" placeholder="列名称" aria-label="关联列名称" />
      <a-select
        v-model:value="c.sectionId"
        :options="value.sections.map(s => ({ label: s.name, value: s.id }))"
        @change="resetColumn(c)"
      />
      <a-select v-model:value="c.kind" :options="kinds(c)" @change="c.fieldId = null" />
      <a-select v-if="c.kind !== 'COUNT'" v-model:value="c.fieldId" :options="columnFields(c)" placeholder="来源字段" />
      <span v-else>统计当前子表范围内的记录数</span>
      <a-button danger @click="value.columns = value.columns.filter(item => item.id !== c.id)">移除</a-button>
    </div>
    <a-button :disabled="!value.sections.length || value.columns.length >= 40" @click="addColumn">
      添加关联字段或汇总列
    </a-button>
    <DynamicSearch
      v-if="filtering"
      :open="true"
      strict
      :fields="
        fields(filtering)
          .filter(supportsAdvancedQuery)
          .map(f => dynamicQueryField(f))
      "
      :model-value="filtering.conditions"
      @confirm="
        c => {
          if (filtering) filtering.conditions = c
          filtering = undefined
        }
      "
      @cancel="filtering = undefined"
      @update:open="
        open => {
          if (!open) filtering = undefined
        }
      "
    />
  </template>
</template>
<style scoped>
.space {
  margin-top: 12px;
}
.column-config {
  display: grid;
  grid-template-columns: 1fr 1fr 120px 1fr auto;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
@media (max-width: 900px) {
  .column-config {
    grid-template-columns: 1fr;
    border-bottom: 1px solid var(--os-border-color, #e5e7eb);
    padding-bottom: 12px;
  }
}
</style>
