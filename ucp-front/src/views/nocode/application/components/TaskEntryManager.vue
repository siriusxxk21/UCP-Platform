<script setup lang="ts">
import { computed, ref } from 'vue'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import { PlusOutlined, EditOutlined, SafetyCertificateOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import ObjectGrantFields from '../../components/ObjectGrantFields.vue'
import ApplicationMembers from './ApplicationMembers.vue'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { TaskEntryMode, type TaskEntryConfig } from '@/types/nocode/task-entry'
import { BusinessAction, RecordScope, type ObjectGrant } from '@/types/nocode/authorization'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { useNocodePlatform } from '@/nocode/platform'
import { RelationType } from '@/types/nocode/enums'
import { getRelatedWriteObjectIds } from '@/nocode/related-form'
import type { FormConfig } from '@/types/nocode/application-ui'
import { defaultObjectGrant } from '@/nocode/object-sharing'

const props = defineProps<{
  applicationId: string
  objects: Record<string, PublishedObject>
  readonly?: boolean
  unsaved?: boolean
}>()
const resources = defineModel<ApplicationResource[]>({ required: true })
const emit = defineEmits<{ change: [] }>()
const platform = useNocodePlatform()
const entries = computed(() => resources.value.filter(r => r.kind === ResourceKind.TASK_ENTRY))
const editing = ref<ApplicationResource>(),
  original = ref(''),
  error = ref('')
const config = computed(() => editing.value?.config as unknown as TaskEntryConfig | undefined)
const changed = () => !!editing.value && !props.readonly && JSON.stringify(editing.value) !== original.value
useUnsavedNavigation(changed)
const policyEntry = ref<ApplicationResource>()
const members = ref<InstanceType<typeof ApplicationMembers>>()
const columns = [
  { title: '工作事项', dataIndex: 'name', key: 'name', width: 220 },
  { title: '类别', key: 'category', width: 120 },
  { title: '办理方式', key: 'mode', width: 140 },
  { title: '业务对象', key: 'object', width: 180 },
  { title: '操作', key: 'actions', width: 250, fixed: 'right' as const }
]
const typed = (resource: ApplicationResource) => resource.config as unknown as TaskEntryConfig
const objectChoices = computed(() =>
  Object.values(props.objects).map(o => ({ label: o.definition.objectName, value: o.objectId }))
)
const dependencyIds = computed(
  () => config.value?.limits.filter(g => g.objectId !== config.value?.objectId).map(g => g.objectId) || []
)
const dependencyChoices = computed(() => objectChoices.value.filter(o => o.value !== config.value?.objectId))
const relatedWriteIds = computed(() =>
  getRelatedWriteObjectIds(
    resources.value.find(r => r.id === config.value?.formId)?.config as FormConfig | undefined,
    props.objects
  )
)
function syncFormObjects() {
  chooseDependencies([...new Set([...dependencyIds.value, ...relatedWriteIds.value])])
}
const choices = (kind: string) =>
  resources.value
    .filter(r => r.kind === kind && r.config.objectId === config.value?.objectId)
    .map(r => ({ label: r.name, value: r.id }))
function limit(objectId: string, main: boolean): ObjectGrant {
  return {
    ...defaultObjectGrant(
      objectId,
      main ? [BusinessAction.READ, BusinessAction.CREATE, BusinessAction.UPDATE] : [BusinessAction.READ],
      main ? RecordScope.OWN : RecordScope.ALL
    ),
    actionScopes: {}
  }
}
function chooseObject() {
  if (!config.value) return
  config.value.viewId = null
  config.value.formId = null
  config.value.limits = config.value.objectId ? [limit(config.value.objectId, true)] : []
  changeMode()
}
function changeMode() {
  if (!config.value) return
  if (config.value.mode === TaskEntryMode.FORM) {
    config.value.viewId = null
    const main = config.value.limits.find(g => g.objectId === config.value!.objectId)
    if (main) main.actions = [BusinessAction.READ, BusinessAction.CREATE]
  }
}
function ceiling(grant: ObjectGrant): ObjectGrant {
  const main = grant.objectId === config.value?.objectId
  const related = relatedWriteIds.value.includes(grant.objectId)
  return {
    ...grant,
    scope: RecordScope.ALL,
    actions: main
      ? config.value?.mode === TaskEntryMode.FORM
        ? [BusinessAction.READ, BusinessAction.CREATE]
        : [BusinessAction.READ, BusinessAction.CREATE, BusinessAction.UPDATE, BusinessAction.DELETE]
      : related
        ? [BusinessAction.READ, BusinessAction.CREATE, BusinessAction.UPDATE]
        : [BusinessAction.READ],
    readFields: props.objects[grant.objectId]!.definition.fields.map(f => f.id!),
    writeFields: main || related ? props.objects[grant.objectId]!.definition.fields.map(f => f.id!) : [],
    readDetails: props.objects[grant.objectId]!.definition.details.map(d => d.id!),
    writeDetails: main || related ? props.objects[grant.objectId]!.definition.details.map(d => d.id!) : [],
    readRelations: props.objects[grant.objectId]!.definition.relations.filter(
      r => r.kind === RelationType.MANY_TO_MANY
    ).map(r => r.id!),
    writeRelations: main
      ? props.objects[grant.objectId]!.definition.relations.filter(r => r.kind === RelationType.MANY_TO_MANY).map(
          r => r.id!
        )
      : [],
    actionScopes: {}
  }
}
function chooseDependencies(ids: string[]) {
  if (!config.value) return
  const current = config.value.limits
  config.value.limits = [
    config.value.objectId,
    ...new Set([...ids, ...relatedWriteIds.value].filter(id => id !== config.value!.objectId))
  ].map(id => current.find(g => g.objectId === id) || limit(id, false))
}
function restrictionReason(grant: ObjectGrant) {
  if (grant.objectId !== config.value?.objectId)
    return relatedWriteIds.value.includes(grant.objectId)
      ? '这是一起填写的关联资料，可明确开放查看、新增和修改。解除关联不删除这份独立资料；仍需应用和成员具有相应数据权限。'
      : '这是供选择的引用资料，只开放查看。需要在这里填写或修改它，请先在业务表单中配置关联表单。'
  return config.value?.mode === TaskEntryMode.FORM
    ? '直接填写用于登记新记录，只开放查看和新增。维护已有记录请选择列表办理。'
    : '列表办理支持查看、新增、修改和删除；实际可执行的操作还受应用数据权限和成员权限限制。'
}
function open(resource?: ApplicationResource) {
  error.value = ''
  editing.value = resource
    ? JSON.parse(JSON.stringify(resource))
    : {
        id: uuid(),
        code: 'task_' + uuid().slice(0, 8),
        name: '',
        kind: ResourceKind.TASK_ENTRY,
        config: {
          objectId: '',
          viewId: null,
          formId: null,
          mode: TaskEntryMode.LIST,
          category: '',
          description: '',
          icon: 'FormOutlined',
          sortOrder: 0,
          limits: []
        }
      }
  original.value = JSON.stringify(editing.value)
}
async function close() {
  if (await confirmDiscard(changed())) editing.value = undefined
}
function apply() {
  if (!editing.value || !config.value) return
  if (!editing.value.name.trim() || !config.value.category.trim() || !config.value.objectId) {
    error.value = '请填写事项名称、类别和业务对象'
    return
  }
  if (config.value.mode === TaskEntryMode.LIST && !config.value.viewId) {
    error.value = '列表办理请选择已有数据列表'
    return
  }
  const next: ApplicationResource = JSON.parse(JSON.stringify(editing.value))
  resources.value = [...resources.value.filter(r => r.id !== next.id), next]
  emit('change')
  editing.value = undefined
  message.success('已应用到草稿；保存后可设置入口授权，发布后员工才能使用')
}
function authorize(resource: ApplicationResource) {
  if (props.unsaved) {
    message.info('请先保存应用草稿，再配置入口授权')
    return
  }
  policyEntry.value = resource
}
async function closePolicy() {
  if (!members.value || (await members.value.canClose())) policyEntry.value = undefined
}
function remove(resource: ApplicationResource) {
  resources.value = resources.value.filter(r => r.id !== resource.id)
  emit('change')
}
</script>
<template>
  <a-alert
    type="info"
    show-icon
    message="员工从任务中心办理，无需进入整个应用。先配置入口并保存草稿，再设置人员与开放状态，最后发布应用。"
    style="margin-bottom: 16px"
  />
  <OsTablePage title="任务入口" :columns="columns" :data-source="entries" row-key="id" :pagination="{ pageSize: 10 }">
    <template #actions>
      <a-button type="primary" :disabled="readonly" @click="open()">
        <PlusOutlined />
        新建任务入口
      </a-button>
    </template>
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'name'">
        <strong>{{ record.name }}</strong>
        <div style="color: #98a2b3; font-size: 12px">{{ record.code }}</div>
      </template>
      <template v-else-if="column.key === 'category'">{{ typed(record).category }}</template>
      <template v-else-if="column.key === 'mode'">
        {{ typed(record).mode === TaskEntryMode.FORM ? '直接填写' : '列表办理' }}
      </template>
      <template v-else-if="column.key === 'object'">
        {{ objects[typed(record).objectId]?.definition.objectName }}
      </template>
      <a-space v-else-if="column.key === 'actions'">
        <a-button type="link" :disabled="readonly" @click="open(record)">
          <EditOutlined />
          配置
        </a-button>
        <a-button v-if="platform.hasPermission('nocode:app:manage')" type="link" @click="authorize(record)">
          <SafetyCertificateOutlined />
          人员与开放
        </a-button>
        <a-popconfirm title="从草稿移除此入口？发布后员工将无法继续办理，业务数据保留。" @confirm="remove(record)">
          <a-button type="link" danger :disabled="readonly"><DeleteOutlined /></a-button>
        </a-popconfirm>
      </a-space>
    </template>
  </OsTablePage>
  <a-modal
    :open="!!editing"
    title="配置任务入口"
    :width="1000"
    :mask-closable="false"
    :destroy-on-close="true"
    ok-text="应用到草稿"
    @ok="apply"
    @cancel="close"
  >
    <a-alert v-if="error" :message="error" type="error" show-icon style="margin-bottom: 16px" />
    <a-form v-if="editing && config" layout="vertical">
      <a-row :gutter="20">
        <a-col :span="12">
          <a-form-item label="工作事项名称" required>
            <a-input v-model:value="editing.name" :maxlength="160" placeholder="例如：登记施工进度" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="入口编码" required><a-input v-model:value="editing.code" :maxlength="64" /></a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="业务类别" required>
            <a-auto-complete
              v-model:value="config.category"
              :options="[...new Set(entries.map(e => typed(e).category))].map(value => ({ value }))"
              placeholder="例如：财务、工程、行政"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="办理方式">
            <a-radio-group v-model:value="config.mode" @change="changeMode">
              <a-radio-button :value="TaskEntryMode.LIST">列表办理</a-radio-button>
              <a-radio-button :value="TaskEntryMode.FORM">直接填写</a-radio-button>
            </a-radio-group>
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="办理说明">
        <a-input v-model:value="config.description" :maxlength="240" placeholder="用一句话告诉员工这里可以做什么" />
      </a-form-item>
      <a-row :gutter="20">
        <a-col :span="8">
          <a-form-item label="业务对象" required>
            <a-select v-model:value="config.objectId" :options="objectChoices" @change="chooseObject" />
          </a-form-item>
        </a-col>
        <a-col v-if="config.mode === TaskEntryMode.LIST" :span="8">
          <a-form-item label="数据列表" required>
            <a-select
              v-model:value="config.viewId"
              :options="choices(ResourceKind.VIEW)"
              placeholder="选择已配置列表"
            />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="填写表单">
            <a-select
              v-model:value="config.formId"
              :options="choices(ResourceKind.FORM)"
              allow-clear
              placeholder="新增或修改时必选"
              @change="syncFormObjects"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="引用或关联表单使用的对象">
        <p v-if="relatedWriteIds.length" class="muted">
          关联表单的对象已加入下方，请按业务需要明确开放新增／修改及字段权限；普通引用资料仍只读。
        </p>
        <a-select
          mode="multiple"
          :value="dependencyIds"
          :options="dependencyChoices"
          @change="(ids: unknown) => chooseDependencies(ids as string[])"
        />
      </a-form-item>
      <a-collapse>
        <a-collapse-panel
          v-for="(grant, index) in config.limits"
          :key="grant.objectId"
          :header="(objects[grant.objectId]?.definition.objectName || '对象已移除') + ' · 入口允许范围'"
        >
          <ObjectGrantFields
            v-if="objects[grant.objectId]"
            v-model="config.limits[index]!"
            :definition="objects[grant.objectId]!.definition"
            :ceiling="ceiling(grant)"
            :restriction-reason="restrictionReason(grant)"
            :write-all-allowed="grant.objectId === config.objectId || relatedWriteIds.includes(grant.objectId)"
          />
        </a-collapse-panel>
      </a-collapse>
      <a-form-item label="显示顺序" style="margin-top: 16px">
        <a-input-number v-model:value="config.sortOrder" :min="0" :max="10000" />
      </a-form-item>
    </a-form>
  </a-modal>
  <a-modal
    :open="!!policyEntry"
    :title="'人员与开放 · ' + (policyEntry?.name || '')"
    :width="1100"
    :footer="null"
    :mask-closable="false"
    :destroy-on-close="true"
    @cancel="closePolicy"
  >
    <ApplicationMembers
      v-if="policyEntry"
      ref="members"
      :application-id="applicationId"
      :entry-id="policyEntry.id"
      :limits="typed(policyEntry).limits"
      :objects="objects"
    />
  </a-modal>
</template>
