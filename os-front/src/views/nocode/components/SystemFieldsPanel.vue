<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { systemFields } from '@/nocode/system-fields'
import type { DatabaseColumn, SaveDesign } from '@/types/nocode/data-center'
import { useNocodePlatform } from '@/nocode/platform'
import { ObjectSource } from '@/types/nocode/enums'

const props = defineProps<{ design: SaveDesign }>()
const selected = ref('main')
const tables = computed(() => [
  {
    value: 'main',
    label: '主表',
    name: props.design.draft.tableName,
    binding: props.design.mainBinding,
    fields: props.design.draft.fields,
    options: props.design.fieldOptions
  },
  ...props.design.details.map(d => ({
    value: d.id || d.code,
    label: `内部明细 · ${d.name}`,
    name: d.tableName,
    binding: d.binding,
    fields: d.fields,
    options: d.fieldOptions
  }))
])
watch(tables, values => {
  if (!values.some(t => t.value === selected.value)) selected.value = 'main'
})
const table = computed(() => tables.value.find(t => t.value === selected.value) || tables.value[0]!)
const api = useNocodePlatform().dataCenter
const actualColumns = ref<DatabaseColumn[]>()
const structureError = ref('')
let generation = 0
watch(
  table,
  async current => {
    const turn = ++generation
    actualColumns.value = undefined
    structureError.value = ''
    if (current.binding?.source !== ObjectSource.ADOPTED) return
    try {
      const result = await api.table(current.binding.schemaName, current.name)
      if (turn === generation) actualColumns.value = result.structure.columns
    } catch {
      if (turn === generation) structureError.value = '实际表结构暂时无法读取，以下仅展示已保存的字段映射。'
    }
  },
  { immediate: true }
)
const rows = computed(() =>
  systemFields(table.value.binding, table.value.fields, table.value.options, actualColumns.value)
)
const columns = [
  { title: '字段名称', key: 'name', dataIndex: 'name', width: 130 },
  { title: '字段编码', key: 'code', dataIndex: 'code', width: 160 },
  { title: '存储类型', key: 'type', dataIndex: 'type', width: 180 },
  { title: '维护规则', key: 'rule', dataIndex: 'rule', width: 260 },
  { title: '用途', key: 'purpose', dataIndex: 'purpose', width: 230 }
]
</script>

<template>
  <div class="system-fields-panel">
    <a-alert v-if="structureError" type="warning" :message="structureError" show-icon />
    <OsTablePage
      class="nocode-embedded-table"
      :columns="columns"
      :data-source="rows"
      row-key="code"
      :pagination="false"
      :scroll="{ x: 'max-content', y: '100%' }"
      resizable
      show-column-settings
      column-settings-key="nocode-system-fields"
    >
      <template #toolbar>
        <a-select
          v-if="tables.length > 1"
          v-model:value="selected"
          aria-label="系统字段所属表"
          :options="tables.map(t => ({ value: t.value, label: t.label }))"
          class="system-table-select"
        />
      </template>
      <template #actions />
      <template #empty>当前映射中没有系统字段，保留已有表结构</template>
    </OsTablePage>
  </div>
</template>

<style scoped>
.system-table-select {
  min-width: 220px;
}
</style>
