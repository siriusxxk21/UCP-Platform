<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  CloseOutlined,
  ColumnWidthOutlined,
  CompressOutlined,
  ExpandOutlined,
  SwapOutlined,
} from '@ant-design/icons-vue'
import { useResizable } from './hooks/useResizable'
import type { DisplayMode, ResizeConstraints } from './types'

// ===== Props =====

interface Props {
  /** 弹窗可见状态（单向绑定，由 Composable 管理） */
  open: boolean

  /** 提交加载状态 */
  loading?: boolean

  /** 弹窗标题 */
  title?: string

  /** 弹窗宽度（默认 600，仅 modal 模式生效；drawer 模式默认 500） */
  width?: string | number

  /** 弹窗高度（仅 modal 模式生效；drawer/fullscreen 忽略） */
  height?: string | number

  /** 表单数据对象 */
  formData?: Record<string, any>

  /** 已包含独立表单的复杂内容关闭外层表单，避免字段注册和布局上下文嵌套。 */
  wrapForm?: boolean

  /** 表单布局，避免调用方再嵌套 a-form 导致上下文和布局冲突。 */
  layout?: 'horizontal' | 'vertical' | 'inline'

  /** 统一控制只读表单；默认行为与历史调用保持一致。 */
  disabled?: boolean

  /** 表单校验规则 */
  rules?: Record<string, any>

  /** 表单布局：label 占位（默认 { span: 5 }） */
  labelCol?: object

  /** 表单布局：内容占位（默认 { span: 17 }） */
  wrapperCol?: object

  /** 是否显示底部按钮（默认 true） */
  showFooter?: boolean

  /** 确认按钮文字（默认 '确定'） */
  okText?: string

  /** 取消按钮文字（默认 '取消'） */
  cancelText?: string

  /** 是否显示蒙层（默认 true；fullscreen 模式下强制 false） */
  mask?: boolean

  /** 点击蒙层是否关闭（默认 false） */
  maskClosable?: boolean

  /** 是否支持 ESC 关闭（默认 true） */
  keyboard?: boolean

  /** 是否销毁时销毁表单（默认 false，保持性能） */
  destroyOnClose?: boolean

  // ===== v1.2 新增：三模式 + 拖拽 =====

  /** 当前展示模式 */
  displayMode?: DisplayMode

  /** 是否允许切换展示模式（默认 true） */
  allowSwitchDisplay?: boolean

  /** 抽屉原位最大化，不切换容器，保留表单与未保存内容。 */
  maximizable?: boolean

  /** 允许切换的展示模式列表 */
  displayModes?: DisplayMode[]

  /** 是否允许拖拽缩放（默认 true） */
  resizable?: boolean

  /** 拖拽缩放边界约束 */
  resizeConstraints?: ResizeConstraints
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  layout: 'horizontal',
  disabled: false,
  title: '',
  width: 600,
  height: undefined,
  formData: () => ({}),
  wrapForm: true,
  rules: () => ({}),
  labelCol: () => ({ span: 5 }),
  wrapperCol: () => ({ span: 17 }),
  showFooter: true,
  okText: '确定',
  cancelText: '取消',
  mask: true,
  maskClosable: false,
  keyboard: true,
  destroyOnClose: false,
  displayMode: 'modal',
  allowSwitchDisplay: true,
  maximizable: false,
  displayModes: () => ['modal', 'drawer', 'fullscreen'],
  resizable: true,
  resizeConstraints: () => ({}),
})

// ===== Emits =====

const emit = defineEmits<{
  /** 校验通过后触发 */
  (e: 'ok'): void
  /** 取消/关闭时触发 */
  (e: 'cancel'): void
  /** 展示模式切换时触发 */
  (e: 'displayModeChange', mode: DisplayMode): void
}>()

// ===== 内部状态 =====

const innerFormRef = ref()
const modalRef = ref()
const drawerRef = ref()
const drawerMaximized = ref(false)
watch(
  () => [props.open, props.displayMode],
  () => {
    if (!props.open || props.displayMode !== 'drawer') {
      drawerMaximized.value = false
    }
  },
)

// 当前拖拽尺寸
const currentWidth = ref<number>(typeof props.width === 'number' ? props.width : 600)
const currentHeight = ref<number>(typeof props.height === 'number' ? props.height : 0) // 0 表示 auto

// 监听 width/height prop 变化
watch(
  () => props.width,
  (newVal: Props['width']) => {
    if (typeof newVal === 'number') {
      currentWidth.value = newVal
    }
  },
)

watch(
  () => props.height,
  (newVal: Props['height']) => {
    if (typeof newVal === 'number') {
      currentHeight.value = newVal
    }
  },
)

