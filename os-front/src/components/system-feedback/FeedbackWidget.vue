<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  CameraOutlined,
  CloseOutlined,
  CommentOutlined,
  DeleteOutlined,
  EditOutlined,
  MinusOutlined,
  UploadOutlined
} from '@ant-design/icons-vue'
import { useUserStore } from '@/stores/user'
import { submitFeedback, type FeedbackType } from '@/api/system/feedback'
import ScreenshotEditor from './ScreenshotEditor.vue'
import { feedbackPath, MAX_IMAGES, validateImage } from './feedback'

declare const __APP_BUILD_INFO__: { commit: string }
const route = useRoute()
const user = useUserStore()
const visible = computed(() => !!user.token && !route.meta.public && !user.mustChangePassword)
const open = ref(false)
const busy = ref(false)
const capturing = ref(false)
const captureController = ref<AbortController>()
const error = ref('')
const submitted = ref(false)
const uploader = ref<HTMLInputElement>()
const titleInput = ref()
const editorSource = ref('')
const editingIndex = ref<number | null>(null)
const form = reactive({ type: 'BUG' as FeedbackType, title: '', description: '', pagePath: '', pageTitle: '' })
const images = ref<{ file: File; url: string }[]>([])
const dragDepth = ref(0)
const attachmentDisabled = computed(() => busy.value || images.value.length >= MAX_IMAGES)
const hasDraft = computed(() => !!form.title || !!form.description || images.value.length > 0)
let previousFocus: HTMLElement | null = null

