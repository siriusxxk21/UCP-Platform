<script setup lang="ts">
import { fieldRelation } from '@/nocode/business-fields'
import SelectionField from './SelectionField.vue'
import { selectionSource, selectionValuesKey } from '@/nocode/selection'
import { computed, provide } from 'vue'
import { NodeKind, type UiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'
import type { BusinessFilePolicy, FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import { FieldType } from '@/types/nocode/enums'
import { directoryTypes } from '@/nocode/directory-options'
import ReferenceField from './ReferenceField.vue'
import DirectoryField from './DirectoryField.vue'
import BusinessFileField from './BusinessFileField.vue'
import HyperlinkField from './HyperlinkField.vue'
import BusinessFieldControl from './BusinessFieldControl.vue'
import { defaultFormNodes } from '@/nocode/form-presentation'
import FormDetailOutlet from './FormDetailOutlet.vue'
import '@/styles/business-form.css'
const props = defineProps<{
  nodes?: UiNode[]
  fields: ObjectField[]
  values: Record<string, unknown>
  options: Record<string, FieldOptions>
  applicationId: string
  objectId?: string
  recordId?: string
  detailId?: string
  detailRecordId?: string
  displayValues?: Record<string, string>
  relations: ObjectRelation[]
  preview?: boolean
  layout?: 'vertical' | 'horizontal'
  nested?: boolean
  businessPolicy?: BusinessFilePolicy | null
}>()
provide(
  selectionValuesKey,
  computed(() => props.values)
)
const displayNodes = computed(() => props.nodes || defaultFormNodes(props.fields))
const field = (node: UiNode) => props.fields.find(f => f.id === node.fieldId)
const relation = (node: UiNode) => fieldRelation(props.relations, node.fieldId)
const selectionValue = (id: string) => props.values[id] as string | string[] | null
function display(node: UiNode) {
  const value = props.values[node.fieldId!]
  if (value == null || value === '' || (Array.isArray(value) && !value.length)) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  const options = props.options[node.fieldId!]?.options || []
  const label = (v: unknown) => options.find(o => o.code === v)?.label || String(v)
  return Array.isArray(value)
    ? value.map(label).join('、')
    : typeof value === 'object'
      ? JSON.stringify(value)
      : label(value)
}
</script>
<template>
  <div class="os-business-form" :class="{ 'os-form-surface': !nested, 'os-read-horizontal': layout === 'horizontal' }">
    <template v-for="node in displayNodes" :key="node.id">
      <div v-if="node.type === NodeKind.FIELD && field(node)" class="os-read-field">
        <div class="os-read-label">{{ node.presentation?.label || field(node)?.name }}</div>
        <div class="os-read-value" :class="{ 'is-empty': display(node) === '—' && !displayValues?.[node.fieldId!] }">
          <template v-if="displayValues && node.fieldId! in displayValues">
            {{ displayValues[node.fieldId!] || '—' }}
          </template>
          <BusinessFieldControl
            v-else-if="field(node)!.type === FieldType.RICH_TEXT"
            :kind="FieldType.RICH_TEXT"
            :model-value="typeof values[node.fieldId!] === 'string' ? (values[node.fieldId!] as string) : ''"
            disabled
            read-only
          />
          <SelectionField
            v-else-if="objectId && (selectionSource(field(node)!, options[node.fieldId!]) || relation(node))"
            :model-value="selectionValue(node.fieldId!)"
            :application-id="applicationId"
            :object-id="objectId"
            :record-id="recordId"
            :detail-id="detailId"
            :preview="preview"
            :presentation="node.presentation?.selection"
            :field-id="node.fieldId!"
            read-only
          />
          <HyperlinkField
            v-else-if="field(node)!.type === FieldType.URL"
            :model-value="values[node.fieldId!]"
            read-only
          />
          <template v-else-if="preview && [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === field(node)!.type)">
            {{
              (values[node.fieldId!] as unknown[])?.length
                ? field(node)!.type === FieldType.IMAGE
                  ? '示例图片.png'
                  : '示例附件.pdf'
                : '—'
            }}
          </template>
          <BusinessFieldControl
            v-else-if="
              preview &&
              (relation(node) ||
                directoryTypes.some(t => t === field(node)!.type) ||
                [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === field(node)!.type))
            "
            :kind="relation(node) ? FieldType.REFERENCE : field(node)!.type"
            :model-value="selectionValue(node.fieldId!)"
            mode="preview"
            disabled
            read-only
          />
          <ReferenceField
            v-else-if="relation(node) && values[node.fieldId!] != null"
            :application-id="applicationId"
            :target-object-id="relation(node)!.targetObjectId"
            :model-value="String(values[node.fieldId!])"
            disabled
            read-only
          />
          <DirectoryField
            v-else-if="
              directoryTypes.includes(field(node)!.type as (typeof directoryTypes)[number]) &&
              values[node.fieldId!] != null
            "
            :kind="field(node)!.type"
            :model-value="values[node.fieldId!] as string"
            disabled
            read-only
          />
          <template v-else-if="[FieldType.ATTACHMENT, FieldType.IMAGE].some(t => t === field(node)!.type)">
            <BusinessFileField
              v-if="(values[node.fieldId!] as unknown[])?.length"
              :model-value="values[node.fieldId!] as string[]"
              :image="field(node)!.type === FieldType.IMAGE"
              :application-id="applicationId"
              :object-id="objectId"
              :record-id="recordId"
              :detail-id="detailId"
              :detail-record-id="detailRecordId"
              :field-id="node.fieldId!"
              :business-policy="businessPolicy"
              :hide-business-path="node.presentation?.showBusinessPath === false"
              detailed
              disabled
            />
            <span v-else>—</span>
          </template>
          <template v-else>{{ display(node) }}</template>
        </div>
      </div>
      <a-row v-else-if="node.type === NodeKind.ROW" :gutter="24" class="os-form-row">
        <a-col v-for="column in node.children" :key="column.id" :span="column.span || 12" class="os-form-column">
          <RecordReadView v-bind="props" :nodes="column.children" nested />
        </a-col>
      </a-row>
      <a-tabs v-else-if="node.type === NodeKind.TABS">
        <a-tab-pane v-for="tab in node.children" :key="tab.id" :tab="tab.text || '资料'" force-render>
          <RecordReadView v-bind="props" :nodes="tab.children" nested />
        </a-tab-pane>
      </a-tabs>
      <a-card v-else-if="node.type === NodeKind.CARD" :title="node.text" size="small" class="os-form-section">
        <RecordReadView v-bind="props" :nodes="node.children" nested />
      </a-card>
      <p v-else-if="node.type === NodeKind.TEXT">{{ node.text }}</p>
      <FormDetailOutlet
        v-else-if="node.type === NodeKind.INTERNAL_DETAIL && node.detail?.detailId"
        :detail-id="node.detail.detailId"
        :mode="node.detail.mode"
        :title="node.text || undefined"
      />
      <a-divider v-else-if="node.type === NodeKind.DIVIDER" />
      <RecordReadView v-else-if="node.children?.length" v-bind="props" :nodes="node.children" nested />
    </template>
  </div>
</template>