// 模式切换时重置拖拽尺寸为默认值，避免残留
watch(
  () => props.displayMode,
  () => {
    currentWidth.value = typeof props.width === 'number' ? props.width : 600
    currentHeight.value = typeof props.height === 'number' ? props.height : 0
  },
)

// ===== 拖拽缩放 Hook =====

// 根据当前模式动态获取容器 ref（取实际内容区 DOM 而非外层遮罩包裹层）
const activeContainerRef = computed(() => {
  if (props.displayMode === 'drawer') {
    return drawerRef.value?.$el ?? (drawerRef.value as HTMLElement | undefined)
  }
  // modal / fullscreen 模式：通过 DOM 查询找到 .ant-modal 实际弹窗元素
  // 优先从 modalRef 的 $el 向下查找，兜底用全局查询
  const wrapEl = modalRef.value?.$el ?? modalRef.value
  if (wrapEl instanceof HTMLElement) {
    return (wrapEl.querySelector('.ant-modal') as HTMLElement) ?? wrapEl
  }
  return wrapEl as HTMLElement | undefined
})

const { startResize } = useResizable(computed(() => activeContainerRef.value) as any, {
  minWidth: props.resizeConstraints?.minWidth ?? 400,
  maxWidth: props.resizeConstraints?.maxWidth,
  minHeight: props.resizeConstraints?.minHeight ?? 300,
  maxHeight: props.resizeConstraints?.maxHeight,
  onResize: (w, h) => {
    currentWidth.value = w
    currentHeight.value = h
  },
})

// ===== 展示模式切换 =====

const isFullscreen = computed(() => props.displayMode === 'fullscreen')
const isDrawer = computed(() => props.displayMode === 'drawer')

// 当前可切换的目标模式列表（排除当前模式）
const availableModes = computed(() => props.displayModes.filter(m => m !== props.displayMode))

// 模式标签 + 图标映射
const modeLabelMap: Record<DisplayMode, string> = {
  modal: '弹窗',
  drawer: '抽屉',
  fullscreen: '全屏',
}
const modeIconMap: Record<DisplayMode, typeof ExpandOutlined> = {
  modal: ColumnWidthOutlined,
  drawer: ColumnWidthOutlined,
  fullscreen: ExpandOutlined,
}

// 是否显示切换入口（允许切换 且 至少有一个目标模式）
const showSwitchDisplay = computed(() => props.allowSwitchDisplay && availableModes.value.length > 0)

function handleSwitchMode(target: DisplayMode) {
  if (target !== props.displayMode) {
    emit('displayModeChange', target)
  }
}

// ===== 事件处理 =====

async function handleOk() {
  try {
    await innerFormRef.value?.validate()
    emit('ok')
  }
  catch {
    // 校验失败，a-form 自动显示错误提示
  }
}

function handleCancel() {
  // 清除校验错误提示（不清除表单数据，数据由 Composable 的 closeModal 负责）
  innerFormRef.value?.resetFields()
  emit('cancel')
}
</script>

