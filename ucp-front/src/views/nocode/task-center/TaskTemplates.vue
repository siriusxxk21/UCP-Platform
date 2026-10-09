<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'

import type {
  SaveTaskTemplate,
  TaskDetail as TaskDetailResult,
  TaskMember,
  TaskTemplate,
  TaskTemplateVersionSummary
} from '@/types/nocode/task-center'
import { newAutoTaskNode, taskNodeError, taskTime } from '@/nocode/task-center'
import { errorMessage } from '@/nocode/data-center'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useOsTablePage } from '@/composables/useOsTablePage'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import TaskNodeEditor from './TaskNodeEditor.vue'
import TaskNodeFields from './TaskNodeFields.vue'
import { formatEffectiveWorkMinutes, validEffectiveWorkMinutes } from '@/nocode/task-work-duration'
import { taskBusinessConfigError } from '@/nocode/task-work-rule'
import TaskLaunchDrawer from './TaskLaunchDrawer.vue'
import TaskDetail from './TaskDetail.vue'
import TaskTemplateInstances from './TaskTemplateInstances.vue'
import { hasPermission } from '@/utils/access'
import { useUserStore } from '@/stores/user'
const { confirmDiscard, confirm } = useTaskConfirmation()
const api = useNocodePlatform().taskCenter,
  error = ref(''),
  members = ref<TaskMember[]>([]),
  editing = ref<SaveTaskTemplate | null>(null),
  busy = ref(false),
  original = ref('')
const launchTemplateId = ref(''),
  launchTemplateVersion = ref<number>(),
  detailId = ref('')
const instanceList = ref<InstanceType<typeof TaskTemplateInstances> | null>(null)
const nodeEditor = ref<InstanceType<typeof TaskNodeEditor> | null>(null)
const savedTemplate = ref<TaskTemplate | null>(null)
const activeTab = ref('arrangement')
const basicOpen = ref(false)
const refreshRequired = ref(false)
const reloading = ref(false)
const versionLoading = ref(false)
const versions = ref<TaskTemplateVersionSummary[]>([])
const versionsError = ref('')
const selectedVersion = ref<'draft' | number>('draft')
const parkedDraft = ref<SaveTaskTemplate | null>(null)
const setPublishedAsPrimary = ref(false)
const versionOptions = computed(() => [
  { value: 'draft', label: '草稿 · 可编辑副本' },
  ...versions.value.map(item => ({
    value: item.version,
    label: `V${item.version}${item.primary ? ' · 主版本' : ''}${item.version === savedTemplate.value?.publishedVersion ? ' · 最新发布' : ''}`
  }))
])
const working = computed(() => busy.value || reloading.value || versionLoading.value)
const canLaunch = computed(() => hasPermission('nocode:task:create') && hasPermission('nocode:task:query'))
function useTemplate(row: TaskTemplate, version?: number) {
  if (canLaunch.value && row.publishedVersion) {
    launchTemplateVersion.value = version
    launchTemplateId.value = row.id
  }
}
function launched(detail: TaskDetailResult) {
  launchTemplateId.value = ''
  detailId.value = detail.task.id
  // 已打开的实例页签继续使用当前筛选和页码，只刷新数据，不重建工作区。
  void instanceList.value?.refresh()
}
const table = useOsTablePage<TaskTemplate, { search: string }>({
  defaultQuery: () => ({ search: '' }),
  fetchFn: async query => {
    const templates = await api.templates()
    if (!editing.value) error.value = ''
    return templates.filter(t => t.name.includes(query.search || ''))
  },
  onError: e => {
    error.value = errorMessage(e)
  }
})
const columns = [
  { title: '模板名称', key: 'name', dataIndex: 'name', width: 250 },
  { title: '说明', key: 'description', dataIndex: 'description', width: 280 },
  { title: '有效工作时长', key: 'effectiveWorkMinutes', width: 160 },
  { title: '子任务数', key: 'nodes', width: 100 },
  { title: '主版本 / 最新发布', key: 'version', width: 180 },
  { title: '更新时间', key: 'time', width: 180 },
  { title: '操作', key: 'actions', width: 245, fixed: 'right' as const }
]
const currentDraft = computed(() => (selectedVersion.value === 'draft' ? editing.value : parkedDraft.value))
const changed = computed(() => !!currentDraft.value && JSON.stringify(currentDraft.value) !== original.value)
watch(
  () => editing.value?.name,
  (name, previous) => {
    if (
      selectedVersion.value === 'draft' &&
      editing.value?.task &&
      (!editing.value.task.title || editing.value.task.title === previous)
    )
      editing.value.task.title = name || ''
  }
)
const user = useUserStore()
const canEdit = (row: TaskTemplate) =>
  hasPermission('nocode:task:template') &&
  (String(row.creatorId) === String(user.userInfo?.id) || hasPermission('nocode:task:manage-all'))
