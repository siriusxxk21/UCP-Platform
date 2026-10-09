<script lang="ts" setup>
import FormCreate from '@form-create/ant-design-vue'
import { message } from 'ant-design-vue'
import { reactive, ref, watch } from 'vue'
import { getForm } from '@/api/bpm/form'
import { setConfAndFields2 } from '@/components/form-create'

const props = defineProps<{
  formId?: string | number
}>()

const open = defineModel<boolean>('open', { default: false })
const loading = ref(false)
const formConfig = reactive<Record<string, any>>({
  option: {},
  rule: []
})

async function loadDetail() {
  if (!open.value || !props.formId) return
  loading.value = true
  try {
    const detail = await getForm(props.formId)
    Object.assign(formConfig, detail)
    setConfAndFields2(formConfig, detail.conf, detail.fields)
  } catch (error: any) {
    console.error('加载流程表单详情失败:', error)
    message.error(error.message || '加载流程表单详情失败')
  } finally {
    loading.value = false
  }
}

watch(() => [open.value, props.formId], loadDetail, { immediate: true })
</script>

<template>
  <a-modal v-model:open="open" :footer="null" title="流程表单详情" width="720px">
    <a-spin :spinning="loading">
      <FormCreate :option="formConfig.option" :rule="formConfig.rule" />
    </a-spin>
  </a-modal>
</template>