<template>
  <!-- ===== 全屏模式 ===== -->
  <a-modal
    v-if="isFullscreen"
    ref="modalRef"
    :open="open"
    title=""
    width="100vw"
    wrap-class-name="os-modal-form-fullscreen"
    :confirm-loading="loading"
    :footer="showFooter ? undefined : null"
    :mask="false"
    :mask-closable="false"
    :keyboard="keyboard"
    :destroy-on-close="destroyOnClose"
    :closable="false"
    @cancel="handleCancel"
  >
    <!-- 全屏顶部栏（自定义标题，不使用 antd 原生标题栏） -->
    <div class="os-modal-form-header">
      <span class="os-modal-form-title">{{ title }}</span>
      <div class="os-modal-form-toolbar">
        <slot name="toolbar" />
        <template v-if="showSwitchDisplay">
          <!-- 只有 1 个目标模式：直接按钮 -->
          <a-tooltip v-if="availableModes.length === 1" :title="`切换为${modeLabelMap[availableModes[0]]}`">
            <a-button type="text" size="small" @click="handleSwitchMode(availableModes[0])">
              <component :is="modeIconMap[availableModes[0]]" />
            </a-button>
          </a-tooltip>
          <!-- 多个目标模式：下拉菜单 -->
          <a-dropdown v-else :trigger="['click']">
            <a-button type="text" size="small" aria-label="切换显示方式">
              <SwapOutlined />
            </a-button>
            <template #overlay>
              <a-menu @click="({ key }: any) => handleSwitchMode(key as DisplayMode)">
                <a-menu-item v-for="mode in availableModes" :key="mode">
                  <component :is="modeIconMap[mode]" style="margin-right: 6px" />
                  {{ modeLabelMap[mode] }}
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <a-button type="text" size="small" @click="handleCancel">
          <CloseOutlined />
        </a-button>
      </div>
    </div>
    <div v-if="!wrapForm" class="os-modal-form-body"><slot name="formItems" :form-data="formData" /></div>
    <a-form
      v-else
      ref="innerFormRef"
      :model="formData"
      :layout="layout"
      :disabled="disabled"
      :rules="rules"
      :label-col="labelCol"
      :wrapper-col="wrapperCol"
      class="os-modal-form-body"
    >
      <slot name="formItems" :form-data="formData" />
    </a-form>
    <!-- 全屏底部按钮 -->
    <template #footer v-if="showFooter">
      <slot name="footer" :loading="loading">
        <a-button @click="handleCancel">{{ cancelText }}</a-button>
        <a-button type="primary" :loading="loading" @click="handleOk">
          {{ okText }}
        </a-button>
      </slot>
    </template>
  </a-modal>

  <!-- ===== 抽屉模式 ===== -->
  <a-drawer
    v-else-if="isDrawer"
    ref="drawerRef"
    :open="open"
    :title="title"
    :width="drawerMaximized ? '100vw' : currentWidth"
    class-name="os-modal-form-drawer"
    :mask="mask"
    :mask-closable="maskClosable"
    :keyboard="keyboard"
    :destroy-on-close="destroyOnClose"
    placement="right"
    @close="handleCancel"
  >
    <!-- 右上角工具栏（插入到 Drawer title 旁边） -->
    <template #extra>
      <div class="os-modal-form-toolbar">
        <slot name="toolbar" />
        <a-button
          v-if="maximizable"
          type="text"
          size="small"
          :aria-label="drawerMaximized ? '还原抽屉' : '最大化抽屉'"
          :title="drawerMaximized ? '还原抽屉' : '最大化抽屉'"
          :aria-pressed="drawerMaximized"
          @click="drawerMaximized = !drawerMaximized"
        >
          <CompressOutlined v-if="drawerMaximized" />
          <ExpandOutlined v-else />
        </a-button>
        <template v-if="showSwitchDisplay">
          <!-- 只有 1 个目标模式：直接按钮 -->
          <a-tooltip v-if="availableModes.length === 1" :title="`切换为${modeLabelMap[availableModes[0]]}`">
            <a-button type="text" size="small" @click="handleSwitchMode(availableModes[0])">
              <component :is="modeIconMap[availableModes[0]]" />
            </a-button>
          </a-tooltip>
          <!-- 多个目标模式：下拉菜单 -->
          <a-dropdown v-else :trigger="['click']">
            <a-button type="text" size="small" aria-label="切换显示方式">
              <SwapOutlined />
            </a-button>
            <template #overlay>
              <a-menu @click="({ key }: any) => handleSwitchMode(key as DisplayMode)">
                <a-menu-item v-for="mode in availableModes" :key="mode">
                  <component :is="modeIconMap[mode]" style="margin-right: 6px" />
                  {{ modeLabelMap[mode] }}
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
      </div>
    </template>
    <div v-if="!wrapForm"><slot name="formItems" :form-data="formData" /></div>
    <a-form
      v-else
      ref="innerFormRef"
      :model="formData"
      :layout="layout"
      :disabled="disabled"
      :rules="rules"
      :label-col="labelCol"
      :wrapper-col="wrapperCol"
    >
      <slot name="formItems" :form-data="formData" />
    </a-form>
    <!-- 抽屉底部按钮 -->
    <template #footer v-if="showFooter">
      <div class="os-modal-form-footer-inner">
        <slot name="footer" :loading="loading">
          <a-button @click="handleCancel">{{ cancelText }}</a-button>
          <a-button type="primary" :loading="loading" @click="handleOk">
            {{ okText }}
          </a-button>
        </slot>
      </div>
    </template>
    <!-- 抽屉左侧拖拽手柄 -->
    <div
      v-if="resizable && !drawerMaximized"
      class="os-modal-form-resize-handle-left"
      @mousedown="startResize($event, 'left')"
    />
  </a-drawer>

  <!-- ===== 弹窗模式（默认） ===== -->
  <a-modal
    v-else
    ref="modalRef"
    wrap-class-name="os-scroll-modal"
    :open="open"
    :width="currentWidth"
    :confirm-loading="loading"
    :footer="showFooter ? undefined : null"
    :mask="mask"
    :mask-closable="maskClosable"
    :keyboard="keyboard"
    :destroy-on-close="destroyOnClose"
    :closable="false"
    :style="{
      '--os-modal-height': currentHeight ? `${currentHeight}px` : undefined,
    }"
    @ok="handleOk"
    @cancel="handleCancel"
  >
    <!-- 标题栏：完全自定义，去掉原生 close 按钮避免遮挡工具栏 -->
    <template #title>
      <div class="os-modal-form-title-bar">
        <span class="os-modal-form-modal-title">{{ title }}</span>
        <div class="os-modal-form-toolbar">
          <slot name="toolbar" />
          <template v-if="showSwitchDisplay">
            <!-- 只有 1 个目标模式：直接按钮 -->
            <a-tooltip v-if="availableModes.length === 1" :title="`切换为${modeLabelMap[availableModes[0]]}`">
              <a-button type="text" size="small" @click="handleSwitchMode(availableModes[0])">
                <component :is="modeIconMap[availableModes[0]]" />
              </a-button>
            </a-tooltip>
            <!-- 多个目标模式：下拉菜单 -->
            <a-dropdown v-else :trigger="['click']">
              <a-button type="text" size="small" aria-label="切换显示方式">
                <SwapOutlined />
              </a-button>
              <template #overlay>
                <a-menu @click="({ key }: any) => handleSwitchMode(key as DisplayMode)">
                  <a-menu-item v-for="mode in availableModes" :key="mode">
                    <component :is="modeIconMap[mode]" style="margin-right: 6px" />
                    {{ modeLabelMap[mode] }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </template>
          <!-- 自定义关闭按钮（替代原生 X，避免绝对定位遮挡工具栏） -->
          <a-button type="text" size="small" @click="handleCancel">
            <CloseOutlined />
          </a-button>
        </div>
      </div>
    </template>
    <div v-if="!wrapForm"><slot name="formItems" :form-data="formData" /></div>
    <a-form
      v-else
      ref="innerFormRef"
      :model="formData"
      :layout="layout"
      :disabled="disabled"
      :rules="rules"
      :label-col="labelCol"
      :wrapper-col="wrapperCol"
    >
      <slot name="formItems" :form-data="formData" />
    </a-form>
    <!-- 底部按钮 -->
    <template #footer v-if="showFooter">
      <slot name="footer" :loading="loading">
        <a-button @click="handleCancel">{{ cancelText }}</a-button>
        <a-button type="primary" :loading="loading" @click="handleOk">
          {{ okText }}
        </a-button>
      </slot>
    </template>
    <!-- 弹窗右下角拖拽手柄 -->
    <div v-if="resizable" class="os-modal-form-resize-handle" @mousedown="startResize($event, 'bottom-right')" />
  </a-modal>
</template>

<style scoped>
/* 工具栏 */
.os-modal-form-toolbar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 12px;
}

