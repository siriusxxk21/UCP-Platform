<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useEditor, EditorContent, Extension } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import Link from '@tiptap/extension-link'
import TextAlign from '@tiptap/extension-text-align'
import Underline from '@tiptap/extension-underline'
import { TextStyle } from '@tiptap/extension-text-style'
import Color from '@tiptap/extension-color'
import Highlight from '@tiptap/extension-highlight'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { Table } from '@tiptap/extension-table'
import { TableRow } from '@tiptap/extension-table-row'
import { TableCell } from '@tiptap/extension-table-cell'
import { TableHeader } from '@tiptap/extension-table-header'
import HorizontalRule from '@tiptap/extension-horizontal-rule'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

// 图标导入
import {
  BoldOutlined,
  ItalicOutlined,
  UnderlineOutlined,
  StrikethroughOutlined,
  OrderedListOutlined,
  UnorderedListOutlined,
  CheckSquareOutlined,
  AlignLeftOutlined,
  AlignCenterOutlined,
  AlignRightOutlined,
  LinkOutlined,
  PictureOutlined,
  RedoOutlined,
  UndoOutlined,
  HighlightOutlined,
  TableOutlined,
  MinusOutlined,
  FontSizeOutlined,
  BgColorsOutlined,
  CodeOutlined,
  ContainerOutlined,
  DownOutlined
} from '@ant-design/icons-vue'

