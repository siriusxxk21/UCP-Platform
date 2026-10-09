<script setup lang="ts">
/**
 * 选择网盘里的一个文件夹
 *
 * 只列业务空间与团队空间里「我能管理」的：把文件夹关联给对象，等于让能看、能改记录的人进到这个文件夹里。
 * 目录逐层懒加载；可以就地新建一个文件夹再选它。不能选空间根。
 */
import { computed, ref, watch } from 'vue'
import { createDriveFolder, getDriveEntryList } from '@/api/drive/entry'
import { getBusinessSpaceList, getMySpaceList } from '@/api/drive/space'
import { DRIVE_ROOT_PARENT_ID, DRIVE_SPACE_TYPE_LABELS } from '@/types/drive'
import type { DriveId, DriveSpace } from '@/types/drive'
import { createRequestSession } from '@/nocode/request-session'

interface FolderNode {
  key: string
  title: string
  id: DriveId
  parentKey?: string
  isLeaf: boolean
  children?: FolderNode[]
}

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; pick: [value: { spaceId: DriveId; entryId: DriveId; path: string }] }>()

// 空间清单与目录树各有各的会话：换空间只作废目录树那一边在途的请求
const session = createRequestSession()
const treeSession = createRequestSession()
const spaces = ref<DriveSpace[]>([])
const spacesLoading = ref(false)
const spaceId = ref<string>()
const roots = ref<FolderNode[]>([])
const rootsLoading = ref(false)
const nodes = new Map<string, FolderNode>()
const selectedKeys = ref<string[]>([])
const expandedKeys = ref<string[]>([])
const loadedKeys = ref<string[]>([])
const creating = ref(false)
const createOpen = ref(false)
const createName = ref('')

const space = computed(() => spaces.value.find(item => String(item.id) === spaceId.value))
const spaceOptions = computed(() =>
  spaces.value.map(item => ({ value: String(item.id), label: `${item.name}（${DRIVE_SPACE_TYPE_LABELS[item.type]}）` }))
)
const selected = computed(() => nodes.get(selectedKeys.value[0] ?? ''))
/** 「空间名 / 目录 / 目录」，与配置回显的写法一致 */
const selectedPath = computed(() => {
  const names: string[] = []
  for (let node = selected.value; node; node = node.parentKey ? nodes.get(node.parentKey) : undefined)
    names.unshift(node.title)
  return space.value && names.length ? [space.value.name, ...names].join(' / ') : ''
})

watch(
  () => props.open,
  open => {
    if (open) void loadSpaces()
    else {
      session.invalidate()
      treeSession.invalidate()
    }
  },
  { immediate: true }
)

async function loadSpaces() {
  const current = session.begin()
  spacesLoading.value = true
  resetTree()
  try {
    // 业务空间在前、团队空间在后；不列个人空间；只列自己能管理的
    const [business, mine] = await Promise.all([
      getBusinessSpaceList().catch(() => [] as DriveSpace[]),
      getMySpaceList().catch(() => [] as DriveSpace[])
    ])
    if (!current()) return
    spaces.value = [...(business || []), ...(mine || []).filter(item => item.type === 'TEAM')].filter(
      item => item.role === 'MANAGER'
    )
    spaceId.value = spaces.value.length ? String(spaces.value[0].id) : undefined
    if (spaceId.value) await loadRoots()
  } finally {
    if (current()) spacesLoading.value = false
  }
}

function resetTree() {
  roots.value = []
  nodes.clear()
  selectedKeys.value = []
  expandedKeys.value = []
  loadedKeys.value = []
  createOpen.value = false
  createName.value = ''
}

async function fetchFolders(parentId: DriveId, parentKey?: string): Promise<FolderNode[]> {
  if (!spaceId.value) return []
  const entries = await getDriveEntryList({ spaceId: spaceId.value, parentId })
  return (entries || [])
    .filter(entry => entry.type === 'FOLDER')
    .map(entry => {
      const key = String(entry.id)
      // 已经展开过的层重新取回时，沿用原来那个节点下面已加载的内容
      const node: FolderNode = { key, title: entry.name, id: entry.id, parentKey, isLeaf: false }
      const known = nodes.get(key)
      if (known?.children) node.children = known.children
      nodes.set(key, node)
      return node
    })
}

