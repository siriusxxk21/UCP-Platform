<template>
  <a-drawer
    :open="visible"
    :title="record?.id ? '编辑消息模板' : '新建消息模板'"
    placement="right"
    width="50%"
    :footer-style="{ textAlign: 'right' }"
    @close="handleClose"
  >
    <a-form ref="formRef" :model="form" layout="vertical">
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="模板编码" name="code" :rules="[{ required: true, message: '请输入编码' }]">
            <a-input v-model:value="form.code" :disabled="!!record?.id" placeholder="如：notify_task_assign" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="模板名称" name="name" :rules="[{ required: true, message: '请输入名称' }]">
            <a-input v-model:value="form.name" placeholder="如：任务分配通知" />
          </a-form-item>
        </a-col>
      </a-row>
      <!-- <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="优先级" name="priority">
            <a-input-number v-model:value="form.priority" :min="0" :max="999" style="width: 100%" placeholder="越小优先级越高" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="可订阅" name="subscribeAble">
            <a-switch v-model:checked="form.subscribeAble" :checked-value="1" :unchecked-value="0" checked-children="可订阅" un-checked-children="不可订阅" />
          </a-form-item>
        </a-col>
      </a-row> -->
      <a-form-item label="标题模板" name="templateTitle" :rules="[{ required: true, message: '请输入标题模板' }]">
        <a-input v-model:value="form.templateTitle" style="width: 100%" placeholder="标题模板" />
      </a-form-item>
      <a-form-item label="内容模板" name="templateContent">
        <!-- <a-textarea
          v-model:value="form.templateContent"
          :rows="3"
          placeholder="标题模板"
          /> -->
        <TiptapEditor v-model:model-value="form.templateContent" placeholder="标题模板" style="height: 300px;" />
      </a-form-item>
      <a-form-item label="路由模板" name="priority">
        <a-input v-model:value="form.templateUrl" style="width: 100%" placeholder="标题模板" />
      </a-form-item>
    </a-form>

    <a-tabs>
      <a-tab-pane key="meta" tab="消息元数据">
        <div style="margin-bottom:8px">
          <a-button type="primary" @click="addMetaItem">+ 添加字段</a-button>
        </div>
        <a-table size="small" :columns="metaColumns" :data-source="metaFields" :pagination="false" row-key="key">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'code'">
              <a-input v-model:value="record.code" size="small" placeholder="标识（必填）" :status="record.code ? '' : 'error'" style="width:100%" />
            </template>
            <template v-else-if="column.key === 'name'">
              <a-input v-model:value="record.name" size="small" placeholder="名称" style="width:100%" />
            </template>
            <template v-else-if="column.key === 'required'">
              <a-switch v-model:checked="record.required" size="small" />
            </template>
            <template v-else-if="column.key === 'action'">
              <a-button type="link" danger @click="removeMetaItem(record.key)">删除</a-button>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="targets" tab="默认接收对象">
        <a-alert
          v-if="!record?.id"
          message="请先保存消息模板，再编辑默认接收对象"
          type="info"
          show-icon
        />
        <template v-else>
          <div class="target-toolbar">
            <a-radio-group v-model:value="currentTargetType" button-style="solid">
              <a-radio-button value="USER">用户</a-radio-button>
              <a-radio-button value="ROLE">角色</a-radio-button>
            </a-radio-group>
            <a-button type="primary" ghost @click="openTargetSelector">
              添加{{ currentTargetType === 'USER' ? '用户' : '角色' }}
            </a-button>
          </div>
          <a-table
            size="small"
            :columns="targetColumns"
            :data-source="templateTargets"
            :pagination="false"
            row-key="key"
          >
            <template #bodyCell="{ column, record: target }">
              <template v-if="column.key === 'targetType'">
                <a-tag :color="target.targetType === 'USER' ? 'blue' : 'green'">
                  {{ target.targetType === 'USER' ? '用户' : '角色' }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-button type="link" danger @click="removeTarget(target.key)">移除</a-button>
              </template>
            </template>
          </a-table>
          <a-empty v-if="templateTargets.length === 0" description="尚未配置默认接收对象" />
        </template>
      </a-tab-pane>
      <a-tab-pane
        v-for="ch in channels"
        :key="ch.code"
        :tab="ch.name"
        force-render
      >
        <a-card size="small">
            <template #title>
                <div>
                    <span>模板配置</span>
                    <span style="margin-left: 12px;font-size: 12px;font-weight: 500;color: #aaa;">
                        <InfoCircleOutlined/> JS标准模板字符串，可使用消息元数据标识符作为变量，如 ${name}
                    </span>
                </div>
            </template>
            <template #extra>
                <a-switch
                    v-model:checked="channelForms[ch.code].open"
                    :checked-value="1"
                    :unchecked-value="0"
                    checked-children="启用"
                    un-checked-children="停用"
                />
            </template>
            <a-alert
                v-if="ch.description"
                :message="ch.description"
                type="info"
                show-icon
                style="margin-bottom:16px"/>
            
            <a-form :model="channelForms[ch.code]" layout="vertical">
                
                <a-form-item
                v-for="meta in ch.metadataList"
                :key="meta.code"
                :label="meta.name + (meta.description ? `（${meta.description}）` : '')"
                :name="meta.code"
                >
                <a-input
                    v-if="meta.type === 'text'"
                    v-model:value="channelForms[ch.code].msgTemplate[meta.code]"
                    :disabled="channelForms[ch.code].open !== 1"
                    :placeholder="'请输入' + meta.name"
                />
                <a-textarea
                    v-else-if="meta.type === 'textarea'"
                    v-model:value="channelForms[ch.code].msgTemplate[meta.code]"
                    :disabled="channelForms[ch.code].open !== 1"
                    :rows="4"
                    :placeholder="'请输入' + meta.name"
                />
                <TiptapEditor v-else-if="meta.type === 'richText'"
                   v-model:model-value="channelForms[ch.code].msgTemplate[meta.code]"
                   :disabled="channelForms[ch.code].open !== 1"
                   :placeholder="'请输入' + meta.name"
                   style="height: 200px;" />
                <a-input
                  v-else
                  v-model:value="channelForms[ch.code].msgTemplate[meta.code]"
                  :disabled="channelForms[ch.code].open !== 1"
                  :placeholder="'请输入' + meta.name"
                />
                </a-form-item>
            </a-form>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <UserSelector
      v-if="userSelectorVisible"
      v-model:visible="userSelectorVisible"
      :multiple="true"
      @confirm="handleUserConfirm"
    />
    <RoleSelector
      v-model:visible="roleSelectorVisible"
      v-model="selectedRoleIds"
      multiple
      @change="handleRoleConfirm"
    />

    <template #footer>
      <a-space>
        <a-button @click="handleClose">取消</a-button>
        <a-button type="primary" :loading="loading" @click="handleSubmit">保存</a-button>
      </a-space>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, watch, reactive, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { InfoCircleOutlined } from '@ant-design/icons-vue'
import {
  addSysMsgTemplate,
  editSysMsgTemplate,
  getSupportChannels,
  getTemplateTargets,
  saveTemplateTargets,
} from '@/api/message/message'
import type { MsgTemplateTarget } from '@/api/message/message'
import TiptapEditor from '@/components/TiptapEditor.vue'
import UserSelector from '@/components/UserSelector/index.vue'
import RoleSelector from '@/components/RoleSelector.vue'
import type { SelectedRole } from '@/components/RoleSelector.vue'
import type { User } from '@/api/system/user'

const props = defineProps<{ visible: boolean; record: any | null }>()
const emit = defineEmits<{ close: []; success: [] }>()

const loading = ref(false)
const formRef = ref()
const channels = ref<any[]>([])
const channelForms = ref<Record<string, { msgTemplate: Record<string, string>; open: number }>>({})
const metaFields = reactive<{ key: number; code: string; name: string; required: boolean }[]>([])
let metaKeySeq = 0
let targetKeySeq = 0
const currentTargetType = ref<'USER' | 'ROLE'>('USER')
const userSelectorVisible = ref(false)
const roleSelectorVisible = ref(false)
const selectedRoleIds = ref<Array<string | number>>([])
type TemplateTargetRow = MsgTemplateTarget & { key: number }
const templateTargets = ref<TemplateTargetRow[]>([])

const targetColumns = [
  { title: '类型', key: 'targetType', width: 90 },
  { title: '名称', dataIndex: 'targetName', key: 'targetName' },
  { title: '操作', key: 'action', width: 80 },
]

const metaColumns = [
  { title: '标识', dataIndex: 'code', key: 'code' },
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '非空', dataIndex: 'required', key: 'required', width: 100 },
  { title: '操作', key: 'action', width: 60 },
]

