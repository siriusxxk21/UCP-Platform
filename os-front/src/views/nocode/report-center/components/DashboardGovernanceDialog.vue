<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { errorMessage } from '@/nocode/data-center'
import { folderTree } from '@/nocode/report-folders'
import type { ReportFolder } from '@/types/nocode/report-center'
import type {
  DashboardAvailableItem,
  DashboardDeletePreview,
  DashboardDetail,
  DashboardRelease
} from '@/types/nocode/report-dashboard'

const props = defineProps<{
  dashboard: DashboardAvailableItem
  mode: 'copy' | 'move' | 'status' | 'delete' | 'history'
  folders?: ReportFolder[]
}>()
const emit = defineEmits<{ close: []; done: [] }>()
const api = useNocodePlatform().reportCenter
const current = ref<DashboardDetail>(),
  preview = ref<DashboardDeletePreview>(),
  versions = ref<DashboardRelease[]>([]),
  versionTotal = ref(0),
  versionPage = ref(1),
  selectedVersion = ref<number>(),
  name = ref(props.dashboard.name.slice(0, 77) + '副本'),
  folderId = ref<string | null>(props.dashboard.folderId || null),
  reason = ref(''),
  confirmed = ref(false),
  busy = ref(false),
  loading = ref(true),
  error = ref('')
