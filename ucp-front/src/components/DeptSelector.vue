<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { getOrgDeptTree } from '@/api/system/organization'

export interface SelectedDept {
  id: string
  name: string
}

interface TreeNode {
  id: string
  key: string
  rawId: string
  name: string
  nodeType: 'org' | 'dept'
  checkable: boolean
  children?: TreeNode[]
  isLeaf?: boolean
}

const props = withDefaults(
  defineProps<{
    visible?: boolean
    modelValue?: Array<string | number>
    multiple?: boolean
    title?: string
    maxCount?: number
    width?: number | string
  }>(),
  {
    visible: false,
    modelValue: () => [],
    multiple: false,
    title: '选择部门',
    maxCount: 0,
    width: 560
  }
)

const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
  (e: 'update:modelValue', value: Array<string | number>): void
  (e: 'change', items: SelectedDept[]): void
}>()

// ============================== 状态 ==============================

const orgDeptTree = ref<TreeNode[]>([])
const deptLoading = ref(false)
const deptKeywords = ref('')
const checkedKeys = ref<(string | number)[]>([])
/** 弹窗打开时快照的已选项，用于取消时回退 */
const snapshotKeys = ref<(string | number)[]>([])

// ============================== 数据获取 ==============================

function transformTree(data: any[]): TreeNode[] {
  if (!Array.isArray(data)) return []
  return data.map((item: any, index: number) => {
    const nodeType: 'org' | 'dept' = item.nodeType === 'dept' ? 'dept' : 'org'
    const rawId = String(item.rawId ?? item.id ?? index)
    const key = `node_${rawId}`
    const nodeName = item.name || item.orgName || item.deptName || item.label || `未命名-${index}`
    return {
      id: key,
      key,
      rawId,
      name: nodeName,
      nodeType,
      checkable: nodeType === 'dept',
      children: item.children?.length ? transformTree(item.children) : undefined,
      isLeaf: !item.children || item.children.length === 0
    }
  })
}

async function fetchDeptTree() {
  deptLoading.value = true
  try {
    const res = await getOrgDeptTree()
    orgDeptTree.value = transformTree(Array.isArray(res) ? res : [])
  } finally {
    deptLoading.value = false
  }
}

// ============================== 树过滤 ==============================

const filteredTree = computed(() => {
  const tree = orgDeptTree.value
  if (!Array.isArray(tree) || tree.length === 0) return []
  if (!deptKeywords.value) return tree

  const keyword = deptKeywords.value.trim().toLowerCase()

  function filterNode(node: TreeNode): TreeNode | null {
    if (!node) return null
    const isMatch = node.name?.toLowerCase().includes(keyword) ?? false
    let filteredChildren: TreeNode[] = []
    if (Array.isArray(node.children)) {
      filteredChildren = node.children.map(filterNode).filter((c): c is TreeNode => c !== null)
    }
    if (isMatch || filteredChildren.length > 0) {
      return { ...node, children: filteredChildren, isLeaf: filteredChildren.length === 0 }
    }
    return null
  }

  return tree.map(filterNode).filter((n): n is TreeNode => n !== null)
})

// ============================== 事件处理 ==============================

// 多选框变更
function handleCheck(keys: (string | number)[], info: any) {
  if (!props.multiple) {
    const clickedNode: TreeNode | undefined = info?.node
    if (clickedNode && clickedNode.nodeType === 'dept') {
      checkedKeys.value = [clickedNode.key]
    }
  } else {
    if (props.maxCount > 0) {
      const deptKeys = (keys || []).filter(k => String(k).startsWith('node_'))
      if (deptKeys.length > props.maxCount) return
    }
    checkedKeys.value = keys || []
  }
}

// 从树节点递归收集选中的部门
function collectSelected(): SelectedDept[] {
  const items: SelectedDept[] = []
  const seen = new Set<string>()
  function walk(nodes: TreeNode[]) {
    for (const node of nodes) {
      if (node.nodeType === 'dept' && checkedKeys.value.includes(node.key) && !seen.has(node.rawId)) {
        seen.add(node.rawId)
        items.push({ id: node.rawId, name: node.name })
      }
      if (node.children) walk(node.children)
    }
  }
  walk(orgDeptTree.value)
  return items
}

