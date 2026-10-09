<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import {
  DeleteOutlined,
  EditOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useOsModalForm } from '@/composables/useOsModalForm'
import AppIcon from '@/components/AppIcon.vue'
import IconSelector from '@/components/IconSelector.vue'
import { isSupportedIcon } from '@/utils/icons'
import { getUserInfo } from '@/api/auth'
import type { Menu, MenuQueryParams } from '@/api/system/menu'
import { createMenu, deleteMenu, getMenuList, getMenuTree, updateMenu } from '@/api/system/menu'
import { useUserStore } from '@/stores/user'
import { MenuType as MENU_TYPE, MenuStatus as MENU_STATUS } from '@/types/system/menu'

const userStore = useUserStore()
const canUpdateMenu = computed(() => userStore.permissions.includes('system:menu:update'))

// ===== 类型/颜色映射 =====

function getMenuTypeColor(type?: number) {
  switch (type) {
    case MENU_TYPE.DIRECTORY:
      return 'blue'
    case MENU_TYPE.MENU:
      return 'green'
    case MENU_TYPE.BUTTON:
      return 'orange'
    default:
      return 'default'
  }
}

function getMenuTypeText(type?: number) {
  switch (type) {
    case MENU_TYPE.DIRECTORY:
      return '目录'
    case MENU_TYPE.MENU:
      return '菜单'
    case MENU_TYPE.BUTTON:
      return '按钮'
    default:
      return '未知'
  }
}

async function refreshCurrentUserMenus() {
  try {
    const permissionInfo = await getUserInfo()
    userStore.applyPermissionInfo(permissionInfo)
  } catch (error) {
    console.error('刷新当前用户菜单权限失败:', error)
    message.warning('菜单已更新，但当前用户菜单刷新失败，请手动刷新页面')
  }
}

// ===== useOsTablePage =====

type MenuQueryForm = Pick<MenuQueryParams, 'name' | 'status'>

const { loading, tableData, queryForm, handleQuery, handleReset, fetchData } = useOsTablePage<Menu, MenuQueryForm>({
  fetchFn: params => getMenuList({ name: params.name || undefined, status: params.status }),
  defaultQuery: () => ({
    name: undefined,
    status: undefined
  }),
  afterFetch
})

// ===== 树形表格展开状态 =====

const expandedRowKeys = ref<string[]>([])
const expandableConfig = computed(() => ({ childrenColumnName: 'children' }))

function getAllIds(menus: Menu[]): string[] {
  const ids: string[] = []
  const traverse = (list: Menu[]) => {
    for (const menu of list) {
      if (menu.children && menu.children.length > 0) {
        ids.push(menu.id)
        traverse(menu.children)
      }
    }
  }
  traverse(menus)
  return ids
}

// 数据加载后默认展开所有
function afterFetch(data: Menu[]): Menu[] {
  expandedRowKeys.value = getAllIds(data)
  return data
}

function handleExpand(_expanded: boolean, record: Menu) {
  const currentKeys = [...expandedRowKeys.value]
  if (currentKeys.includes(record.id)) {
    currentKeys.splice(currentKeys.indexOf(record.id), 1)
  } else {
    currentKeys.push(record.id)
  }
  expandedRowKeys.value = currentKeys
}

function handleExpandAll() {
  expandedRowKeys.value = getAllIds(tableData.value)
}

function handleCollapseAll() {
  expandedRowKeys.value = []
}

// ===== 列定义 =====

const columns: TableColumnType[] = [
  { title: '菜单名称', dataIndex: 'name', key: 'name', width: 250, ellipsis: true, align: 'left', fixed: 'left' },
  { title: '菜单类型', dataIndex: 'menuType', key: 'menuType', width: 100, align: 'center' },
  { title: '显示排序', dataIndex: 'sort', key: 'sort', width: 100, align: 'center' },
  { title: '权限标识', dataIndex: 'permission', key: 'permission', width: 190, ellipsis: true },
  { title: '路由地址', dataIndex: 'path', key: 'path', width: 190, ellipsis: true },
  { title: '组件路径', dataIndex: 'component', key: 'component', width: 210, ellipsis: true },
  { title: '组件名称', dataIndex: 'componentName', key: 'componentName', width: 160, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100, align: 'center' },
  { title: '操作', key: 'action', width: 250, align: 'center', fixed: 'right' }
]

function toMenuForm(record: Menu): MenuForm {
  return {
    id: record.id,
    name: record.name,
    permission: record.permission || '',
    path: record.path || '',
    component: record.component || '',
    componentName: record.componentName || '',
    icon: record.icon || undefined,
    parentId: record.parentId,
    sort: record.sort,
    status: record.status ?? MENU_STATUS.ENABLED,
    menuType: record.menuType ?? MENU_TYPE.MENU,
    visible: record.visible ?? true,
    keepAlive: record.keepAlive ?? false,
    alwaysShow: record.alwaysShow ?? true
  }
}

