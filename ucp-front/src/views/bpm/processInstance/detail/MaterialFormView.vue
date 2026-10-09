<script setup lang="ts">
import { computed, onErrorCaptured, ref, watch } from 'vue'
import FormCreate from '@form-create/ant-design-vue'
import { setConfAndFields2 } from '@/components/form-create'
import RecordReadView from '@/views/nocode/application/components/RecordReadView.vue'
import type { FlowMaterialDetail } from '@/api/nocode/flow-material'
import { businessFields } from '@/nocode/business-fields'
import { FieldType } from '@/types/nocode/enums'

const props = defineProps<{ detail: FlowMaterialDetail }>()
const emit = defineEmits<{ failed: [reason: string] }>()
const flow = ref({ rule: [] as any[], option: {} as Record<string, any>, value: {} as Record<string, unknown> })
const formApi = ref<any>()
const renderError = ref('')
watch(
  () => props.detail,
  detail => {
    renderError.value = ''
    if (!detail.flowForm) return
    try {
      setConfAndFields2(
        flow,
        detail.flowForm.conf,
        detail.flowForm.fields,
        JSON.parse(JSON.stringify(detail.flowForm.values))
      )
      flow.value.option = {
        ...flow.value.option,
        submitBtn: false,
        resetBtn: false,
        form: { ...flow.value.option.form, disabled: true }
      }
    } catch {
      renderError.value = '材料表单结构无法解析，请重新读取或联系管理员'
      emit('failed', renderError.value)
    }
  },
  { immediate: true }
)
watch(formApi, api => api?.disabled?.(true), { flush: 'post' })
onErrorCaptured(() => {
  renderError.value = '材料展示失败，请重新读取或联系管理员'
  emit('failed', renderError.value)
  return false
})
const business = computed(() => props.detail.businessForm)
const fields = computed(() =>
  business.value
    ? businessFields(business.value.model.object).filter(field =>
        business.value!.model.permissions.readFields.includes(field.id!)
      )
    : []
)
const labels = computed(() =>
  Object.fromEntries(
    fields.value
      .filter(field => ![FieldType.ATTACHMENT, FieldType.IMAGE].some(type => type === field.type))
      .map(field => {
        const source = business.value!,
          value = source.values[field.id!]
        if (source.displayValues && field.id! in source.displayValues)
          return [field.id!, source.displayValues[field.id!] || '—']
        const options = source.model.object.fieldOptions[field.id!]?.options || []
        const label = (value: unknown) =>
          options.find(option => option.code === value)?.label ||
          (typeof value === 'boolean'
            ? value
              ? '是'
              : '否'
            : typeof value === 'object'
              ? JSON.stringify(value)
              : String(value))
        return [
          field.id!,
          value == null || value === '' ? '—' : Array.isArray(value) ? value.map(label).join('、') : label(value)
        ]
      })
  )
)
</script>
<template>
  <a-alert v-if="renderError" type="error" show-icon :message="renderError" />
  <FormCreate
    v-else-if="detail.flowForm"
    :model-value="flow.value"
    v-model:api="formApi"
    :rule="flow.rule"
    :option="flow.option"
    class="material-flow-form"
  />
  <RecordReadView
    v-else-if="business"
    :fields="fields"
    :values="business.values"
    :display-values="labels"
    :options="business.model.object.fieldOptions"
    :nodes="business.form.nodes"
    :application-id="business.applicationId"
    :object-id="business.model.object.objectId"
    :record-id="business.recordId || undefined"
    :relations="business.model.object.relations"
    :layout="business.form.options?.layout"
    :business-policy="business.model.object.settings.businessFilePolicy || null"
  />
</template>
