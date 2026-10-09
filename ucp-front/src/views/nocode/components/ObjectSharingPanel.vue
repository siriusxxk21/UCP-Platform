<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Drawer, message, Modal } from 'ant-design-vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import '../management-tables.css'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { PublishedDefinition } from '@/types/nocode/application'
import { BusinessAction, RecordScope, type ObjectGrant, type ObjectSharingGrant } from '@/types/nocode/authorization'
import ObjectGrantFields from './ObjectGrantFields.vue'
import { defaultObjectGrant } from '@/nocode/object-sharing'

const props = defineProps<{
  objectId: string
  compact?: boolean
  applicationId?: string
  applicationName?: string
  editorOnly?: boolean
}>()
const emit = defineEmits<{ changed: []; closed: [] }>()
const api = useNocodePlatform().applications
const applicationInputId = useId()
const reasonInput = ref()
const reasonError = ref('')
const editorOpen = ref(false)
const dirty = ref(false)
const loaded = ref(false)
let requestGeneration = 0
let disposed = false
const rows = ref<ObjectSharingGrant[]>([]),
  targets = ref<Array<{ id: string; name: string }>>([])
const definition = ref<PublishedDefinition>(),
  selected = ref<string>(),
  grant = ref<ObjectGrant>()
const revision = ref(0),
  reason = ref(''),
  error = ref(''),
  busy = ref(false)
