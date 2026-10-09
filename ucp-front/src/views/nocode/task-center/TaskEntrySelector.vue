<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { v4 as uuid } from 'uuid'
import request from '@/utils/request'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  mergeTaskEntrySelection,
  selectedTaskEntryIds,
  taskEntryIdentity,
  taskViewHasDynamicScope,
  type TaskEntryCandidate
} from '@/nocode/task-entry-selection'
import type { ApplicationRow } from '@/types/nocode/application'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    entries: TaskWorkEntryConfig[]
    isRoot?: boolean
    multiple?: boolean
    applicationId?: string | null
  }>(),
  { multiple: true }
)
const emit = defineEmits<{
  cancel: []
  confirm: [entries: TaskWorkEntryConfig[]]
  catalog: [rows: TaskEntryCandidate[]]
}>()
const runtime = useNocodePlatform().runtime
const entryApi = createTaskEntryApi(request)
const apps = ref<ApplicationRow[]>([]),
  catalog = ref<TaskEntryCandidate[]>([])
const selected = ref<string[]>([]),
  applicationId = ref<string>(),
  search = ref('')
const catalogLoading = ref(false),
  formLoading = ref(false),
  saving = ref(false),
  error = ref(''),
  formError = ref('')
const appsLoaded = ref(false),
  loadedForms = ref<string[]>([])
const loading = computed(() => catalogLoading.value || formLoading.value)
let generation = 0,
  formGeneration = 0
