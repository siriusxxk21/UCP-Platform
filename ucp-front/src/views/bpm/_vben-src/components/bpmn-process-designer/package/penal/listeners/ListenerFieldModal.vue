<script lang="ts" setup>
import { ref } from 'vue'

import { useVbenModal } from '@vben/common-ui'

import { Form, FormItem, Input, Select, SelectOption } from 'ant-design-vue'

import { fieldType } from './utilSelf'

defineOptions({ name: 'ListenerFieldModal' })

const emit = defineEmits<{
  confirm: [data: any]
}>()

const fieldTypeObject = ref(fieldType)
const form = ref<any>({})
const formRef = ref()

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen) {
    if (isOpen) {
      const data = modalApi.getData<any>()
      form.value = data || {}
      // clear validate
      setTimeout(() => {
        formRef.value?.clearValidate()
      }, 50)
    }
  },
  onConfirm: async () => {
    try {
      await formRef.value?.validate()
      emit('confirm', { ...form.value })
      await modalApi.close()
    } catch {
      // validate failed
    }
  }
})
</script>

<template>
  <Modal class="w-3/5" title="字段配置">
    <Form ref="formRef" :label-col="{ span: 4 }" :model="form" :wrapper-col="{ span: 18 }">
      <FormItem
        :rules="[
          {
            required: true,
            message: '请填写字段名称',
            trigger: ['blur', 'change']
          }
        ]"
        label="字段名称："
        name="name"
      >
        <Input v-model:value="form.name" allow-clear />
      </FormItem>
      <FormItem
        :rules="[
          {
            required: true,
            message: '请选择字段类型',
            trigger: ['blur', 'change']
          }
        ]"
        label="字段类型："
        name="fieldType"
      >
        <Select v-model:value="form.fieldType">
          <SelectOption v-for="i in Object.keys(fieldTypeObject)" :key="i" :value="i">
            {{ fieldTypeObject[i as keyof typeof fieldType] }}
          </SelectOption>
        </Select>
      </FormItem>
      <FormItem
        v-if="form.fieldType === 'string'"
        key="field-string"
        :rules="[
          {
            required: true,
            message: '请填写字段值',
            trigger: ['blur', 'change']
          }
        ]"
        label="字段值："
        name="string"
      >
        <Input v-model:value="form.string" allow-clear />
      </FormItem>
      <FormItem
        v-if="form.fieldType === 'expression'"
        key="field-expression"
        :rules="[
          {
            required: true,
            message: '请填写表达式',
            trigger: ['blur', 'change']
          }
        ]"
        label="表达式："
        name="expression"
      >
        <Input v-model:value="form.expression" allow-clear />
      </FormItem>
    </Form>
  </Modal>
</template>
