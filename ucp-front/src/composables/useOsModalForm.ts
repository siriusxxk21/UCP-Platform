import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import type {
  DisplayMode,
  ModalFormMode,
  UseOsModalFormOptions,
  UseOsModalFormReturn
} from '../components/ucp-modal-form/types'

export function useOsModalForm<T extends Record<string, any> = Record<string, any>>(
  options: UseOsModalFormOptions<T>
): UseOsModalFormReturn<T> {
  const {
    createFn,
    updateFn,
    defaultForm,
    afterSuccess,
    onError,
    titles = { add: '新增', edit: '编辑' },
    autoResetOnOpen = true,
    afterOpenEdit,
    afterOpenAdd,
    beforeSubmit,
    defaultDisplayMode = 'modal',
    allowSwitchDisplay = true,
    displayModes = ['modal', 'drawer', 'fullscreen'],
    resizable = true,
    resizeConstraints = {},
    displayModeStorageKey,
    emptyFields
  } = options

  // ===== 核心状态 =====

  const modalVisible = ref(false)
  const modalLoading = ref(false)
  const mode = ref<ModalFormMode>('add')
  const formData = reactive<T>(defaultForm())

  // ===== 计算属性 =====

  const isEdit = computed(() => mode.value === 'edit')
  const isAdd = computed(() => mode.value === 'add')
  const modalTitle = computed(() => (isEdit.value ? (titles.edit ?? '编辑') : (titles.add ?? '新增')))

  // ===== 表单操作 =====

  function resetForm(): void {
    const defaults = defaultForm()
    Object.keys(defaults).forEach(key => {
      ;(formData as any)[key] = (defaults as any)[key]
    })
  }

  // ===== 打开弹窗 =====

  function openAdd(): void {
    mode.value = 'add'
    if (autoResetOnOpen) {
      resetForm()
    }
    modalVisible.value = true
    // 新增打开后执行副作用
    if (afterOpenAdd) {
      afterOpenAdd()
    }
  }

  function openEdit(record: T): void {
    mode.value = 'edit'
    // 先重置为默认值
    resetForm()
    // 填充数据：只填充 defaultForm 定义的字段，避免多余字段（如 createTime）
    Object.keys(defaultForm()).forEach(key => {
      if ((record as any)[key] !== undefined) {
        ;(formData as any)[key] = (record as any)[key]
      }
    })
    modalVisible.value = true
    // 编辑打开后执行副作用（如加载关联数据）
    if (afterOpenEdit) {
      afterOpenEdit(formData)
    }
  }

  // ===== 关闭弹窗 =====

  function closeModal(): void {
    modalVisible.value = false
    resetForm()
  }

  // ===== 展示模式状态 =====

  // 从 localStorage 恢复用户上次的展示模式选择
  function loadDisplayMode(): DisplayMode {
    if (!displayModeStorageKey) return defaultDisplayMode
    try {
      const saved = localStorage.getItem(`os-modal-form-display:${displayModeStorageKey}`)
      if (saved && displayModes.includes(saved as DisplayMode)) {
        return saved as DisplayMode
      }
    } catch {
      // ignore
    }
    return defaultDisplayMode
  }

  const displayMode = ref<DisplayMode>(loadDisplayMode())

  function switchDisplayMode(newMode: DisplayMode): void {
    if (!displayModes.includes(newMode)) return
    displayMode.value = newMode
    // 持久化用户选择
    if (displayModeStorageKey) {
      try {
        localStorage.setItem(`os-modal-form-display:${displayModeStorageKey}`, newMode)
      } catch {
        // ignore
      }
    }
  }

  // ===== 提交逻辑 =====

  async function handleSubmit(): Promise<void> {
    modalLoading.value = true
    try {
      let submitData = { ...formData } as T

      // 自动清洗空字符串字段为 undefined
      if (emptyFields && emptyFields.length > 0) {
        emptyFields.forEach(key => {
          if ((submitData as any)[key] === '' || (submitData as any)[key] === null) {
            ;(submitData as any)[key] = undefined
          }
        })
      }

      // 提交前钩子：允许业务层修改提交数据
      if (beforeSubmit) {
        submitData = beforeSubmit(submitData, mode.value)
      }

      if (isEdit.value) {
        if (!updateFn) {
          message.warning('未配置编辑请求函数')
          return
        }
        await updateFn(submitData)
        message.success('编辑成功')
      } else {
        if (!createFn) {
          message.warning('未配置新增请求函数')
          return
        }
        await createFn(submitData)
        message.success('新增成功')
      }

      closeModal()
      if (afterSuccess) {
        afterSuccess()
      }
    } catch (error) {
      console.error('[useOsModalForm] 保存失败:', error)
      if (onError) onError(error)
    } finally {
      modalLoading.value = false
    }
  }

  // ===== 自动透传 Props / Events =====

  const modalProps = computed(() => ({
    open: modalVisible.value,
    title: modalTitle.value,
    loading: modalLoading.value,
    formData,
    displayMode: displayMode.value,
    allowSwitchDisplay,
    displayModes,
    resizable,
    resizeConstraints
  }))

  const modalEvents = {
    ok: handleSubmit,
    cancel: closeModal,
    displayModeChange: switchDisplayMode
  }

  return {
    modalVisible,
    modalLoading,
    mode,
    isEdit,
    isAdd,
    modalTitle,
    formData,
    openAdd,
    openEdit,
    closeModal,
    handleSubmit,
    modalProps,
    modalEvents,
    displayMode,
    switchDisplayMode,
    // 透传给组件的配置
    allowSwitchDisplay,
    displayModes,
    resizable,
    resizeConstraints
  }
}