function addMetaItem() {
  metaFields.push({ key: ++metaKeySeq, code: '', name: '', required: false })
}

function removeMetaItem(key: number) {
  const idx = metaFields.findIndex(m => m.key === key)
  if (idx >= 0) metaFields.splice(idx, 1)
}

function parseMetaData(val: any) {
  metaFields.splice(0, metaFields.length)
  metaKeySeq = 0
  if (!val) return
  let list: any[] = []
  if (typeof val === 'string') { try { list = JSON.parse(val) } catch { return } }
  else if (Array.isArray(val)) list = val
  else return
  list.forEach(item => {
    metaFields.push({ key: ++metaKeySeq, code: item.code || '', name: item.name || '', required: !!item.required })
  })
}

function serializeMetaData(): string {
  return JSON.stringify(metaFields.map(m => ({ code: m.code, name: m.name, required: m.required })).filter(m => m.code || m.name))
}

async function loadTargets() {
  templateTargets.value = []
  targetKeySeq = 0
  if (!props.record?.id) return
  const targets = await getTemplateTargets(props.record.id)
  templateTargets.value = targets.map(target => ({ ...target, key: ++targetKeySeq }))
}

function openTargetSelector() {
  if (currentTargetType.value === 'USER') {
    userSelectorVisible.value = true
    return
  }
  selectedRoleIds.value = templateTargets.value
    .filter(target => target.targetType === 'ROLE')
    .map(target => target.targetId)
  roleSelectorVisible.value = true
}

