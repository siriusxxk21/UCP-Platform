<script setup lang="ts">
import SelectionField from './SelectionField.vue'
import { computed, defineAsyncComponent } from 'vue'
import { PictureOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { FieldType } from '@/types/nocode/enums'
import { fieldTypes } from '@/nocode/object-draft'
import type { FieldRuleView, FormRenderMode } from '@/nocode/business-field-rules'
import ReferenceField from './ReferenceField.vue'
import DirectoryField from './DirectoryField.vue'
import BusinessFileField from './BusinessFileField.vue'
const TiptapEditor = defineAsyncComponent(() => import('@/components/TiptapEditor.vue'))

const props = withDefaults(
  defineProps<{
    modelValue?: string | string[] | null
    kind: FieldType
    mode?: FormRenderMode
    applicationId?: string
    objectId?: string
    detailId?: string
    recordId?: string
    detailRecordId?: string
    required?: boolean
    _osSelection?: import('@/types/nocode/selection').SelectionPresentation
    formId?: string
    selectionPresentation?: import('@/types/nocode/selection').SelectionPresentation
    fieldId?: string
    selection?: boolean
    multiple?: boolean
    creating?: boolean
    targetObjectId?: string
    disabled?: boolean
    readOnly?: boolean
    placeholder?: string
    businessPolicy?: import('@/types/nocode/data-center').BusinessFilePolicy | null
    hideBusinessPath?: boolean
    /** 对象字段规则的运行时呈现，由 RecordForm 按求值结果局部合并。 */
    _osLinkage?: FieldRuleView | null
    ruleDependsOn?: string[]
    ruleMasterDependsOn?: string[]
  }>(),
  { mode: 'runtime' }
)
const emit = defineEmits<{
  'update:modelValue': [value: string | string[] | null]
  'upload-status': [status: { pending: boolean; failed: boolean }]
}>()
const files = computed(() => [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === props.kind))
const label = computed(() =>
  props.kind === FieldType.REFERENCE ? '关联记录' : fieldTypes.find(t => t.value === props.kind)?.label || '记录'
)
const locked = computed(() => !!props._osLinkage?.locked)
const formulaLocked = computed(() => props._osLinkage?.kind === 'DEFAULT_FORMULA')
const sampleOptions = computed(() =>
  [1, 2].map(i => ({ value: `preview-${props.kind}-${i}`, label: `示例${label.value}${i}` }))
)
</script>
<template>
  <div class="business-field-control" :class="{ 'rule-locked': locked }">
    <span
      v-if="locked"
      class="rule-linkage-mark"
      :title="formulaLocked ? '公式默认值计算，不可修改' : '数据联动带出，不可修改'"
    >
      {{ formulaLocked ? '公式' : '联动' }}
    </span>
    <TiptapEditor
      v-if="kind === FieldType.RICH_TEXT"
      :model-value="typeof modelValue === 'string' ? modelValue : ''"
      :disabled="disabled || readOnly || locked || mode === 'design'"
      :placeholder="placeholder"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <template v-else-if="mode !== 'design' && (mode === 'runtime' || selection)">
      <SelectionField
        v-if="selection && applicationId && objectId && fieldId"
        :model-value="modelValue"
        :application-id="applicationId"
        :object-id="objectId"
        :field-id="fieldId"
        :form-id="formId"
        :presentation="selectionPresentation"
        :preview="mode !== 'runtime'"
        :required="required"
        :detail-id="detailId"
        :record-id="recordId"
        :detail-record-id="detailRecordId"
        :multiple="multiple"
        :disabled="disabled || locked"
        :read-only="readOnly"
        :creating="creating"
        :placeholder="placeholder"
        :rule-depends-on="ruleDependsOn"
        :rule-master-depends-on="ruleMasterDependsOn"
        :rule-out-of-scope="_osLinkage?.inScope === false"
        :rule-pending="_osLinkage?.kind === 'REFERENCE' ? _osLinkage.pending : null"
        @update:model-value="emit('update:modelValue', $event)"
      />
      <BusinessFileField
        v-else-if="files && mode === 'runtime'"
        :model-value="Array.isArray(modelValue) ? modelValue : []"
        :image="kind === FieldType.IMAGE"
        :disabled="disabled || locked"
        :read-only="readOnly"
        :application-id="applicationId"
        :object-id="objectId"
        :record-id="recordId"
        :detail-id="detailId"
        :detail-record-id="detailRecordId"
        :field-id="fieldId"
        :business-policy="businessPolicy"
        :hide-business-path="hideBusinessPath"
        @update:model-value="emit('update:modelValue', $event)"
        @upload-status="emit('upload-status', $event)"
      />
      <a-alert v-else-if="mode !== 'runtime'" type="warning" message="缺少应用或对象版本，无法读取真实候选" />
      <ReferenceField
        v-else-if="kind === FieldType.REFERENCE && applicationId && targetObjectId"
        :model-value="modelValue"
        :application-id="applicationId"
        :target-object-id="targetObjectId"
        :placeholder="placeholder"
        :disabled="disabled || locked"
        :read-only="readOnly"
        @update:model-value="emit('update:modelValue', $event)"
      />
      <a-alert v-else-if="kind === FieldType.REFERENCE" type="warning" message="请先配置对象关系" />
      <DirectoryField
        v-else
        :model-value="typeof modelValue === 'string' ? modelValue : null"
        :kind="kind"
        :placeholder="placeholder"
        :disabled="disabled || locked"
        :read-only="readOnly"
        @update:model-value="emit('update:modelValue', $event)"
      />
    </template>
    <!-- 设计画布只展示禁用结构控件；文件预览使用示例，不调用上传接口。 -->
    <div v-else-if="files" class="file-preview">
      <a-button :disabled="disabled || mode === 'design'" @click="emit('update:modelValue', ['preview-file'])">
        <PictureOutlined v-if="kind === FieldType.IMAGE" />
        <UploadOutlined v-else />
        {{ mode === 'preview' ? '添加示例' + label : '选择' + label }}
      </a-button>
      <span v-if="Array.isArray(modelValue) && modelValue.length">
        {{ kind === FieldType.IMAGE ? '示例图片.png' : '示例附件.pdf' }}
      </span>
      <span v-else class="muted">{{ kind === FieldType.IMAGE ? '图片上传与预览' : '文件上传' }}</span>
      <a-button
        v-if="mode === 'preview' && modelValue?.length && !disabled"
        type="link"
        size="small"
        @click="emit('update:modelValue', [])"
      >
        移除
      </a-button>
    </div>
    <span v-else-if="readOnly && mode !== 'design'">
      {{
        sampleOptions
          .filter(o => (Array.isArray(modelValue) ? modelValue.includes(o.value) : modelValue === o.value))
          .map(o => o.label)
          .join('、') || '—'
      }}
    </span>
    <a-select
      v-else
      :value="modelValue || undefined"
      :options="sampleOptions"
      :mode="multiple ? 'multiple' : undefined"
      :disabled="disabled || mode === 'design'"
      :placeholder="placeholder || '选择' + label"
      show-search
      allow-clear
      option-filter-prop="label"
      style="width: 100%"
      @change="
        emit('update:modelValue', Array.isArray($event) ? $event.map(String) : $event == null ? null : String($event))
      "
    />
    <div v-if="_osLinkage?.message" class="rule-message">{{ _osLinkage.message }}</div>
  </div>
</template>
<style scoped>
.business-field-control {
  position: relative;
}
.rule-linkage-mark {
  position: absolute;
  top: -10px;
  right: 0;
  z-index: 1;
  padding: 0 4px;
  border-radius: 2px;
  background: var(--os-color-primary-bg, #e6f4ff);
  color: var(--os-color-primary, #1677ff);
  font-size: 11px;
  line-height: 16px;
  pointer-events: none;
}
.rule-message {
  margin-top: 4px;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
  line-height: 18px;
}
.file-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
</style>
