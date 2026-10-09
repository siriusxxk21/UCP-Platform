<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ViewUpdate } from '@codemirror/view'
import { crosshairCursor, Decoration, drawSelection, dropCursor, EditorView, highlightActiveLine, highlightActiveLineGutter, highlightSpecialChars, keymap, lineNumbers, placeholder, rectangularSelection, ViewPlugin } from '@codemirror/view'
import { Compartment, EditorState, RangeSetBuilder } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { bracketMatching, defaultHighlightStyle, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { html } from '@codemirror/lang-html'
import { javascript } from '@codemirror/lang-javascript'
import { java } from '@codemirror/lang-java'
import { sql } from '@codemirror/lang-sql'
import { json } from '@codemirror/lang-json'
import { xml } from '@codemirror/lang-xml'
import { oneDark } from '@codemirror/theme-one-dark'

interface Props {
  modelValue: string
  language?: 'html' | 'javascript' | 'java' | 'xml' | 'sql' | 'json'
  theme?: 'light' | 'dark'
  readonly?: boolean
  height?: string
  highlightLines?: number[] // 要高亮的行号列表
  placeholder?: string // 占位提示文本（内容为空时显示）
}

const props = withDefaults(defineProps<Props>(), {
  language: 'html',
  theme: 'light',
  readonly: false,
  height: '400px',
  highlightLines: () => [],
  placeholder: '',
} as const)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorRef = ref<HTMLElement>()
let editorView: EditorView | null = null

// 语言配置
const languageConf = new Compartment()

// 占位符配置（可动态更新）
const placeholderConf = new Compartment()

/** 构建占位符扩展（空文本时返回空扩展，不显示） */
function buildPlaceholderExtension() {
  const text = props.placeholder?.trim()
  return text ? placeholder(text) : []
}

// 高亮行装饰
const highlightLineDecoration = Decoration.line({
  attributes: { class: 'cm-highlight-line' },
})

// 创建高亮行的插件
function createHighlightPlugin(highlightLines: number[]) {
  return ViewPlugin.fromClass(class {
    decorations: any

    constructor(view: EditorView) {
      this.decorations = this.buildDecorations(view, highlightLines)
    }

    update(update: ViewUpdate) {
      if (update.docChanged || update.viewportChanged) {
        this.decorations = this.buildDecorations(update.view, highlightLines)
      }
    }

    buildDecorations(view: EditorView, lines: number[]) {
      const builder = new RangeSetBuilder<Decoration>()
      for (const lineNum of lines) {
        const line = view.state.doc.line(lineNum)
        if (line) {
          builder.add(line.from, line.from, highlightLineDecoration)
        }
      }
      return builder.finish()
    }
  }, {
    decorations: v => v.decorations,
  })
}

// 获取语言扩展
function getLanguageExtension() {
  switch (props.language) {
    case 'html':
      return html()
    case 'xml':
      return xml()
    case 'javascript':
      return javascript()
    case 'java':
      return java()
    case 'sql':
      return sql()
    case 'json':
      return json()
    default:
      return html()
  }
}

// 获取主题扩展
function getThemeExtension() {
  const baseTheme = EditorView.theme({
    '&': {
      fontSize: '14px',
      fontFamily: '\'Consolas\', \'Monaco\', \'Courier New\', monospace',
    },
    '.cm-editor': {
      height: props.height,
    },
    '.cm-scroller': {
      overflow: 'auto',
    },
    '.cm-content': {
      padding: '12px 0',
    },
    '.cm-line': {
      padding: '0 12px',
    },
    '.cm-gutters': {
      backgroundColor: props.theme === 'dark' ? '#1e1e1e' : '#f5f5f5',
      borderRight: `1px solid ${props.theme === 'dark' ? '#333' : '#e0e0e0'}`,
      fontFamily: '\'Consolas\', \'Monaco\', \'Courier New\', monospace',
    },
  })

  if (props.theme === 'dark') {
    return [oneDark, baseTheme]
  }
  return [baseTheme]
}

// 创建编辑器
function createEditor() {
  if (!editorRef.value)
    return

  const extensions = [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    drawSelection(),
    dropCursor(),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    bracketMatching(),
    rectangularSelection(),
    crosshairCursor(),
    highlightActiveLine(),
    keymap.of([
      ...defaultKeymap,
      ...historyKeymap,
    ]),
    languageConf.of(getLanguageExtension()),
    placeholderConf.of(buildPlaceholderExtension()),
    EditorView.updateListener.of(update => {
      if (update.docChanged) {
        emit('update:modelValue', update.state.doc.toString())
      }
    }),
    EditorView.editable.of(!props.readonly),
    ...getThemeExtension(),
  ]

  // 如果有高亮行，添加高亮插件
  if (props.highlightLines && props.highlightLines.length > 0) {
    extensions.push(createHighlightPlugin(props.highlightLines))
  }

  const state = EditorState.create({
    doc: props.modelValue,
    extensions,
  })

  editorView = new EditorView({
    state,
    parent: editorRef.value,
  })
}

// 更新内容
function updateContent(content: string) {
  if (editorView && content !== editorView.state.doc.toString()) {
    const transaction = editorView.state.update({
      changes: {
        from: 0,
        to: editorView.state.doc.length,
        insert: content,
      },
    })
    editorView.dispatch(transaction)
  }
}

onMounted(() => {
  nextTick(() => {
    createEditor()
  })
})

onUnmounted(() => {
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
})

// 监听外部值变化
watch(() => props.modelValue, (newValue) => {
  nextTick(() => {
    updateContent(newValue)
  })
}, { immediate: true })

// 监听语言变化
watch(() => props.language, () => {
  if (editorView) {
    editorView.dispatch({
      effects: languageConf.reconfigure(getLanguageExtension()),
    })
  }
})

// 监听占位符变化
watch(() => props.placeholder, () => {
  if (editorView) {
    editorView.dispatch({
      effects: placeholderConf.reconfigure(buildPlaceholderExtension()),
    })
  }
})

// 监听主题变化
watch(() => props.theme, () => {
  if (editorView) {
    editorView.destroy()
    createEditor()
  }
})

// 监听只读状态
watch(() => props.readonly, () => {
  if (editorView) {
    editorView.destroy()
    createEditor()
  }
})
</script>

<template>
  <div ref="editorRef" class="code-editor" />
</template>

<style scoped>
.code-editor {
  height: v-bind(height);
}

.code-editor :deep(.cm-editor) {
  height: 100%;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
}

.code-editor :deep(.cm-scroller) {
  flex: 1;
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #c1c1c1 #f1f1f1;
}

.code-editor :deep(.cm-scroller::-webkit-scrollbar) {
  width: 10px;
  height: 10px;
}

.code-editor :deep(.cm-scroller::-webkit-scrollbar-track) {
  background: #f1f1f1;
  border-radius: 4px;
}

.code-editor :deep(.cm-scroller::-webkit-scrollbar-thumb) {
  background: #c1c1c1;
  border-radius: 4px;
}

.code-editor :deep(.cm-scroller::-webkit-scrollbar-thumb:hover) {
  background: #a8a8a8;
}

.code-editor :deep(.cm-focused) {
  outline: none;
}

/* 占位符样式 */
.code-editor :deep(.cm-placeholder) {
  color: #bfbfbf;
  font-style: italic;
}

/* 高亮行样式 */
:deep(.cm-highlight-line) {
  background-color: rgba(82, 196, 26, 0.15) !important;
}

:deep(.cm-highlight-line .cm-gutterElement) {
  background-color: rgba(82, 196, 26, 0.2) !important;
  border-left: 3px solid #52c41a;
}
</style>
