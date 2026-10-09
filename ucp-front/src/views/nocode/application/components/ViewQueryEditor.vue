<script setup lang="ts">
import { computed, provide } from 'vue'
import type { ObjectField } from '@/types/nocode/object'
import type { PublishedObject } from '@/types/nocode/application'
import type { ViewQueryOptions } from '@/types/nocode/data-scope'
import { FieldType } from '@/types/nocode/enums'
import { scopeFields } from '@/nocode/data-scope'
import { fieldRelation } from '@/nocode/business-fields'
import { selectionSource, selectionPreviewKey } from '@/nocode/selection'
import DataScopeEditor from '../../components/DataScopeEditor.vue'
import SelectionField from './SelectionField.vue'
import RelativeDateValue from '../../components/RelativeDateValue.vue'
import { relativeDateField } from '@/nocode/relative-date'
const props = defineProps<{
  fields: ObjectField[]
  choices: (id: string) => { label: string; value: string }[]
  applicationId?: string
  objectId?: string
  objects?: Record<string, PublishedObject>
}>()
const query = defineModel<ViewQueryOptions>({ required: true })
// 与表单、统计固定值共用只读候选入口，按应用草稿引用版本和当前权限解析名称。
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects || {}).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    }))
  }))
)
function remoteSelection(id: string) {
  const definition = props.objects?.[props.objectId || '']?.definition
  const field = props.fields.find(f => f.id === id)
  if (!props.applicationId || !definition || !field) return false
  const source = selectionSource(field, definition.fieldOptions[id])
  return !!fieldRelation(definition.relations, id) || (!!source && source.kind !== 'LOCAL_OPTIONS')
}
const fixed = computed({
  get: () => ({ logic: 'AND' as const, conditions: query.value.fixed, groups: [] }),
  set: v => {
    query.value.fixed = v.conditions
  }
})
const fields = computed(() => scopeFields(props.fields).map(f => ({ label: f.name, value: f.id! })))
const name = (id: string) => props.fields.find(f => f.id === id)?.name || '字段已移除'
/** 日期 / 日期时间字段的默认查询可存相对日期（如「本月」），打开列表时按当天换算。 */
const dateField = (id: string) => relativeDateField(props.fields.find(f => f.id === id)?.type)
function many(id: string) {
  return props.fields.find(f => f.id === id)?.type === FieldType.MULTI_SELECT
}
function choices(id: string) {
  return props.fields.find(f => f.id === id)?.type === FieldType.BOOLEAN
    ? [
        { label: '是', value: 'true' },
        { label: '否', value: 'false' }
      ]
    : props.choices(id)
}
</script>
<template>
  <a-divider orientation="left">固定数据范围</a-divider>
  <p class="hint">
    以下条件全部满足；清空用户查询不会移除固定范围。限定多个分类时选择“属于任意一个”，再选择分类。选择空集合表示无结果。
  </p>
  <DataScopeEditor v-model="fixed" :fields="props.fields" :choices="choices" simple>
    <template #value="{ condition, multiple }">
      <SelectionField
        v-if="remoteSelection(condition.fieldId)"
        v-model="condition.value"
        :application-id="applicationId!"
        :object-id="objectId!"
        :field-id="condition.fieldId"
        :multiple="multiple"
        preview
        placeholder="请选择固定范围"
      />
    </template>
  </DataScopeEditor>
  <a-divider orientation="left">默认查询</a-divider>
  <p class="hint">
    首次打开列表时填入，用户可以清除。对应字段需同时配置为常用查询字段。日期字段可选「今天 / 本周 /
    本月」等相对日期，每次打开按当天换算。
  </p>
  <div v-for="(value, id) in query.defaults" :key="id" class="query-row">
    <span>{{ name(id) }}</span>
    <SelectionField
      v-if="remoteSelection(id)"
      v-model="query.defaults[id]"
      :application-id="applicationId!"
      :object-id="objectId!"
      :field-id="id"
      :multiple="many(id)"
      preview
      placeholder="默认查询值"
    />
    <a-select
      v-else-if="choices(id).length || many(id)"
      v-model:value="query.defaults[id]"
      :options="choices(id)"
      :mode="many(id) ? 'multiple' : undefined"
      allow-clear
      placeholder="默认查询值"
    />
    <RelativeDateValue v-else-if="dateField(String(id))" v-model="query.defaults[id]" operator="eq">
      <a-input v-model:value="query.defaults[id]" placeholder="默认查询值" />
    </RelativeDateValue>
    <a-input v-else v-model:value="query.defaults[id]" placeholder="默认查询值" />
    <a-button @click="delete query.defaults[id]">移除</a-button>
  </div>
  <a-select
    :value="undefined"
    :options="fields.filter(f => !(f.value in query.defaults))"
    placeholder="添加默认查询字段"
    class="query-add"
    @change="query.defaults[String($event)] = null"
  />
  <a-divider orientation="left">筛选候选范围</a-divider>
  <p class="hint">固定范围中的选项会自动收紧查询候选，无需重复配置；可在这里进一步限制，不改变列表的数据范围。</p>
  <div v-for="(value, id) in query.candidates" :key="id" class="query-row">
    <span>{{ name(id) }}</span>
    <SelectionField
      v-if="remoteSelection(id)"
      v-model="query.candidates[id]"
      :application-id="applicationId!"
      :object-id="objectId!"
      :field-id="id"
      multiple
      preview
      placeholder="允许选择的值（留空表示无候选）"
    />
    <a-select
      v-else
      v-model:value="query.candidates[id]"
      :options="choices(id)"
      :mode="choices(id).length ? 'multiple' : 'tags'"
      placeholder="允许选择的值（留空表示无候选）"
    />
    <a-button @click="delete query.candidates[id]">移除</a-button>
  </div>
  <a-select
    :value="undefined"
    :options="fields.filter(f => !(f.value in query.candidates))"
    placeholder="添加候选范围字段"
    class="query-add"
    @change="query.candidates[String($event)] = choices(String($event)).map(v => v.value)"
  />
</template>
<style scoped>
.query-row {
  display: grid;
  grid-template-columns: 170px minmax(150px, 1fr) auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}
.query-add {
  min-width: 260px;
}
.hint {
  color: var(--text-secondary);
}
@media (max-width: 640px) {
  .query-row {
    grid-template-columns: 1fr;
  }
}
</style>
