<script lang="ts" setup>
import type { FormInstance, FormProps } from 'ant-design-vue'
import { computed, reactive, ref, watch } from 'vue'
import type { InfraConfig, InfraConfigSaveParams } from '@/api/infra/config'
import { createConfig, getConfig, updateConfig } from '@/api/infra/config'

const props = defineProps<{
  open: boolean
  record?: InfraConfig | null
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'success', action: 'create' | 'update'): void
}>()

interface ConfigForm {
  id?: string | number
  category: string
  name: string
  key: string
  value: string
  visible: boolean
  remark: string
}

const formRef = ref<FormInstance>()
const loading = ref(false)
const submitting = ref(false)
const formData = reactive<ConfigForm>(createDefaultForm())

const isEdit = computed(() => formData.id !== undefined)

const rules: FormProps['rules'] = {
  category: [
    { required: true, whitespace: true, message: '请输入参数分类', trigger: 'blur' },
    { max: 50, message: '参数分类不能超过 50 个字符', trigger: 'blur' }
  ],
  name: [
    { required: true, whitespace: true, message: '请输入参数名称', trigger: 'blur' },
    { max: 100, message: '参数名称不能超过 100 个字符', trigger: 'blur' }
  ],
  key: [
    { required: true, whitespace: true, message: '请输入参数键名', trigger: 'blur' },
    { max: 100, message: '参数键名不能超过 100 个字符', trigger: 'blur' }
  ],
  value: [
    { required: true, whitespace: true, message: '请输入参数键值', trigger: 'blur' },
    { max: 500, message: '参数键值不能超过 500 个字符', trigger: 'blur' }
  ],
  visible: [{ required: true, message: '请选择是否可见', trigger: 'change' }]
}

function createDefaultForm(): ConfigForm {
  return {
    id: undefined,
    category: '',
    name: '',
    key: '',
    value: '',
    visible: true,
    remark: ''
  }
}

function resetForm() {
  Object.assign(formData, createDefaultForm())
  formRef.value?.clearValidate()
}

async function loadRecord(record?: InfraConfig | null) {
  resetForm()
  if (record?.id === undefined) return

  loading.value = true
  try {
    const detail = await getConfig(record.id)
    Object.assign(formData, {
      id: detail.id,
      category: detail.category,
      name: detail.name,
      key: detail.key,
      value: detail.value,
      visible: detail.visible,
      remark: detail.remark ?? ''
    })
  } finally {
    loading.value = false
  }
}

function handleOpenChange(open: boolean) {
  if (open) loadRecord(props.record)
}

watch(() => props.open, handleOpenChange)

function buildSubmitData(): InfraConfigSaveParams {
  return {
    id: formData.id,
    category: formData.category.trim(),
    name: formData.name.trim(),
    key: formData.key.trim(),
    value: formData.value.trim(),
    visible: formData.visible,
    remark: formData.remark.trim() || undefined
  }
}

async function handleSubmit() {
  await formRef.value?.validate()
  const action = isEdit.value ? 'update' : 'create'

  submitting.value = true
  try {
    const data = buildSubmitData()
    if (action === 'update') await updateConfig(data)
    else await createConfig(data)

    emit('update:open', false)
    emit('success', action)
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  if (!submitting.value) emit('update:open', false)
}
</script>

<template>
  <a-modal
    :closable="!submitting"
    :confirm-loading="submitting"
    :mask-closable="!submitting"
    :open="open"
    :title="isEdit ? '编辑参数' : '新增参数'"
    cancel-text="取消"
    ok-text="保存"
    width="640px"
    @cancel="handleCancel"
    @ok="handleSubmit"
  >
    <a-spin :spinning="loading">
      <a-form ref="formRef" :model="formData" :rules="rules" layout="vertical">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="参数分类" name="category">
              <a-input v-model:value="formData.category" allow-clear placeholder="请输入参数分类" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="参数名称" name="name">
              <a-input v-model:value="formData.name" allow-clear placeholder="请输入参数名称" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="参数键名" name="key">
          <a-input v-model:value="formData.key" allow-clear placeholder="请输入参数键名" />
        </a-form-item>

        <a-form-item label="参数键值" name="value">
          <a-textarea
            v-model:value="formData.value"
            :auto-size="{ minRows: 2, maxRows: 6 }"
            :maxlength="500"
            placeholder="请输入参数键值"
            show-count
          />
        </a-form-item>

        <a-form-item label="是否可见" name="visible">
          <a-radio-group v-model:value="formData.visible" button-style="solid">
            <a-radio-button :value="true">是</a-radio-button>
            <a-radio-button :value="false">否</a-radio-button>
          </a-radio-group>
        </a-form-item>

        <a-form-item label="备注" name="remark">
          <a-textarea
            v-model:value="formData.remark"
            :auto-size="{ minRows: 2, maxRows: 4 }"
            placeholder="请输入备注"
          />
        </a-form-item>
      </a-form>
    </a-spin>
  </a-modal>
</template>