function toMenuSavePayload(data: MenuForm) {
  const isDirectory = data.menuType === MENU_TYPE.DIRECTORY
  const isMenu = data.menuType === MENU_TYPE.MENU
  const isRoute = isDirectory || isMenu

  return {
    name: data.name.trim(),
    permission: isDirectory ? '' : data.permission.trim(),
    path: isRoute ? data.path.trim() : '',
    component: isMenu ? data.component.trim() : '',
    componentName: isMenu ? data.componentName.trim() : '',
    icon: isRoute ? data.icon : '',
    parentId: String(data.parentId ?? 0),
    sort: data.sort,
    status: data.status,
    menuType: data.menuType,
    visible: isRoute ? data.visible : true,
    keepAlive: isMenu ? data.keepAlive : false,
    alwaysShow: isRoute ? data.alwaysShow : false
  }
}

// ===== 状态切换 =====

async function handleStatusChange(record: Menu, checked: boolean) {
  try {
    await updateMenu({
      ...toMenuSavePayload(toMenuForm(record)),
      id: record.id,
      status: checked ? MENU_STATUS.ENABLED : MENU_STATUS.DISABLED
    })
    message.success('状态更新成功')
    await fetchData()
    await refreshCurrentUserMenus()
  } catch (error) {
    console.error('状态更新失败:', error)
    message.error('状态更新失败')
  }
}

// ===== 删除 =====

async function handleDelete(record: Menu) {
  try {
    await deleteMenu(record.id)
    message.success('删除成功')
    await fetchData()
    await refreshCurrentUserMenus()
  } catch (error) {
    console.error('删除失败:', error)
    message.error('删除失败')
  }
}

// ===== 菜单树（用于表单的上级菜单选择） =====

interface ParentMenuOption extends Menu {
  disabled?: boolean
  children?: ParentMenuOption[]
}

const menuTreeData = ref<ParentMenuOption[]>([])

function toParentMenuOptions(menus: Menu[], excludedId?: string): ParentMenuOption[] {
  return menus.flatMap(menu => {
    if (menu.id === excludedId) return []
    return [
      {
        ...menu,
        disabled: menu.menuType === MENU_TYPE.BUTTON,
        children: toParentMenuOptions(menu.children || [], excludedId)
      }
    ]
  })
}

async function loadMenuTree(excludedId?: string) {
  try {
    const res = await getMenuTree()
    menuTreeData.value = [
      {
        id: '0',
        name: '顶级菜单',
        path: '',
        parentId: '0',
        sort: 0,
        children: toParentMenuOptions(res || [], excludedId)
      }
    ]
  } catch (error) {
    console.error('获取菜单树失败:', error)
    message.error('获取上级菜单失败')
  }
}

// ===== 新增 / 编辑弹窗（OsModalForm） =====

interface MenuForm {
  id?: string
  name: string
  permission: string
  path: string
  component: string
  componentName: string
  icon?: string
  parentId: string
  sort: number
  status: number
  menuType: number
  visible: boolean
  keepAlive: boolean
  alwaysShow: boolean
}

const menuForm = useOsModalForm<MenuForm>({
  createFn: async data => {
    await createMenu(toMenuSavePayload(data))
  },
  updateFn: async data => {
    await updateMenu({
      ...toMenuSavePayload(data),
      id: data.id!
    })
  },
  defaultForm: () => ({
    id: undefined,
    name: '',
    permission: '',
    path: '',
    component: '',
    componentName: '',
    icon: undefined,
    parentId: '0',
    sort: 0,
    status: MENU_STATUS.ENABLED,
    menuType: MENU_TYPE.MENU,
    visible: true,
    keepAlive: false,
    alwaysShow: true
  }),
  afterSuccess: async () => {
    await fetchData()
    await refreshCurrentUserMenus()
  },
  titles: { add: '新增菜单', edit: '编辑菜单' },
  afterOpenAdd: () => loadMenuTree(),
  afterOpenEdit: data => loadMenuTree(data.id)
})

function handleAdd() {
  menuForm.openAdd()
}

function handleAddChild(record: Menu) {
  menuForm.openAdd()
  menuForm.formData.parentId = record.id
}

function handleEdit(record: Menu) {
  menuForm.openEdit(toMenuForm(record))
}

