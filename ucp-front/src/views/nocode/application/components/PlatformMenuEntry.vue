<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getUserInfo } from '@/api/auth'
import { createMenu, deleteMenu, getMenuById, getMenuList, updateMenu } from '@/api/system/menu'
import type { Menu } from '@/types/system/menu'
import { MenuStatus } from '@/types/system/menu'
import { useUserStore } from '@/stores/user'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  applicationEntries,
  applicationEntryPath,
  applicationEntryPayload,
  platformEntryDirectories
} from '@/nocode/application-entry'
import type { PlatformEntryForm } from '@/nocode/application-entry'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import IconSelector from '@/components/IconSelector.vue'

const props = defineProps<{ applicationId: string; applicationName: string; published: boolean }>()
const platform = useNocodePlatform(),
  userStore = useUserStore(),
  router = useRouter()
const menus = ref<Menu[]>([]),
  loading = ref(false),
  busy = ref(false),
  open = ref(false),
  error = ref(''),
  formError = ref('')
const entries = computed(() => applicationEntries(menus.value, props.applicationId))
const entry = computed(() => entries.value[0])
const directories = computed(() => platformEntryDirectories(menus.value))
const canQuery = computed(() => platform.hasPermission('system:menu:query'))
const canSave = computed(
  () => entries.value.length <= 1 && platform.hasPermission(entry.value ? 'system:menu:update' : 'system:menu:create')
)
const parentName = computed(() => menus.value.find(menu => menu.id === entry.value?.parentId)?.name || '目录已调整')
const form = reactive<PlatformEntryForm>({
  parentId: '',
  name: '',
  icon: 'AppstoreOutlined',
  sort: 1,
  status: MenuStatus.ENABLED,
  visible: true
})
const rules = {
  parentId: [{ required: true, message: '请选择左侧一级目录' }],
  name: [{ required: true, whitespace: true, message: '请输入入口名称' }],
  sort: [{ required: true, message: '请输入排序' }]
}