const applicationName = computed(
  () =>
    props.applicationName ||
    rows.value.find(row => row.applicationId === selected.value)?.applicationName ||
    props.applicationId ||
    selected.value
)
const canRevoke = computed(() => !!rows.value.find(row => row.applicationId === selected.value)?.permission)
function openEditor(id = props.applicationId) {
  selected.value = id
  grant.value = undefined
  revision.value = 0
  reason.value = ''
  reasonError.value = ''
  if (id) choose(id)
  editorOpen.value = true
}
function closeEditor() {
  if (busy.value) return
  confirmDiscard(() => {
    dirty.value = false
    editorOpen.value = false
    emit('closed')
  })
}
function confirmDiscard(action?: () => void) {
  if (!dirty.value) {
    action?.()
    return Promise.resolve(true)
  }
  const generation = requestGeneration
  return new Promise<boolean>(resolve =>
    Modal.confirm({
      title: '放弃未保存的权限修改？',
      content: '已生效的权限不会改变。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => {
        if (disposed || generation !== requestGeneration) {
          resolve(false)
          return
        }
        action?.()
        resolve(true)
      },
      onCancel: () => resolve(false)
    })
  )
}
function changeApplication(id: string) {
  if (id === selected.value) return
  confirmDiscard(() => {
    selected.value = id
    choose(id)
  })
}
function changeReason() {
  reasonError.value = ''
  dirty.value = true
}
function preventUnload(event: BeforeUnloadEvent) {
  if (!editorOpen.value || !dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
window.addEventListener('beforeunload', preventUnload)
onBeforeUnmount(() => {
  disposed = true
  requestGeneration++
  window.removeEventListener('beforeunload', preventUnload)
})
function canLeave() {
  if (!editorOpen.value) return true
  if (busy.value) return false
  return confirmDiscard()
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
function choose(id: string) {
  const previous = rows.value.find(r => r.applicationId === id)
  revision.value = previous?.revision || 0
  grant.value = JSON.parse(
    JSON.stringify(
      previous?.permission ||
        // 与应用首次引用对象时系统自动写入的默认授权相同：同一件事只有一个默认值。
        defaultObjectGrant(
          props.objectId,
          [
            BusinessAction.READ,
            BusinessAction.CREATE,
            BusinessAction.UPDATE,
            BusinessAction.DELETE,
            BusinessAction.IMPORT,
            BusinessAction.EXPORT
          ],
          RecordScope.ALL
        )
    )
  )
  reason.value = ''
  reasonError.value = ''
  dirty.value = false
}
async function load() {
  const generation = ++requestGeneration
  const objectId = props.objectId
  const applicationId = props.applicationId
  const isCurrent = () =>
    !disposed &&
    generation === requestGeneration &&
    objectId === props.objectId &&
    applicationId === props.applicationId
  busy.value = true
  error.value = ''
  loaded.value = false
  definition.value = undefined
  try {
    const [items, apps, object] = await Promise.all([
      api.objectSharing(objectId),
      applicationId ? Promise.resolve([]) : api.sharingTargets(),
      api.sharingDefinition(objectId).catch(() => null)
    ])
    if (!isCurrent()) return
    rows.value = items
    targets.value = apps
    definition.value = object?.definition
    loaded.value = true
    if (!object) error.value = '暂无法读取已发布对象结构；仍可撤销已有授权，恢复对象可用状态后再调整范围。'
    if (selected.value) choose(selected.value)
    return generation
  } catch (e) {
    if (isCurrent()) error.value = errorMessage(e)
  } finally {
    if (isCurrent()) busy.value = false
  }
}
function reloadEditor() {
  if (!busy.value)
    confirmDiscard(() => {
      void load()
    })
}
async function save(revoke = false) {
  if (busy.value) return
  if (!loaded.value || (!revoke && (!definition.value || !grant.value))) return
  if (!selected.value || !reason.value.trim()) {
    reasonError.value = '请填写变更说明，说明本次授权调整的原因'
    reasonInput.value?.focus()
    return
  }
  busy.value = true
  error.value = ''
  const generation = requestGeneration
  const objectId = props.objectId
  const applicationId = selected.value
  const isCurrent = () =>
    !disposed && generation === requestGeneration && objectId === props.objectId && applicationId === selected.value
  try {
    const saved = await api.saveObjectSharing({
      objectId,
      applicationId,
      expectedRevision: revision.value,
      permission: revoke ? null : JSON.parse(JSON.stringify(grant.value)),
      reason: reason.value
    })
    if (!isCurrent()) return
    // 保存响应即为新的授权基线；不要让随后刷新失败将已成功保存误报成失败。
    rows.value = [...rows.value.filter(row => row.applicationId !== saved.applicationId), saved]
    dirty.value = false
    message.success(revoke ? '应用数据权限已撤销' : '应用数据权限已生效')
    editorOpen.value = false
    choose(saved.applicationId)
    emit('changed')
    if (props.editorOnly) emit('closed')
  } catch (e) {
    if (isCurrent()) error.value = errorMessage(e)
  } finally {
    if (isCurrent()) busy.value = false
  }
}
watch(
  () => [props.objectId, props.applicationId],
  async () => {
    selected.value = props.applicationId
    grant.value = undefined
    rows.value = []
    reason.value = ''
    dirty.value = false
    if (props.editorOnly) editorOpen.value = true
    const generation = await load()
    if (!disposed && generation === requestGeneration && props.applicationId && loaded.value)
      openEditor(props.applicationId)
  },
  { immediate: true }
)
</script>
<template>
  <a-space direction="vertical" size="middle" class="object-sharing-panel">
    <a-alert
      v-if="!editorOnly"
      type="info"
      show-icon
      message="这里决定对象允许应用做什么。应用创建者和成员均受此上限约束；修改立即生效，不随发布回退。引用/主从关系仍在对象关系统一维护。"
    />
    <a-alert v-if="error && !editorOpen" type="error" :message="error" show-icon />
    <OsTablePage
      v-if="!editorOnly"
      :title="compact ? undefined : '对象的应用数据权限'"
      class="nocode-embedded-table"
      show-column-settings
      column-settings-key="nocode-object-sharing"
      resizable
      :scroll="{ x: 'max-content', y: compact ? '100%' : undefined }"
      :data-source="rows"
      row-key="applicationId"
      :pagination="false"
      :loading="busy"
      :columns="[
        { title: '应用', key: 'applicationName', dataIndex: 'applicationName', width: 220, ellipsis: true },
        { title: '状态', key: 'state', width: 110 },
        { title: '变更说明', key: 'reason', dataIndex: 'reason', width: 320, ellipsis: true },
        { title: '修订', key: 'revision', dataIndex: 'revision', width: 90 },
        { title: '操作', key: 'action', fixed: 'right', width: 160 }
      ]"
    >
      <template #actions>
        <a-button :loading="busy" @click="load">
          <ReloadOutlined />
          刷新
        </a-button>
        <a-button type="primary" :disabled="busy" @click="openEditor()">
          <PlusOutlined />
          新增应用授权
        </a-button>
      </template>
      <template #empty>
        <a-empty description="暂无应用授权，请点击右上角“新增应用授权”进行配置" />
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'">
          <a-tag :color="record.permission ? 'green' : 'default'">{{ record.permission ? '已授权' : '已撤销' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <span class="nocode-table-actions">
            <a-button type="link" :disabled="busy" @click="openEditor(record.applicationId)">
              <EditOutlined />
              {{ record.permission ? '配置权限' : '重新授权' }}
            </a-button>
          </span>
        </template>
      </template>
    </OsTablePage>
    <component
      :is="applicationId ? Drawer : Modal"
      v-bind="applicationId ? { placement: 'right' } : { wrapClassName: 'os-scroll-modal' }"
      :open="editorOpen"
      :title="(definition?.objectName || '数据对象') + ' · 对象权限'"
      :width="applicationId ? 'min(880px, 100vw)' : 880"
      :mask-closable="false"
      :closable="!busy"
      :keyboard="!busy"
      destroy-on-close
      @cancel="closeEditor"
      @close="closeEditor"
    >
      <div class="object-sharing-editor">
        <div v-if="applicationId" class="object-sharing-context">
          <div>
            <span>当前应用</span>
            <strong>{{ applicationName }}</strong>
          </div>
          <a-tag :color="canRevoke ? 'green' : 'default'">{{ canRevoke ? '已授权' : '未授权' }}</a-tag>
        </div>
        <p class="object-sharing-hint">保存后立即生效，应用创建者和成员均受此范围约束。</p>
        <a-alert v-if="error" type="error" :message="error" show-icon>
          <template #action><a-button size="small" :disabled="busy" @click="reloadEditor">重新加载</a-button></template>
        </a-alert>
        <a-spin v-if="busy && !loaded" />
        <a-form layout="vertical" :disabled="busy">
          <a-form-item v-if="!applicationId" label="授权应用" :html-for="applicationInputId" required>
            <a-select
              :id="applicationInputId"
              :disabled="!!applicationId"
              :value="selected"
              show-search
              option-filter-prop="label"
              placeholder="请选择需要授权的应用"
              :options="targets.map(a => ({ label: a.name, value: a.id }))"
              not-found-content="暂无可选应用，请先在应用中心保存应用草稿"
              @change="(id: unknown) => changeApplication(id as string)"
            />
          </a-form-item>
        </a-form>
        <template v-if="grant">
          <ObjectGrantFields
            v-if="definition"
            v-model="grant"
            :definition="definition"
            :readonly="busy"
            :streamlined="!!applicationId"
            @change="dirty = true"
          />
          <a-form layout="vertical" :disabled="busy">
            <a-form-item
              label="变更说明"
              :html-for="`${applicationInputId}-reason`"
              :validate-status="reasonError ? 'error' : undefined"
              :help="reasonError || undefined"
              required
            >
              <a-textarea
                ref="reasonInput"
                :id="`${applicationInputId}-reason`"
                v-model:value="reason"
                placeholder="填写授权或撤销的原因"
                :maxlength="1000"
                :rows="2"
                show-count
                @change="changeReason"
              />
            </a-form-item>
          </a-form>
        </template>
      </div>
      <template #footer>
        <div class="object-sharing-footer">
          <a-popconfirm
            v-if="canRevoke && loaded"
            :disabled="busy || !reason.trim()"
            title="撤销后，此应用所有成员及创建者都会失去该对象的数据访问权限。继续？"
            ok-text="确认撤销"
            cancel-text="保留授权"
            @confirm="save(true)"
          >
            <a-button danger :disabled="busy" @click="!reason.trim() && save(true)">撤销授权</a-button>
          </a-popconfirm>
          <span v-if="dirty" class="object-sharing-dirty">有未保存修改</span>
          <a-button :disabled="busy" @click="closeEditor">关闭</a-button>
          <a-button type="primary" :loading="busy" :disabled="!loaded || !definition || !grant" @click="save(false)">
            保存权限
          </a-button>
        </div>
      </template>
    </component>
  </a-space>
</template>

<style scoped>
.object-sharing-panel {
  width: 100%;
}

.object-sharing-editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.object-sharing-editor > :last-child {
  margin-bottom: 8px;
}
.object-sharing-context,
.object-sharing-context > div {
  display: flex;
  align-items: center;
  gap: 12px;
}
.object-sharing-context {
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: var(--radius-sm);
  background: var(--bg-page);
}
.object-sharing-context span,
.object-sharing-hint {
  color: var(--text-secondary);
  font-size: 12px;
}
.object-sharing-hint {
  margin: 0;
}
.object-sharing-dirty {
  align-self: center;
  margin-right: auto;
  color: var(--text-secondary);
  font-size: 12px;
}
.object-sharing-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}
</style>
