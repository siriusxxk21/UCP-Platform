<script setup lang="ts">
/** 网盘专用存储源选择；连接参数沿用文件底座，切换不影响其他模块。 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { FILE_STORAGE } from '@/api/infra/file-config'
import { getDriveStorageSetting, updateDriveStorageSetting } from '@/api/drive/storage'
import type { DriveStorageOption, DriveStorageSetting } from '@/api/drive/storage'
import FileConfigModal from '@/views/infra/fileConfig/components/FileConfigModal.vue'
import { hasPermission } from '@/utils/access'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const saving = ref(false)
const setting = ref<DriveStorageSetting | null>(null)
const selected = ref(0)
const configModalOpen = ref(false)
const editingConfig = ref<DriveStorageOption | null>(null)

const canSwitch = computed(() => hasPermission('drive:storage:update'))
const canCreateConfig = computed(() => hasPermission('infra:file-config:create'))
const canEditConfig = computed(
  () => hasPermission('infra:file-config:query') && hasPermission('infra:file-config:update')
)
const changed = computed(() => selected.value !== (setting.value?.selectedConfigId ?? 0))

function handleOpen(open: boolean) {
  if (open) void load()
}
watch(() => props.open, handleOpen)

async function load() {
  loading.value = true
  try {
    setting.value = await getDriveStorageSetting()
    selected.value = setting.value.selectedConfigId ?? 0
  } finally {
    loading.value = false
  }
}

function storageLabel(option: DriveStorageOption) {
  return option.storage === FILE_STORAGE.LOCAL ? '本地目录' : 'S3 兼容（MinIO / 阿里云 OSS）'
}

function openCreate() {
  editingConfig.value = null
  configModalOpen.value = true
}

function openEdit(option: DriveStorageOption) {
  editingConfig.value = option
  configModalOpen.value = true
}

async function handleConfigSaved() {
  message.success(editingConfig.value ? '连接配置已更新' : '连接配置已创建')
  editingConfig.value = null
  await load()
}

async function save() {
  if (!changed.value) return
  saving.value = true
  try {
    await updateDriveStorageSetting(selected.value || null)
    message.success('网盘存储源已更新，新上传的文件将使用此配置')
    await load()
  } finally {
    saving.value = false
  }
}

function close() {
  if (!saving.value) emit('close')
}
</script>

<template>
  <a-drawer :open="open" title="网盘存储配置" width="min(640px, 100vw)" @close="close">
    <a-spin :spinning="loading">
      <a-alert type="info" show-icon message="存储源仅作用于网盘后续上传和复制；已有文件仍从原存储读取。" />
      <div class="drive-storage-drawer__heading">
        <strong>选择存储源</strong>
        <a-button v-if="canCreateConfig" @click="openCreate">新增连接配置</a-button>
      </div>
      <a-radio-group v-model:value="selected" class="drive-storage-drawer__options" :disabled="!canSwitch">
        <div class="drive-storage-drawer__option">
          <a-radio :value="0">跟随平台主配置</a-radio>
          <span v-if="setting?.selectedConfigId === null" class="drive-storage-drawer__current">当前</span>
        </div>
        <div v-for="option in setting?.options || []" :key="option.id" class="drive-storage-drawer__option">
          <div>
            <a-radio :value="option.id">
              {{ option.name }}
            </a-radio>
            <div class="drive-storage-drawer__meta">
              {{ storageLabel(option) }}
              <span v-if="option.master">· 平台主配置</span>
            </div>
          </div>
          <a-button v-if="canEditConfig" type="link" @click="openEdit(option)">修改配置</a-button>
        </div>
      </a-radio-group>
      <a-empty v-if="!loading && !setting?.options.length" description="暂无本地目录或 S3 存储配置" />
      <p class="drive-storage-drawer__hint">
        连接参数由平台文件底座管理。已存文件所在的本地目录、对象存储节点和存储桶不可原地更改；如需迁移位置，请新增连接后切换，并保留旧连接供历史文件读取。
      </p>
    </a-spin>
    <template #footer>
      <a-space>
        <a-button @click="close">关闭</a-button>
        <a-button v-if="canSwitch" type="primary" :disabled="!changed" :loading="saving" @click="save">
          保存选择
        </a-button>
      </a-space>
    </template>
    <FileConfigModal
      v-model:open="configModalOpen"
      :record="editingConfig"
      :allowed-storages="[FILE_STORAGE.LOCAL, FILE_STORAGE.S3]"
      @success="handleConfigSaved"
    />
  </a-drawer>
</template>

<style scoped>
.drive-storage-drawer__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 24px 0 12px;
}
.drive-storage-drawer__options {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.drive-storage-drawer__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 12px;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}
.drive-storage-drawer__meta {
  margin: 4px 0 0 24px;
  color: var(--text-color-secondary, #6b7280);
  font-size: 12px;
}
.drive-storage-drawer__current {
  margin-left: 8px;
  color: var(--primary-color, #1677ff);
}
.drive-storage-drawer__hint {
  margin-top: 20px;
  color: var(--text-color-secondary, #6b7280);
  line-height: 1.6;
}
</style>