const formRules = computed(() => ({
  parentId: [{ required: true, message: '请选择上级菜单', trigger: 'change' }],
  name: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }],
  menuType: [{ required: true, message: '请选择菜单类型', trigger: 'change' }],
  path: [
    {
      validator: async (_rule: unknown, value: string) => {
        if (menuForm.formData.menuType !== MENU_TYPE.BUTTON && !value?.trim()) {
          throw new Error('请输入路由地址')
        }
      },
      trigger: 'blur'
    }
  ],
  componentName: [
    {
      validator: async (_rule: unknown, value: string) => {
        if (menuForm.formData.menuType === MENU_TYPE.MENU && menuForm.formData.keepAlive && !value?.trim()) {
          throw new Error('开启页面缓存时请输入组件名称')
        }
      },
      trigger: 'blur'
    }
  ],
  sort: [{ required: true, type: 'number', message: '请输入显示顺序', trigger: 'change' }],
  status: [{ required: true, message: '请选择菜单状态', trigger: 'change' }]
}))
</script>

<template>
  <div class="menu-manage">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="false"
      :show-index="false"
      row-key="id"
      title="菜单列表"
      :scroll="{ x: 1550, y: 'calc(100vh - 360px)' }"
      resizable
      :expandable="expandableConfig"
      :expanded-row-keys="expandedRowKeys"
      @search="handleQuery"
      @expand="handleExpand"
    >
      <!-- 搜索区域 -->
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="菜单名称">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              placeholder="请输入菜单名称"
              style="width: 180px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="状态">
            <a-select v-model:value="queryForm.status" style="width: 180px" allow-clear placeholder="请选择">
              <a-select-option :value="MENU_STATUS.ENABLED">启用</a-select-option>
              <a-select-option :value="MENU_STATUS.DISABLED">禁用</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <!-- 工具栏按钮 -->
      <template #toolbar>
        <a-button @click="handleExpandAll">
          <NodeExpandOutlined />
          展开全部
        </a-button>
        <a-button @click="handleCollapseAll">
          <NodeCollapseOutlined />
          折叠全部
        </a-button>
      </template>

      <!-- 新增按钮 -->
      <template #actions>
        <a-button v-hasPerm="'system:menu:create'" type="primary" @click="handleAdd">
          <PlusOutlined />
          新增
        </a-button>
      </template>

      <!-- 单元格渲染 -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a-space>
            <AppIcon
              v-if="(record as Menu).menuType !== MENU_TYPE.BUTTON && isSupportedIcon((record as Menu).icon)"
              :name="(record as Menu).icon"
              style="color: var(--brand)"
            />
            <span
              v-else-if="
                (record as Menu).menuType !== MENU_TYPE.BUTTON &&
                (record as Menu).icon?.trim() &&
                (record as Menu).icon?.trim() !== '#'
              "
              style="color: #ff4d4f; font-size: 12px"
            >
              [{{ (record as Menu).icon }}]
            </span>
            <span v-else style="display: inline-block; width: 14px" />
            <span>{{ (record as Menu).name }}</span>
          </a-space>
        </template>
        <template v-else-if="column.key === 'menuType'">
          <a-tag :color="getMenuTypeColor((record as Menu).menuType)">
            {{ getMenuTypeText((record as Menu).menuType) }}
          </a-tag>
        </template>
        <template v-else-if="['permission', 'path', 'component', 'componentName'].includes(String(column.key))">
          <span v-if="(record as any)[String(column.key)]">{{ (record as any)[String(column.key)] }}</span>
          <span v-else class="field-placeholder">-</span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-switch
            v-if="canUpdateMenu"
            :checked="(record as Menu).status === MENU_STATUS.ENABLED"
            checked-children="启用"
            un-checked-children="禁用"
            @change="(checked: boolean) => handleStatusChange(record as Menu, checked)"
          />
          <a-tag v-else :color="(record as Menu).status === MENU_STATUS.ENABLED ? 'success' : 'default'">
            {{ (record as Menu).status === MENU_STATUS.ENABLED ? '启用' : '禁用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <span
              v-if="(record as Menu).menuType !== MENU_TYPE.BUTTON"
              v-hasPerm="'system:menu:create'"
              class="action-item"
            >
              <a-button style="color: #6b7280; padding: 0 6px" type="link" @click="handleAddChild(record as Menu)">
                <PlusOutlined />
                新增下级
              </a-button>
            </span>
            <span v-hasPerm="'system:menu:update'" class="action-item">
              <a-button
                style="color: #4338ca; padding: 0 6px; font-weight: 500"
                type="link"
                @click="handleEdit(record as Menu)"
              >
                <EditOutlined />
                编辑
              </a-button>
            </span>
            <span v-hasPerm="'system:menu:delete'" class="action-item">
              <a-popconfirm
                cancel-text="取消"
                description="存在下级菜单时需要先删除下级菜单。"
                ok-text="确定"
                title="确定要删除这个菜单吗？"
                @confirm="handleDelete(record as Menu)"
              >
                <a-button danger style="padding: 0 6px; font-weight: 500" type="link">
                  <DeleteOutlined />
                  删除
                </a-button>
              </a-popconfirm>
            </span>
          </div>
        </template>
      </template>
    </OsTablePage>

    <!-- 新增/编辑弹窗 -->
    <OsModalForm
      :rules="formRules"
      :label-col="{ span: 5 }"
      :wrapper-col="{ span: 18 }"
      v-bind="menuForm.modalProps.value"
      width="640px"
      v-on="menuForm.modalEvents"
    >
      <template #formItems="{ formData: fd }">
        <a-form-item label="菜单类型" name="menuType">
          <a-radio-group v-model:value="fd.menuType">
            <a-radio :value="MENU_TYPE.DIRECTORY">目录</a-radio>
            <a-radio :value="MENU_TYPE.MENU">菜单</a-radio>
            <a-radio :value="MENU_TYPE.BUTTON">按钮</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="上级菜单" name="parentId">
          <a-tree-select
            v-model:value="fd.parentId"
            :dropdown-style="{ maxHeight: '400px', overflow: 'auto' }"
            :field-names="{ label: 'name', value: 'id', children: 'children' }"
            :tree-data="menuTreeData"
            allow-clear
            placeholder="请选择上级菜单"
            show-search
            tree-default-expand-all
            tree-node-filter-prop="name"
          />
        </a-form-item>
        <a-form-item label="菜单名称" name="name">
          <a-input v-model:value="fd.name" placeholder="请输入菜单名称" />
        </a-form-item>
        <a-form-item v-if="fd.menuType !== MENU_TYPE.BUTTON" label="菜单图标" name="icon">
          <IconSelector v-model="fd.icon" placeholder="请选择图标" />
        </a-form-item>
        <a-form-item v-if="fd.menuType !== MENU_TYPE.BUTTON" label="路由地址" name="path">
          <a-input v-model:value="fd.path" placeholder="例如：/system/user 或 https://example.com" />
        </a-form-item>
        <a-form-item v-if="fd.menuType === MENU_TYPE.MENU" label="组件路径" name="component">
          <a-input v-model:value="fd.component" placeholder="例如：system/user/index" />
        </a-form-item>
        <a-form-item
          v-if="fd.menuType === MENU_TYPE.MENU"
          extra="开启页面缓存时，组件名称应与页面组件的 name 保持一致"
          label="组件名称"
          name="componentName"
        >
          <a-input v-model:value="fd.componentName" placeholder="例如：SystemUser" />
        </a-form-item>
        <a-form-item v-if="fd.menuType !== MENU_TYPE.DIRECTORY" label="权限标识" name="permission">
          <a-input v-model:value="fd.permission" placeholder="例如：system:menu:query" />
        </a-form-item>
        <a-form-item label="显示顺序" name="sort">
          <a-input-number v-model:value="fd.sort" :min="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="菜单状态" name="status">
          <a-radio-group v-model:value="fd.status">
            <a-radio :value="MENU_STATUS.ENABLED">启用</a-radio>
            <a-radio :value="MENU_STATUS.DISABLED">禁用</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="fd.menuType !== MENU_TYPE.BUTTON" label="显示状态" name="visible">
          <a-radio-group v-model:value="fd.visible" button-style="solid">
            <a-radio-button :value="true">显示</a-radio-button>
            <a-radio-button :value="false">隐藏</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="fd.menuType !== MENU_TYPE.BUTTON" label="总是显示" name="alwaysShow">
          <a-radio-group v-model:value="fd.alwaysShow" button-style="solid">
            <a-radio-button :value="true">总是</a-radio-button>
            <a-radio-button :value="false">不是</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="fd.menuType === MENU_TYPE.MENU" label="缓存状态" name="keepAlive">
          <a-radio-group v-model:value="fd.keepAlive" button-style="solid">
            <a-radio-button :value="true">缓存</a-radio-button>
            <a-radio-button :value="false">不缓存</a-radio-button>
          </a-radio-group>
        </a-form-item>
      </template>
    </OsModalForm>
  </div>
</template>

<style scoped>
.menu-manage {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.action-cell {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.action-item {
  display: inline-flex;
  align-items: center;
}

.action-item + .action-item::before {
  color: #d1d5db;
  content: '|';
  padding: 0 6px;
}

.field-placeholder {
  color: #bfbfbf;
}

.menu-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.menu-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