function source() {
  form.pagePath = feedbackPath(route.fullPath)
  const heading = document.querySelector('.workspace-header h2, main h1, main h2')
  form.pageTitle = [String(route.meta.title || document.title), heading?.textContent?.trim()]
    .filter(Boolean)
    .join(' · ')
    .slice(0, 200)
}
async function show() {
  previousFocus = document.activeElement as HTMLElement
  if (!hasDraft.value) source()
  submitted.value = false
  error.value = ''
  open.value = true
  await nextTick()
  titleInput.value?.focus()
}
function minimize() {
  dragDepth.value = 0
  open.value = false
  previousFocus?.focus()
}
function clear() {
  dragDepth.value = 0
  images.value.forEach(item => URL.revokeObjectURL(item.url))
  images.value = []
  Object.assign(form, { type: 'BUG', title: '', description: '', pagePath: '', pageTitle: '' })
  error.value = ''
  submitted.value = false
}
function continueFeedback() {
  clear()
  source()
}
function addFiles(files: File[]) {
  if (busy.value || submitted.value) return
  for (const file of files) {
    const issue = validateImage(file, images.value.length)
    if (issue) {
      message.warning(issue)
      continue
    }
    images.value.push({ file, url: URL.createObjectURL(file) })
  }
}
function uploaded(event: Event) {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files || []))
  input.value = ''
}
function dragEnter(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files') || attachmentDisabled.value) return
  dragDepth.value += 1
}
function dragOver(event: DragEvent) {
  if (event.dataTransfer) event.dataTransfer.dropEffect = attachmentDisabled.value ? 'none' : 'copy'
}
function dropFiles(event: DragEvent) {
  dragDepth.value = 0
  if (editorSource.value) return
  addFiles(Array.from(event.dataTransfer?.files || []))
}
function imageSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
function paste(event: ClipboardEvent) {
  if (!open.value || editorSource.value || busy.value || submitted.value) return
  if (!(event.target instanceof Element) || !event.target.closest('.feedback-panel')) return
  const files = Array.from(event.clipboardData?.items || [])
    .filter(item => item.type.startsWith('image/'))
    .map(item => item.getAsFile())
    .filter((item): item is File => !!item)
  if (files.length) {
    event.preventDefault()
    addFiles(files)
  }
}
function remove(index: number) {
  URL.revokeObjectURL(images.value[index].url)
  images.value.splice(index, 1)
}
function edit(index: number) {
  editingIndex.value = index
  editorSource.value = images.value[index].url
}
function closeEditor() {
  if (editingIndex.value === null && editorSource.value) URL.revokeObjectURL(editorSource.value)
  editorSource.value = ''
  editingIndex.value = null
}
function confirmImage(file: File) {
  const issue = validateImage(file, editingIndex.value === null ? images.value.length : images.value.length - 1)
  if (issue) {
    message.warning(issue)
    return
  }
  if (editingIndex.value === null) addFiles([file])
  else {
    const index = editingIndex.value
    URL.revokeObjectURL(images.value[index].url)
    images.value[index] = { file, url: URL.createObjectURL(file) }
  }
  closeEditor()
}
async function capture() {
  if (images.value.length >= MAX_IMAGES) {
    message.warning('最多添加 3 张图片')
    return
  }
  if (capturing.value) return
  capturing.value = true
  error.value = ''
  const controller = new AbortController()
  captureController.value = controller
  try {
    await nextTick()
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    const { default: html2canvas } = await import('html2canvas')
    const canvas = await html2canvas(document.body, {
      width: window.innerWidth,
      height: window.innerHeight,
      x: window.scrollX,
      y: window.scrollY,
      scale: Math.min(window.devicePixelRatio, 1.5),
      useCORS: true,
      logging: false,
      ignoreElements: element => element.hasAttribute('data-feedback-overlay')
    })
    if (controller.signal.aborted) return
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'))
    if (!blob) throw new Error('截图失败，请上传或粘贴图片')
    if (controller.signal.aborted) return
    editingIndex.value = null
    editorSource.value = URL.createObjectURL(blob)
  } catch (cause) {
    if (controller.signal.aborted) return
    error.value = cause instanceof Error ? cause.message : '截图失败，请上传或粘贴图片'
  } finally {
    capturing.value = false
    captureController.value = undefined
  }
}
async function submit() {
  if (busy.value) return
  if (!form.title.trim() || !form.description.trim()) {
    error.value = '请填写标题和反馈描述'
    return
  }
  busy.value = true
  error.value = ''
  const submittingUser = user.userInfo?.id
  const data = new FormData()
  Object.entries(form).forEach(([key, value]) => data.append(key, value.trim()))
  data.append('buildCommit', __APP_BUILD_INFO__.commit)
  images.value.forEach(item => data.append('images', item.file))
  try {
    await submitFeedback(data)
    if (user.userInfo?.id !== submittingUser || !user.token) return
    clear()
    submitted.value = true
    window.dispatchEvent(new CustomEvent('system-feedback-created'))
  } catch {
    if (user.userInfo?.id !== submittingUser || !user.token) return
    error.value = '提交失败，填写内容和图片已保留，请重试。'
  } finally {
    busy.value = false
  }
}
function resetSession() {
  captureController.value?.abort()
  closeEditor()
  clear()
  open.value = false
}
watch(() => user.userInfo?.id, resetSession)
watch(
  () => user.token,
  token => {
    if (!token) resetSession()
  }
)
onMounted(() => document.addEventListener('paste', paste))
onBeforeUnmount(() => {
  document.removeEventListener('paste', paste)
  resetSession()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="feedback-root" data-feedback-overlay>
      <button v-if="!open && !capturing" class="feedback-launcher" aria-label="提交系统反馈" @click="show">
        <CommentOutlined />
        <span>反馈</span>
        <i v-if="hasDraft" class="draft-dot" />
      </button>
      <section
        v-if="open && !capturing"
        class="feedback-panel"
        role="dialog"
        aria-labelledby="feedback-title"
        @keydown.esc.stop="minimize"
      >
        <header class="feedback-header">
          <div>
            <h2 id="feedback-title">问题与需求反馈</h2>
            <p>记录遇到的问题，帮助系统持续改进</p>
          </div>
          <div class="feedback-window-actions">
            <a-button type="text" aria-label="最小化反馈框" @click="minimize"><MinusOutlined /></a-button>
            <a-button type="text" aria-label="关闭反馈框并保留草稿" @click="minimize"><CloseOutlined /></a-button>
          </div>
        </header>
        <div v-if="submitted" class="feedback-success" role="status">
          <a-result status="success" title="反馈已提交" sub-title="管理员会统一收集和处理，感谢你的反馈。" />
          <a-button @click="continueFeedback">继续反馈</a-button>
          <a-button type="primary" @click="minimize">完成</a-button>
        </div>
        <template v-else>
          <div
            class="feedback-body"
            @dragenter.prevent="dragEnter"
            @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)"
            @dragover.prevent="dragOver"
            @drop.prevent="dropFiles"
          >
            <a-alert v-if="error" :message="error" type="error" show-icon class="feedback-error" />
            <a-form layout="vertical" :model="form" :disabled="busy">
              <a-form-item label="反馈类型" required>
                <a-radio-group v-model:value="form.type" button-style="solid" aria-label="反馈类型">
                  <a-radio-button value="BUG">Bug / 问题</a-radio-button>
                  <a-radio-button value="REQUIREMENT">需求 / 建议</a-radio-button>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="标题" required>
                <a-input
                  ref="titleInput"
                  v-model:value="form.title"
                  aria-label="反馈标题"
                  :maxlength="100"
                  show-count
                  placeholder="一句话描述遇到的问题或建议"
                />
              </a-form-item>
              <a-form-item label="反馈描述" required>
                <a-textarea
                  v-model:value="form.description"
                  aria-label="反馈描述"
                  :rows="5"
                  :maxlength="2000"
                  show-count
                  :placeholder="
                    form.type === 'BUG'
                      ? '你进行了什么操作？实际出现了什么问题？期望的结果是什么？'
                      : '你希望增加或改进什么功能？在什么场景下使用？'
                  "
                />
              </a-form-item>
              <a-form-item class="feedback-attachment-field">
                <div class="feedback-attachment-heading">
                  <span id="feedback-attachment-label">
                    图片附件
                    <small>（选填）</small>
                  </span>
                  <span class="feedback-attachment-count" role="status" aria-live="polite">
                    {{ images.length }} / {{ MAX_IMAGES }} 张
                  </span>
                </div>
                <div
                  class="feedback-attachment-area"
                  :class="{ 'is-dragging': dragDepth > 0, 'is-disabled': attachmentDisabled }"
                  role="group"
                  aria-labelledby="feedback-attachment-label"
                >
                  <button
                    type="button"
                    class="feedback-upload-zone"
                    :disabled="attachmentDisabled"
                    :aria-label="images.length >= MAX_IMAGES ? '已达到图片数量上限' : '上传图片'"
                    aria-describedby="feedback-upload-hint"
                    @click="uploader?.click()"
                  >
                    <span class="feedback-upload-icon"><UploadOutlined /></span>
                    <span class="feedback-upload-copy">
                      <strong>
                        {{
                          images.length >= MAX_IMAGES
                            ? '已添加 3 张图片'
                            : dragDepth > 0
                              ? '松开鼠标，添加图片'
                              : '点击上传或拖入图片'
                        }}
                      </strong>
                      <span id="feedback-upload-hint">
                        {{
                          images.length >= MAX_IMAGES
                            ? '删除已有图片后可继续添加'
                            : 'PNG / JPG / WebP · 单张不超过 5 MB'
                        }}
                      </span>
                    </span>
                  </button>
                  <div class="feedback-image-actions">
                    <a-button size="small" type="text" :disabled="attachmentDisabled" @click="capture">
                      <CameraOutlined />
                      截取当前页面
                    </a-button>
                    <span class="feedback-paste-hint">也可在反馈框内粘贴图片</span>
                  </div>
                </div>
                <input
                  ref="uploader"
                  class="feedback-file-input"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  multiple
                  :disabled="attachmentDisabled"
                  tabindex="-1"
                  aria-hidden="true"
                  @change="uploaded"
                />
                <ul v-if="images.length" class="feedback-images" aria-label="已添加的图片">
                  <li v-for="(item, index) in images" :key="item.url" class="feedback-image">
                    <button
                      type="button"
                      class="feedback-image-preview"
                      :disabled="busy"
                      :aria-label="'编辑图片 ' + (index + 1)"
                      @click="edit(index)"
                    >
                      <img :src="item.url" :alt="item.file.name" />
                      <span class="feedback-image-edit">
                        <EditOutlined />
                        编辑
                      </span>
                    </button>
                    <div class="feedback-image-info">
                      <span class="feedback-image-name" :title="item.file.name">{{ item.file.name }}</span>
                      <span class="feedback-image-size">{{ imageSize(item.file.size) }}</span>
                    </div>
                    <a-button
                      class="feedback-image-remove"
                      type="text"
                      size="small"
                      :disabled="busy"
                      :aria-label="'删除图片 ' + (index + 1)"
                      :title="'删除 ' + item.file.name"
                      @click="remove(index)"
                    >
                      <DeleteOutlined />
                    </a-button>
                  </li>
                </ul>
                <p class="feedback-attachment-note">图片将在提交反馈时一并上传</p>
              </a-form-item>
            </a-form>
            <div class="feedback-source">
              <span>来源页面</span>
              <strong>{{ form.pageTitle }}</strong>
              <code>{{ form.pagePath }}</code>
              <small>来源信息随本次反馈提交，切换页面后仍保留。</small>
            </div>
          </div>
          <footer class="feedback-footer">
            <span>关闭后保留本次草稿</span>
            <a-button type="primary" :loading="busy" @click="submit">提交反馈</a-button>
          </footer>
        </template>
      </section>
      <div v-if="capturing" class="feedback-capturing" role="status">正在截图，请稍候…</div>
      <ScreenshotEditor v-if="editorSource" :source="editorSource" @confirm="confirmImage" @cancel="closeEditor" />
    </div>
  </Teleport>
</template>

<style scoped>
.feedback-root {
  position: relative;
  z-index: 12000;
}
.feedback-launcher {
  position: fixed;
  /* 入口占用屏幕边缘留白，避免覆盖弹窗中的字段和底部保存操作。 */
  right: 0;
  bottom: 96px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  width: 24px;
  height: 62px;
  justify-content: center;
  border: 1px solid #dedff4;
  border-radius: 10px 0 0 10px;
  color: #4338ca;
  background: #fff;
  box-shadow: 0 6px 24px #1e1b4b20;
  cursor: pointer;
}
.feedback-launcher .anticon {
  font-size: 18px;
}
.feedback-launcher span {
  font-size: 12px;
  writing-mode: vertical-rl;
}
.feedback-launcher:focus-visible {
  outline: 2px solid #4338ca;
  outline-offset: 2px;
}
.feedback-launcher:hover {
  background: #f5f3ff;
}
.draft-dot {
  position: absolute;
  right: 8px;
  top: 8px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f59e0b;
}
.feedback-panel {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: min(460px, calc(100vw - 24px));
  max-height: calc(100dvh - 48px);
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #1e293b;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  box-shadow: 0 20px 70px #0f172a30;
  overflow: hidden;
}
.feedback-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 20px 20px 16px;
  border-bottom: 1px solid #f0f0f0;
}
.feedback-header h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}
.feedback-header p {
  color: #64748b;
  font-size: 12px;
  margin: 5px 0 0;
}
.feedback-window-actions {
  display: flex;
}
.feedback-body {
  padding: 20px;
  overflow-y: auto;
  min-height: 0;
}
.feedback-error {
  margin-bottom: 16px;
}
.feedback-attachment-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.feedback-attachment-heading small {
  color: #64748b;
  font-size: 12px;
}
.feedback-attachment-count {
  padding: 1px 8px;
  border-radius: 4px;
  background: #f1f5f9;
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}
.feedback-attachment-area {
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  transition:
    border-color 0.15s,
    background-color 0.15s;
  overflow: hidden;
}
.feedback-attachment-area:not(.is-disabled):hover,
.feedback-attachment-area.is-dragging {
  border-color: #6366f1;
  background: #f5f3ff;
}
.feedback-upload-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  width: 100%;
  padding: 20px 12px 16px;
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.feedback-upload-zone:focus-visible {
  outline: 2px solid #6366f1;
  outline-offset: -3px;
  border-radius: 7px;
}
.feedback-upload-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border: 1px solid #e0e7ff;
  border-radius: 8px;
  background: #eef2ff;
  color: #4f46e5;
  font-size: 20px;
}
.feedback-upload-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.feedback-upload-copy strong {
  color: #4338ca;
  font-size: 13px;
  font-weight: 500;
}
.feedback-upload-copy > span,
.feedback-paste-hint {
  font-size: 12px;
  color: #64748b;
}
.feedback-image-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 8px;
  margin: 0 12px;
  padding: 8px 0;
  border-top: 1px solid #e2e8f0;
}
.feedback-upload-zone:disabled,
.feedback-image-preview:disabled {
  cursor: not-allowed;
}
.is-disabled .feedback-upload-icon {
  border-color: #e2e8f0;
  background: #f1f5f9;
  color: #94a3b8;
}
.is-disabled .feedback-upload-copy strong {
  color: #64748b;
}
.feedback-attachment-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: #64748b;
}
.feedback-panel .feedback-file-input {
  display: none;
}
.feedback-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 0;
  margin: 12px 0 0;
  list-style: none;
}
.feedback-image {
  position: relative;
  min-width: 0;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}
