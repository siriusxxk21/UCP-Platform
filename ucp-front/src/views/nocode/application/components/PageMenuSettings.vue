<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { FolderOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { Modal } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import PlatformMenuFields from '@/components/PlatformMenuFields.vue'
import AppIcon from '@/components/AppIcon.vue'
import type { ApplicationResource } from '@/types/nocode/application'
import type { Menu } from '@/types/system/menu'
import {
  applyPageNavigation,
  pageNavigation,
  pageNavigationSettings,
  pageNavigationUnavailableReason,
  type PageNavigationSettings
} from '@/nocode/application-navigation'
import { useUnsavedNavigation } from '@/nocode/unsaved'

const props = defineProps<{
  open: boolean
  resource?: ApplicationResource
  resources: ApplicationResource[]
  directories: Menu[]
  readOnly: boolean
  directoryError?: string
  directoriesLoading: boolean
  canQueryDirectories: boolean
}>()
const emit = defineEmits<{ close: []; apply: [resources: ApplicationResource[]]; reload: [] }>()
const form = reactive<PageNavigationSettings>({
  resourceName: '',
  showInMenu: false,
  platformParentId: '',
  menuName: '',
  icon: '',
  sort: 10,
  defaultHome: false
})
const error = ref('')
let initial = ''
const unavailable = computed(() => (props.resource ? pageNavigationUnavailableReason(props.resource) : ''))
const legacy = computed(() => {
  const entry = props.resource && pageNavigation(props.resources, props.resource.id)
  return !!entry && entry.config.navigationVersion !== 2
})
const currentHome = computed(() => {
  const entry = props.resources.find(item => item.kind === 'MENU' && item.config.defaultHome === true)
  return props.resources.find(item => item.id === entry?.config.targetId)?.name || '未设置'
})
const directoryName = computed(
  () => props.directories.find(item => item.id === form.platformParentId)?.name || '请选择目录'
)
const settingsReadOnly = computed(() => props.readOnly || !props.canQueryDirectories)
const changed = () => props.open && !settingsReadOnly.value && JSON.stringify(form) !== initial
useUnsavedNavigation(changed)

watch(
  () => [props.open, props.resource?.id],
  () => {
    if (!props.open || !props.resource) return
    Object.assign(form, pageNavigationSettings(props.resources, props.resource))
    initial = JSON.stringify(form)
    error.value = ''
  },
  { immediate: true }
)

function close() {
  if (changed())
    Modal.confirm({
      title: '放弃尚未保存到草稿的菜单设置？',
      okText: '放弃修改',
      cancelText: '继续设置',
      onOk: () => emit('close')
    })
  else emit('close')
}
function apply() {
  if (!props.resource || settingsReadOnly.value) return
  error.value = ''
  try {
    if (form.showInMenu && (props.directoriesLoading || props.directoryError))
      throw new Error('请先成功加载平台目录，再保存菜单设置')
    if (form.showInMenu && !form.menuName.trim()) throw new Error('请填写菜单名称')
    emit('apply', applyPageNavigation(props.resources, props.resource.id, form, props.directories))
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  }
}
</script>

<template>
  <OsModalForm
    :open="open"
    title="页面菜单设置"
    display-mode="drawer"
    :allow-switch-display="false"
    :width="520"
    :form-data="form"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    :disabled="settingsReadOnly"
    :show-footer="!settingsReadOnly"
    ok-text="保存设置"
    @ok="apply"
    @cancel="close"
  >
    <template #formItems>
      <div class="page-menu-settings">
        <div class="page-menu-identity">
          <FileTextOutlined />
          <strong>{{ resource?.name }}</strong>
        </div>
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <a-alert
          v-if="!canQueryDirectories"
          type="warning"
          show-icon
          message="需要平台菜单查询权限才能调整菜单设置。已有配置继续保留。"
        />
        <a-alert
          v-if="legacy"
          type="info"
          show-icon
          message="此页面保留了原应用入口。保存设置后随应用发布切换为新的页面菜单，已有按钮链接仍可使用。"
        />
        <a-alert v-if="unavailable" type="info" show-icon :message="unavailable" />
        <h4>基本信息</h4>
        <a-form-item label="页面名称" required>
          <a-input v-model:value="form.resourceName" aria-label="页面名称" :maxlength="160" />
        </a-form-item>
        <h4>菜单入口</h4>
        <PlatformMenuFields
          :model-value="form"
          :directories="directories"
          :directories-loading="directoriesLoading"
          :directory-error="directoryError"
          :disabled="settingsReadOnly"
          :unavailable="!!unavailable"
          @update:model-value="Object.assign(form, $event)"
          @reload="emit('reload')"
        />
        <a-form-item label="设为应用首页">
          <a-switch
            v-model:checked="form.defaultHome"
            aria-label="设为应用首页"
            :disabled="settingsReadOnly || (!!unavailable && !form.defaultHome)"
          />
          <span class="home-hint">当前首页：{{ currentHome }}</span>
          <p class="hint">从“我的应用”进入时默认打开。设置后替换当前首页。</p>
        </a-form-item>
        <h4>效果预览</h4>
        <div v-if="form.showInMenu" class="menu-preview">
          <div>
            <FolderOutlined />
            {{ directoryName }}
          </div>
          <div class="menu-preview-page">
            <AppIcon :name="form.icon"><FileTextOutlined /></AppIcon>
            {{ form.menuName || form.resourceName }}
            <a-tag v-if="form.defaultHome" color="purple">首页</a-tag>
          </div>
          <p class="hint">点击二级菜单直接打开此页面。</p>
        </div>
        <p v-else class="hint">
          不显示在平台菜单，可通过页面按钮、详情入口{{ form.defaultHome ? '或应用首页' : '' }}打开。
        </p>
        <a-alert
          type="info"
          show-icon
          message="设置保存到应用草稿，保存并发布应用后生效。菜单角色授权与应用业务权限分别校验。"
        />
      </div>
    </template>
  </OsModalForm>
</template>

<style scoped>
.page-menu-settings {
  display: grid;
  gap: var(--spacing-md, 12px);
}
.page-menu-identity {
  display: flex;
  align-items: center;
  gap: var(--spacing-md, 12px);
  padding-bottom: var(--spacing-lg, 16px);
  border-bottom: 1px solid var(--border, #e5e7eb);
}
.page-menu-identity > .anticon {
  color: var(--brand, #4338ca);
  font-size: 24px;
}
.page-menu-settings h4 {
  margin: 0;
  font-size: var(--font-size-base, 14px);
}
.hint {
  color: var(--text-secondary, #64748b);
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
.home-hint {
  margin-inline-start: 12px;
  color: var(--text-secondary, #64748b);
}
.menu-preview {
  padding: var(--spacing-lg, 16px);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: var(--radius, 6px);
  background: var(--bg-layout, #f8fafc);
}
.menu-preview-page {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 0 20px;
  color: var(--brand, #4338ca);
}
</style>