// 自定义 FontSize 扩展
const FontSize = Extension.create({
  name: 'fontSize',
  addOptions() {
    return {
      types: ['textStyle']
    }
  },
  addGlobalAttributes() {
    return [
      {
        types: this.options.types,
        attributes: {
          fontSize: {
            default: null,
            parseHTML: element => element.style.fontSize?.replace(/['"]+/g, ''),
            renderHTML: attributes => {
              if (!attributes.fontSize) return {}
              return { style: `font-size: ${attributes.fontSize}` }
            }
          }
        }
      }
    ]
  },
  addCommands() {
    return {
      setFontSize:
        (fontSize: string) =>
        ({ chain }) => {
          return chain().setMark('textStyle', { fontSize }).run()
        },
      unsetFontSize:
        () =>
        ({ chain }) => {
          return chain().setMark('textStyle', { fontSize: null }).removeEmptyTextStyle().run()
        }
    }
  }
})

const props = defineProps<{
  modelValue: string
  placeholder?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const userStore = useUserStore()
const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

// 弹出面板状态
const showFontSizePanel = ref(false)
const showColorPanel = ref(false)
const fontSizePanelRef = ref<HTMLElement | null>(null)
const colorPanelRef = ref<HTMLElement | null>(null)

// 图片上传处理
const handleImageUpload = async (file: File): Promise<string | null> => {
  const formData = new FormData()
  formData.append('file', file)

  try {
    const response = await fetch(`${baseUrl}/infra/file/upload`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${userStore.token}`
      },
      body: formData
    })

    const res = await response.json()
    if (res.code === 0) {
      return `${baseUrl}/infra/file/${res.data.configId}/get/${res.data.path}`
    } else {
      message.error('图片上传失败')
      return null
    }
  } catch (err: any) {
    message.error(`图片上传失败: ${err.message || '未知错误'}`)
    return null
  }
}

// 字号选项
const fontSizes = [
  { label: '12px', value: '12px' },
  { label: '14px', value: '14px' },
  { label: '16px', value: '16px' },
  { label: '18px', value: '18px' },
  { label: '20px', value: '20px' },
  { label: '24px', value: '24px' },
  { label: '28px', value: '28px' },
  { label: '32px', value: '32px' }
]

// 颜色选项
const textColors = [
  { label: '默认', value: '' },
  { label: '深红', value: '#cf1322' },
  { label: '红色', value: '#ff4d4f' },
  { label: '橙色', value: '#fa8c16' },
  { label: '金色', value: '#faad14' },
  { label: '黄色', value: '#fadb14' },
  { label: '绿色', value: '#52c41a' },
  { label: '翠绿', value: '#237804' },
  { label: '青色', value: '#13c2c2' },
  { label: '蓝色', value: '#1677ff' },
  { label: '深蓝', value: '#003eb3' },
  { label: '紫色', value: '#722ed1' },
  { label: '品红', value: '#eb2f96' },
  { label: '粉色', value: '#ff85c0' },
  { label: '灰色', value: '#8c8c8c' },
  { label: '深灰', value: '#434343' }
]

// 创建编辑器
const editor = useEditor({
  content: props.modelValue,
  editable: !props.disabled,
  extensions: [
    StarterKit.configure({
      // 显式扩展保留现有配置，关闭 StarterKit 内同名扩展，避免重复插件与命令。
      link: false,
      underline: false,
      horizontalRule: false,
      heading: {
        levels: [1, 2, 3]
      }
    }),
    Image.configure({
      inline: false,
      allowBase64: true,
      HTMLAttributes: {
        class: 'tiptap-image'
      }
    }),
    Placeholder.configure({
      placeholder: props.placeholder || '请输入消息内容...'
    }),
    Link.configure({
      openOnClick: false,
      HTMLAttributes: {
        class: 'tiptap-link'
      }
    }),
    TextAlign.configure({
      types: ['heading', 'paragraph']
    }),
    Underline,
    TextStyle,
    Color,
    Highlight.configure({
      multicolor: true
    }),
    TaskList,
    TaskItem.configure({
      nested: true
    }),
    Table.configure({
      resizable: true,
      HTMLAttributes: {
        class: 'tiptap-table'
      }
    }),
    TableRow,
    TableCell,
    TableHeader,
    HorizontalRule,
    FontSize
  ],
  editorProps: {
    attributes: {
      class: 'tiptap-content',
      role: 'textbox',
      'aria-label': props.placeholder || '富文本内容',
      'aria-multiline': 'true'
    },
    handlePaste: (view, event) => {
      if (props.disabled) return false
      const items = event.clipboardData?.items
      if (!items) return false

      for (const item of Array.from(items)) {
        if (item.type.startsWith('image/')) {
          event.preventDefault()
          const file = item.getAsFile()
          if (file) {
            handleImageUpload(file).then(url => {
              if (url) {
                editor.value?.chain().focus().setImage({ src: url }).run()
              }
            })
          }
          return true
        }
      }
      return false
    },
    handleDrop: (view, event) => {
      if (props.disabled) return false
      const files = event.dataTransfer?.files
      if (!files || files.length === 0) return false

      const file = files[0]
      if (file.type.startsWith('image/')) {
        event.preventDefault()
        handleImageUpload(file).then(url => {
          if (url) {
            editor.value?.chain().focus().setImage({ src: url }).run()
          }
        })
        return true
      }
      return false
    }
  },
  onUpdate: ({ editor: editorInstance }) => {
    emit('update:modelValue', editorInstance.getHTML())
  }
})

// 监听外部内容变化
watch(
  () => props.modelValue,
  val => {
    if (editor.value && val !== editor.value.getHTML()) {
      editor.value.commands.setContent(val, { emitUpdate: false })
    }
  }
)

// 监听 disabled 变化，动态切换编辑器的可编辑状态
watch(
  () => props.disabled,
  val => {
    if (editor.value) {
      editor.value.setEditable(!val)
    }
  }
)

// 工具栏操作
const setLink = () => {
  const previousUrl = editor.value?.getAttributes('link').href
  const url = window.prompt('请输入链接 URL', previousUrl)

  if (url === null) return

  if (url === '') {
    editor.value?.chain().focus().extendMarkRange('link').unsetLink().run()
    return
  }

  editor.value?.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
}

const addImage = async () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (file) {
      if (file.size > 5 * 1024 * 1024) {
        message.error('图片大小不能超过 5MB')
        return
      }
      const url = await handleImageUpload(file)
      if (url) {
        editor.value?.chain().focus().setImage({ src: url }).run()
      }
    }
  }
  input.click()
}

// 设置字号
const setFontSize = (size: string) => {
  if (size) {
    editor.value?.chain().focus().setFontSize(size).run()
  } else {
    editor.value?.chain().focus().unsetFontSize().run()
  }
  showFontSizePanel.value = false
}

// 设置文字颜色
const setTextColor = (color: string) => {
  if (color) {
    editor.value?.chain().focus().setColor(color).run()
  } else {
    editor.value?.chain().focus().unsetColor().run()
  }
  showColorPanel.value = false
}

// 点击外部关闭面板
const handleClickOutside = (event: MouseEvent) => {
  const target = event.target as HTMLElement
  if (fontSizePanelRef.value && !fontSizePanelRef.value.contains(target)) {
    showFontSizePanel.value = false
  }
  if (colorPanelRef.value && !colorPanelRef.value.contains(target)) {
    showColorPanel.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

// 插入表格
const insertTable = () => {
  editor.value?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
}

// 插入水平线
const insertHorizontalRule = () => {
  editor.value?.chain().focus().setHorizontalRule().run()
}

// 插入代码块
const insertCodeBlock = () => {
  editor.value?.chain().focus().toggleCodeBlock().run()
}

// 插入引用
const insertBlockquote = () => {
  editor.value?.chain().focus().toggleBlockquote().run()
}

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
  editor.value?.destroy()
})
</script>

<template>
  <div class="tiptap-editor" :class="{ 'is-disabled': disabled }">
    <!-- 工具栏 -->
    <div v-if="editor" class="toolbar">
      <!-- 文字样式 -->
      <div class="toolbar-group">
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('bold') }"
          :disabled="disabled"
          title="粗体"
          @click="editor.chain().focus().toggleBold().run()"
        >
          <BoldOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('italic') }"
          :disabled="disabled"
          title="斜体"
          @click="editor.chain().focus().toggleItalic().run()"
        >
          <ItalicOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('underline') }"
          :disabled="disabled"
          title="下划线"
          @click="editor.chain().focus().toggleUnderline().run()"
        >
          <UnderlineOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('strike') }"
          :disabled="disabled"
          title="删除线"
          @click="editor.chain().focus().toggleStrike().run()"
        >
          <StrikethroughOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('highlight') }"
          :disabled="disabled"
          title="高亮"
          @click="editor.chain().focus().toggleHighlight().run()"
        >
          <HighlightOutlined />
        </button>

        <!-- 字号选择 -->
        <div class="toolbar-popup-wrapper" ref="fontSizePanelRef">
          <button
            type="button"
            class="toolbar-btn-with-arrow"
            :disabled="disabled"
            title="字号"
            @click.stop="showFontSizePanel = !showFontSizePanel"
          >
            <FontSizeOutlined />
            <DownOutlined class="arrow-icon" />
          </button>
          <div v-if="showFontSizePanel" class="toolbar-popup">
            <div v-for="size in fontSizes" :key="size.value" class="popup-item" @click="setFontSize(size.value)">
              {{ size.label }}
            </div>
            <div class="popup-item popup-item-reset" @click="setFontSize('')">重置</div>
          </div>
        </div>

        <!-- 字体颜色 -->
        <div class="toolbar-popup-wrapper" ref="colorPanelRef">
          <button
            type="button"
            class="toolbar-btn-with-arrow"
            :disabled="disabled"
            title="字体颜色"
            @click.stop="showColorPanel = !showColorPanel"
          >
            <BgColorsOutlined />
            <DownOutlined class="arrow-icon" />
          </button>
          <div v-if="showColorPanel" class="toolbar-popup color-popup">
            <div
              v-for="color in textColors"
              :key="color.value"
              class="color-item"
              :title="color.label"
              @click="setTextColor(color.value)"
            >
              <div class="color-preview" :style="{ backgroundColor: color.value || '#333' }" />
            </div>
          </div>
        </div>
      </div>

      <div class="toolbar-divider" />

      <!-- 结构：标题 + 列表 -->
      <div class="toolbar-group">
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('heading', { level: 1 }) }"
          :disabled="disabled"
          title="标题 1"
          @click="editor.chain().focus().toggleHeading({ level: 1 }).run()"
        >
          H1
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('heading', { level: 2 }) }"
          :disabled="disabled"
          title="标题 2"
          @click="editor.chain().focus().toggleHeading({ level: 2 }).run()"
        >
          H2
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('heading', { level: 3 }) }"
          :disabled="disabled"
          title="标题 3"
          @click="editor.chain().focus().toggleHeading({ level: 3 }).run()"
        >
          H3
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('bulletList') }"
          :disabled="disabled"
          title="无序列表"
          @click="editor.chain().focus().toggleBulletList().run()"
        >
          <UnorderedListOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('orderedList') }"
          :disabled="disabled"
          title="有序列表"
          @click="editor.chain().focus().toggleOrderedList().run()"
        >
          <OrderedListOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('taskList') }"
          :disabled="disabled"
          title="任务列表"
          @click="editor.chain().focus().toggleTaskList().run()"
        >
          <CheckSquareOutlined />
        </button>
      </div>

      <div class="toolbar-divider" />

      <!-- 排版：对齐 + 插入 -->
      <div class="toolbar-group">
        <button
          type="button"
          :class="{ 'is-active': editor.isActive({ textAlign: 'left' }) }"
          :disabled="disabled"
          title="左对齐"
          @click="editor.chain().focus().setTextAlign('left').run()"
        >
          <AlignLeftOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive({ textAlign: 'center' }) }"
          :disabled="disabled"
          title="居中对齐"
          @click="editor.chain().focus().setTextAlign('center').run()"
        >
          <AlignCenterOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive({ textAlign: 'right' }) }"
          :disabled="disabled"
          title="右对齐"
          @click="editor.chain().focus().setTextAlign('right').run()"
        >
          <AlignRightOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('link') }"
          :disabled="disabled"
          title="插入链接"
          @click="setLink"
        >
          <LinkOutlined />
        </button>
        <button type="button" :disabled="disabled" title="插入图片" @click="addImage">
          <PictureOutlined />
        </button>
        <button type="button" :disabled="disabled" title="插入表格" @click="insertTable">
          <TableOutlined />
        </button>
        <button type="button" :disabled="disabled" title="插入水平线" @click="insertHorizontalRule">
          <MinusOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('codeBlock') }"
          :disabled="disabled"
          title="代码块"
          @click="insertCodeBlock"
        >
          <CodeOutlined />
        </button>
        <button
          type="button"
          :class="{ 'is-active': editor.isActive('blockquote') }"
          :disabled="disabled"
          title="引用"
          @click="insertBlockquote"
        >
          <ContainerOutlined />
        </button>
      </div>

      <div class="toolbar-spacer" />

      <div class="toolbar-group">
        <button
          type="button"
          :disabled="disabled || !editor.can().undo()"
          title="撤销"
          @click="editor.chain().focus().undo().run()"
        >
          <UndoOutlined />
        </button>
        <button
          type="button"
          :disabled="disabled || !editor.can().redo()"
          title="重做"
          @click="editor.chain().focus().redo().run()"
        >
          <RedoOutlined />
        </button>
      </div>
    </div>

    <!-- 编辑器内容 -->
    <EditorContent :editor="editor" class="editor-content" />
  </div>
</template>

<style scoped>
.tiptap-editor {
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 520px;
  transition: border-color 0.2s;
}

.tiptap-editor:hover {
  border-color: #4096ff;
}

.tiptap-editor:focus-within {
  border-color: #4096ff;
  box-shadow: 0 0 0 2px rgba(64, 150, 255, 0.1);
}

/* 禁用态：只读展示，去除交互边框效果 */
.tiptap-editor.is-disabled {
  background: #fafafa;
}

.tiptap-editor.is-disabled:hover,
.tiptap-editor.is-disabled:focus-within {
  border-color: #d9d9d9;
  box-shadow: none;
  cursor: not-allowed;
}

.tiptap-editor.is-disabled :deep(.tiptap-content) {
  cursor: not-allowed;
  user-select: text;
}

/* 工具栏样式 */
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #d9d9d9;
  min-height: 40px;
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 2px;
}

.toolbar-divider {
  width: 1px;
  height: 20px;
  background: #d9d9d9;
  margin: 0 6px;
  flex-shrink: 0;
}

/* 工具栏弹性占位符 */
.toolbar-spacer {
  flex: 1;
}

.toolbar button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  color: #595959;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.2s;
}

.toolbar button:hover {
  background: #e6e6e6;
  color: #262626;
}

.toolbar button.is-active {
  background: #e6f4ff;
  color: #1677ff;
  border-color: #91caff;
}

.toolbar button:disabled {
  color: #bfbfbf;
  cursor: not-allowed;
}

.toolbar button:disabled:hover {
  background: transparent;
}

/* 编辑器内容区域 */
.editor-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* Tiptap 编辑器内部样式 */
.editor-content :deep(.tiptap-content) {
  padding: 16px;
  min-height: 100%;
  outline: none;
}

.editor-content :deep(.tiptap-content) > *:first-child {
  margin-top: 0;
}

.editor-content :deep(.tiptap-content) p {
  margin: 0 0 8px 0;
  line-height: 1.6;
}

.editor-content :deep(.tiptap-content) h1 {
  font-size: 1.75em;
  margin: 16px 0 8px 0;
  font-weight: 600;
  line-height: 1.3;
}

.editor-content :deep(.tiptap-content) h2 {
  font-size: 1.5em;
  margin: 14px 0 8px 0;
  font-weight: 600;
  line-height: 1.3;
}

.editor-content :deep(.tiptap-content) h3 {
  font-size: 1.25em;
  margin: 12px 0 8px 0;
  font-weight: 600;
  line-height: 1.3;
}

.editor-content :deep(.tiptap-content) ul,
.editor-content :deep(.tiptap-content) ol {
  padding-left: 24px;
  margin: 8px 0;
}

.editor-content :deep(.tiptap-content) li {
  margin: 4px 0;
  line-height: 1.6;
}

.editor-content :deep(.tiptap-content) blockquote {
  border-left: 3px solid #d9d9d9;
  padding-left: 12px;
  margin: 8px 0;
  color: #595959;
}

.editor-content :deep(.tiptap-content) img,
.editor-content :deep(.tiptap-content) .tiptap-image {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 8px 0;
  cursor: pointer;
  transition: opacity 0.2s;
}
.editor-content :deep(.tiptap-content) img:hover,
.editor-content :deep(.tiptap-content) .tiptap-image:hover {
  opacity: 0.85;
}

.editor-content :deep(.tiptap-content) a {
  color: #1677ff;
  text-decoration: none;
}

.editor-content :deep(.tiptap-content) a:hover {
  text-decoration: underline;
}

.editor-content :deep(.tiptap-content) mark {
  border-radius: 2px;
  padding: 0 2px;
}

/* 任务列表样式 */
.editor-content :deep(.tiptap-content) ul[data-type='taskList'] {
  list-style: none;
  padding-left: 0;
}

.editor-content :deep(.tiptap-content) ul[data-type='taskList'] li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 0;
}

.editor-content :deep(.tiptap-content) ul[data-type='taskList'] li > label {
  flex-shrink: 0;
  margin-top: 4px;
}

.editor-content :deep(.tiptap-content) ul[data-type='taskList'] li > label input[type='checkbox'] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.editor-content :deep(.tiptap-content) ul[data-type='taskList'] li > div {
  flex: 1;
}

/* Placeholder 样式 */
.editor-content :deep(.tiptap-content) p.is-editor-empty:first-child::before {
  color: #bfbfbf;
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}

/* 选中文本高亮 */
.editor-content :deep(.tiptap-content) ::selection {
  background: #b5d7ff;
}

/* 表格样式 */
.editor-content :deep(.tiptap-content) table {
  border-collapse: collapse;
  margin: 16px 0;
  width: 100%;
  overflow: hidden;
}

.editor-content :deep(.tiptap-content) table td,
.editor-content :deep(.tiptap-content) table th {
  border: 1px solid #d9d9d9;
  padding: 8px 12px;
  min-width: 80px;
  vertical-align: top;
}

.editor-content :deep(.tiptap-content) table th {
  background: #fafafa;
  font-weight: 600;
  text-align: left;
}

.editor-content :deep(.tiptap-content) table tr:hover td {
  background: #f5f5f5;
}

/* 代码块样式 */
.editor-content :deep(.tiptap-content) pre {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 16px;
  margin: 12px 0;
  overflow-x: auto;
}

.editor-content :deep(.tiptap-content) pre code {
  background: none;
  padding: 0;
  font-size: 14px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: #24292e;
}

/* 行内代码样式 */
.editor-content :deep(.tiptap-content) code {
  background: #f0f0f0;
  border-radius: 3px;
  padding: 2px 6px;
  font-size: 0.9em;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: #e83e8c;
}

/* 引用块样式 */
.editor-content :deep(.tiptap-content) blockquote {
  border-left: 4px solid #1677ff;
  padding-left: 16px;
  margin: 12px 0;
  color: #595959;
  background: #f6f8fa;
  padding: 12px 16px;
  border-radius: 0 6px 6px 0;
}

/* 水平线样式 */
.editor-content :deep(.tiptap-content) hr {
  border: none;
  border-top: 2px solid #e8e8e8;
  margin: 24px 0;
}

/* 工具栏带箭头按钮样式 */
.toolbar-btn-with-arrow {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  color: #595959;
  transition: all 0.2s;
}

.toolbar-btn-with-arrow:hover {
  background: #e6e6e6;
  color: #262626;
}

.toolbar-btn-with-arrow:disabled {
  color: #bfbfbf;
  cursor: not-allowed;
}

.toolbar-btn-with-arrow:disabled:hover {
  background: transparent;
  color: #bfbfbf;
}

.toolbar-btn-with-arrow .arrow-icon {
  font-size: 10px;
}

/* 弹出面板容器 */
.toolbar-popup-wrapper {
  position: relative;
}

/* 弹出面板样式 */
.toolbar-popup {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 4px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  padding: 4px 0;
  z-index: 100;
  min-width: 80px;
}

/* 弹出面板选项 */
.popup-item {
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  color: #333;
  transition: all 0.15s;
  white-space: nowrap;
}

.popup-item:hover {
  background: #f5f5f5;
  color: #1677ff;
}

.popup-item-reset {
  border-top: 1px solid #f0f0f0;
  color: #8c8c8c;
  margin-top: 4px;
  padding-top: 8px;
}

.popup-item-reset:hover {
  color: #ff4d4f;
}

/* 颜色弹出面板 */
.color-popup {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  padding: 8px;
  min-width: 120px;
}

/* 颜色选项 */
.color-item {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
  border: 2px solid transparent;
}

.color-item:hover {
  border-color: #1677ff;
  transform: scale(1.1);
}

.color-preview {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  border: 1px solid rgba(0, 0, 0, 0.1);
}
</style>