async function load() {
  if (!canQuery.value) return
  loading.value = true
  error.value = ''
  try {
    menus.value = await getMenuList()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function edit() {
  formError.value = ''
  Object.assign(form, {
    parentId: entry.value?.parentId || '',
    name: entry.value?.name || props.applicationName.slice(0, 50),
    icon: entry.value?.icon === '#' ? '' : entry.value?.icon || 'AppstoreOutlined',
    sort: entry.value?.sort ?? 1,
    status: entry.value?.status ?? MenuStatus.ENABLED,
    visible: entry.value?.visible !== false
  })
  open.value = true
}

function changeEnabled(checked: boolean) {
  form.status = checked ? MenuStatus.ENABLED : MenuStatus.DISABLED
}

/** 使用底座权限信息刷新菜单和路由；不把应用成员自动改成系统角色。 */
async function refreshNavigation() {
  try {
    userStore.applyPermissionInfo(await getUserInfo())
  } catch {
    message.warning('入口已保存，当前导航刷新失败，请刷新页面')
  }
}

async function currentEntry(): Promise<Menu | undefined> {
  if (!entry.value) return undefined
  const current = await getMenuById(entry.value.id)
  if (!current || !applicationEntries([current], props.applicationId).length)
    throw new Error('平台菜单已被其他操作调整，请刷新后重试')
  return current
}

async function save() {
  busy.value = true
  formError.value = ''
  try {
    if (!canSave.value) throw new Error('没有维护平台菜单入口的权限')
    const latest = await getMenuList()
    if (!platformEntryDirectories(latest).some(menu => menu.id === form.parentId))
      throw new Error('请选择已启用且可见的左侧一级目录')
    const current = await currentEntry()
    if (!current && (!props.published || applicationEntries(latest, props.applicationId).length))
      throw new Error('应用尚未发布，或入口已由其他操作创建，请刷新后重试')
    const payload = applicationEntryPayload(props.applicationId, form, current)
    if (current) await updateMenu({ ...payload, id: current.id })
    else await createMenu(payload)
    open.value = false
    await load()
    await refreshNavigation()
    message.success('平台菜单入口已保存')
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  busy.value = true
  error.value = ''
  try {
    const current = await currentEntry()
    if (!current) throw new Error('入口不存在，请刷新后重试')
    await deleteMenu(current.id)
    await load()
    await refreshNavigation()
    message.success('平台菜单入口已移除')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

watch(() => [props.applicationId, canQuery.value], load, { immediate: true })
</script>

<template>
  <section class="platform-entry">
    <h3>平台菜单入口</h3>
    <p class="hint">
      将应用挂到平台左侧一级目录，例如“经营管理 → 订单应用”。应用里面的页面入口在“页面与视图 → 应用内导航”配置。
    </p>
    <a-alert
      type="info"
      show-icon
      message="入口保存后立即生效，无需重新发布应用。角色的菜单授权控制入口是否可见；应用的成员与权限控制业务数据访问。"
    />
    <a-alert v-if="!canQuery" type="warning" show-icon message="需要底座菜单查询权限才能配置。请联系平台菜单管理员。" />
    <template v-else>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-alert
        v-if="entries.length > 1"
        type="warning"
        show-icon
        message="发现多个手工配置的应用入口，请先在系统菜单管理中整理为一个入口，再在此维护。"
      />
      <a-spin :spinning="loading">
        <a-descriptions v-if="entry" bordered :column="1">
          <a-descriptions-item label="挂载位置">{{ parentName }} → {{ entry.name }}</a-descriptions-item>
          <a-descriptions-item label="入口状态">
            {{ entry.status === MenuStatus.ENABLED ? '已启用' : '已停用' }} ·
            {{ entry.visible === false ? '已隐藏' : '可见' }}
          </a-descriptions-item>
        </a-descriptions>
        <a-empty v-else description="尚未配置平台菜单入口，可继续从“我的应用”进入" />
      </a-spin>
      <a-space wrap>
        <a-button
          v-if="canSave"
          type="primary"
          :disabled="loading || busy || !!error || (!entry && !published)"
          @click="edit"
        >
          {{ entry ? '修改平台菜单入口' : '添加平台菜单入口' }}
        </a-button>
        <a-button v-if="entry" :disabled="busy" @click="router.push(applicationEntryPath(applicationId))">
          打开应用
        </a-button>
        <a-popconfirm
          v-if="entry && entries.length === 1 && platform.hasPermission('system:menu:delete')"
          title="移除这个平台入口？应用、应用内导航及业务数据保留。"
          @confirm="remove"
        >
          <a-button danger :loading="busy">移除平台菜单入口</a-button>
        </a-popconfirm>
        <a-button :disabled="busy" @click="load">刷新</a-button>
        <a-button @click="router.push('/system/menu')">系统菜单管理</a-button>
        <a-button v-if="platform.hasPermission('system:role:query')" @click="router.push('/system/role')">
          角色菜单授权
        </a-button>
      </a-space>
      <p v-if="!published" class="hint">首次添加平台入口前，请先发布应用。</p>
      <p class="hint">每个应用在这里维护一个平台入口。新目录请在系统菜单管理中创建；移除入口不会停用应用。</p>
    </template>
    <OsModalForm
      :open="open"
      :loading="busy"
      :title="entry ? '修改平台菜单入口' : '添加平台菜单入口'"
      :form-data="form"
      :rules="rules"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      ok-text="保存入口"
      @ok="save"
      @cancel="open = false"
    >
      <template #formItems>
        <a-alert v-if="formError" type="error" :message="formError" show-icon />
        <a-form-item label="挂载目录" name="parentId">
          <a-select
            v-model:value="form.parentId"
            aria-label="挂载目录"
            placeholder="选择左侧一级目录"
            :options="directories.map(menu => ({ value: menu.id, label: menu.name }))"
          />
        </a-form-item>
        <a-form-item label="入口名称" name="name">
          <a-input v-model:value="form.name" aria-label="入口名称" :maxlength="50" />
        </a-form-item>
        <a-form-item label="入口图标"><IconSelector v-model:value="form.icon" /></a-form-item>
        <a-form-item label="排序" name="sort">
          <a-input-number v-model:value="form.sort" :min="0" :max="9999" :precision="0" />
        </a-form-item>
        <a-form-item label="启用入口">
          <a-switch :checked="form.status === MenuStatus.ENABLED" @change="changeEnabled" />
        </a-form-item>
        <a-form-item label="显示入口"><a-switch v-model:checked="form.visible" /></a-form-item>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.platform-entry {
  display: grid;
  gap: 16px;
  max-width: 1000px;
}
.platform-entry h3,
.hint {
  margin: 0;
}
.hint {
  color: #64748b;
  line-height: 1.7;
}
</style>