.feedback-image-preview {
  position: relative;
  display: block;
  padding: 0;
  width: 100%;
  height: 80px;
  border: 0;
  border-bottom: 1px solid #e2e8f0;
  background: #f1f5f9;
  cursor: pointer;
}
.feedback-image-preview:focus-visible {
  outline: 2px solid #6366f1;
  outline-offset: -2px;
}
.feedback-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.feedback-image-edit {
  position: absolute;
  left: 4px;
  bottom: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 1px 5px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  color: #475569;
  background: #fffffff2;
  font-size: 11px;
}
.feedback-image-info {
  display: flex;
  flex-direction: column;
  padding: 6px 8px;
  line-height: 18px;
}
.feedback-image-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #475569;
  font-size: 12px;
}
.feedback-image-size {
  font-size: 11px;
  color: #64748b;
}
.feedback-image .feedback-image-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 1px solid #e2e8f0;
  color: #64748b;
  background: #fffffff2;
}
.feedback-image .feedback-image-remove:not(:disabled):hover {
  border-color: #fecaca;
  color: #dc2626;
  background: #fef2f2;
}
.feedback-source {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.feedback-source span,
.feedback-source small {
  font-size: 12px;
  color: #94a3b8;
}
.feedback-source strong,
.feedback-source code {
  font-size: 12px;
  color: #64748b;
  font-weight: 400;
}
.feedback-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
}
.feedback-footer span {
  font-size: 12px;
  color: #94a3b8;
}
.feedback-success {
  padding: 12px 20px 28px;
  text-align: center;
}
.feedback-success > .ant-btn {
  margin: 0 6px;
}
.feedback-capturing {
  position: fixed;
  right: 24px;
  bottom: 24px;
  padding: 12px 18px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 6px 24px #0002;
}
@media (max-width: 560px) {
  .feedback-panel {
    right: 12px;
    bottom: 12px;
    max-height: calc(100dvh - 24px);
  }
}
</style>
