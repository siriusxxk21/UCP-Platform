<template>
  <OsTablePage
    :columns="columns"
    :data-source="tableData"
    :loading="loading"
    :pagination="pagination"
    :row-selection="false"
    :scroll="{ y: 'calc(100vh - 360px)' }"
    :selected-row-keys="selectedRowKeys"
    row-key="id"
    title="我的消息"
    @change="handleTableChange"
    @search="handleQuery"
  >
    <!-- 搜索区域 -->
    <template #search="{ triggerSearch }">
      <a-form :model="queryForm" layout="inline">
        <a-space>
          <a-select
            v-model:value="queryForm.msgTemplateId"
            :options="templateOptions"
            allow-clear
            placeholder="消息类型"
            style="width: 180px"
            @change="triggerSearch"
          />
          <a-select
            v-model:value="queryForm.hasRead"
            allow-clear
            placeholder="阅读状态"
            style="width: 180px"
            @change="triggerSearch"
          >
            <a-select-option :value="0">未读</a-select-option>
            <a-select-option :value="1">已读</a-select-option>
          </a-select>
          <a-input-search
            v-model:value="queryForm.keyword"
            allow-clear
            placeholder="搜索标题/内容"
            style="width: 220px"
            @search="triggerSearch"
          />
        </a-space>
      </a-form>
    </template>

    <!-- 操作按钮 -->
    <template #actions>
      <a-space>
        <a-button :disabled="!hasUnread" type="primary" @click="handleMarkAllRead">
          <CheckOutlined />
          全部标为已读
        </a-button>
      </a-space>
    </template>

    <!-- 自定义列渲染 -->
    <template #bodyCell="{ column, record }">
      <!-- 标题（链接样式，点击查看详情并标记已读） -->
      <template v-if="column.key === 'title'">
        <a :class="{ 'title-read': record.hasRead === 1 }" class="title-link" @click="handleView(record)">
          <span v-if="record.msgTemplateId && templateLabelMap[record.msgTemplateId]" class="title-tag">
            {{ templateLabelMap[record.msgTemplateId] }}
          </span>
          <span :title="record.title || '无标题'" class="title-text">{{ record.title || '无标题' }}</span>
        </a>
      </template>

      <!-- 内容（去除富文本标签，简要展示，超长省略） -->
      <template v-if="column.key === 'content'">
        <span :style="cellStyle(record)" :title="stripHtml(record.content) || ''" class="content-cell">
          {{ stripHtml(record.content) || '-' }}
        </span>
      </template>

      <!-- 发送人 -->
      <template v-if="column.key === 'ownerName'">
        <span :style="cellStyle(record)">{{ record.ownerName || '-' }}</span>
      </template>

      <!-- 发送时间 -->
      <template v-if="column.key === 'sendTime'">
        <span :style="cellStyle(record)">{{ record.sendTime || '-' }}</span>
      </template>

      <!-- 阅读状态 -->
      <template v-if="column.key === 'hasRead'">
        <a-tag :color="record.hasRead === 1 ? 'default' : 'processing'">
          {{ record.hasRead === 1 ? '已读' : '未读' }}
        </a-tag>
      </template>
    </template>
  </OsTablePage>

  <!-- 消息详情抽屉 -->
  <a-drawer
    :footer-style="{ textAlign: 'right' }"
    :open="drawerVisible"
    placement="right"
    title="消息详情"
    width="50%"
    @close="handleDrawerClose"
  >
    <template v-if="detailRecord">
      <a-card :bordered="true" class="detail-card" size="small">
        <template #title>
          <div class="card-header-wrap">
            <span class="card-title-text">
              <template v-if="detailRecord.msgTemplateId && templateLabelMap[detailRecord.msgTemplateId]">
                {{ templateLabelMap[detailRecord.msgTemplateId] }}
              </template>
              <a v-if="!!detailRecord.url" @click="handleRoute(detailRecord)">{{ detailRecord.title || '无标题' }}</a>
              <span v-else>{{ detailRecord.title || '无标题' }}</span>
            </span>
          </div>
        </template>
        <div class="card-meta-tip">
          <span>发送人：{{ detailRecord.ownerName || '-' }}</span>
          <a-divider type="vertical" />
          <span>发送时间：{{ detailRecord.sendTime || '-' }}</span>
        </div>
        <a-divider style="margin: 8px 0" />
        <div class="info-content" @click="handleContentClick" v-html="detailRecord.content || '无内容'" />
      </a-card>
    </template>
  </a-drawer>

  <!-- 使用隐藏缩略图承载 Ant Design 图片预览状态，避免打开新的浏览器标签页。 -->
  <div v-if="previewUrl" class="message-preview-host">
    <a-image
      :height="1"
      :preview="{ visible: previewOpen, onVisibleChange: handlePreviewVisibleChange }"
      :src="previewUrl"
      :width="1"
      alt="消息图片预览"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { CheckOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import type { SysMsgNoticeVO } from '@/api/message/message'
import { getNoticeDetail, listAllSysMsgTemplate, markNoticeRead, pageMyNotice } from '@/api/message/message'
import { useRealtimeEvent } from '@/realtime'

// 动态菜单路由统一携带可选 id 参数；显式声明后避免多根节点页面产生属性透传告警。
defineProps<{ id?: string }>()

/** 去除富文本中的 HTML 标签并返回纯文本摘要，供表格简要展示 */
function stripHtml(html: string | undefined | null): string {
  if (!html) return ''
  // 1. 在浏览器环境用 DOM 解析，避免正则误伤；同时过滤 script/style 等标签内容
  if (typeof document !== 'undefined') {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    doc.querySelectorAll('script, style').forEach(el => el.remove())
    return (doc.body.textContent || '').replace(/\s+/g, ' ').trim()
  }
  // 2. 兜底：非浏览器环境用正则粗略去除标签
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

interface QueryForm {
  msgTemplateId: number | undefined
  hasRead: number | undefined
  keyword: string | undefined
}

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData
} = useOsTablePage<SysMsgNoticeVO, QueryForm>({
  fetchFn: params =>
    pageMyNotice({
      pageNo: params.pageNum,
      pageSize: params.pageSize,
      msgTemplateId: params.msgTemplateId,
      hasRead: params.hasRead,
      keyword: params.keyword || undefined
    }),
  defaultQuery: () => ({
    msgTemplateId: undefined,
    hasRead: undefined,
    keyword: undefined
  }),
  dataField: 'records'
})

const columns: TableColumnType[] = [
  { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true, align: 'left' },
  { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true, align: 'left' },
  { title: '发送人', dataIndex: 'ownerName', key: 'ownerName', width: 120, align: 'left' },
  { title: '发送时间', dataIndex: 'sendTime', key: 'sendTime', width: 170, align: 'left' },
  { title: '阅读状态', dataIndex: 'hasRead', key: 'hasRead', width: 90, align: 'center' }
]

const hasUnread = computed(() => tableData.value.some(r => r.hasRead === 0))

// ---- 模板选项（用于筛选） ----
const templateOptions = ref<{ label: string; value: number }[]>([])
const templateLabelMap = ref<Record<number, string>>({})

async function loadTemplateOptions() {
  try {
    const list: any[] = await listAllSysMsgTemplate()
    templateOptions.value = list.map((t: any) => ({
      label: `${t.name}`,
      value: t.id
    }))
    list.forEach((t: any) => {
      templateLabelMap.value[t.id] = `【${t.name}】`
    })
  } catch {
    // 静默失败
  }
}

function cellStyle(record: SysMsgNoticeVO): Record<string, string> {
  if (record.hasRead === 1) {
    return { color: '#aaa' }
  }
  return { color: '#000' }
}

// ---- 标记已读 ----
async function handleMarkAllRead() {
  const unreadIds = tableData.value.filter(r => r.hasRead === 0).map(r => r.id)
  if (unreadIds.length === 0) {
    message.info('没有未读消息')
    return
  }
  try {
    await markNoticeRead(unreadIds)
    message.success('全部标为已读')
    fetchData()
  } catch {
    message.error('操作失败')
  }
}

// ---- 查看详情 ----
const drawerVisible = ref(false)
const detailRecord = ref<SysMsgNoticeVO | null>(null)
const previewOpen = ref(false)
const previewUrl = ref('')

/** 拦截富文本图片链接，在当前页面预览，避免跳转到新浏览器标签页。 */
function handleContentClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement) || !target.src)
    return
  event.preventDefault()
  event.stopPropagation()
  previewUrl.value = target.src
  previewOpen.value = true
}

