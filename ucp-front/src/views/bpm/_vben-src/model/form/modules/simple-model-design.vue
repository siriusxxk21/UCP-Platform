<script lang="ts" setup>
import { ref } from 'vue'

import { ContentWrap } from '@vben/common-ui'

import { SimpleProcessDesigner } from '#/views/bpm/components/simple-process-design'

defineOptions({ name: 'SimpleModelDesign' })

defineProps<{
  modelFormId?: number
  modelFormType?: number
  modelName?: string
  startDeptIds?: number[]
  startUserIds?: number[]
}>()

const emit = defineEmits(['success'])

const designerRef = ref()

/** 保存成功回调 */
function handleSuccess(data?: any) {
  if (data) {
    emit('success', data)
  }
}

/** 设计器配置校验 */
async function validateConfig() {
  return await designerRef.value.validate()
}

defineExpose({ validateConfig })
</script>
<template>
  <ContentWrap class="px-4 py-5">
    <SimpleProcessDesigner
      ref="designerRef"
      :model-form-id="modelFormId"
      :model-form-type="modelFormType"
      :model-name="modelName"
      :start-dept-ids="startDeptIds"
      :start-user-ids="startUserIds"
      @success="handleSuccess"
    />
  </ContentWrap>
</template>