function handleUserConfirm(users: User[]) {
  users.forEach(user => addTarget({
    targetType: 'USER',
    targetId: String(user.id),
    targetName: user.nickname || user.username || '',
  }))
}

function handleRoleConfirm(roles: SelectedRole[]) {
  templateTargets.value = templateTargets.value.filter(target => target.targetType !== 'ROLE')
  roles.forEach(role => addTarget({
    targetType: 'ROLE',
    targetId: String(role.id),
    targetName: role.name,
  }))
}

function addTarget(target: MsgTemplateTarget) {
  const exists = templateTargets.value.some(item =>
    item.targetType === target.targetType && item.targetId === target.targetId)
  if (!exists) templateTargets.value.push({ ...target, key: ++targetKeySeq })
}

function removeTarget(key: number) {
  templateTargets.value = templateTargets.value.filter(target => target.key !== key)
}

const form = ref({
  code: '', name: '', priority: 0, subscribeAble: 0, templateTitle: '', templateContent: '', templateUrl: ''
})

/** 根据 channels 数据初始化 channelForms */
function initChannelForms() {
  const forms: Record<string, { msgTemplate: Record<string, string>; open: number }> = {}
  channels.value.forEach(ch => {
    const msgTemplate: Record<string, string> = {}
    if (ch.metadataList) {
      ch.metadataList.forEach((meta: any) => {
        msgTemplate[meta.code] = ''
      })
    }
    forms[ch.code] = { msgTemplate, open: 0 }
  })
  channelForms.value = forms
}

/** 加载支持的通知渠道列表 */
async function loadChannels() {
  try {
    const res: any[] = await getSupportChannels()
    channels.value = res
    initChannelForms()
  } catch {
    // 静默失败，页签列表为空
  }
}

watch(() => props.visible, async val => {
  if (val) {
    await loadChannels()
    await nextTick()
    if (props.record) {
      form.value = {
        code: props.record.code || '',
        name: props.record.name || '',
        priority: props.record.priority ?? 0,
        subscribeAble: props.record.subscribeAble ?? 0,
        templateTitle: props.record.templateTitle || '',
        templateContent: props.record.templateContent || '',
        templateUrl: props.record.templateUrl || '',
      }
      parseMetaData(props.record.metaData)
      await loadTargets()
      // 从 noticeConfig 回填渠道配置
      if (props.record.noticeConfig) {
        let noticeChannelConfig: any[] = []
        try { 
          noticeChannelConfig = JSON.parse(props.record.noticeConfig) } catch { /* ignore */ }
          noticeChannelConfig.forEach((config: any) => {
          const chForm = channelForms.value[config.channel]
          if (chForm) {
            chForm.open = config.open ?? 0
            chForm.msgTemplate = config.msgTemplate ?? {}
          }
        })
      }
    } else {
      form.value = { code: '', name: '', priority: 0, subscribeAble: 0, templateTitle: '', templateContent: '', templateUrl: '' }
      metaFields.splice(0, metaFields.length)
      templateTargets.value = []
    }
  }
})

function validateMetaUnique(): boolean {
  const codes = metaFields.map(m => m.code.trim()).filter(Boolean)
  const names = metaFields.map(m => m.name.trim()).filter(Boolean)
  if (new Set(codes).size !== codes.length) { message.warning('元数据标识不能重复'); return false }
  if (new Set(names).size !== names.length) { message.warning('元数据名称不能重复'); return false }
  return true
}

async function handleSubmit() {
  if (!validateMetaUnique()) return
  loading.value = true
  try {
    const noticeConfig = Object.entries(channelForms.value).map(([chCode, form]) => ({
      channel: chCode,
      msgTemplate: form.msgTemplate,
      open: form.open,
    }))
    const payload = {
      ...form.value,
      metaData: serializeMetaData(),
      noticeConfig: JSON.stringify(noticeConfig),
    }
    if (props.record?.id) {
      await editSysMsgTemplate({ ...payload, id: props.record.id })
      await saveTemplateTargets(props.record.id, templateTargets.value.map(target => ({
        id: target.id,
        targetType: target.targetType,
        targetId: target.targetId,
        targetName: target.targetName,
      })))
      message.success('修改成功')
    } else {
      await addSysMsgTemplate(payload)
      message.success('添加成功')
    }
    emit('success')
    emit('close')
  } catch {
    message.error(props.record?.id ? '修改失败' : '添加失败')
  } finally {
    loading.value = false
  }
}

function handleClose() { emit('close') }
</script>

<style scoped>
.target-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
</style>