/** 同步 Ant Design 图片预览层的受控显示状态，关闭时仍保留当前图片以便再次打开。 */
function handlePreviewVisibleChange(visible: boolean) {
  previewOpen.value = visible
}

function handleDrawerClose() {
  drawerVisible.value = false
  previewOpen.value = false
}

function handleView(record: SysMsgNoticeVO) {
  detailRecord.value = record
  drawerVisible.value = true
  // 查看时自动标记已读
  if (record.hasRead === 0) {
    markNoticeRead([record.id])
      .then(() => {
        // 更新本地记录状态
        record.hasRead = 1
        if (detailRecord.value) {
          detailRecord.value.hasRead = 1
        }
        fetchData()
      })
      .catch(() => {})
  }
}

async function handleOpenMessageDetail(messageId: string) {
  try {
    const msgDetail = await getNoticeDetail(messageId)
    handleView(msgDetail)
  } catch {
    message.error('获取消息详情失败')
  }
}

useRealtimeEvent('notification.detail-requested', request => {
  if (request.kind === 'message') void handleOpenMessageDetail(request.messageId)
})

const router = useRouter()
function handleRoute(notice) {
  const resolved = router.resolve(notice.url)

  router.push({
    path: resolved.path,
    query: {
      ...resolved.query,
      sourceId: notice.sourceId,
      sourceType: notice.sourceType
    },
    hash: resolved.hash
  })
}

