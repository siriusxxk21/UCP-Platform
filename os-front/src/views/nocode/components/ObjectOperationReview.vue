<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import ObjectOperationImpact from './ObjectOperationImpact.vue'
import type { ObjectOperationPreview, ObjectOperationPreviewRequest } from '@/types/nocode/data-center'

const emit = defineEmits<{ navigate: [route: string] }>()
const visible = ref(false)
const request = ref<ObjectOperationPreviewRequest | null>(null)
const result = ref<ObjectOperationPreview>()
const title = ref('检查字段变更')
let resolveReview: ((accepted: boolean) => void) | undefined
function finish(accepted: boolean) {
  if (accepted && !result.value?.allowed) return
  visible.value = false
  resolveReview?.(accepted)
  resolveReview = undefined
  request.value = null
  result.value = undefined
}
function review(value: ObjectOperationPreviewRequest, name: string): Promise<boolean> {
  finish(false)
  request.value = JSON.parse(JSON.stringify(value)) as ObjectOperationPreviewRequest
  title.value = `${value.operation === 'restore_field' ? '恢复' : '停用'}字段“${name}”`
  visible.value = true
  return new Promise(resolve => {
    resolveReview = resolve
  })
}
defineExpose({ review })
onBeforeUnmount(() => finish(false))
</script>

<template>
  <OsModalForm
    v-if="visible"
    :open="visible"
    :title="title"
    :width="820"
    ok-text="确认并应用到草稿"
    @ok="finish(true)"
    @cancel="finish(false)"
  >
    <template #formItems>
      <ObjectOperationImpact
        v-if="visible"
        :request="request"
        @checked="result = $event"
        @navigate="emit('navigate', $event)"
      />
    </template>
    <template #footer>
      <a-button @click="finish(false)">取消</a-button>
      <a-button type="primary" :disabled="!result?.allowed" @click="finish(true)">确认并应用到草稿</a-button>
    </template>
  </OsModalForm>
</template>
