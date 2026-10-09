<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import RecordForm from '../../src/views/nocode/application/components/RecordForm.vue'
import { formDesignModel } from '../../src/nocode/form-design'
import { defaultFieldOptions } from '../../src/nocode/data-center'
import { newField } from '../../src/nocode/object-draft'
import { nocodePlatformKey, type NocodePlatform } from '../../src/nocode/platform'
import { selectionPreviewKey } from '../../src/nocode/selection'
import type { ObjectRelation } from '../../src/types/nocode/data-center'
import type { SelectionQuery } from '../../src/types/nocode/selection'

// 复用真实表单和选择器，仅候选接口使用内存数据；不依赖发布或修改业务库。
const candidates = [
  { value: '9007199254740993', label: '办公设备' },
  { value: '9007199254740994', label: '电子设备' },
  { value: '9007199254740995', label: '交通工具' }
].map(o => ({ ...o, code: o.value, path: null, parentValue: null, disabled: false, unavailable: false }))
async function selection(query: SelectionQuery) {
  const options = candidates.filter(o => !query.search || o.label.includes(query.search))
  return {
    options,
    selected: candidates.filter(o => query.selected?.includes(o.value)),
    total: options.length,
    tree: false,
    defaultValue: null
  }
}
provide(nocodePlatformKey, {
  runtime: { selection },
  applications: { previewSelection: ({ query }: { query: SelectionQuery }) => selection(query) }
} as unknown as NocodePlatform)
provide(
  selectionPreviewKey,
  ref({ applicationId: 'fixture', objects: [{ objectId: 'asset', versionNo: 1, checksum: 'fixture' }] })
)
const fields = [
  { ...newField(0), id: 'name', key: 'name', code: 'zcmc', name: '资产名称', required: true },
  {
    ...newField(1),
    id: 'count',
    key: 'count',
    code: 'count',
    name: '数量',
    type: 'INTEGER' as const,
    length: null,
    required: true
  },
  {
    ...newField(2),
    id: 'asset_type',
    key: 'asset_type',
    code: 'asset_type_id',
    name: '资产类型',
    type: 'INTEGER' as const,
    length: null
  }
]
const options = { asset_type: { ...defaultFieldOptions(), generated: true } }
const relations: ObjectRelation[] = [
  {
    id: 'relation',
    code: 'asset_type',
    name: '资产类型',
    kind: 'REFERENCE',
    fieldId: 'asset_type',
    targetObjectId: 'asset_types',
    targetFieldId: null,
    onDelete: 'RESTRICT',
    required: false
  }
]
const values = ref<Record<string, unknown>>({ name: '办公电脑', count: '1', asset_type: null })
const preview = ref(false)
const selected = computed(() => candidates.find(o => o.value === values.value.asset_type)?.label || '未选择')
</script>
<template>
  <article class="reference-example">
    <h2>资产明细 · 下拉单选</h2>
    <p>正式表单组件交互验证；候选为示例数据，不保存业务记录。</p>
    <a-switch v-model:checked="preview" checked-children="预览模式" un-checked-children="运行模式" />
    <RecordForm
      :key="String(preview)"
      v-model="values"
      :fields="fields"
      :options="options"
      :relations="relations"
      :model="formDesignModel"
      :preview="preview"
      application-id="fixture"
      object-id="asset"
      creating
    />
    <a-descriptions title="选择结果" :column="1" bordered size="small">
      <a-descriptions-item label="显示名称">{{ selected }}</a-descriptions-item>
      <a-descriptions-item label="提交值">{{ values.asset_type ?? '空' }}</a-descriptions-item>
      <a-descriptions-item label="选择数量">最多一项</a-descriptions-item>
    </a-descriptions>
  </article>
</template>
<style scoped>
.reference-example {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
h2 {
  margin: 0 0 8px;
  font-size: 18px;
}
p {
  color: #6b7280;
}
.ant-switch {
  margin-bottom: 20px;
}
</style>