/* 弹窗模式标题栏：flex 布局，标题左对齐，工具栏右对齐 */
.os-modal-form-title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* 宽度需要填充到 ant-modal-header 的 padding 右边界，同时为 toolbar 留足空间 */
  width: calc(100% + 16px);
  margin-right: -16px;
}
.os-modal-form-modal-title {
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

/* 全屏模式 */
.os-modal-form-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}
.os-modal-form-title {
  font-size: 16px;
  font-weight: 500;
}
.os-modal-form-body {
  padding: 24px 0;
  overflow-y: auto;
  flex: 1;
}

/* 拖拽手柄 - 弹窗右下角 */
.os-modal-form-resize-handle {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16px;
  height: 16px;
  cursor: nwse-resize;
  z-index: 100;
}
.os-modal-form-resize-handle::after {
  content: '';
  position: absolute;
  right: 4px;
  bottom: 4px;
  width: 8px;
  height: 8px;
  border-right: 2px solid #bfbfbf;
  border-bottom: 2px solid #bfbfbf;
}
.os-modal-form-resize-handle:hover::after {
  border-color: var(--brand);
}

/* 拖拽手柄 - 抽屉左侧 */
.os-modal-form-resize-handle-left {
  position: absolute;
  left: 0;
  top: 0;
  width: 4px;
  height: 100%;
  cursor: ew-resize;
  z-index: 100;
}
.os-modal-form-resize-handle-left:hover {
  background: var(--brand);
  opacity: 0.3;
}

/* 抽屉底部按钮右对齐（与 Modal 保持一致） */
.os-modal-form-footer-inner {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 全屏模式全局样式覆盖 */
:global(.os-modal-form-fullscreen .ant-modal) {
  max-width: 100vw;
  top: 0;
  padding: 0;
  margin: 0;
}
:global(.os-modal-form-fullscreen .ant-modal-content) {
  height: 100vh;
  border-radius: 0;
  display: flex;
  flex-direction: column;
}
:global(.os-modal-form-fullscreen .ant-modal-body) {
  flex: 1;
  overflow: auto;
}
</style>
