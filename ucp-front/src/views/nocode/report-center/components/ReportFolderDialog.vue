<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { errorMessage } from '@/nocode/data-center'
import { folderTree } from '@/nocode/report-folders'
import type { ReportFolder } from '@/types/nocode/report-center'
const props = defineProps<{
  resourceKind?: 'DATASET' | 'DASHBOARD'
  folders: ReportFolder[]
  folder?: ReportFolder
  parentId: string | null
  deleting?: boolean
}>()
const emit = defineEmits<{ close: []; done: [] }>()
const resourceKind = props.resourceKind === 'DASHBOARD' ? 'DASHBOARD' : undefined
const resourceLabel = props.resourceKind === 'DASHBOARD' ? '仪表板' : '数据集'
const api = useNocodePlatform().reportCenter
const form = reactive({
  name: props.folder?.name || '',
  parentId: props.folder?.parentId ?? props.parentId,
  sortNo: props.folder?.sortNo || 0,
  reason: ''
})
const initial = JSON.stringify(form)
const dirty = computed(() => initial !== JSON.stringify(form))
useUnsavedNavigation(() => dirty.value)
const saving = ref(false),
  error = ref('')
const title = props.deleting ? '删除目录' : props.folder ? '编辑目录' : '新建目录'
const tree = computed(() => folderTree(props.folders, props.folder?.id))
async function close() {
  if (!saving.value && (await confirmDiscard(dirty.value, '放弃尚未保存的目录修改？'))) emit('close')
}
async function save() {
  if (saving.value) return
  if (!form.reason.trim() || (!props.deleting && !form.name.trim())) {
    error.value = '请填写目录名称和操作原因'
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (props.deleting && props.folder)
      await api.deleteFolder({
        resourceKind,
        id: props.folder.id,
        expectedRevision: props.folder.revision,
        reason: form.reason.trim()
      })
    else
      await api.saveFolder({
        resourceKind,
        id: props.folder?.id || null,
        expectedRevision: props.folder?.revision || 0,
        ...form,
        parentId: form.parentId || null,
        name: form.name.trim(),
        reason: form.reason.trim()
      })
    emit('done')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    :title="title"
    :loading="saving"
    :width="600"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    :ok-text="deleting ? '删除目录' : '保存目录'"
    @ok="save"
    @cancel="close"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-alert
        v-if="deleting"
        type="warning"
        :message="`确认删除目录「${folder?.name}」？包含子目录或${resourceLabel}时无法删除。`"
        show-icon
      />
      <template v-else>
        <a-form-item label="目录名称" required>
          <a-input v-model:value="form.name" aria-label="目录名称" :maxlength="80" :disabled="saving" />
        </a-form-item>
        <a-form-item label="上级目录">
          <a-tree-select
            v-model:value="form.parentId"
            aria-label="上级目录"
            :tree-data="tree"
            placeholder="根目录"
            allow-clear
            tree-default-expand-all
            :disabled="saving"
          />
        </a-form-item>
        <a-form-item label="排序">
          <a-input-number
            v-model:value="form.sortNo"
            aria-label="目录排序"
            :min="0"
            :max="9999"
            :precision="0"
            :disabled="saving"
          />
        </a-form-item>
      </template>
      <a-form-item label="操作原因" required>
        <a-textarea
          v-model:value="form.reason"
          aria-label="目录操作原因"
          :rows="2"
          :maxlength="1000"
          :disabled="saving"
        />
      </a-form-item>
    </template>
  </OsModalForm>
</template>
