<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import PlatformMenuFields from '@/components/PlatformMenuFields.vue'
import { getMenuList } from '@/api/system/menu'
import { platformEntryDirectories } from '@/nocode/application-entry'
import { useNocodePlatform } from '@/nocode/platform'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { errorMessage } from '@/nocode/data-center'
import type { Menu } from '@/types/system/menu'
import type { PlatformNavigationSettings } from '@/types/nocode/platform-navigation'
import type { DashboardContent } from '@/types/nocode/report-dashboard'
import { dashboardNavigationSettings, validateDashboardNavigation } from '@/nocode/report-dashboard-navigation'
const props = defineProps<{ open: boolean; content: DashboardContent; readOnly: boolean }>()
const emit = defineEmits<{ close: []; apply: [settings: PlatformNavigationSettings] }>()
const platform = useNocodePlatform()
const canQuery = computed(() => platform.hasPermission('system:menu:query'))
const disabled = computed(() => props.readOnly || !canQuery.value)
const form = reactive(dashboardNavigationSettings(props.content))
const directories = ref<Menu[]>([]),
  loading = ref(false),
  directoryError = ref(''),
  error = ref('')
let initial = '',
  generation = 0
const changed = () => props.open && !disabled.value && JSON.stringify(form) !== initial
useUnsavedNavigation(changed)
async function loadDirectories() {
  const g = ++generation
  directories.value = []
  directoryError.value = ''
  if (!canQuery.value) return
  loading.value = true
  try {
    const menus = await getMenuList()
    if (g === generation) directories.value = platformEntryDirectories(menus)
  } catch (cause) {
    if (g === generation) directoryError.value = errorMessage(cause)
  } finally {
    if (g === generation) loading.value = false
  }
}
watch(
  () => props.open,
  open => {
    if (!open) {
      generation++
      loading.value = false
      return
    }
    Object.assign(form, dashboardNavigationSettings(props.content))
    initial = JSON.stringify(form)
    error.value = ''
    void loadDirectories()
  },
  { immediate: true }
)
onScopeDispose(() => generation++)
function close() {
  if (changed())
    Modal.confirm({
      title: '放弃尚未应用的菜单设置？',
      okText: '放弃修改',
      cancelText: '继续设置',
      onOk: () => emit('close')
    })
  else emit('close')
}
function apply() {
  if (disabled.value) return
  error.value = ''
  try {
    if (form.showInMenu && (loading.value || directoryError.value))
      throw new Error('请先成功加载平台目录，再应用菜单设置')
    const next = validateDashboardNavigation(form, directories.value)
    emit('apply', next)
    emit('close')
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
</script>
<template>
  <OsModalForm
    :open="open"
    title="仪表板菜单设置"
    display-mode="drawer"
    :width="520"
    :allow-switch-display="false"
    :form-data="form"
    layout="vertical"
    :disabled="disabled"
    :show-footer="!disabled"
    ok-text="应用到草稿"
    @ok="apply"
    @cancel="close"
  >
    <template #formItems>
      <p>{{ content.name }}</p>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-alert
        v-if="!canQuery"
        type="warning"
        message="需要平台菜单查询权限才能调整菜单设置。已有配置继续保留。"
        show-icon
      />
      <PlatformMenuFields
        :model-value="form"
        :directories="directories"
        :directories-loading="loading"
        :directory-error="directoryError"
        :disabled="disabled"
        @update:model-value="Object.assign(form, $event)"
        @reload="loadDirectories"
      />
      <a-alert
        type="info"
        show-icon
        message="设置保存到仪表板草稿，发布后同步平台菜单。关闭显示保留原入口；菜单角色授权沿用系统菜单管理。"
      />
    </template>
  </OsModalForm>
</template>
