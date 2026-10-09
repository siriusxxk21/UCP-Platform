<script setup lang="ts">
import { computed } from 'vue'
import type { ObjectGrant } from '@/types/nocode/authorization'
import type { ReportObjectVersion } from '@/types/nocode/report-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldType } from '@/types/nocode/enums'
import { emptyScope } from '@/nocode/data-scope'
import DataScopeEditor from '../../components/DataScopeEditor.vue'
const props = defineProps<{ object?: ReportObjectVersion; disabled?: boolean }>()
const model = defineModel<ObjectGrant>({ required: true })
const fields = computed<ObjectField[]>(() =>
  (props.object?.fields || []).map((field, sort) => ({
    key: field.id,
    id: field.id,
    code: field.code,
    name: field.name,
    type: field.type as FieldType,
    required: false,
    unique: false,
    length: null,
    precision: null,
    scale: null,
    sort
  }))
)
function toggleScope(value: boolean) {
  model.value.actionScopes ??= {}
  if (value) model.value.actionScopes.READ = emptyScope()
  else delete model.value.actionScopes.READ
}
</script>
<template>
  <a-alert v-if="!object" type="warning" message="来源字段暂不可用，已保存权限会保留。" show-icon />
  <a-form layout="vertical" :disabled="disabled || !object">
    <a-form-item label="数据操作">
      <a-checkbox-group
        v-model:value="model.actions"
        :options="[
          { label: '读取', value: 'READ' },
          { label: '导出', value: 'EXPORT' }
        ]"
      />
    </a-form-item>
    <a-form-item label="可读取字段" extra="仅勾选实际需要的字段；未选字段不可用于分析。">
      <a-select
        v-model:value="model.readFields"
        mode="multiple"
        aria-label="可读取字段"
        :options="fields.map(f => ({ value: f.id!, label: f.name }))"
      />
    </a-form-item>
    <a-form-item label="记录范围">
      <a-radio-group
        v-model:value="model.scope"
        :options="[
          { label: '全部记录', value: 'ALL' },
          { label: '本人创建', value: 'OWN' }
        ]"
      />
    </a-form-item>
    <a-form-item label="附加读取条件">
      <a-switch :checked="!!model.actionScopes?.READ" @change="(value: boolean) => toggleScope(value)" />
    </a-form-item>
    <DataScopeEditor
      v-if="model.actionScopes?.READ"
      v-model="model.actionScopes.READ"
      :fields="fields"
      :readonly="disabled || !object"
    />
    <template v-if="model.actionScopes?.EXPORT">
      <a-divider>已有导出条件</a-divider>
      <DataScopeEditor v-model="model.actionScopes.EXPORT" :fields="fields" :readonly="disabled || !object" />
    </template>
  </a-form>
</template>
