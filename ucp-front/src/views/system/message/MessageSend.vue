<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message, Empty } from 'ant-design-vue'
import { PlusOutlined, SendOutlined } from '@ant-design/icons-vue'

import { listAllSysMsgTemplate, sendMessage, supportTargetTypies } from '@/api/message/message'
import UserSelector from '@/components/UserSelector/index.vue'
import DeptSelector from '@/components/DeptSelector.vue'
import RoleSelector from '@/components/RoleSelector.vue'
import type { SelectedDept } from '@/components/DeptSelector.vue'
import type { SelectedRole } from '@/components/RoleSelector.vue'
import type { User } from '@/types/system/user'
import TiptapEditor from '@/components/TiptapEditor.vue'

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

interface MsgDataItem {
  key: string
  value: string
}

/** 目标类型由后端 MsgTargetType(code + name) 定义 */
interface TargetTypeOption {
  code: string
  name: string
}

interface Recipient {
  key: number
  targetType: TargetTypeOption
  targetId: string
  targetName: string
}

const formRef = ref()
const submitting = ref(false)
const userSelectorVisible = ref(false)
const deptSelectorVisible = ref(false)
const deptSelectedIds = ref<Array<string | number>>([])
const roleSelectorVisible = ref(false)
const roleSelectedIds = ref<Array<string | number>>([])
const currentTargetCode = ref<string>('USER')
const recipients = ref<Recipient[]>([])
let recipientKeySeq = 0

/** 从后端加载支持的目标类型 */
const targetTypeOptions = ref<TargetTypeOption[]>([])
const targetTypeMap = ref<Record<string, string>>({})

const typeColorMap: Record<string, string> = {
  USER: 'blue',
  ROLE: 'green',
  DEPT: 'orange',
  ALL: 'purple'
}

function getTypeColor(code: string): string {
  return typeColorMap[code] || 'default'
}

const currentTargetName = computed(() => {
  return targetTypeMap.value[currentTargetCode.value] || currentTargetCode.value
})

const targetColumns = [
  { title: '类型', dataIndex: 'targetType', key: 'targetType', width: 80 },
  { title: '名称', dataIndex: 'targetName', key: 'targetName', align: 'left' },
  { title: '操作', key: 'action', width: 80 }
]

const form = ref({
  title: '',
  msgCode: undefined as string | undefined,
  content: '',
  sourceType: '',
  sourceId: '',
  msgData: [] as MsgDataItem[]
})

const rules = {
  title: [{ required: true, message: '请输入消息标题' }],
  msgCode: [{ required: true, message: '请选择消息模板' }]
}

/** 元数据字段定义 */
interface MetaFieldDef {
  code: string
  name: string
  required: boolean
}

// 模板相关
const templateOptions = ref<{ label: string; value: string }[]>([])
const templateMap = ref<Record<string, { code: string; name: string }>>({})
const templateMetaMap = ref<Record<string, MetaFieldDef[]>>({})
const subscribableTemplateCodes = ref<Set<string>>(new Set())

/** 加载消息模板列表 */
async function loadTemplates() {
  try {
    const list: any[] = await listAllSysMsgTemplate()
    templateOptions.value = list.map((t: any) => ({
      label: `${t.name}`,
      value: t.code
    }))
    templateMap.value = {}
    templateMetaMap.value = {}
    subscribableTemplateCodes.value = new Set()
    list.forEach((t: any) => {
      templateMap.value[t.code] = { code: t.code, name: t.name }
      // 解析 metaData
      if (t.metaData) {
        try {
          const parsed = typeof t.metaData === 'string' ? JSON.parse(t.metaData) : t.metaData
          if (Array.isArray(parsed)) {
            templateMetaMap.value[t.code] = parsed.map((m: any) => ({
              code: m.code || '',
              name: m.name || '',
              required: !!m.required
            }))
          }
        } catch {
          templateMetaMap.value[t.code] = []
        }
      } else {
        templateMetaMap.value[t.code] = []
      }
      if (t.subscribeAble === 1) {
        subscribableTemplateCodes.value.add(t.code)
      }
    })
    // 默认选中第一个模板
    if (list.length > 0 && !form.value.msgCode) {
      form.value.msgCode = list[0].code
      onTemplateChange(list[0].code)
    }
  } catch {
    // 静默失败
  }
}