function handleConfirm() {
  const items = collectSelected()
  emit('update:modelValue', items.map(item => item.id))
  emit('change', items)
  emit('update:visible', false)
}

function handleCancel() {
  checkedKeys.value = [...snapshotKeys.value]
  emit('update:visible', false)
}

// ============================== 弹窗打开逻辑 ==============================

watch(
  () => props.visible,
  val => {
    if (val) {
      deptKeywords.value = ''
      snapshotKeys.value = [...(props.modelValue.map(id => `node_${id}`))]
      checkedKeys.value = [...snapshotKeys.value]
      if (orgDeptTree.value.length === 0) fetchDeptTree()
    }
  },
  // 调用方用 v-if 挂载组件时 visible 一开始就是 true，必须有 immediate 才会首次加载组织树
  { immediate: true }
)

// 暴露打开方法，供父组件通过 ref 调用
defineExpose({
  open() {
    emit('update:visible', true)
  }
})
</script>

<template>
  <a-modal
    :open="visible"
    :title="title"
    :width="width"
    :body-style="{ padding: '0' }"
    @update:open="$emit('update:visible', $event)"
    @ok="handleConfirm"
    @cancel="handleCancel"
  >
    <div class="dept-panel">
      <div class="panel-search">
        <a-input v-model:value="deptKeywords" placeholder="请输入组织或部门名称" allow-clear>
          <template #prefix>
            <SearchOutlined />
          </template>
        </a-input>
      </div>
      <div class="panel-tree">
        <a-tree
          v-if="filteredTree.length"
          :key="deptKeywords"
          :tree-data="filteredTree"
          :field-names="{ title: 'name', key: 'id', children: 'children' }"
          :checked-keys="checkedKeys"
          checkable
          default-expand-all
          block-node
          @check="handleCheck"
        >
          <template #title="node">
            <span v-if="node" class="tree-node-title">
              <span v-if="node.nodeType === 'org'" class="node-tag org-tag">组织</span>
              <span v-else-if="node.nodeType === 'dept'" class="node-tag dept-tag">部门</span>
              <span class="node-name">{{ node.name }}</span>
            </span>
          </template>
        </a-tree>
        <a-empty v-else-if="!deptLoading" :description="deptKeywords ? '未搜索到结果' : '暂无数据'" />
      </div>
    </div>

    <template #footer>
      <div class="selector-footer">
        <a-button @click="handleCancel">取消</a-button>
        <a-button type="primary" @click="handleConfirm">确认</a-button>
      </div>
    </template>
  </a-modal>
</template>

<style scoped>
.dept-panel {
  background: #f9fafb;
}

.panel-search {
  padding: 16px 16px 0;
}

.panel-tree {
  max-height: 440px;
  overflow-y: auto;
  padding: 8px 16px 16px;
}

.tree-node-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  user-select: none;
  padding-left: 4px;
}

.node-tag {
  padding: 1px 4px;
  border-radius: 3px;
  font-weight: 600;
  line-height: 1.2;
}

.org-tag {
  background: #eff6ff;
  color: #3b82f6;
  border: 1px solid #bfdbfe;
  font-size: 12px;
  font-weight: 500;
}

.dept-tag {
  background: #f0fdf4;
  color: #22c55e;
  border: 1px solid #bbf7d0;
  font-size: 12px;
  font-weight: 500;
}

.node-name {
  color: #374151;
  font-size: 14px;
  font-weight: 500;
}

:deep(.ant-tree-treenode-checkbox-checked) .ant-tree-node-content-wrapper {
  background: #e6f7ff;
}

:deep(.ant-modal-footer) {
  padding: 0;
  margin-top: 0;
  border-top: none;
}

:deep(.ant-modal-content) {
  padding: 0;
}

.selector-footer {
  padding: 12px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
