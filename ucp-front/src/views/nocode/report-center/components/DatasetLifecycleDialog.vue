<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { DatasetDeletePreview, DatasetDetail } from '@/types/nocode/report-center'
const props = defineProps<{ mode: 'copy' | 'delete'; dataset: DatasetDetail }>()
const emit = defineEmits<{ close: []; done: [] }>()
const api = useNocodePlatform().reportCenter
const loading = ref(true),
  saving = ref(false),
  error = ref(''),
  reason = ref(''),
  confirmed = ref(false)
const name = ref(props.dataset.draft.name.slice(0, 77) + '副本')
const current = ref<DatasetDetail>(),
  preview = ref<DatasetDeletePreview>()
let disposed = false
async function load() {
  try {
    if (props.mode === 'copy') current.value = await api.get(props.dataset.id)
    else preview.value = await api.deletePreview(props.dataset.id)
  } catch (e) {
    if (!disposed) error.value = errorMessage(e)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function execute() {
  if (loading.value || saving.value) return
  if (!reason.value.trim()) {
    error.value = '请填写操作原因'
    return
  }
  if (props.mode === 'copy' && (!current.value || !name.value.trim())) {
    error.value = '请填写副本名称'
    return
  }
  if (props.mode === 'delete' && (!preview.value?.canDelete || !confirmed.value)) return
  const expectedRevision = props.mode === 'copy' ? current.value?.revision : preview.value?.revision
  if (expectedRevision === undefined) return
  saving.value = true
  error.value = ''
  try {
    if (props.mode === 'copy')
      await api.copy({
        id: props.dataset.id,
        expectedRevision,
        name: name.value.trim(),
        reason: reason.value.trim()
      })
    else await api.delete({ id: props.dataset.id, expectedRevision, reason: reason.value.trim() })
    emit('done')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
void load()
onScopeDispose(() => {
  disposed = true
})
</script>
<template>
  <OsModalForm
    :open="true"
    :title="mode === 'copy' ? '复制数据集' : '删除数据集'"
    :width="600"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    :loading="loading || saving"
    @cancel="
      () => {
        if (!saving) emit('close')
      }
    "
  >
    <template #formItems>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-spin :spinning="loading">
        <template v-if="mode === 'copy' && current">
          <a-alert
            type="info"
            message="复制当前已保存草稿，不复制发布版本和协作成员。副本默认可读取来源数据，保存后需重新发布。"
            show-icon
          />
          <a-form-item label="副本名称" required>
            <a-input v-model:value="name" aria-label="副本名称" :maxlength="80" :disabled="saving" />
          </a-form-item>
        </template>
        <template v-if="mode === 'delete' && preview">
          <a-alert
            :type="preview.canDelete ? 'warning' : 'error'"
            show-icon
            :message="
              preview.canDelete
                ? '未发现有效引用。删除后不可从报表中心访问，来源对象和业务数据会保留。'
                : `仍有 ${preview.referenceCount} 个引用，请联系引用资源管理员解除后重试。`
            "
          />
          <a-checkbox v-if="preview.canDelete" v-model:checked="confirmed" :disabled="saving">
            确认删除数据集「{{ dataset.draft.name }}」
          </a-checkbox>
        </template>
        <a-form-item v-if="current || preview?.canDelete" label="操作原因" required>
          <a-textarea v-model:value="reason" aria-label="操作原因" :rows="3" :maxlength="1000" :disabled="saving" />
        </a-form-item>
      </a-spin>
    </template>
    <template #footer>
      <a-button :disabled="saving" @click="emit('close')">取消</a-button>
      <a-button
        type="primary"
        :danger="mode === 'delete'"
        :loading="saving"
        :disabled="loading || (mode === 'copy' ? !current : !preview?.canDelete || !confirmed)"
        @click="execute"
      >
        {{ mode === 'copy' ? '创建副本' : '确认删除' }}
      </a-button>
    </template>
  </OsModalForm>
</template>