/** 当前选中的模板是否为可订阅类型 */
const isSubscribable = computed(() => {
  return form.value.msgCode ? subscribableTemplateCodes.value.has(form.value.msgCode) : false
})

/** 加载支持的目标类型 */
async function loadTargetTypes() {
  try {
    const list = await supportTargetTypies()
    targetTypeOptions.value = list
    list.forEach(t => {
      targetTypeMap.value[t.code] = t.name
    })
    if (list.length > 0) {
      currentTargetCode.value = list[0].code
    }
  } catch {
    // 兜底默认值
    const fallback: TargetTypeOption[] = [
      { code: 'USER', name: '用户' },
      { code: 'ROLE', name: '角色' },
      { code: 'DEPT', name: '部门' },
      { code: 'ALL', name: '全员' }
    ]
    targetTypeOptions.value = fallback
    fallback.forEach(t => {
      targetTypeMap.value[t.code] = t.name
    })
  }
}

/** 选择模板后自动回填标题并初始化变量 */
function onTemplateChange(code: string | undefined) {
  if (!code) {
    form.value.msgData = []
    return
  }
  const tpl = templateMap.value[code]
  if (tpl && !form.value.title) {
    form.value.title = tpl.name
  }
  // 根据 metaData 初始化 msgData
  const fields = templateMetaMap.value[code] || []
  form.value.msgData = fields.map(f => ({ key: f.code, value: '' }))
}

// ---- 接收对象 ----
/** 打开接收对象选择器（非 ALL 类型） */
function openTargetSelector() {
  if (currentTargetCode.value === 'DEPT') {
    deptSelectedIds.value = recipients.value.filter(r => r.targetType.code === 'DEPT').map(r => r.targetId)
    deptSelectorVisible.value = true
  } else if (currentTargetCode.value === 'ROLE') {
    roleSelectedIds.value = recipients.value.filter(r => r.targetType.code === 'ROLE').map(r => r.targetId)
    roleSelectorVisible.value = true
  } else if (currentTargetCode.value === 'USER') {
    userSelectorVisible.value = true
  }
}

/** 添加 ALL（全员）目标 */
function addAllTarget() {
  // 如果已有 ALL 则跳过
  const exists = recipients.value.some(r => r.targetType.code === 'ALL')
  if (exists) {
    message.info('全员发送已设置')
    return
  }
  recipients.value.push({
    key: ++recipientKeySeq,
    targetType: { code: 'ALL', name: '全员' },
    targetId: '',
    targetName: '全部用户'
  })
}

/** 选择器确认回调 */
function onSelectorConfirm(users: User[]) {
  const typeCode = currentTargetCode.value
  const typeName = targetTypeMap.value[typeCode] || typeCode
  users.forEach(user => {
    const exists = recipients.value.some(r => r.targetType.code === typeCode && r.targetId === String(user.id))
    if (!exists) {
      recipients.value.push({
        key: ++recipientKeySeq,
        targetType: { code: typeCode, name: typeName },
        targetId: String(user.id),
        targetName: user.nickname || user.username || ''
      })
    }
  })
}

/** 部门选择器确认回调 */
function onDeptSelectorConfirm(depts: SelectedDept[]) {
  const typeCode = 'DEPT'
  const typeName = targetTypeMap.value[typeCode] || '部门'
  recipients.value = recipients.value.filter(r => r.targetType.code !== typeCode)
  depts.forEach(dept => {
    recipients.value.push({
      key: ++recipientKeySeq,
      targetType: { code: typeCode, name: typeName },
      targetId: String(dept.id),
      targetName: dept.name
    })
  })
}