const editable = computed(() =>
  savedTemplate.value ? canEdit(savedTemplate.value) : hasPermission('nocode:task:template')
)
const locked = computed(
  () => selectedVersion.value !== 'draft' || !editable.value || working.value || refreshRequired.value
)
const allNodes = computed(() =>
  editing.value?.task ? [editing.value.task, ...editing.value.nodes] : editing.value?.nodes || []
)
useUnsavedNavigation(() => changed.value, { confirm: confirmDiscard })
onMounted(async () => {
  try {
    members.value = await api.members()
  } catch (e) {
    error.value = errorMessage(e)
  }
})
function open(row?: TaskTemplate, preserveWorkspace = false) {
  selectedVersion.value = 'draft'
  parkedDraft.value = null
  setPublishedAsPrimary.value = false
  savedTemplate.value = row || null
  if (!preserveWorkspace) {
    activeTab.value = 'arrangement'
    basicOpen.value = !row
  }
  refreshRequired.value = false
  editing.value = row
    ? {
        id: row.id,
        expectedRevision: row.revision,
        name: row.name,
        description: row.description,
        task: row.task ? JSON.parse(JSON.stringify(row.task)) : null,
        nodes: JSON.parse(JSON.stringify(row.nodes))
      }
    : {
        id: null,
        expectedRevision: null,
        name: '',
        description: '',
        task: { ...newAutoTaskNode(), dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } },
        nodes: []
      }
  original.value = JSON.stringify(editing.value)
  error.value = ''
  versions.value = []
  versionsError.value = ''
  if (row) void loadVersions(row.id)
}
let versionDirectoryGeneration = 0
let snapshotGeneration = 0
onBeforeUnmount(() => {
  versionDirectoryGeneration++
  snapshotGeneration++
})
async function loadVersions(id: string) {
  const generation = ++versionDirectoryGeneration
  versionsError.value = ''
  try {
    const result = await api.templateVersions(id)
    if (generation === versionDirectoryGeneration && savedTemplate.value?.id === id) versions.value = result
  } catch (cause) {
    if (generation === versionDirectoryGeneration && savedTemplate.value?.id === id)
      versionsError.value = `版本列表加载失败：${errorMessage(cause)}`
  }
}
async function changeVersion(value: unknown) {
  const next = value === 'draft' ? 'draft' : Number(value)
  if (!savedTemplate.value || !editing.value || working.value || next === selectedVersion.value) return
  if (next !== 'draft' && (!Number.isInteger(next) || !versions.value.some(item => item.version === next))) return
  if (
    selectedVersion.value === 'draft' &&
    changed.value &&
    !(await confirm(
      '查看已发布版本？',
      '未保存的草稿会保留在本页，切回“草稿”后可以继续编辑；发布版本只读。',
      '切换版本'
    ))
  )
    return
  if (next === 'draft') {
    selectedVersion.value = 'draft'
    editing.value = parkedDraft.value
    parkedDraft.value = null
    error.value = ''
    return
  }
  const id = savedTemplate.value.id
  const generation = ++snapshotGeneration
  versionLoading.value = true
  error.value = ''
  try {
    const snapshot = await api.templateVersion(id, next)
    if (generation !== snapshotGeneration || savedTemplate.value?.id !== id) return
    if (selectedVersion.value === 'draft') parkedDraft.value = editing.value
    selectedVersion.value = next
    editing.value = {
      id,
      expectedRevision: savedTemplate.value.revision,
      name: snapshot.name,
      description: snapshot.description,
      task: snapshot.task ? JSON.parse(JSON.stringify(snapshot.task)) : null,
      nodes: JSON.parse(JSON.stringify(snapshot.nodes))
    }
  } catch (cause) {
    if (generation === snapshotGeneration) error.value = `版本加载失败：${errorMessage(cause)}`
  } finally {
    if (generation === snapshotGeneration) versionLoading.value = false
  }
}
async function setPrimary() {
  const row = savedTemplate.value
  const version = selectedVersion.value
  if (
    !row ||
    version === 'draft' ||
    !canEdit(row) ||
    working.value ||
    refreshRequired.value ||
    row.primaryVersion === version
  )
    return
  if (
    !(await confirm(
      `将 V${version} 设为主版本？`,
      '以后选择此模板时默认使用该版本。已有任务及已保存任务草稿不会改变。',
      '设为主版本'
    ))
  )
    return
  busy.value = true
  error.value = ''
  try {
    const updated = await api.setPrimaryTemplateVersion(row.id, version, row.revision)
    versionDirectoryGeneration++
    savedTemplate.value = updated
    // 切主版本只推进修订号，不能用接口中的草稿覆盖本页尚未保存的内容。
    if (currentDraft.value) currentDraft.value.expectedRevision = updated.revision
    const baseline = JSON.parse(original.value) as SaveTaskTemplate
    baseline.expectedRevision = updated.revision
    original.value = JSON.stringify(baseline)
    versions.value = versions.value.map(item => ({ ...item, primary: item.version === version }))
    await table.fetchData()
    message.success(`已将 V${version} 设为主版本，历史任务保持不变`)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function close() {
  if (working.value) return
  if (await confirmDiscard(changed.value)) {
    editing.value = null
    savedTemplate.value = null
    parkedDraft.value = null
    versionDirectoryGeneration++
    snapshotGeneration++
  }
}
async function save() {
  if (!editing.value || locked.value) return
  const root = editing.value.task
  const allNodes = root
    ? [root, ...editing.value.nodes.map(node => ({ ...node, parentId: node.parentId || root.id }))]
    : editing.value.nodes
  error.value = !editing.value.name.trim() ? '请填写模板名称' : taskNodeError(allNodes) || ''
  if (!validEffectiveWorkMinutes(root?.effectiveWorkMinutes)) {
    error.value = '任务标准总工时须为 0 至 9999 小时 59 分钟的整数分钟'
    activeTab.value = 'business'
    return
  }
  if (error.value) {
    if (!editing.value.name.trim()) basicOpen.value = true
    else activeTab.value = 'arrangement'
    return
  }
  busy.value = true
  try {
    const saved = await api.saveTemplate(JSON.parse(JSON.stringify(editing.value)))
    open(saved, true)
    await table.fetchData()
    message.success('模板草稿已保存，发布后即可用来新建任务')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function publish(row: TaskTemplate, promote = false) {
  if (busy.value || !canEdit(row)) return
  error.value = taskBusinessConfigError([...(row.task ? [row.task] : []), ...row.nodes])
  if (error.value) {
    if (editing.value?.id === row.id) activeTab.value = 'business'
    return
  }
  const setAsPrimary = !row.primaryVersion || promote
  if (
    !(await confirm(
      '发布当前模板草稿？',
      `发布会保留新的独立版本，${setAsPrimary ? '并设为主版本' : `主版本仍为 V${row.primaryVersion}`}。已创建的任务不受影响。`,
      '发布'
    ))
  )
    return
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const published = await api.publishTemplate(row.id, row.revision, setAsPrimary)
    if (editing.value?.id === row.id) {
      savedTemplate.value = { ...row, publishedVersion: published.version }
      // 发布会推进服务端修订号，必须刷新头版本后才继续编辑，不能用旧 revision 再提交。
      refreshRequired.value = true
      await reloadWorkspace()
    }
    await table.fetchData()
    message.success('模板已发布，新建任务可选择此版本')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function publishCurrent() {
  if (!savedTemplate.value || changed.value || locked.value) return
  await publish(savedTemplate.value, setPublishedAsPrimary.value)
}
async function reloadWorkspace() {
  if (!editing.value?.id || reloading.value) return
  // 重试互斥；同时绑定草稿对象，旧响应不能覆盖重新打开的同名模板会话。
  const draft = editing.value
  reloading.value = true
  try {
    const latest = (await api.templates()).find(row => row.id === draft.id)
    if (!latest) throw new Error('模板暂不可访问，请返回列表刷新')
    if (editing.value === draft) open(latest, true)
  } catch (cause) {
    if (editing.value === draft)
      error.value = `模板已发布，但最新草稿加载失败：${errorMessage(cause)}。请重新加载后继续编辑。`
  } finally {
    reloading.value = false
  }
}
</script>
<template>
  <section :class="editing || launchTemplateId ? 'task-template-workspace' : 'nocode-list-page'">
    <a-alert v-if="error && !editing && !launchTemplateId" class="notice" type="error" :message="error" show-icon />
    <OsTablePage
      v-if="!editing"
      v-show="!launchTemplateId"
      :columns="columns"
      :data-source="table.tableData.value"
      :loading="table.loading.value || busy"
      :pagination="table.pagination"
      :scroll="{ x: 1280 }"
      show-column-settings
      resizable
      column-settings-key="task-templates-v3"
      @change="table.handleTableChange"
      @search="table.handleQuery"
    >
      <template #search>
        <a-form layout="inline">
          <a-form-item label="模板名称">
            <a-input v-model:value="table.queryForm.search" allow-clear @press-enter="table.handleQuery" />
          </a-form-item>
          <a-button type="primary" @click="table.handleQuery">查询</a-button>
          <a-button @click="table.handleReset">重置</a-button>
        </a-form>
      </template>
      <template #title>任务模板</template>
      <template #actions>
        <slot name="page-actions" />
        <a-button v-if="hasPermission('nocode:task:template')" type="primary" @click="open()">新建模板</a-button>
      </template>
      <template #empty>
        <a-empty
          :description="
            error
              ? '模板暂时无法加载，请重新查询。'
              : table.queryForm.search
                ? '没有找到匹配的模板，试试其他名称。'
                : '暂无任务模板。将常用任务保存并发布为模板，下次可直接使用。'
          "
        />
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'nodes'">{{ record.nodes.length }}</template>
        <template v-else-if="column.key === 'effectiveWorkMinutes'">
          {{ formatEffectiveWorkMinutes(record.task?.effectiveWorkMinutes) }}
        </template>
        <template v-else-if="column.key === 'name'">
          <a-button type="link" @click="open(record)">{{ record.name }}</a-button>
        </template>
        <template v-else-if="column.key === 'version'">
          {{
            record.publishedVersion
              ? `主版本 V${record.primaryVersion ?? record.publishedVersion} / 最新 V${record.publishedVersion}`
              : '未发布'
          }}
        </template>
        <template v-else-if="column.key === 'time'">{{ taskTime(record.updatedAt) }}</template>
        <template v-else-if="column.key === 'actions'">
          <div class="nocode-table-actions">
            <a-button v-if="canEdit(record)" type="link" @click="open(record)">编辑草稿</a-button>
            <a-button v-else type="link" @click="open(record)">查看</a-button>
            <a-button v-if="canEdit(record)" type="link" @click="publish(record)">发布</a-button>
            <a-button v-if="record.publishedVersion && canLaunch" type="link" @click="useTemplate(record)">
              使用模板
            </a-button>
          </div>
        </template>
        <template v-else-if="column.dataIndex && column.key !== '_index'">{{ record[column.dataIndex] }}</template>
      </template>
    </OsTablePage>
    <TaskLaunchDrawer
      v-if="launchTemplateId"
      :initial-template-id="launchTemplateId"
      :initial-template-version="launchTemplateVersion"
      @close="launchTemplateId = ''"
      @created="launched"
    />
    <TaskDetail v-if="detailId" :id="detailId" plan-readonly @close="detailId = ''" @select="detailId = $event" />
    <div v-if="editing" v-show="!launchTemplateId" class="task-template-workspace">
      <header class="task-template-workspace__header">
        <a-button
          class="task-template-workspace__back"
          :disabled="working"
          aria-label="返回模板列表"
          title="返回模板列表"
          @click="close"
        >
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>
        <div class="task-template-workspace__identity">
          <h3 :title="editing.name">{{ editing.name || '新建任务模板' }}</h3>
          <div class="task-template-workspace__status">
            <a-tag :color="selectedVersion === 'draft' ? 'blue' : 'green'">
              {{ selectedVersion === 'draft' ? (editable ? '草稿' : '只读') : `V${selectedVersion} · 只读` }}
            </a-tag>
            <a-tag v-if="changed" color="orange">草稿未保存</a-tag>
          </div>
        </div>
        <a-space class="task-template-workspace__actions" wrap>
          <a-button :disabled="working" @click="basicOpen = !basicOpen">
            {{ basicOpen ? '收起基本信息' : editable && selectedVersion === 'draft' ? '编辑基本信息' : '基本信息' }}
          </a-button>
          <a-button v-if="refreshRequired" :disabled="working" :loading="reloading" @click="reloadWorkspace">
            重新加载草稿
          </a-button>
          <a-button
            v-if="editable && selectedVersion === 'draft'"
            type="primary"
            :loading="working"
            :disabled="refreshRequired"
            @click="save"
          >
            保存草稿
          </a-button>
          <a-button
            v-if="editable && selectedVersion === 'draft'"
            :disabled="!editing.id || changed || locked"
            :title="changed || !editing.id ? '请先保存草稿，再发布' : '发布当前已保存草稿'"
            @click="publishCurrent"
          >
            发布
          </a-button>
          <a-button
            v-if="savedTemplate?.publishedVersion && canLaunch"
            :disabled="working"
            @click="useTemplate(savedTemplate, selectedVersion === 'draft' ? undefined : selectedVersion)"
          >
            {{ selectedVersion === 'draft' ? '使用已发布模板' : '使用此版本发起' }}
          </a-button>
        </a-space>
        <div v-if="savedTemplate" class="task-template-workspace__version-bar">
          <div class="task-template-workspace__version-field">
            <label for="task-template-version">模板版本</label>
            <a-select
              id="task-template-version"
              aria-label="查看模板版本"
              :value="selectedVersion"
              :options="versionOptions"
              :disabled="working"
              :loading="versionLoading"
              @change="changeVersion"
            />
          </div>
          <span v-if="savedTemplate.publishedVersion" class="task-template-workspace__version-summary">
            最新发布 V{{ savedTemplate.publishedVersion }}
          </span>
          <div
            v-if="selectedVersion !== 'draft' || (editable && savedTemplate.primaryVersion)"
            class="task-template-workspace__version-policy"
          >
            <template v-if="selectedVersion !== 'draft'">
              <a-tag v-if="savedTemplate.primaryVersion === selectedVersion" color="purple">主版本</a-tag>
              <a-button v-else-if="editable" :disabled="working || refreshRequired" @click="setPrimary">
                设为主版本
              </a-button>
              <span class="task-template-workspace__version-note">已发布内容只读；主版本只影响新建时的默认选择。</span>
            </template>
            <a-checkbox v-else v-model:checked="setPublishedAsPrimary" :disabled="working">发布时设为主版本</a-checkbox>
          </div>
        </div>
      </header>
      <a-alert v-if="versionsError" type="warning" :message="versionsError" show-icon>
        <template #action>
          <a-button @click="savedTemplate && loadVersions(savedTemplate.id)">重试版本列表</a-button>
        </template>
      </a-alert>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-form v-if="basicOpen" class="task-template-workspace__basics" layout="vertical">
        <a-form-item label="模板名称" required>
          <a-input
            v-model:value="editing.name"
            :disabled="locked"
            :maxlength="160"
            aria-label="模板名称"
            placeholder="例如：标准施工项目模板"
          />
        </a-form-item>
        <a-form-item label="模板说明">
          <a-textarea
            v-model:value="editing.description"
            :disabled="locked"
            :auto-size="{ minRows: 1, maxRows: 3 }"
            :maxlength="2000"
            aria-label="模板说明"
            placeholder="选填，简要说明模板用途或适用场景"
          />
        </a-form-item>
      </a-form>
      <a-tabs v-model:active-key="activeTab" class="task-template-workspace__tabs" :animated="false">
        <template #rightExtra>
          <a-space v-if="activeTab === 'arrangement' && nodeEditor" align="center">
            <a-button @click="nodeEditor.openGraph()">图上编排</a-button>
            <a-dropdown v-if="nodeEditor.hasExpandableNodes" :trigger="['click']">
              <a-button>表格操作</a-button>
              <template #overlay>
                <a-menu>
                  <a-menu-item :disabled="!nodeEditor.hasExpandableNodes" @click="nodeEditor.expandAll()">
                    展开全部
                  </a-menu-item>
                  <a-menu-item :disabled="!nodeEditor.hasExpandedNodes" @click="nodeEditor.collapseAll()">
                    收起全部
                  </a-menu-item>
                  <a-menu-item :disabled="locked" @click="nodeEditor.orderSteps()">整理显示</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </a-space>
        </template>
        <a-tab-pane key="arrangement" tab="任务编排">
          <TaskNodeEditor
            ref="nodeEditor"
            v-model="editing.nodes"
            template-editing
            inline-configuration
            external-actions
            :members="members"
            :root="editing.task || undefined"
            :readonly="locked"
            @update:root="editing.task = $event"
          />
        </a-tab-pane>
        <a-tab-pane key="business" tab="业务关联">
          <a-form v-if="editing.task" layout="vertical">
            <TaskNodeFields
              v-model="editing.task"
              section="business"
              :nodes="allNodes"
              :hierarchy-root-id="editing.task.id"
              :members="members"
              template-editing
              :readonly="locked"
              is-root
            />
          </a-form>
          <a-alert v-else type="info" message="此历史模板按节点配置业务数据，请在任务编排中打开对应节点的配置。" />
        </a-tab-pane>
        <a-tab-pane key="instances" tab="任务实例">
          <TaskTemplateInstances
            v-if="editing.id"
            ref="instanceList"
            :template-id="editing.id"
            :published-version="savedTemplate?.publishedVersion"
            :version-summaries="versions"
            @open="detailId = $event"
          />
          <a-empty v-else description="模板保存并发布后，由它创建的任务会显示在这里。" />
        </a-tab-pane>
      </a-tabs>
    </div>
  </section>
</template>
<style scoped>
.task-template-workspace {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.task-template-workspace__header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--spacing-sm) var(--spacing-md);
  padding-inline: var(--spacing-md);
  flex-shrink: 0;
}
.task-template-workspace__back {
  width: var(--control-height);
  padding: 0;
}
.task-template-workspace__identity {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  min-width: 0;
}
.task-template-workspace__status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  max-width: 100%;
}
.task-template-workspace__header :deep(.ant-tag) {
  margin-inline-end: 0;
}
.task-template-workspace__actions {
  justify-self: end;
  justify-content: flex-end;
}
.task-template-workspace__version-bar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm) var(--spacing-lg);
  min-width: 0;
}
.task-template-workspace__version-field {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
  max-width: 100%;
}
.task-template-workspace__version-field label {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.task-template-workspace__version-field :deep(.ant-select) {
  width: calc(var(--control-height) * 7);
  min-width: 0;
}
.task-template-workspace__version-summary,
.task-template-workspace__version-note {
  color: var(--text-secondary);
  font-size: 12px;
}
.task-template-workspace__version-summary {
  white-space: nowrap;
}
.task-template-workspace__version-policy {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  min-width: 0;
  padding-inline-start: var(--spacing-lg);
  border-inline-start: 1px solid var(--border);
}
.task-template-workspace__identity h3 {
  margin: 0;
  min-width: 0;
  max-width: 100%;
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-template-workspace__basics {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(240px, 2fr);
  align-items: start;
  gap: var(--spacing-xl);
  width: 100%;
  padding: var(--spacing-md);
  background: var(--bg-container);
  border-radius: var(--border-radius-lg);
}
.task-template-workspace__basics :deep(.ant-form-item) {
  min-width: 0;
  margin-bottom: 0;
}
.task-template-workspace__tabs {
  display: flex;
  flex: 1;
  min-height: 0;
  padding: 0 var(--spacing-md);
  background: var(--bg-container);
  border-radius: var(--border-radius-lg);
}
.task-template-workspace__tabs :deep(.ant-tabs-content-holder),
.task-template-workspace__tabs :deep(.ant-tabs-content) {
  min-height: 0;
  height: 100%;
}
.task-template-workspace__tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
  overflow: auto;
  padding-bottom: var(--spacing-md);
}
@media (max-width: 900px) {
  .task-template-workspace__header {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .task-template-workspace__actions {
    grid-column: 1 / -1;
    grid-row: 3;
    justify-self: start;
    justify-content: flex-start;
  }
  .task-template-workspace__basics {
    grid-template-columns: 1fr;
    gap: var(--spacing-md);
    max-height: 35vh;
    overflow: auto;
  }
}
@media (max-width: 600px) {
  .task-template-workspace__header {
    padding-inline: 0;
  }
  .task-template-workspace__version-policy {
    width: 100%;
    padding-inline-start: 0;
    border-inline-start: 0;
  }
}
</style>
