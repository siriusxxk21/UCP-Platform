<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FormItemRest } from 'ant-design-vue'
import type { ApplicationResource, PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import type { ObjectDetail } from '@/types/nocode/data-center'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import { MemberState } from '@/types/nocode/enums'
import { boundFields } from '@/nocode/application-ui'
import { selectionSource } from '@/nocode/selection'
import FieldBehaviorEditor from './FieldBehaviorEditor.vue'
import SelectionPresentationEditor from './SelectionPresentationEditor.vue'
const props = defineProps<{
  definition: PublishedDefinition
  detail: ObjectDetail
  objects: Record<string, PublishedObject>
  resources?: ApplicationResource[]
  formFieldIds?: string[]
  readOnly?: boolean
  compact?: boolean
  selectedFieldId?: string
}>()
const emit = defineEmits<{ selectField: [fieldId: string] }>()
const model = defineModel<UiNode[] | undefined>()
const nodes = ref<UiNode[]>()
// 引擎属性表单复制传入值；列属性必须显式回传，才能进入画布及撤销历史。
watch(
  () => model.value,
  value => {
    if (JSON.stringify(value) !== JSON.stringify(nodes.value))
      nodes.value = value ? JSON.parse(JSON.stringify(value)) : undefined
  },
  { deep: true, immediate: true }
)
watch(
  nodes,
  value => {
    if (JSON.stringify(value) !== JSON.stringify(model.value))
      model.value = value ? JSON.parse(JSON.stringify(value)) : undefined
  },
  { deep: true }
)
const selectedId = ref('')
const definition = computed<PublishedDefinition>(() => ({
  ...props.definition,
  fields: props.detail.fields,
  fieldOptions: props.detail.fieldOptions,
  details: [],
  relations: props.definition.relations
    .filter(r => r.sourceDetailId === props.detail.id)
    .map(r => ({ ...r, sourceDetailId: null }))
}))
const rowFieldIds = computed(() => boundFields(nodes.value || []))
// 明细投影用于本行带入；只有候选联动允许使用主表字段，不能把主表关系混入带入来源。
const selectionDefinition = computed<PublishedDefinition>(() => ({
  ...definition.value,
  fields: [...props.definition.fields, ...props.detail.fields],
  fieldOptions: { ...props.definition.fieldOptions, ...props.detail.fieldOptions },
  relations: [...props.definition.relations.filter(r => !r.sourceDetailId), ...definition.value.relations]
}))
const sourceGroups = computed(() => [
  { label: '本行字段', fieldIds: rowFieldIds.value },
  { label: '主表字段', fieldIds: props.formFieldIds || [] }
])
const availableFieldIds = computed(() => [...rowFieldIds.value, ...(props.formFieldIds || [])])
function initialize() {
  nodes.value = props.detail.fields
    .filter(f => props.detail.fieldOptions[f.id!]?.state !== MemberState.INACTIVE)
    .map(f => uiNode(NodeKind.FIELD, { id: 'detail-' + f.id, fieldId: f.id!, presentation: { label: f.name } }))
}
const field = (node: UiNode) => props.detail.fields.find(f => f.id === node.fieldId)
const selectedNode = computed(() => nodes.value?.find(n => n.id === selectedId.value))
const selectedField = computed(() => (selectedNode.value ? field(selectedNode.value) : undefined))
const selectedIndex = computed(() => nodes.value?.findIndex(n => n.id === selectedId.value) ?? -1)
watch(selectedId, id => {
  const fieldId = nodes.value?.find(node => node.id === id)?.fieldId
  if (fieldId) emit('selectField', fieldId)
})
watch(
  () => [props.selectedFieldId, nodes.value] as const,
  ([id]) => {
    const node = nodes.value?.find(n => n.fieldId === id)
    if (node) selectedId.value = node.id
  },
  { immediate: true }
)
watch(
  () => nodes.value?.map(n => n.id),
  ids => {
    if (!ids?.includes(selectedId.value)) selectedId.value = ids?.[0] || ''
  },
  { immediate: true }
)
function move(offset: number) {
  if (!nodes.value) return
  const index = selectedIndex.value
  const target = index + offset
  if (index < 0 || target < 0 || target >= nodes.value.length) return
  const next = [...nodes.value]
  next.splice(target, 0, next.splice(index, 1)[0]!)
  nodes.value = next
}
</script>
<template>
  <div class="detail-settings" :class="{ 'detail-settings-compact': compact }">
    <FormItemRest>
      <p>
        {{
          compact ? '点击画布中的列或在下方选择，配置本行字段。' : '点击左侧明细列配置属性。'
        }}每行独立执行联动、带入和动态条件，明细随整单保存。
      </p>
      <a-button v-if="!nodes" :disabled="readOnly" @click="initialize">自定义明细列</a-button>
      <template v-else>
        <div class="detail-columns">
          <a-select
            v-if="compact"
            v-model:value="selectedId"
            aria-label="当前明细列"
            :options="
              nodes.map(node => ({
                value: node.id,
                label: node.presentation?.label || field(node)?.name || '已失效字段'
              }))
            "
          />
          <nav v-else class="detail-column-list" aria-label="明细列">
            <a-button
              v-for="node in nodes"
              :key="node.id"
              block
              :type="selectedId === node.id ? 'primary' : 'default'"
              :aria-pressed="selectedId === node.id"
              @click="selectedId = node.id"
            >
              {{ node.presentation?.label || field(node)?.name || '已失效字段' }}
            </a-button>
          </nav>
          <section v-if="selectedNode && selectedField" class="detail-column-properties">
            <strong>{{ detail.name }} · {{ selectedField.name }}</strong>
            <a-space>
              <a-button :disabled="readOnly || selectedIndex === 0" @click="move(-1)">前移</a-button>
              <a-button :disabled="readOnly || selectedIndex === nodes.length - 1" @click="move(1)">后移</a-button>
            </a-space>
            <template v-if="selectedNode.presentation">
              <a-form-item label="显示名称">
                <a-input v-model:value="selectedNode.presentation.label" :maxlength="100" />
              </a-form-item>
              <a-form-item label="输入提示">
                <a-input v-model:value="selectedNode.presentation.placeholder" :maxlength="500" />
              </a-form-item>
              <a-form-item label="填写说明">
                <a-input v-model:value="selectedNode.presentation.help" :maxlength="500" />
              </a-form-item>
              <a-checkbox v-model:checked="selectedNode.presentation.readOnly">本表单只读</a-checkbox>
              <a-form-item label="动态条件">
                <FieldBehaviorEditor
                  v-model="selectedNode.presentation.behavior"
                  :fields="detail.fields.filter(f => rowFieldIds.includes(f.id!))"
                />
              </a-form-item>
              <a-form-item
                v-if="
                  selectionSource(selectedField, detail.fieldOptions[selectedNode.fieldId!]) ||
                  definition.relations.some(r => r.fieldId === selectedNode!.fieldId)
                "
                label="选择器设置"
              >
                <SelectionPresentationEditor
                  :key="'selection-' + selectedNode.id"
                  v-model="selectedNode.presentation.selection"
                  :definition="selectionDefinition"
                  :objects="objects"
                  :resources="resources"
                  :field-id="selectedNode.fieldId!"
                  :available-field-ids="availableFieldIds"
                  :source-groups="sourceGroups"
                />
              </a-form-item>
            </template>
            <a-button v-else :disabled="readOnly" @click="selectedNode.presentation = {}">配置此字段</a-button>
          </section>
          <a-alert v-else-if="selectedNode" type="warning" message="此明细列已不可用，请同步对象版本并检查字段配置。" />
        </div>
        <a-button :disabled="readOnly" @click="nodes = undefined">恢复对象默认字段</a-button>
      </template>
    </FormItemRest>
  </div>
</template>
<style scoped>
.detail-settings {
  display: grid;
  gap: 16px;
}
.detail-columns {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(0, 3fr);
  gap: 24px;
  align-items: start;
}
.detail-settings-compact .detail-columns {
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}
.detail-column-list,
.detail-column-properties {
  display: grid;
  gap: 12px;
}
.detail-column-list {
  position: sticky;
  top: 0;
}
.detail-column-list :deep(.ant-btn) {
  height: auto;
  min-height: 32px;
  white-space: normal;
  text-align: left;
}
.detail-column-properties :deep(.ant-form-item) {
  margin-bottom: 0;
}
p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
}
@media (max-width: 640px) {
  .detail-columns {
    grid-template-columns: 1fr;
  }
  .detail-column-list {
    position: static;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