/** 角色选择器确认回调 */
function onRoleSelectorConfirm(roles: SelectedRole[]) {
  const typeCode = 'ROLE'
  const typeName = targetTypeMap.value[typeCode] || '角色'
  recipients.value = recipients.value.filter(r => r.targetType.code !== typeCode)
  roles.forEach(role => {
    recipients.value.push({
      key: ++recipientKeySeq,
      targetType: { code: typeCode, name: typeName },
      targetId: String(role.id),
      targetName: role.name
    })
  })
}

/** 移除接收对象 */
function removeRecipient(key: number) {
  recipients.value = recipients.value.filter(r => r.key !== key)
}

/** 发送消息 */
async function handleSend() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  if (!isSubscribable.value && recipients.value.length === 0) {
    message.warning('请至少选择一个接收对象')
    return
  }

  submitting.value = true
  try {
    // 构建 msgData
    const msgData: Record<string, any> = {}
    form.value.msgData.forEach(item => {
      if (item.key) {
        msgData[item.key] = item.value
      }
    })

    await sendMessage({
      msgCode: form.value.msgCode || undefined,
      title: form.value.title,
      content: form.value.content || undefined,
      msgData: Object.keys(msgData).length > 0 ? msgData : undefined,
      sourceType: form.value.sourceType || undefined,
      sourceId: form.value.sourceId || undefined,
      targets: isSubscribable.value
        ? []
        : recipients.value.map(r => ({
            targetType: r.targetType,
            targetId: r.targetId,
            targetName: r.targetName
          }))
    })
    message.success('消息发送成功')
    handleReset()
  } catch {
    message.error('消息发送失败')
  } finally {
    submitting.value = false
  }
}

/** 重置表单 */
function handleReset() {
  const currentMsgCode = form.value.msgCode
  form.value = {
    title: '',
    msgCode: currentMsgCode,
    content: '',
    sourceType: '',
    sourceId: '',
    msgData: []
  }
  recipients.value = []
  recipientKeySeq = 0
  formRef.value?.resetFields()
}

onMounted(() => {
  loadTemplates()
  loadTargetTypes()
})
</script>