const tree = computed(() => folderTree(props.folders || []))
const titles = {
  copy: '复制仪表板',
  move: '移动仪表板',
  status: '仪表板状态',
  delete: '删除仪表板',
  history: '历史版本'
}
const dirty = computed(
  () =>
    !!reason.value ||
    (props.mode === 'copy' && name.value !== props.dashboard.name.slice(0, 77) + '副本') ||
    (props.mode === 'move' && folderId.value !== (props.dashboard.folderId || null))
)
useUnsavedNavigation(() => dirty.value)
const canRestore = computed(() => !!current.value?.capabilities?.canEdit)
const canExecute = computed(() => {
  if (loading.value || busy.value) return false
  if (props.mode === 'delete') return !!preview.value?.canDelete && confirmed.value
  if (!current.value) return false
  return props.mode !== 'history' || (canRestore.value && selectedVersion.value !== undefined)
})
let generation = 0
async function loadVersions() {
  const g = generation
  loading.value = true
  error.value = ''
  try {
    const page = await api.dashboardReleases(props.dashboard.id, { pageNo: versionPage.value, pageSize: 10 })
    if (g !== generation) return
    versions.value = page.list
    versionTotal.value = page.total
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
async function load() {
  const g = ++generation
  loading.value = true
  error.value = ''
  try {
    if (props.mode === 'delete') {
      const data = await api.dashboardDeletePreview(props.dashboard.id)
      if (g === generation) preview.value = data
    } else {
      const data = await api.dashboardGet(props.dashboard.id)
      if (g !== generation) return
      current.value = data
      folderId.value = data.folderId || null
      if (props.mode === 'history') await loadVersions()
    }
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
async function close() {
  if (!busy.value && (await confirmDiscard(dirty.value, '放弃尚未提交的仪表板操作？'))) emit('close')
}
async function execute() {
  if (!canExecute.value) return
  if (!reason.value.trim()) {
    error.value = '请填写操作原因'
    return
  }
  if (props.mode === 'copy' && !name.value.trim()) {
    error.value = '请填写副本名称'
    return
  }
  const revision = props.mode === 'delete' ? preview.value?.revision : current.value?.revision
  if (revision === undefined) return
  const body = { id: props.dashboard.id, expectedRevision: revision, reason: reason.value.trim() }
  const g = generation
  busy.value = true
  error.value = ''
  try {
    switch (props.mode) {
      case 'copy':
        await api.dashboardCopy({ ...body, name: name.value.trim() })
        break
      case 'move':
        await api.dashboardMove({ ...body, folderId: folderId.value || null })
        break
      case 'status':
        await api.dashboardStatus({ ...body, status: current.value?.status === 'INACTIVE' ? 'ACTIVE' : 'INACTIVE' })
        break
      case 'delete':
        await api.dashboardDelete(body)
        break
      case 'history':
        if (selectedVersion.value === undefined) return
        await api.dashboardRestore({ ...body, versionNo: selectedVersion.value })
        break
    }
    if (g === generation) emit('done')
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) busy.value = false
  }
}
function changeVersionPage(page: number) {
  versionPage.value = page
  void loadVersions()
}
void load()
onScopeDispose(() => generation++)
</script>
<template>
  <OsModalForm
    :open="true"
    :title="titles[mode]"
    :width="mode === 'history' ? 760 : 600"
    :loading="loading || busy"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    @cancel="close"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-skeleton v-if="loading && !current && !preview" active />
      <template v-if="current">
        <a-form-item label="仪表板">{{ current.draft.name }}</a-form-item>
        <template v-if="mode === 'copy'">
          <a-alert type="info" message="复制已保存的草稿，新副本需要单独发布和配置协作权限。" show-icon />
          <a-form-item label="副本名称" required>
            <a-input v-model:value="name" aria-label="副本名称" :maxlength="80" :disabled="busy" />
          </a-form-item>
        </template>
        <template v-else-if="mode === 'move'">
          <a-form-item label="目标目录">
            <a-tree-select
              v-model:value="folderId"
              aria-label="目标目录"
              :tree-data="tree"
              placeholder="未分类"
              allow-clear
              tree-default-expand-all
              :disabled="busy"
            />
          </a-form-item>
        </template>
        <a-alert
          v-else-if="mode === 'status'"
          :type="current.status === 'INACTIVE' ? 'info' : 'warning'"
          :message="
            current.status === 'INACTIVE'
              ? '启用前将检查发布版本的引用和配置。'
              : '停用后所有发布版本停止运行，应用中的固定引用也会停止。'
          "
          show-icon
        />
        <template v-else-if="mode === 'history'">
          <a-alert type="info" message="恢复历史配置会写入草稿，重新发布后生效。" show-icon />
          <a-radio-group v-model:value="selectedVersion" class="dashboard-versions" :disabled="busy || !canRestore">
            <a-radio v-for="version in versions" :key="version.versionNo" :value="version.versionNo">
              V{{ version.versionNo }} · {{ version.content.name }} · {{ version.content.charts.length }} 个组件
              <a-tag v-if="version.versionNo === current.publishedVersion">当前发布</a-tag>
            </a-radio>
          </a-radio-group>
          <a-empty v-if="!loading && !versionTotal" description="暂无发布历史" />
          <a-pagination
            v-if="versionTotal > 10"
            :current="versionPage"
            :total="versionTotal"
            :page-size="10"
            :disabled="busy || loading"
            @change="changeVersionPage"
          />
        </template>
      </template>
      <template v-if="mode === 'delete' && preview">
        <a-alert
          :type="preview.canDelete ? 'warning' : 'error'"
          :message="
            preview.canDelete
              ? '删除后无法访问此仪表板，来源对象和业务数据会保留。'
              : `仍有 ${preview.referenceCount} 个引用，请解除引用后重试。`
          "
          show-icon
        />
        <a-checkbox v-if="preview.canDelete" v-model:checked="confirmed" :disabled="busy">
          确认删除仪表板「{{ dashboard.name }}」
        </a-checkbox>
      </template>
      <a-form-item
        v-if="(current && (mode !== 'history' || canRestore)) || preview?.canDelete"
        label="操作原因"
        required
      >
        <a-textarea v-model:value="reason" aria-label="仪表板操作原因" :rows="2" :maxlength="1000" :disabled="busy" />
      </a-form-item>
      <a-button v-if="!loading && !current && !preview" @click="load">重新加载</a-button>
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="close">关闭</a-button>
      <a-button
        v-if="mode !== 'history' || canRestore"
        type="primary"
        :danger="mode === 'delete' || (mode === 'status' && current?.status !== 'INACTIVE')"
        :disabled="!canExecute"
        :loading="busy"
        @click="execute"
      >
        {{
          mode === 'copy'
            ? '创建副本'
            : mode === 'move'
              ? '确认移动'
              : mode === 'delete'
                ? '确认删除'
                : mode === 'history'
                  ? '恢复为草稿'
                  : current?.status === 'INACTIVE'
                    ? '启用'
                    : '停用'
        }}
      </a-button>
    </template>
  </OsModalForm>
</template>
<style scoped>
.dashboard-versions {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin: var(--spacing-lg) 0;
}
</style>
