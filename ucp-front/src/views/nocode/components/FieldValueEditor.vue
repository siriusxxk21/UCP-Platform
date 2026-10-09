<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { BusinessFilePolicy, FieldOptions } from '@/types/nocode/data-center'
import { decodeFieldDefault, encodeFieldDefault, fieldDefaultError, multiDefaultTypes } from '@/nocode/field-defaults'
import { directoryOptions, directoryTypes } from '@/nocode/directory-options'
import { recordDisplay } from '@/nocode/record-display'
import { getOrganizationTree } from '@/api/system/organization'
import { getDepartmentTree } from '@/api/system/department'
import { directoryDefaultOptions, type DirectoryScopeOption } from '@/nocode/directory-scope-options'
import request from '@/utils/request'
import UserSelectorTrigger from '@/components/UserSelectorTrigger.vue'
import BusinessFileField from '../application/components/BusinessFileField.vue'
import HyperlinkField from '../application/components/HyperlinkField.vue'
import RichTextDisplay from './RichTextDisplay.vue'
const TiptapEditor = defineAsyncComponent(() => import('@/components/TiptapEditor.vue'))
const props = defineProps<{
  field: ObjectField
  options: FieldOptions
  modelValue?: string | null
  disabled?: boolean
  preview?: boolean
  displayOnly?: boolean
  applicationId?: string
  objectId?: string
  recordId?: string
  detailId?: string
  detailRecordId?: string
  businessPolicy?: BusinessFilePolicy | null
}>()
const emit = defineEmits<{
  'update:modelValue': [string | null]
  'upload-status': [{ pending: boolean; failed: boolean }]
}>()
const value = computed(() => decodeFieldDefault(props.field, props.modelValue))
const pickerError = computed(() =>
  [FieldType.DATE, FieldType.DATETIME, FieldType.TIME].some(type => type === props.field.type)
    ? fieldDefaultError(props.field, { ...props.options, defaultValue: props.modelValue })
    : null
)
const pickerValue = computed(() => (pickerError.value ? undefined : props.modelValue || undefined))
const multiple = computed(() => multiDefaultTypes.includes(props.field.type))
const numeric = computed(() =>
  [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(type => type === props.field.type)
)
const dateFormat = computed(() =>
  props.field.type === FieldType.DATE
    ? 'YYYY-MM-DD'
    : props.options.nativeType?.includes('with time zone')
      ? 'YYYY-MM-DDTHH:mm:ssZ'
      : 'YYYY-MM-DD HH:mm:ss'
)
const directory = computed(
  () =>
    props.options.selection?.directory ||
    ([...directoryTypes, FieldType.ORGANIZATION].some(type => type === props.field.type) ? props.field.type : '')
)
const choices = ref<DirectoryScopeOption[]>([]),
  error = ref('')
const optionList = computed(() =>
  directory.value
    ? directoryDefaultOptions(choices.value, props.options.selection)
    : props.options.selection?.kind === 'SYSTEM_DICTIONARY'
      ? choices.value
      : props.options.options.map(item => ({
          value: item.code,
          label: item.label || '未命名选项',
          disabled: item.disabled || !item.code
        }))
)
const displayText = computed(() => {
  const current = value.value
  if (current == null || current === '' || (Array.isArray(current) && !current.length)) return '—'
  if (directory.value || props.options.selection?.kind === 'SYSTEM_DICTIONARY') {
    const label = (item: unknown) =>
      choices.value.find(choice => choice.value === String(item))?.label || '已失效或无权查看'
    return (Array.isArray(current) ? current : [current]).map(label).join('、')
  }
  return recordDisplay(
    {
      id: 'preview',
      revision: '0',
      values: { preview: props.field.type === FieldType.BOOLEAN ? current === 'true' : current }
    },
    'preview',
    props.options,
    props.field
  )
})
let sequence = 0
watch(
  () => [
    directory.value,
    props.options.selection?.dictionaryType,
    directory.value === FieldType.USER && props.displayOnly ? props.modelValue : null
  ],
  async () => {
    const current = ++sequence
    error.value = ''
    choices.value = []
    try {
      let list: DirectoryScopeOption[] = []
      if (directory.value === FieldType.ORGANIZATION) {
        const visit = (items: Awaited<ReturnType<typeof getOrganizationTree>>, path = ''): void => {
          for (const item of items) {
            const label = path + item.orgName
            list.push({
              value: String(item.id),
              label,
              parentValue: item.parentId ? String(item.parentId) : null,
              organizationType: item.orgType,
              disabled: item.status !== 0
            })
            visit(item.children || [], label + ' / ')
          }
        }
        visit(await getOrganizationTree())
      } else if (directory.value === FieldType.DEPARTMENT) {
        const visit = (items: Awaited<ReturnType<typeof getDepartmentTree>>, path = ''): void => {
          for (const item of items) {
            const label = path + item.deptName
            list.push({
              value: String(item.id),
              label,
              parentValue: item.parentId ? String(item.parentId) : null,
              disabled: item.status !== 0
            })
            visit(item.children || [], label + ' / ')
          }
        }
        visit(await getDepartmentTree())
      } else if (directory.value === FieldType.USER && props.displayOnly) {
        const currentValue = value.value
        list = await directoryOptions(
          FieldType.USER,
          (Array.isArray(currentValue) ? currentValue : currentValue ? [currentValue] : []).map(String)
        )
      } else if (directory.value && directory.value !== FieldType.USER)
        list = await directoryOptions(directory.value as FieldType)
      else if (props.options.selection?.kind === 'SYSTEM_DICTIONARY' && props.options.selection.dictionaryType) {
        const result = await request.get<Array<{ dictType: string; value: string; label: string }>>(
          '/system/dict-data/list-all-simple'
        )
        list = result
          .filter(item => item.dictType === props.options.selection?.dictionaryType)
          .map(item => ({ value: item.value, label: item.label }))
      }
      if (sequence === current) choices.value = list
    } catch {
      if (sequence === current) error.value = '无法读取候选项，请确认拥有相应目录的查看权限后重试。'
    }
  },
  { immediate: true }
)
function update(next: unknown) {
  emit('update:modelValue', encodeFieldDefault(props.field, next))
}
</script>
<template>
  <div class="field-value-editor">
    <template v-if="displayOnly">
      <BusinessFileField
        v-if="field.type === FieldType.IMAGE || field.type === FieldType.ATTACHMENT"
        :model-value="Array.isArray(value) ? value : []"
        :image="field.type === FieldType.IMAGE"
        :application-id="applicationId"
        :object-id="objectId"
        :record-id="recordId"
        :detail-id="detailId"
        :detail-record-id="detailRecordId"
        :field-id="field.id || field.key"
        :business-policy="businessPolicy"
        disabled
      />
      <HyperlinkField v-else-if="field.type === FieldType.URL" :model-value="value" read-only />
      <RichTextDisplay v-else-if="field.type === FieldType.RICH_TEXT" :value="modelValue" compact />
      <span v-else :class="{ 'multiline-display': field.type === FieldType.TEXTAREA }">{{ displayText }}</span>
    </template>
    <a-input-number
      v-else-if="numeric"
      :value="modelValue || undefined"
      string-mode
      :precision="field.type === FieldType.INTEGER ? 0 : (field.scale ?? undefined)"
      :disabled="disabled"
      :addon-after="field.type === FieldType.PERCENT ? '%' : undefined"
      placeholder="未设置"
      @update:value="update"
    />
    <a-select
      v-else-if="field.type === FieldType.BOOLEAN"
      :value="modelValue || undefined"
      :options="[
        { value: 'true', label: '是 / 开启' },
        { value: 'false', label: '否 / 关闭' }
      ]"
      allow-clear
      :disabled="disabled"
      placeholder="未设置"
      @change="update"
    />
    <a-date-picker
      v-else-if="field.type === FieldType.DATE || field.type === FieldType.DATETIME"
      :key="pickerError ? 'invalid' : 'valid'"
      :value="pickerValue"
      :show-time="field.type === FieldType.DATETIME"
      :value-format="dateFormat"
      :disabled="disabled"
      placeholder="选择日期"
      @update:value="update"
    />
    <a-time-picker
      v-else-if="field.type === FieldType.TIME"
      :key="pickerError ? 'invalid' : 'valid'"
      :value="pickerValue"
      value-format="HH:mm:ss"
      :disabled="disabled"
      placeholder="选择时间"
      @update:value="update"
    />
    <template v-else-if="field.type === FieldType.IMAGE || field.type === FieldType.ATTACHMENT">
      <BusinessFileField
        :model-value="Array.isArray(value) ? value : []"
        :image="field.type === FieldType.IMAGE"
        :disabled="disabled || preview"
        :application-id="applicationId"
        :object-id="objectId"
        :record-id="recordId"
        :detail-id="detailId"
        :detail-record-id="detailRecordId"
        :field-id="field.id || field.key"
        :business-policy="businessPolicy"
        @update:model-value="update"
        @upload-status="emit('upload-status', $event)"
      />
      <small v-if="preview">这里展示默认文件；在上方默认值中上传或更换。</small>
    </template>
    <HyperlinkField
      v-else-if="field.type === FieldType.URL"
      :model-value="value"
      :disabled="disabled"
      @update:model-value="update"
    />
    <TiptapEditor
      v-else-if="field.type === FieldType.RICH_TEXT"
      :model-value="modelValue || ''"
      :disabled="disabled"
      @update:model-value="update"
    />
    <UserSelectorTrigger
      v-else-if="directory === FieldType.USER"
      :model-value="Array.isArray(value) ? value : value ? [String(value)] : []"
      selector-type="user"
      :mode="multiple ? 'multiple' : 'single'"
      :disabled="disabled"
      @update:model-value="update(multiple ? $event.map(String) : $event[0] == null ? null : String($event[0]))"
    />
    <a-select
      v-else-if="
        directory ||
        [FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.REGION, FieldType.CASCADE].some(
          type => type === field.type
        )
      "
      :value="value ?? undefined"
      :mode="multiple ? 'multiple' : undefined"
      :options="optionList"
      :disabled="disabled"
      show-search
      allow-clear
      option-filter-prop="label"
      placeholder="请选择候选项"
      @change="update"
    />
    <a-textarea
      v-else-if="field.type === FieldType.TEXTAREA"
      :value="modelValue || ''"
      :rows="3"
      :disabled="disabled"
      allow-clear
      @update:value="update"
    />
    <a-input
      v-else
      :value="modelValue || ''"
      :maxlength="field.length ?? undefined"
      :disabled="disabled"
      :placeholder="field.type === FieldType.UUID ? 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' : '未设置'"
      allow-clear
      @update:value="update"
    />
    <p v-if="pickerError && !displayOnly" role="alert">
      原值“{{ modelValue }}”无效，请重新选择。
      <a-button type="link" size="small" :disabled="disabled" @click="update(null)">清空无效值</a-button>
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.field-value-editor,
.field-value-editor :deep(.ant-input-number),
.field-value-editor :deep(.ant-select),
.field-value-editor :deep(.ant-picker) {
  width: 100%;
  min-width: 0;
}
small {
  color: var(--text-secondary);
}
p {
  color: var(--ant-color-error);
}
.multiline-display {
  display: block;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
