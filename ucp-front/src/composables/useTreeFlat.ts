import {computed, type ComputedRef, type Ref, ref, watch} from 'vue'

// ===== 公共类型 =====

export interface TreeNode<T> {
  id: string
  data: T
  children: TreeNode<T>[]
  expanded: boolean
  level: number
}

export interface FlatTreeRow<T> {
  data: T
  level: number
  hasChildren: boolean
  expanded: boolean
  nodeId: string
}

export interface UseTreeFlatOptions<T> {
  /** 获取节点层级深度（如 item => item.indent） */
  getLevel: (item: T) => number
  /** 获取节点唯一标识 */
  getId: (item: T) => string
  /** 初始是否展开（默认 false，即全部折叠） */
  defaultExpanded?: boolean
}

export interface UseTreeFlatReturn<T> {
  /** 树形数据（根节点数组） */
  treeData: Ref<TreeNode<T>[]>
  /** 展平后的行数据（computed，响应式自动更新） */
  flatRows: ComputedRef<FlatTreeRow<T>[]>
  /** 切换单个节点的展开/折叠 */
  toggleExpand: (nodeId: string) => void
  /** 全部展开 */
  expandAll: () => void
  /** 全部折叠 */
  collapseAll: () => void
}

// ===== 工具函数 =====

/**
 * 基于 indent 层级将扁平数组构建为树
 *
 * 算法：维护一个栈，栈中保存从根到当前节点的路径。
 * 当新节点的 indent 小于等于栈顶时，弹出栈顶直到找到父节点。
 */
export function buildTree<T>(
  items: T[],
  getLevel: (item: T) => number,
  getId: (item: T) => string,
  defaultExpanded: boolean = false,
): TreeNode<T>[] {
  const roots: TreeNode<T>[] = []
  const stack: TreeNode<T>[] = []

  for (const item of items) {
    const level = getLevel(item)
    const node: TreeNode<T> = {
      id: getId(item),
      data: item,
      children: [],
      expanded: defaultExpanded,
      level,
    }

    // 弹出栈中层级 >= 当前节点的元素（它们不是当前节点的祖先）
    while (stack.length > 0 && stack[stack.length - 1].level >= level) {
      stack.pop()
    }

    if (stack.length === 0) {
      roots.push(node)
    } else {
      stack[stack.length - 1].children.push(node)
    }
    stack.push(node)
  }

  return roots
}

/**
 * 深度优先展平树，仅输出已展开节点
 */
export function flattenTree<T>(nodes: TreeNode<T>[], level: number = 0): FlatTreeRow<T>[] {
  const result: FlatTreeRow<T>[] = []

  for (const node of nodes) {
    result.push({
      data: node.data,
      level,
      hasChildren: node.children.length > 0,
      expanded: node.expanded,
      nodeId: node.id,
    })

    if (node.expanded && node.children.length > 0) {
      result.push(...flattenTree(node.children, level + 1))
    }
  }

  return result
}

// ===== Composable =====

export function useTreeFlat<T>(
  source: Ref<T[]>,
  options: UseTreeFlatOptions<T>,
): UseTreeFlatReturn<T> {
  const { getLevel, getId, defaultExpanded = false } = options

  // ── 核心状态 ──
  const treeData = ref<TreeNode<T>[]>([]) as Ref<TreeNode<T>[]>

  // ── 展平行（computed 自动响应 treeData 变化） ──
  const flatRows = computed<FlatTreeRow<T>[]>(() => flattenTree(treeData.value))

  // ── 操作函数 ──
  function toggleExpand(nodeId: string): void {
    const walk = (nodes: TreeNode<T>[]): boolean => {
      for (const n of nodes) {
        if (n.id === nodeId) {
          n.expanded = !n.expanded
          return true
        }
        if (walk(n.children)) return true
      }
      return false
    }
    walk(treeData.value)
    // 触发响应式更新
    treeData.value = [...treeData.value]
  }

  function expandAll(): void {
    const walk = (nodes: TreeNode<T>[]): void => {
      for (const n of nodes) {
        n.expanded = true
        walk(n.children)
      }
    }
    walk(treeData.value)
    treeData.value = [...treeData.value]
  }

  function collapseAll(): void {
    const walk = (nodes: TreeNode<T>[]): void => {
      for (const n of nodes) {
        n.expanded = false
        walk(n.children)
      }
    }
    walk(treeData.value)
    treeData.value = [...treeData.value]
  }

  // ── 监听源数据变化，重建树 ──
  watch(
    source,
    (list) => {
      treeData.value = buildTree(list, getLevel, getId, defaultExpanded)
    },
    { immediate: true },
  )

  return {
    treeData,
    flatRows,
    toggleExpand,
    expandAll,
    collapseAll,
  }
}
