<script setup lang="ts">
import type { FormInstance } from 'ant-design-vue'
import { computed, reactive, ref, watch } from 'vue'
import {
  createFileConfig,
  FILE_STORAGE,
  FILE_STORAGE_OPTIONS,
  getFileConfig,
  updateFileConfig
} from '@/api/infra/file-config'
import type { FileClientConfig, FileConfigSaveParams, FileStorage, InfraFileConfig } from '@/api/infra/file-config'

const props = defineProps<{
  open: boolean
  record?: Pick<InfraFileConfig, 'id'> | null
  allowedStorages?: FileStorage[]
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'success'): void
}>()

interface FileConfigForm {
  id?: number
  name: string
  storage?: FileStorage
  remark: string
  config: FileClientConfig
}

const formRef = ref<FormInstance>()
const loading = ref(false)
const submitting = ref(false)
const s3Provider = ref<'MINIO' | 'ALIYUN'>('MINIO')

const formData = reactive<FileConfigForm>(createDefaultForm())

const isEdit = computed(() => Boolean(formData.id))
const isPathStorage = computed(
  () =>
    formData.storage === FILE_STORAGE.LOCAL ||
    formData.storage === FILE_STORAGE.FTP ||
    formData.storage === FILE_STORAGE.SFTP
)
const isRemoteStorage = computed(() => formData.storage === FILE_STORAGE.FTP || formData.storage === FILE_STORAGE.SFTP)
const isFtp = computed(() => formData.storage === FILE_STORAGE.FTP)
const isS3 = computed(() => formData.storage === FILE_STORAGE.S3)
const storageOptions = computed(() =>
  props.allowedStorages
    ? FILE_STORAGE_OPTIONS.filter(item => props.allowedStorages?.includes(item.value))
    : FILE_STORAGE_OPTIONS
)

const requiredRule = { required: true, message: '此项不能为空', trigger: ['blur', 'change'] }
const domainRules = [
  requiredRule,
  { type: 'url' as const, message: '请输入正确的 URL，例如 https://files.example.com', trigger: 'blur' }
]
const optionalDomainRules = [
  { type: 'url' as const, message: '请输入正确的 URL，例如 https://files.example.com', trigger: 'blur' }
]

function createDefaultConfig(): FileClientConfig {
  return {
    domain: '',
    basePath: '',
    host: '',
    port: undefined,
    username: '',
    password: '',
    mode: 'Passive',
    endpoint: '',
    bucket: '',
    accessKey: '',
    accessSecret: '',
    enablePathStyleAccess: false,
    enablePublicAccess: false,
    region: ''
  }
}

function createDefaultForm(): FileConfigForm {
  return {
    id: undefined,
    name: '',
    storage: undefined,
    remark: '',
    config: createDefaultConfig()
  }
}

function resetForm() {
  Object.assign(formData, createDefaultForm())
  formData.config = createDefaultConfig()
  s3Provider.value = 'MINIO'
  formRef.value?.clearValidate()
}

async function loadRecord(record?: Pick<InfraFileConfig, 'id'> | null) {
  resetForm()
  if (!record?.id) return

  loading.value = true
  try {
    const detail = await getFileConfig(record.id)
    Object.assign(formData, {
      id: detail.id,
      name: detail.name,
      storage: detail.storage,
      remark: detail.remark ?? ''
    })
    formData.config = { ...createDefaultConfig(), ...detail.config }
    if (detail.storage === FILE_STORAGE.S3) {
      s3Provider.value = detail.config.endpoint?.includes('aliyuncs.com') ? 'ALIYUN' : 'MINIO'
    }
  } finally {
    loading.value = false
  }
}

function handleOpenChange(open: boolean) {
  if (open) loadRecord(props.record)
}

watch(() => props.open, handleOpenChange)

function handleStorageChange() {
  if (!isEdit.value) formData.config = createDefaultConfig()
  if (props.allowedStorages && isS3.value) formData.config.enablePathStyleAccess = true
  formRef.value?.clearValidate()
}

function handleS3ProviderChange() {
  formData.config.enablePathStyleAccess = s3Provider.value === 'MINIO'
  formRef.value?.clearValidate()
}

function buildConfig(): FileClientConfig {
  const config = formData.config
  switch (formData.storage) {
    case FILE_STORAGE.DB:
      return { domain: config.domain }
    case FILE_STORAGE.LOCAL:
      return { basePath: config.basePath, domain: config.domain }
    case FILE_STORAGE.FTP:
      return {
        basePath: config.basePath,
        domain: config.domain,
        host: config.host,
        port: config.port,
        username: config.username,
        password: config.password,
        mode: config.mode
      }
    case FILE_STORAGE.SFTP:
      return {
        basePath: config.basePath,
        domain: config.domain,
        host: config.host,
        port: config.port,
        username: config.username,
        password: config.password
      }
    case FILE_STORAGE.S3:
      return {
        endpoint: config.endpoint,
        domain: config.domain,
        bucket: config.bucket,
        accessKey: config.accessKey,
        accessSecret: config.accessSecret,
        enablePathStyleAccess: config.enablePathStyleAccess,
        enablePublicAccess: config.enablePublicAccess,
        region: config.region || undefined
      }
    default:
      return {}
  }
}