async function loadRoots() {
  const current = treeSession.begin()
  rootsLoading.value = true
  try {
    const list = await fetchFolders(DRIVE_ROOT_PARENT_ID)
    if (current()) roots.value = list
  } catch {
    if (current()) roots.value = []
  } finally {
    if (current()) rootsLoading.value = false
  }
}

function changeSpace(value: unknown) {
  spaceId.value = String(value)
  resetTree()
  void loadRoots()
}

async function loadChildren(treeNode: { key?: string | number }) {
  const node = nodes.get(String(treeNode.key ?? ''))
  if (!node || node.children) return
  try {
    node.children = await fetchFolders(node.id, node.key)
  } catch {
    node.children = []
  }
  roots.value = [...roots.value]
}

function startCreate() {
  createName.value = ''
  createOpen.value = true
}

/** 在选中的文件夹里新建；没选中时建在空间根下 */
async function create() {
  const name = createName.value.trim()
  if (!name || !spaceId.value || creating.value) return
  const parent = selected.value
  creating.value = true
  try {
    const id = await createDriveFolder({ spaceId: spaceId.value, parentId: parent?.id ?? DRIVE_ROOT_PARENT_ID, name })
    if (parent) {
      parent.children = await fetchFolders(parent.id, parent.key)
      roots.value = [...roots.value]
      if (!loadedKeys.value.includes(parent.key)) loadedKeys.value = [...loadedKeys.value, parent.key]
      if (!expandedKeys.value.includes(parent.key)) expandedKeys.value = [...expandedKeys.value, parent.key]
    } else {
      roots.value = await fetchFolders(DRIVE_ROOT_PARENT_ID)
    }
    selectedKeys.value = [String(id)]
    createOpen.value = false
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    creating.value = false
  }
}

function confirm() {
  const node = selected.value
  if (!node || !spaceId.value) return
  emit('pick', { spaceId: spaceId.value, entryId: node.id, path: selectedPath.value })
}
</script>

<template>
  <a-modal
    :open="open"
    title="选择网盘里的文件夹"
    width="560px"
    ok-text="确定"
    cancel-text="取消"
    :ok-button-props="{ disabled: !selected }"
    @ok="confirm"
    @cancel="emit('close')"
  >
    <a-spin :spinning="spacesLoading">
      <a-empty
        v-if="!spacesLoading && !spaces.length"
        description="你在网盘里没有可管理的业务空间或团队空间。请先到「网盘 → 空间管理」建一个业务空间。"
      />
      <template v-else>
        <div class="folder-picker__bar">
          <a-select
            class="folder-picker__space"
            :value="spaceId"
            :options="spaceOptions"
            placeholder="选择空间"
            @change="changeSpace"
          />
          <a-button :disabled="!spaceId" @click="startCreate">在这里新建文件夹</a-button>
        </div>
        <div v-if="createOpen" class="folder-picker__create">
          <span class="folder-picker__hint">建在：{{ selectedPath || space?.name || '' }}</span>
          <a-input
            v-model:value="createName"
            class="folder-picker__name"
            placeholder="文件夹名称"
            :maxlength="100"
            @press-enter="create"
          />
          <a-button type="primary" :loading="creating" :disabled="!createName.trim()" @click="create">新建</a-button>
          <a-button @click="createOpen = false">取消</a-button>
        </div>
        <a-spin :spinning="rootsLoading">
          <div class="folder-picker__tree">
            <a-tree
              v-if="roots.length"
              v-model:selected-keys="selectedKeys"
              v-model:expanded-keys="expandedKeys"
              v-model:loaded-keys="loadedKeys"
              :tree-data="roots"
              :load-data="loadChildren"
              block-node
            />
            <a-empty v-else-if="!rootsLoading" description="这个空间里还没有文件夹，可以先新建一个" />
          </div>
        </a-spin>
        <p class="folder-picker__hint">
          {{ selectedPath ? `已选：${selectedPath}` : '请选中一个文件夹（不能选空间本身）' }}
        </p>
      </template>
    </a-spin>
  </a-modal>
</template>

<style scoped>
.folder-picker__bar,
.folder-picker__create {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.folder-picker__space {
  flex: 1;
  min-width: 0;
}
.folder-picker__name {
  flex: 1;
  min-width: 0;
}
.folder-picker__tree {
  min-height: 220px;
  max-height: 320px;
  padding: 8px;
  overflow: auto;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 6px;
}
.folder-picker__hint {
  margin: 8px 0 0;
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.folder-picker__create .folder-picker__hint {
  margin: 0;
  white-space: nowrap;
}
</style>