<template>
  <div class="msg-send-page">
    <a-card title="发送消息" :bordered="false" style="height: 100%">
      <template #extra>
        <a-space>
          <a-button @click="handleReset">重置</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSend">
            <template #icon><SendOutlined /></template>
            发送消息
          </a-button>
        </a-space>
      </template>
      <a-form ref="formRef" :model="form" layout="vertical" :rules="rules">
        <a-form-item label="标题" name="title">
          <a-input-group compact>
            <a-form-item-rest>
              <a-select
                style="width: 15%"
                v-model:value="form.msgCode"
                placeholder="请选择消息类型"
                :options="templateOptions"
                allow-clear
                show-search
                :filter-option="
                  (input: string, option: any) => option.label.toLowerCase().includes(input.toLowerCase())
                "
                @change="onTemplateChange"
              />
            </a-form-item-rest>
            <a-input style="width: 85%" v-model:value="form.title" placeholder="请输入消息标题" />
          </a-input-group>
        </a-form-item>

        <!-- <a-row :gutter="24">
          <a-col :span="12">
            <a-form-item label="来源类型" name="sourceType">
              <a-input v-model:value="form.sourceType" placeholder="来源类型（可选）" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="来源ID" name="sourceId">
              <a-input v-model:value="form.sourceId" placeholder="来源ID（可选）" />
            </a-form-item>
          </a-col>
        </a-row> -->

        <a-form-item label="内容" name="content">
          <!-- <a-textarea v-model:value="form.content" :rows="4" placeholder="请输入消息内容" /> -->
          <TiptapEditor v-model:model-value="form.content" placeholder="请输入消息内容" style="height: 300px" />
        </a-form-item>

        <!-- 模板变量（根据 metaData 动态展示） -->
        <!-- <a-form-item label="模板变量" v-if="currentMetaFields.length > 0">
          <div class="msg-data-editor">
            <div v-for="(field, index) in currentMetaFields" :key="field.code" class="msg-data-row">
              <span class="meta-field-label">{{ field.name }}<span v-if="field.required" style="color: #ff4d4f">*</span>：</span>
              <a-input
                v-model:value="form.msgData[index].value"
                :placeholder="'请输入' + field.name"
                style="flex: 1"
              />
            </div>
          </div>
        </a-form-item> -->

        <a-form-item v-if="!isSubscribable" label="接收对象" required>
          <div class="target-select-area">
            <div class="target-type-bar">
              <span class="label">选择方式：</span>
              <a-radio-group v-model:value="currentTargetCode" button-style="solid">
                <a-radio-button v-for="t in targetTypeOptions" :key="t.code" :value="t.code">
                  {{ t.name }}
                </a-radio-button>
              </a-radio-group>
              <template v-if="currentTargetCode !== 'ALL'">
                <a-button type="primary" ghost style="margin-left: 16px" @click="openTargetSelector">
                  <template #icon><PlusOutlined /></template>
                  添加{{ currentTargetName }}
                </a-button>
              </template>
              <template v-else>
                <a-button type="primary" ghost style="margin-left: 16px" @click="addAllTarget">
                  <template #icon><PlusOutlined /></template>
                  设置全员发送
                </a-button>
              </template>
            </div>

            <a-table
              v-if="recipients.length > 0"
              size="small"
              :columns="targetColumns"
              :data-source="recipients"
              :pagination="false"
              row-key="key"
              style="margin-top: 12px"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'targetType'">
                  <a-tag :color="getTypeColor(record.targetType.code)">
                    {{ record.targetType.name }}
                  </a-tag>
                </template>
                <template v-if="column.key === 'action'">
                  <a-button type="link" size="small" danger @click="removeRecipient(record.key)">移除</a-button>
                </template>
              </template>
            </a-table>

            <a-empty v-else description="请选择接收对象" :image="simpleImage" style="margin-top: 16px" />
          </div>
        </a-form-item>
      </a-form>
    </a-card>

    <!-- 选择器弹窗 -->
    <UserSelector
      v-if="userSelectorVisible"
      v-model:visible="userSelectorVisible"
      :multiple="true"
      @confirm="onSelectorConfirm"
    />
    <DeptSelector
      v-model:visible="deptSelectorVisible"
      v-model="deptSelectedIds"
      multiple
      @change="onDeptSelectorConfirm"
    />
    <RoleSelector
      v-model:visible="roleSelectorVisible"
      v-model="roleSelectedIds"
      multiple
      @change="onRoleSelectorConfirm"
    />
  </div>
</template>

<style scoped>
.msg-send-page {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.target-select-area {
  background: #fafafa;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  padding: 16px;
}

.target-type-bar {
  display: flex;
  align-items: center;
}

.target-type-bar .label {
  font-size: 14px;
  color: #666;
  margin-right: 8px;
  white-space: nowrap;
}

.msg-data-editor {
  background: #fafafa;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  padding: 12px 16px;
}

.msg-data-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.msg-data-row:last-child {
  margin-bottom: 0;
}

.meta-field-label {
  width: 120px;
  font-size: 13px;
  color: #333;
  white-space: nowrap;
  flex-shrink: 0;
}
@media (max-width: 767px) {
  .target-type-bar {
    flex-wrap: wrap;
    gap: 8px;
  }
  .target-type-bar :deep(.ant-btn) {
    margin-left: 0 !important;
  }
  .target-select-area {
    padding: 12px;
  }
  .msg-send-page :deep(.ant-card-body) {
    padding: 12px;
  }
  .msg-send-page :deep(.ant-input-group-compact) {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .msg-send-page :deep(.ant-input-group-compact > *) {
    width: 100% !important;
  }
  .msg-send-page :deep(.ant-card-head-wrapper) {
    flex-wrap: wrap;
    gap: 8px;
    padding-block: 12px;
  }
  .msg-send-page :deep(.ant-card-extra) {
    margin-left: 0;
  }
}
</style>
