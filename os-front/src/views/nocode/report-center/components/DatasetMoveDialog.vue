<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { folderTree } from '@/nocode/report-folders'
import type { DatasetDetail, ReportFolder } from '@/types/nocode/report-center'
const props = defineProps<{ id: string; folders: ReportFolder[] }>()
const emit = defineEmits<{ close: []; done: [] }>()
const api = useNocodePlatform().reportCenter
const current = ref<DatasetDetail>(),
  folderId = ref<string | null>(null),
  reason = ref(''),
  error = ref(''),
  busy = ref(true)
const tree = computed(() => folderTree(props.folders))
onMounted(async () => {
  try {
    current.value = await api.get(props.id)
    folderId.value = current.value.folderId
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
})
async function save() {
  if (!current.value || busy.value) return
  if (!reason.value.trim()) {
    error.value = '请填写移动原因'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.move({
      id: props.id,
      expectedRevision: current.value.revision,
      folderId: folderId.value || null,
      reason: reason.value.trim()
    })
    emit('done')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="移动数据集"
    :loading="busy"
    :disabled="!current"
    :width="600"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    ok-text="确认移动"
    @ok="save"
    @cancel="!busy && emit('close')"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-alert type="info" message="移动只改变分类位置，已发布内容和资源授权保持不变。" show-icon />
      <a-form-item label="数据集">
        <span>{{ current?.draft.name }}</span>
      </a-form-item>
      <a-form-item label="目标目录">
        <a-tree-select
          v-model:value="folderId"
          aria-label="目标目录"
          :tree-data="tree"
          placeholder="未分类"
          allow-clear
          tree-default-expand-all
          :disabled="busy || !current"
        />
      </a-form-item>
      <a-form-item label="移动原因" required>
        <a-textarea v-model:value="reason" aria-label="移动原因" :rows="2" :maxlength="1000" :disabled="busy" />
      </a-form-item>
    </template>
  </OsModalForm>
</template>