async function handleSubmit() {
  await formRef.value?.validate()
  const data: FileConfigSaveParams = {
    id: formData.id,
    name: formData.name.trim(),
    storage: formData.storage,
    remark: formData.remark.trim() || undefined,
    config: buildConfig()
  }

  submitting.value = true
  try {
    if (formData.id) await updateFileConfig(data)
    else await createFileConfig(data)
    emit('update:open', false)
    emit('success')
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  if (!submitting.value) emit('update:open', false)
}
</script>

<template>
  <a-modal
    :open="open"
    :title="isEdit ? '编辑文件配置' : '新增文件配置'"
    width="720px"
    :confirm-loading="submitting"
    :mask-closable="!submitting"
    :closable="!submitting"
    ok-text="保存"
    cancel-text="取消"
    @ok="handleSubmit"
    @cancel="handleCancel"
  >
    <a-alert
      v-if="isEdit"
      type="info"
      show-icon
      message="可修改名称、凭据等连接参数。配置已有文件时，本地基础路径、对象存储节点和存储桶不可更改；更换存储位置请新增配置并切换。"
      style="margin-bottom: 16px"
    />
    <a-spin :spinning="loading">
      <a-form ref="formRef" :model="formData" layout="vertical">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="配置名" name="name" :rules="requiredRule">
              <a-input v-model:value="formData.name" placeholder="请输入配置名" allow-clear />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="存储器" name="storage" :rules="requiredRule">
              <a-select
                v-model:value="formData.storage"
                :options="storageOptions"
                :disabled="isEdit"
                placeholder="请选择存储器"
                @change="handleStorageChange"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <template v-if="formData.storage">
          <a-divider orientation="left">连接配置</a-divider>

          <a-form-item v-if="isPathStorage" label="基础路径" :name="['config', 'basePath']" :rules="requiredRule">
            <a-input v-model:value="formData.config.basePath" placeholder="例如 /data/files" allow-clear />
          </a-form-item>

          <a-row v-if="isRemoteStorage" :gutter="16">
            <a-col :span="16">
              <a-form-item label="主机地址" :name="['config', 'host']" :rules="requiredRule">
                <a-input v-model:value="formData.config.host" placeholder="请输入主机地址" allow-clear />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="主机端口" :name="['config', 'port']" :rules="requiredRule">
                <a-input-number v-model:value="formData.config.port" :min="1" :max="65535" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row v-if="isRemoteStorage" :gutter="16">
            <a-col :span="12">
              <a-form-item label="用户名" :name="['config', 'username']" :rules="requiredRule">
                <a-input v-model:value="formData.config.username" placeholder="请输入用户名" allow-clear />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="密码" :name="['config', 'password']" :rules="requiredRule">
                <a-input-password v-model:value="formData.config.password" placeholder="请输入密码" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item v-if="isFtp" label="连接模式" :name="['config', 'mode']" :rules="requiredRule">
            <a-radio-group v-model:value="formData.config.mode" button-style="solid">
              <a-radio-button value="Active">主动模式</a-radio-button>
              <a-radio-button value="Passive">被动模式</a-radio-button>
            </a-radio-group>
          </a-form-item>

          <template v-if="isS3">
            <a-form-item v-if="allowedStorages" label="对象存储服务">
              <a-radio-group v-model:value="s3Provider" button-style="solid" @change="handleS3ProviderChange">
                <a-radio-button value="MINIO">MinIO</a-radio-button>
                <a-radio-button value="ALIYUN">阿里云 OSS</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="节点地址" :name="['config', 'endpoint']" :rules="requiredRule">
              <a-input
                v-model:value="formData.config.endpoint"
                :placeholder="
                  s3Provider === 'MINIO' ? '例如 http://127.0.0.1:9000' : '例如 oss-cn-hangzhou.aliyuncs.com'
                "
                allow-clear
              />
            </a-form-item>
            <a-form-item label="存储桶" :name="['config', 'bucket']" :rules="requiredRule">
              <a-input v-model:value="formData.config.bucket" placeholder="请输入存储桶名称" allow-clear />
            </a-form-item>
            <a-row :gutter="16">
              <a-col :span="12">
                <a-form-item label="访问密钥" :name="['config', 'accessKey']" :rules="requiredRule">
                  <a-input v-model:value="formData.config.accessKey" placeholder="请输入访问密钥" allow-clear />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="Secret 密钥" :name="['config', 'accessSecret']" :rules="requiredRule">
                  <a-input-password v-model:value="formData.config.accessSecret" placeholder="请输入 Secret 密钥" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="区域" :name="['config', 'region']">
              <a-input
                v-model:value="formData.config.region"
                placeholder="AWS 可填写 us-east-1，其他存储通常可留空"
                allow-clear
              />
            </a-form-item>
            <a-row :gutter="16">
              <a-col :span="12">
                <a-form-item label="路径风格" :name="['config', 'enablePathStyleAccess']" :rules="requiredRule">
                  <a-radio-group v-model:value="formData.config.enablePathStyleAccess" button-style="solid">
                    <a-radio-button :value="true">启用</a-radio-button>
                    <a-radio-button :value="false">禁用</a-radio-button>
                  </a-radio-group>
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="公开访问" :name="['config', 'enablePublicAccess']" :rules="requiredRule">
                  <a-radio-group v-model:value="formData.config.enablePublicAccess" button-style="solid">
                    <a-radio-button :value="true">公开</a-radio-button>
                    <a-radio-button :value="false">私有</a-radio-button>
                  </a-radio-group>
                </a-form-item>
              </a-col>
            </a-row>
          </template>

          <a-form-item
            label="自定义域名"
            :name="['config', 'domain']"
            :rules="isS3 ? optionalDomainRules : domainRules"
          >
            <a-input v-model:value="formData.config.domain" placeholder="例如 https://files.example.com" allow-clear />
          </a-form-item>
        </template>

        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="formData.remark" :rows="3" placeholder="请输入备注" :maxlength="500" show-count />
        </a-form-item>
      </a-form>
    </a-spin>
  </a-modal>
</template>
