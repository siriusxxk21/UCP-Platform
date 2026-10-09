import type { Ref, ComputedRef, UnwrapNestedRefs } from 'vue'

/** 表单模式（新增/编辑） */
export type ModalFormMode = 'add' | 'edit'

/** 展示模式（弹窗/抽屉/全屏） */
export type DisplayMode = 'modal' | 'drawer' | 'fullscreen'

/** 拖拽缩放边界约束 */
export interface ResizeConstraints {
  /** 最小宽度（px），默认 400 */
  minWidth?: number
  /** 最大宽度（px），默认 window.innerWidth */
  maxWidth?: number
  /** 最小高度（px），默认 300 */
  minHeight?: number
  /** 最大高度（px），默认 window.innerHeight */
  maxHeight?: number
}

/** useOsModalForm 配置选项 */
export interface UseOsModalFormOptions<T> {
  /** 新增请求函数 */
  createFn?: (data: T) => Promise<void>

  /** 编辑请求函数 */
  updateFn?: (data: T) => Promise<void>

  /** 表单默认值工厂函数（确保每次 reset 都是新对象） */
  defaultForm: () => T

  /** 保存成功回调（如刷新列表） */
  afterSuccess?: () => void

  /** 保存失败回调 */
  onError?: (error: unknown) => void

  /** 弹窗标题配置 */
  titles?: {
    add?: string   // 默认 '新增'
    edit?: string  // 默认 '编辑'
  }

  /** 是否在打开时自动重置表单（默认 true） */
  autoResetOnOpen?: boolean

  /** 编辑打开后回调（用于加载关联数据等副作用，在数据填充后、弹窗可见后执行） */
  afterOpenEdit?: (formData: UnwrapNestedRefs<T>) => void

  /** 新增打开后回调（用于初始化关联数据等副作用，在表单重置后、弹窗可见后执行） */
  afterOpenAdd?: () => void

  /** 提交前回调（可修改提交数据，如清洗空值；须返回处理后的数据） */
  beforeSubmit?: (data: T, mode: ModalFormMode) => T

  /** 初始展示模式（默认 'modal'） */
  defaultDisplayMode?: DisplayMode

  /** 是否允许切换展示模式（默认 true） */
  allowSwitchDisplay?: boolean

  /** 允许切换的展示模式列表（默认全部 ['modal', 'drawer', 'fullscreen']） */
  displayModes?: DisplayMode[]

  /** 是否允许拖拽缩放（默认 true） */
  resizable?: boolean

  /** 拖拽缩放边界约束 */
  resizeConstraints?: ResizeConstraints

  /** 模式持久化 key（传入则 localStorage 记忆用户的展示模式选择） */
  displayModeStorageKey?: string

  /** 需要将空字符串转为 undefined 的字段名列表（提交前自动清洗，免去 createFn/updateFn 中手动 `x || undefined`） */
  emptyFields?: (keyof T)[]
}

/** useOsModalForm 返回值 */
export interface UseOsModalFormReturn<T> {
  /** 弹窗可见状态 */
  modalVisible: Ref<boolean>

  /** 提交加载状态 */
  modalLoading: Ref<boolean>

  /** 当前模式 */
  mode: Ref<ModalFormMode>

  /** 是否为编辑模式 */
  isEdit: ComputedRef<boolean>

  /** 是否为新增模式 */
  isAdd: ComputedRef<boolean>

  /** 弹窗标题 */
  modalTitle: ComputedRef<string>

  /** 表单数据（reactive 对象） */
  formData: UnwrapNestedRefs<T>

  /** 打开新增弹窗 */
  openAdd: () => void

  /** 打开编辑弹窗 */
  openEdit: (record: T) => void

  /** 关闭弹窗并重置表单 */
  closeModal: () => void

  /** 提交表单（组件校验通过后 emit ok，业务页绑定 @ok 调用此方法即可） */
  handleSubmit: () => Promise<void>

  /** 自动透传给 OsModalForm 的 props（含 open/title/loading/formData/displayMode 等），业务页面用 v-bind 即可 */
  modalProps: ComputedRef<{
    open: boolean
    title: string
    loading: boolean
    formData: UnwrapNestedRefs<T>
    displayMode: DisplayMode
    allowSwitchDisplay: boolean
    displayModes: DisplayMode[]
    resizable: boolean
    resizeConstraints: ResizeConstraints
  }>

  /** 事件处理器对象，业务页面可用 v-on 绑定 */
  modalEvents: {
    ok: () => Promise<void>
    cancel: () => void
    displayModeChange: (mode: DisplayMode) => void
  }

  /** 当前展示模式 */
  displayMode: Ref<DisplayMode>

  /** 切换展示模式 */
  switchDisplayMode: (mode: DisplayMode) => void

  /** 是否允许切换展示模式 */
  allowSwitchDisplay: boolean

  /** 允许切换的展示模式列表 */
  displayModes: DisplayMode[]

  /** 是否允许拖拽缩放 */
  resizable: boolean

  /** 拖拽缩放边界约束 */
  resizeConstraints: ResizeConstraints
}