const route = useRoute()

onMounted(async () => {
  loadTemplateOptions()
  // 读取路由查询参数 messageId，若有则打开详情
  const messageId = route.query.messageId
  if (messageId) {
    try {
      const msgDetail = await getNoticeDetail(String(messageId))
      handleView(msgDetail)
    } catch {
      message.error('获取消息详情失败')
    }
  }
})
</script>

<style scoped>
.title-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  color: #1677ff;
  text-decoration: none;
}

.title-link:hover {
  color: #4096ff;
}

.title-link.title-read {
  color: #8ab4f8;
}

.title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 1;
}

.title-tag {
  color: #999;
  font-size: 12px;
  white-space: nowrap;
  flex-shrink: 0;
}

.content-cell {
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.detail-card {
  margin-bottom: 0;
}

:deep(.ant-card-head-title) {
  width: 100%;
}

.card-header-wrap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title-text {
  font-size: 15px;
  font-weight: 600;
  color: #000;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta-tip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}

.info-content {
  font-size: 13px;
  color: var(--text-primary);
  padding: 8px 12px;
  background: var(--neutral-bg);
  border-radius: var(--radius-sm);
  overflow-wrap: break-word;
  word-break: break-word;
  overflow: hidden;
}
.info-content :deep(img),
.info-content :deep(video),
.info-content :deep(table),
.info-content :deep(iframe),
.info-content :deep(pre),
.info-content :deep(svg) {
  max-width: 100% !important;
  height: auto !important;
  display: block;
}
.info-content :deep(img) {
  cursor: pointer;
  transition: opacity 0.2s;
  border-radius: 4px;
}
.info-content :deep(img):hover {
  opacity: 0.85;
}
.info-content :deep(table) {
  display: block;
  overflow-x: auto;
  white-space: nowrap;
}

.message-preview-host {
  position: fixed;
  top: -2px;
  left: -2px;
  z-index: -1;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}
</style>
