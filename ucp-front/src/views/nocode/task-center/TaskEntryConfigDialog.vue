<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { TaskNodeInput, TaskDataPolicy } from '@/types/nocode/task-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { taskWorkRuleError } from '@/nocode/task-work-rule'
import { taskEntryScope } from '@/nocode/task-entry-scope'
import TaskBindingPicker from './TaskBindingPicker.vue'
import TaskWorkRuleFields from './TaskWorkRuleFields.vue'
import TaskDataScopeFields from './TaskDataScopeFields.vue'

const props = defineProps<{
  entry: TaskWorkEntryConfig | null
  sourceInfo?: { applicationName: string; viewName: string; formName: string }
  readonly?: boolean
  unified?: boolean
  legacyPolicy?: TaskDataPolicy | null
  nodes?: TaskNodeInput[]
  currentId?: string
  rootEntries?: TaskWorkEntryConfig[]
}>()
const emit = defineEmits<{ cancel: []; save: [entry: TaskWorkEntryConfig] }>()
const draft = ref<TaskWorkEntryConfig | null>(null),
  error = ref(''),
  fields = ref<ObjectField[]>([])
const readFields = ref<Array<{ value: string; label: string }>>([]),
  writeFields = ref<Array<{ value: string; label: string }>>([])
const fieldOptions = ref<Record<string, FieldOptions>>({})
let original = ''
let directBinding: TaskWorkEntryConfig['binding'] = null
watch(
  () => props.entry,
  entry => {
    draft.value = entry ? JSON.parse(JSON.stringify(entry)) : null
    original = JSON.stringify(draft.value)
    directBinding = draft.value?.dataMode === 'SOURCE_SHARED' ? null : draft.value?.binding || null
    error.value = ''
    fields.value = []
  },
  { immediate: true }
)
const rule = computed(() => draft.value?.workRule)
const dataScope = computed({
  get: () => (draft.value ? taskEntryScope(draft.value, props.legacyPolicy) : 'GROUP'),
  set: (value: 'GROUP' | 'ALL') => {
    if (draft.value && !props.readonly) draft.value.dataScope = value
  }
})
const sourceNodes = computed(() => {
  const allowed = new Set<string>(),
    nodes = props.nodes || []
  function walk(id?: string | null) {
    if (!id || allowed.has(id)) return
    allowed.add(id)
    const node = nodes.find(n => n.id === id)
    walk(node?.parentId)
    node?.predecessorIds.forEach(walk)
  }
  const current = nodes.find(n => n.id === props.currentId)
  walk(current?.parentId)
  current?.predecessorIds.forEach(walk)
  function inheritedEntries(node: TaskNodeInput, visited = new Set<string>()): TaskWorkEntryConfig[] {
    if (visited.has(node.id)) return []
    visited.add(node.id)
    if (node.entries?.length) return node.entries
    const parent = nodes.find(item => item.id === node.parentId)
    return parent ? inheritedEntries(parent, visited) : props.rootEntries || []
  }
  return nodes
    .filter(n => allowed.has(n.id))
    .map(node => ({ ...node, entries: inheritedEntries(node) }))
    .filter(node => node.entries.length)
})
function changeRelation() {
  if (!draft.value) return
  draft.value.sourceNodeId = null
  draft.value.sourceEntryKey = null
  if (draft.value.dataMode === 'SOURCE_SHARED') {
    directBinding = draft.value.binding
    draft.value.binding = null
    draft.value.writableFieldIds = []
  } else if (directBinding) draft.value.binding = { ...directBinding }
}
function useRoot(key: unknown) {
  const root = props.rootEntries?.find(entry => entry.key === key)
  if (!root || !draft.value) return
  draft.value.key = root.key
  draft.value.binding = root.binding
  draft.value.name = root.name
}
function sourceChanged() {
  if (!draft.value) return
  const source = sourceNodes.value
    .find(n => n.id === draft.value?.sourceNodeId)
    ?.entries?.find(e => e.key === draft.value?.sourceEntryKey)
  draft.value.binding = source?.binding || null
}
function resetSource() {
  if (!draft.value) return
  draft.value.sourceEntryKey = null
  draft.value.binding = null
  draft.value.writableFieldIds = []
}
function cancel() {
  if (!props.readonly && JSON.stringify(draft.value) !== original) {
    Modal.confirm({
      title: '放弃本次配置修改？',
      content: '未保存的修改不会影响当前模板。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => emit('cancel')
    })
  } else emit('cancel')
}
function save() {
  if (!draft.value || props.readonly) return
  error.value = !draft.value.name.trim() ? '请填写办理项名称。' : taskWorkRuleError(draft.value.workRule)
  if (error.value) return
  emit('save', JSON.parse(JSON.stringify(draft.value)))
}
function toggleRule(enabled: boolean) {
  if (draft.value && !props.readonly) draft.value.workRule = enabled ? { mode: 'RECORD_ONCE', minutes: 0 } : null
}
</script>
<template>
  <OsModalForm
    :open="!!entry"
    title="配置业务办理项"
    :width="820"
    :wrap-form="false"
    :allow-switch-display="false"
    @cancel="cancel"
  >
    <template #formItems>
      <a-form v-if="draft" layout="vertical" :disabled="readonly" class="entry-config">
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <a-form-item label="办理项名称" required>
          <a-input v-model:value="draft.name" :maxlength="100" placeholder="如：房间 Wi-Fi 配置" />
        </a-form-item>
        <details v-if="sourceInfo" class="entry-config__source">
          <summary>关联来源</summary>
          <dl>
            <dt>所属应用</dt>
            <dd>{{ sourceInfo.applicationName }}</dd>
            <dt>业务视图</dt>
            <dd>{{ sourceInfo.viewName }}</dd>
            <dt>办理表单</dt>
            <dd>{{ sourceInfo.formName }}</dd>
          </dl>
        </details>
        <TaskBindingPicker
          v-model="draft.binding"
          fields-only
          @rule-fields="fields = $event"
          @field-options="fieldOptions = $event"
          @read-fields="readFields = $event"
          @fields="writeFields = $event"
        />
        <section class="entry-config__section">
          <TaskDataScopeFields v-model="dataScope" label="业务数据范围" :readonly="readonly" />
          <p v-if="!unified && draft.dataMode !== 'INDEPENDENT'" class="entry-config__hint">
            引用上级或前序办理项时，还受来源范围限制，不能扩大来源授权。
          </p>
        </section>
        <section class="entry-config__section">
          <div class="entry-config__heading">
            <strong>标准工时</strong>
            <a-switch
              :checked="!!rule"
              :disabled="readonly"
              checked-children="已设置"
              un-checked-children="不计工时"
              @change="toggleRule(!!$event)"
            />
          </div>
          <TaskWorkRuleFields
            v-if="rule"
            v-model="draft.workRule"
            :readonly="readonly"
            :fields="fields"
            :field-options="fieldOptions"
          />
        </section>
        <section class="entry-config__section">
          <strong>完成要求</strong>
          <a-checkbox v-model:checked="draft.required">完成任务前，此项必须有已保存的业务数据</a-checkbox>
        </section>
        <a-collapse
          v-if="!unified || draft.dataMode !== 'ROOT_SHARED' || draft.readableFieldIds || draft.writableFieldIds"
          ghost
        >
          <a-collapse-panel key="scope" header="数据共享与字段范围">
            <a-form-item label="数据关系">
              <a-select
                v-model:value="draft.dataMode"
                :disabled="draft.key === '__business'"
                @change="changeRelation"
                :options="[
                  { value: 'ROOT_SHARED', label: '沿用上级／向下级共享' },
                  { value: 'INDEPENDENT', label: '独立数据组，下级可沿用' },
                  { value: 'SOURCE_SHARED', label: '引用指定祖先或前序办理项' }
                ]"
              />
            </a-form-item>
            <a-form-item
              v-if="draft.key !== '__business' && draft.dataMode === 'ROOT_SHARED' && rootEntries?.length"
              label="沿用上级已有办理项"
            >
              <a-select
                :value="rootEntries?.some(entry => entry.key === draft?.key) ? draft.key : undefined"
                :options="rootEntries.map(entry => ({ value: entry.key, label: entry.name }))"
                placeholder="选择后沿用同一组数据"
                @change="useRoot"
              />
            </a-form-item>
            <template v-if="draft.dataMode === 'SOURCE_SHARED'">
              <a-form-item label="来源节点">
                <a-select
                  v-model:value="draft.sourceNodeId"
                  :options="sourceNodes.map(n => ({ value: n.id, label: n.title }))"
                  @change="resetSource"
                />
              </a-form-item>
              <a-form-item label="来源办理项">
                <a-select
                  v-model:value="draft.sourceEntryKey"
                  :options="
                    (sourceNodes.find(n => n.id === draft?.sourceNodeId)?.entries || []).map(e => ({
                      value: e.key,
                      label: e.name
                    }))
                  "
                  @change="sourceChanged"
                />
              </a-form-item>
            </template>
            <a-form-item label="可查看字段">
              <a-checkbox
                :checked="draft.readableFieldIds !== null"
                @change="draft.readableFieldIds = $event.target.checked ? [] : null"
              >
                限定字段
              </a-checkbox>
              <a-select
                v-if="draft.readableFieldIds !== null"
                v-model:value="draft.readableFieldIds"
                mode="multiple"
                :options="readFields"
                placeholder="选择可查看字段"
              />
            </a-form-item>
            <a-form-item label="可填写字段">
              <a-checkbox
                :checked="draft.writableFieldIds !== null"
                @change="draft.writableFieldIds = $event.target.checked ? [] : null"
              >
                限定字段
              </a-checkbox>
              <a-select
                v-if="draft.writableFieldIds !== null"
                v-model:value="draft.writableFieldIds"
                mode="multiple"
                :options="writeFields.filter(f => !draft?.readableFieldIds || draft.readableFieldIds.includes(f.value))"
                placeholder="空选表示只读"
              />
            </a-form-item>
            <p class="entry-config__hint">共享不扩大权限，实际可读写范围仍受应用、视图及字段权限限制。</p>
          </a-collapse-panel>
        </a-collapse>
      </a-form>
    </template>
    <template #footer>
      <a-space>
        <a-button @click="cancel">{{ readonly ? '关闭' : '取消' }}</a-button>
        <a-button v-if="!readonly" type="primary" @click="save">保存配置</a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>
<style scoped>
.entry-config {
  display: grid;
  gap: var(--spacing-md);
}
.entry-config__source {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.entry-config__source summary {
  cursor: pointer;
  width: fit-content;
}
.entry-config__source dl {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--spacing-sm) var(--spacing-lg);
  margin: var(--spacing-md) 0 0;
}
.entry-config__source dd {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text-primary);
}
.entry-config__section {
  display: grid;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  border: 1px solid var(--color-border-secondary, #e5e7eb);
  border-radius: var(--border-radius-lg, 8px);
}
.entry-config__heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
}
.entry-config__hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.entry-config :deep(.ant-form-item) {
  margin-bottom: 0;
}
.entry-config :deep(.ant-select),
.entry-config :deep(.ant-input-number),
.entry-config :deep(.ant-picker) {
  width: 100%;
}
</style>
