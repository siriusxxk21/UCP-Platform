<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import {
  getFeedbackDetail,
  getFeedbackPage,
  saveFeedback,
  type Feedback,
  type FeedbackDetail,
  type FeedbackQuery,
  type FeedbackStatus
} from '@/api/system/feedback'
import { formatDateTime } from '@/utils/format'

const columns = [
  { title: '工单编号', dataIndex: 'feedbackNo', key: 'feedbackNo', width: 210, ellipsis: true },
  { title: '类型', dataIndex: 'feedbackType', key: 'feedbackType', width: 90 },
  { title: '标题', dataIndex: 'title', key: 'title', width: 260, ellipsis: true },
  { title: '解决状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '提交人', dataIndex: 'submitterName', key: 'submitterName', width: 120 },
  { title: '来源页面', dataIndex: 'pageTitle', key: 'pageTitle', width: 180, ellipsis: true },
  { title: '提交时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 110, fixed: 'right' as const }
]
const tagMap = {
  feedbackType: { BUG: { label: 'Bug', color: 'red' }, REQUIREMENT: { label: '需求', color: 'blue' } },
  status: { PENDING: { label: '未解决', color: 'orange' }, CLOSED: { label: '已解决', color: 'green' } }
}
const listError = ref(false)
const { loading, tableData, pagination, queryForm, handleQuery, handleReset, handleTableChange, fetchData } =
  useOsTablePage<Feedback, FeedbackQuery>({
    fetchFn: async params => {
      listError.value = false
      return getFeedbackPage(params)
    },
    defaultQuery: () => ({ keyword: undefined, type: undefined, status: 'PENDING' }),
    dataField: 'list',
    onError: () => {
      listError.value = true
    }
  })
const drawerOpen = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const detail = ref<FeedbackDetail>()
const detailError = ref('')
const status = ref<FeedbackStatus>('PENDING')
const content = ref('')
let currentId = ''
let requestSequence = 0

async function loadDetail(id: string) {
  const sequence = ++requestSequence
  detailLoading.value = true
  detailError.value = ''
  detail.value = undefined
  try {
    const value = await getFeedbackDetail(id)
    if (sequence !== requestSequence) return
    detail.value = value
    status.value = value.status
    content.value = ''
  } catch {
    if (sequence === requestSequence) detailError.value = '工单详情加载失败，请重试。'
  } finally {
    if (sequence === requestSequence) detailLoading.value = false
  }
}
function show(row: Feedback) {
  if (saving.value) return
  currentId = row.id
  drawerOpen.value = true
  void loadDetail(currentId)
}
function close() {
  if (saving.value) return
  requestSequence++
  drawerOpen.value = false
}
async function save() {
  if (!detail.value || saving.value) return
  if (status.value === detail.value.status && !content.value.trim()) {
    message.info('请修改解决状态或填写处理说明')
    return
  }
  saving.value = true
  detailError.value = ''
  try {
    await saveFeedback(detail.value.id, {
      status: status.value,
      content: content.value.trim(),
      version: detail.value.version
    })
    message.success('处理结果已保存')
    await loadDetail(currentId)
    await fetchData()
  } catch {
    detailError.value = '保存失败，处理说明已保留。如工单已被他人更新，请刷新详情后重试。'
  } finally {
    saving.value = false
  }
}
function refresh() {
  void fetchData()
}
onMounted(() => window.addEventListener('system-feedback-created', refresh))
onBeforeUnmount(() => {
  requestSequence++
  window.removeEventListener('system-feedback-created', refresh)
})
</script>

<template>
  <div class="feedback-management">
    <a-alert v-if="listError" type="error" message="工单列表加载失败，当前列表可能不是最新数据。" show-icon>
      <template #action><a-button size="small" @click="fetchData">重试</a-button></template>
    </a-alert>
    <OsTablePage
      title="反馈工单"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :tag-map="tagMap"
      column-settings-key="system-feedback"
      show-column-settings
      resizable
      :show-advanced-search="false"
      :show-export="false"
      :show-import="false"
      :scroll="{ x: 1340 }"
      @change="handleTableChange"
      @search="handleQuery"
    >
      <template #search>
        <a-form layout="inline" :model="queryForm" class="feedback-search">
          <a-form-item label="关键词">
            <a-input
              v-model:value="queryForm.keyword"
              placeholder="标题 / 编号 / 提交人 / 来源"
              allow-clear
              @press-enter="handleQuery"
            />
          </a-form-item>
          <a-form-item label="类型">
            <a-select v-model:value="queryForm.type" placeholder="全部" allow-clear class="feedback-filter">
              <a-select-option value="BUG">Bug</a-select-option>
              <a-select-option value="REQUIREMENT">需求</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="状态">
            <a-select v-model:value="queryForm.status" placeholder="全部" allow-clear class="feedback-filter">
              <a-select-option value="PENDING">未解决</a-select-option>
              <a-select-option value="CLOSED">已解决</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="handleQuery">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template #actions>
        <a-button @click="fetchData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
        <template v-else-if="column.key === 'title'">
          <a-button type="link" class="feedback-title-link" @click="show(record)">{{ record.title }}</a-button>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" @click="show(record)">查看 / 处理</a-button>
        </template>
      </template>
    </OsTablePage>
    <a-drawer :open="drawerOpen" title="工单详情" :width="680" :mask-closable="false" @close="close">
      <a-alert v-if="detailError" :message="detailError" type="error" show-icon class="feedback-detail-error">
        <template #action>
          <a-button size="small" :disabled="saving" @click="loadDetail(currentId)">刷新详情</a-button>
        </template>
      </a-alert>
      <a-spin :spinning="detailLoading">
        <template v-if="detail">
          <div class="feedback-detail-heading">
            <a-tag :color="detail.feedbackType === 'BUG' ? 'red' : 'blue'">
              {{ detail.feedbackType === 'BUG' ? 'Bug' : '需求' }}
            </a-tag>
            <a-tag :color="detail.status === 'CLOSED' ? 'green' : 'orange'">
              {{ detail.status === 'CLOSED' ? '已解决' : '未解决' }}
            </a-tag>
            <h2>{{ detail.title }}</h2>
            <small>{{ detail.feedbackNo }}</small>
          </div>
          <a-descriptions :column="2" size="small">
            <a-descriptions-item label="提交人">{{ detail.submitterName }}</a-descriptions-item>
            <a-descriptions-item label="提交时间">{{ formatDateTime(detail.createTime) }}</a-descriptions-item>
            <a-descriptions-item label="来源页面" :span="2">{{ detail.pageTitle || '—' }}</a-descriptions-item>
            <a-descriptions-item label="来源路由" :span="2">
              <span class="feedback-route">{{ detail.pagePath || '—' }}</span>
            </a-descriptions-item>
            <a-descriptions-item label="系统版本">{{ detail.buildCommit || '—' }}</a-descriptions-item>
          </a-descriptions>
          <h3>反馈内容</h3>
          <p class="feedback-description">{{ detail.description }}</p>
          <a-image-preview-group>
            <div v-if="detail.imageUrls.length" class="feedback-attachments">
              <a-image
                v-for="(url, index) in detail.imageUrls"
                :key="url"
                :src="url"
                :width="160"
                :height="110"
                :alt="'反馈图片 ' + (index + 1)"
              />
            </div>
          </a-image-preview-group>
          <a-divider />
          <h3>处理工单</h3>
          <a-form layout="vertical" :disabled="saving">
            <a-form-item label="解决状态">
              <a-radio-group v-model:value="status" aria-label="解决状态">
                <a-radio-button value="PENDING">未解决</a-radio-button>
                <a-radio-button value="CLOSED">已解决</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="处理说明">
              <a-textarea
                v-model:value="content"
                aria-label="处理说明"
                :rows="4"
                :maxlength="2000"
                show-count
                placeholder="记录排查情况或解决方案（选填）"
              />
            </a-form-item>
          </a-form>
          <a-divider />
          <h3>处理记录</h3>
          <a-timeline class="feedback-history">
            <a-timeline-item v-for="item in detail.followUps" :key="item.id">
              <strong>{{ item.operatorName }}</strong>
              <span>· {{ formatDateTime(item.createTime) }}</span>
              <p v-if="item.fromStatus !== item.toStatus">{{ item.toStatusLabel || '未解决' }}</p>
              <p v-if="item.content">{{ item.content }}</p>
            </a-timeline-item>
          </a-timeline>
        </template>
      </a-spin>
      <template #footer>
        <div class="feedback-detail-footer">
          <a-button :disabled="saving" @click="close">关闭</a-button>
          <a-button type="primary" :loading="saving" :disabled="!detail || detailLoading" @click="save">
            保存处理结果
          </a-button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.feedback-management {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.feedback-search {
  row-gap: 12px;
}
.feedback-filter {
  min-width: 120px;
}
.feedback-title-link {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}
.feedback-detail-heading {
  margin-bottom: 24px;
}
.feedback-detail-heading h2 {
  font-size: 20px;
  margin: 12px 0 4px;
  overflow-wrap: anywhere;
}
.feedback-detail-heading small,
.feedback-history span {
  color: #94a3b8;
}
h3 {
  font-size: 14px;
  margin: 16px 0 12px;
}
.feedback-description,
.feedback-history p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.feedback-description {
  padding: 16px;
  background: #f8fafc;
  border-radius: 8px;
  line-height: 1.8;
}
.feedback-route {
  overflow-wrap: anywhere;
}
.feedback-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}
.feedback-attachments :deep(img) {
  object-fit: cover;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}
.feedback-history {
  margin-top: 20px;
}
.feedback-history p {
  margin: 6px 0;
}
.feedback-detail-error {
  margin-bottom: 16px;
}
.feedback-detail-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