const columns = [
  { title: '业务视图', key: 'name', dataIndex: 'name', width: 230, align: 'left' as const },
  { title: '办理表单', key: 'formName', dataIndex: 'formName', width: 190, align: 'left' as const },
  { title: '数据对象', key: 'objectName', dataIndex: 'objectName', width: 210, align: 'left' as const },
  { title: '所属应用', key: 'applicationName', dataIndex: 'applicationName', width: 210, align: 'left' as const }
]
const rows = computed(() => {
  const existing = new Set(selectedTaskEntryIds(props.entries))
  // 旧入口已绑定的表单沿用原授权路径，不再诱导用户重复添加一份直接表单绑定。
  const result = catalog.value.filter(
    candidate =>
      candidate.source !== 'FORM' ||
      existing.has(candidate.id) ||
      !catalog.value.some(
        legacy =>
          legacy.source === 'ENTRY' &&
          legacy.applicationId === candidate.applicationId &&
          legacy.resolvedFormId === candidate.binding.formId
      )
  )
  const seen = new Set(result.map(c => c.id))
  for (const entry of props.entries) {
    const id = taskEntryIdentity(entry.binding)
    if (!id || seen.has(id) || entry.dataMode === 'SOURCE_SHARED' || !entry.binding) continue
    const app = apps.value.find(a => a.id === entry.binding!.applicationId)
    const checked = loadedForms.value.includes(entry.binding.applicationId) || (appsLoaded.value && !app)
    result.push({
      id,
      name: entry.name,
      applicationId: entry.binding.applicationId,
      applicationName: app?.name || '原应用',
      source: entry.binding.viewId ? 'VIEW' : entry.binding.entryId ? 'ENTRY' : 'FORM',
      binding: entry.binding,
      available: false,
      pendingVerification: !checked,
      status: checked ? '当前无权限或已下架；保留原配置' : '已选办理项；选择所属应用可核对'
    })
    seen.add(id)
  }
  // 选择只改变勾选状态，保留表单原有顺序，避免点击后行位置跳动。
  return result
})
const applicationChoices = computed(() => {
  const choices = new Map(apps.value.map(app => [app.id, { value: app.id, label: app.name }]))
  for (const candidate of rows.value)
    if (!choices.has(candidate.applicationId))
      choices.set(candidate.applicationId, { value: candidate.applicationId, label: candidate.applicationName })
  if (props.applicationId && !choices.has(props.applicationId))
    choices.set(props.applicationId, { value: props.applicationId, label: '当前应用' })
  return [...choices.values()]
})
const filtered = computed(() =>
  rows.value.filter(
    row =>
      (applicationId.value ? row.applicationId === applicationId.value : selected.value.includes(row.id)) &&
      `${row.name} ${row.formName || ''} ${row.objectName || ''} ${row.applicationName}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase())
  )
)
const protectedCount = computed(
  () => props.entries.filter(e => e.dataMode === 'SOURCE_SHARED' || !taskEntryIdentity(e.binding)).length
)
const rowSelection = computed(() => ({
  type: props.multiple ? 'checkbox' : 'radio',
  preserveSelectedRowKeys: true,
  getCheckboxProps: (row: TaskEntryCandidate) => ({ disabled: !row.available })
}))
function changeSelection(keys: (string | number)[]) {
  selected.value = props.multiple ? keys.map(String) : keys.slice(-1).map(String)
}
function reportCatalog() {
  emit('catalog', rows.value)
}
async function load() {
  const token = ++generation
  catalogLoading.value = true
  error.value = ''
  const legacy = [
    ...new Map(
      props.entries
        .filter(entry => entry.binding?.entryId && entry.dataMode !== 'SOURCE_SHARED')
        .map(entry => [taskEntryIdentity(entry.binding), entry])
    ).values()
  ]
  const [results, legacyResults] = await Promise.all([
    Promise.allSettled([runtime.mine()]),
    Promise.allSettled(
      legacy.map(entry =>
        entryApi.context({ applicationId: entry.binding!.applicationId, entryId: entry.binding!.entryId! })
      )
    )
  ])
  if (token !== generation || !props.open) return
  const failures: string[] = []
  if (results[0].status === 'fulfilled') {
    apps.value = results[0].value
    appsLoaded.value = true
  } else failures.push('应用候选加载失败')
  // 历史入口只核对原授权，不把它替换成视图或直接表单引用。
  const retained: TaskEntryCandidate[] = legacy.map((entry, index) => {
    const result = legacyResults[index]
    const context = result?.status === 'fulfilled' ? result.value : undefined
    const form = context?.resources.find(resource => resource.id === context.config.formId && resource.kind === 'FORM')
    const binding = entry.binding!
    const changed = !!(context && binding.formId && context.config.formId !== binding.formId)
    return {
      id: taskEntryIdentity(binding)!,
      name: !changed && form ? form.name : entry.name,
      applicationId: binding.applicationId,
      applicationName:
        context?.entry.applicationName || apps.value.find(app => app.id === binding.applicationId)?.name || '原应用',
      source: 'ENTRY',
      binding,
      resolvedFormId: binding.formId || context?.config.formId || undefined,
      objectName: changed ? undefined : context?.model.object.objectName,
      available: !!form && !changed,
      status: changed
        ? '原配置对应的表单已变更；原引用已保留，请核对后重新选择'
        : form
          ? `已配置：${entry.name}`
          : '原配置已保留；暂无法核对业务表单'
    }
  })
  catalog.value = [...catalog.value.filter(candidate => candidate.source !== 'ENTRY'), ...retained]
  if (retained.some(candidate => !candidate.available)) failures.push('部分已选表单暂无法核对')
  error.value = failures.length ? `${failures.join('；')}，已选配置保持不变，可重试。` : ''
  catalogLoading.value = false
  reportCatalog()
}
async function loadForms(id: string | undefined, retry = false) {
  const token = ++formGeneration
  formError.value = ''
  if (!props.open || !id || (!retry && loadedForms.value.includes(id))) {
    formLoading.value = false
    return
  }
  formLoading.value = true
  try {
    // 按应用加载，避免打开选择器就扫描所有应用和数据对象。
    const app = await runtime.application(id)
    const applicationName = app.application?.name || apps.value.find(a => a.id === id)?.name || '应用表单'
    const forms = app.definition.resources.filter(r => r.kind === 'FORM' && r.config.objectId)
    const views = app.definition.resources.filter(r => r.kind === 'VIEW')
    const permitted = new Map<string, string>()
    const readOnlyObjects = new Set<string>()
    let incomplete = false
    const objects = [...new Set(views.filter(v => v.config.objectId).map(v => String(v.config.objectId)))]
    for (let i = 0; i < objects.length; i += 4) {
      const batch = objects.slice(i, i + 4)
      const models = await Promise.allSettled(batch.map(object => runtime.model(id, object)))
      if (token !== formGeneration || !props.open) return
      models.forEach((model, index) => {
        if (model.status === 'fulfilled') {
          permitted.set(batch[index]!, model.value.object.objectName)
          if (
            !model.value.writable ||
            !model.value.permissions.actions.some(action => action === 'CREATE' || action === 'UPDATE')
          )
            readOnlyObjects.add(batch[index]!)
        } else incomplete = true
      })
    }
    if (token !== formGeneration || !props.open) return
    const additions = views.map(view => {
      const form = forms.find(f => f.id === view.config.formId && f.config.objectId === view.config.objectId)
      const existing = props.entries.find(
        entry => entry.binding?.applicationId === id && entry.binding?.viewId === view.id
      )
      const changed = !!(existing?.binding?.formId && existing.binding.formId !== form?.id)
      const status = changed
        ? '已选视图对应的表单已变更；保留原配置，明确移除后可重新选择'
        : view.config.composition
          ? '复合／聚合视图暂不支持办理，请选择普通主记录视图'
          : taskViewHasDynamicScope(view.config)
            ? '任务办理暂不支持动态身份范围，请使用固定条件视图'
            : !form
              ? '未配置可用表单，请先在应用中为该视图设置表单'
              : Array.isArray(form.config.relatedForms) && form.config.relatedForms.length > 0
                ? '视图办理暂不支持跨对象联合录入表单，请把关联对象配置为独立办理项'
                : readOnlyObjects.has(String(view.config.objectId))
                  ? '当前仅有查看权限，不可作为办理入口'
                  : !permitted.has(String(view.config.objectId))
                    ? '当前无权访问数据对象，暂不可选择'
                    : undefined
      const binding =
        changed && existing?.binding
          ? existing.binding
          : { applicationId: id, viewId: view.id, formId: form?.id || null, entryId: null }
      return {
        id: taskEntryIdentity(binding)!,
        name: view.name,
        applicationId: id,
        applicationName,
        source: 'VIEW' as const,
        formName: changed ? '原表单（已变更）' : form?.name,
        objectName: permitted.get(String(view.config.objectId)),
        binding,
        available: !status,
        status
      }
    })
    // 无视图的存量表单只作为已选项核对，不能在新建列表里继续添加。
    const legacyForms = props.entries
      .filter(e => e.binding?.applicationId === id && !e.binding.viewId && !e.binding.entryId)
      .map(entry => {
        const form = forms.find(f => f.id === entry.binding?.formId)
        return {
          id: taskEntryIdentity(entry.binding)!,
          name: entry.name,
          applicationId: id,
          applicationName,
          source: 'FORM' as const,
          formName: form?.name,
          binding: entry.binding!,
          available: false,
          status: '历史表单关联；原数据与授权保留，可在卡片中配置或明确移除'
        }
      })
    catalog.value = [
      ...catalog.value.filter(c => c.source === 'ENTRY' || c.applicationId !== id),
      ...additions,
      ...legacyForms
    ]
    if (!incomplete) loadedForms.value = [...new Set([...loadedForms.value, id])]
    else formError.value = '部分表单暂无法核对授权，请重试；未加载的历史项继续保留。'
    reportCatalog()
  } catch (e) {
    if (token === formGeneration) formError.value = `表单候选加载失败：${errorMessage(e)}。原配置仍保留，可重试。`
  } finally {
    if (token === formGeneration) formLoading.value = false
  }
}
watch(
  () => props.open,
  open => {
    if (!open) {
      generation++
      formGeneration++
      return
    }
    saving.value = false
    formLoading.value = false
    formError.value = ''
    selected.value = selectedTaskEntryIds(props.entries)
    search.value = ''
    applicationId.value = props.applicationId || undefined
    catalog.value = []
    loadedForms.value = []
    appsLoaded.value = false
    void load()
    // 固定应用重新打开时 watch 的值可能不变，仍需重新核对当前发布表单。
    void loadForms(applicationId.value, true)
  },
  { immediate: true }
)
watch(applicationId, id => {
  void loadForms(id)
})
watch(
  () => props.applicationId,
  id => {
    if (props.open) applicationId.value = id || undefined
  }
)
onBeforeUnmount(() => {
  generation++
  formGeneration++
})
async function refresh() {
  await Promise.all([load(), loadForms(applicationId.value, true)])
}
async function confirm() {
  if (loading.value || saving.value) return
  const token = generation
  saving.value = true
  error.value = ''
  try {
    if (token !== generation || !props.open) return
    const existing = new Set(selectedTaskEntryIds(props.entries))
    if (
      selected.value.some(
        id => !existing.has(id) && !catalog.value.some(candidate => candidate.id === id && candidate.available)
      )
    )
      throw new Error('部分新选表单暂不可用，请刷新核对后重新选择；原配置仍保留。')
    if (props.multiple) {
      emit('confirm', mergeTaskEntrySelection(props.entries, catalog.value, selected.value, !!props.isRoot, uuid))
    } else {
      const id = selected.value[0]
      const original = props.entries.find(entry => taskEntryIdentity(entry.binding) === id)
      const candidate = catalog.value.find(item => item.id === id)
      if (
        props.applicationId !== undefined &&
        (original?.binding?.applicationId || candidate?.applicationId) !== props.applicationId
      )
        throw new Error('请选择当前应用的业务表单；原配置仍保留。')
      if (original) emit('confirm', [original])
      else {
        if (!id || !candidate?.available) throw new Error('请选择当前可用的业务表单；原配置仍保留。')
        emit('confirm', mergeTaskEntrySelection([], catalog.value, [id], !!props.isRoot, uuid))
      }
    }
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) saving.value = false
  }
}
</script>
<template>
  <OsModalForm
    :open="open"
    title="选择业务视图"
    :width="1040"
    display-mode="drawer"
    :allow-switch-display="false"
    :wrap-form="false"
    @cancel="emit('cancel')"
  >
    <template #formItems>
      <div class="task-entry-selector">
        <a-alert
          type="info"
          show-icon
          :message="
            multiple
              ? '按应用选择业务视图，可跨应用多选。每个视图使用其已配置的表单；确认后可按需设置工时与办理要求。'
              : '选择业务视图及其对应表单。仅替换当前关联，不会改变其他办理项。'
          "
        />
        <a-alert v-if="error" type="warning" :message="error" />
        <a-space wrap>
          <a-select
            v-model:value="applicationId"
            show-search
            :allow-clear="props.applicationId === undefined"
            :disabled="props.applicationId !== undefined"
            option-filter-prop="label"
            placeholder="选择应用"
            aria-label="选择应用"
            :options="applicationChoices"
            class="task-entry-selector-control"
          />
          <a-input
            v-model:value="search"
            class="task-entry-selector-control"
            placeholder="搜索视图、表单或数据对象"
            allow-clear
            aria-label="搜索视图、表单或数据对象"
          />
          <a-button size="small" :loading="loading" @click="refresh">刷新视图</a-button>
        </a-space>
        <a-typography-text v-if="!applicationId" type="secondary">
          {{
            multiple ? '切换应用会保留已选视图；清空应用可查看所有已选项。' : '确认后替换当前关联，取消不会改变原配置。'
          }}
        </a-typography-text>
        <a-alert v-if="formError" type="warning" :message="formError">
          <template #action>
            <a-button size="small" @click="loadForms(applicationId, true)">重试视图</a-button>
          </template>
        </a-alert>
        <a-empty v-if="!applicationId && !filtered.length && !loading" description="请先选择应用，再勾选业务视图" />
        <OsTablePage
          v-else
          :columns="columns"
          :data-source="filtered"
          :loading="loading"
          row-key="id"
          :pagination="{ pageSize: 10, showSizeChanger: false }"
          :row-selection="rowSelection"
          :selected-row-keys="selected"
          :show-index="false"
          :show-batch-bar="false"
          :scroll="{ x: 'max-content' }"
          @selection-change="changeSelection"
        >
          <template #empty>
            <a-empty :description="search ? '没有匹配的业务视图' : '该应用暂无视图，请先在应用中配置视图及表单'" />
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <strong>{{ record.name }}</strong>
              <div v-if="record.status" class="candidate-status">{{ record.status }}</div>
            </template>
            <template v-else>{{ record[column.key] || '—' }}</template>
          </template>
        </OsTablePage>
      </div>
    </template>
    <template #footer>
      <div class="selector-footer">
        <span>
          已选 {{ selected.length }} 项
          <span v-if="protectedCount">，另保留 {{ protectedCount }} 项指定配置</span>
        </span>
        <a-space>
          <a-button @click="emit('cancel')">取消</a-button>
          <a-button type="primary" :disabled="loading" :loading="saving" @click="confirm">确认选择</a-button>
        </a-space>
      </div>
    </template>
  </OsModalForm>
</template>
<style scoped>
.task-entry-selector {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--spacing-lg);
}
.task-entry-selector-control {
  width: calc(var(--spacing-lg) * 15);
  max-width: 100%;
}
.selector-footer {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-lg);
}
.candidate-status {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
</style>
