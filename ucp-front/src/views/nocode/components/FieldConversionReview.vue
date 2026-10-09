<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import type { FieldConversion, FieldConversionRows } from '@/types/nocode/data-center'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { conversionClearsColumn, conversionImpactRoute, conversionTypeLabel } from '@/nocode/field-conversion'
import { distinctFieldImpacts, fieldConversionConclusion, fieldImpactKey } from '@/nocode/field-impact-presentation'

const props = defineProps<{
  planId: string
  objectId?: string
  conversions: FieldConversion[]
  blocked?: boolean
  reviewedApplicationIds?: string[]
}>()
const emit = defineEmits<{ navigate: [route: string] }>()
const platform = useNocodePlatform()
const canManage = computed(
  () => platform.hasPermission('nocode:object:manage') && platform.hasPermission('nocode:object:update')
)
const canPreview = computed(
  () => platform.hasPermission('nocode:table:query') && platform.hasPermission('nocode:table:preview')
)
const canMaintainData = computed(
  () =>
    !!props.objectId && platform.hasPermission('nocode:object:query') && platform.hasPermission('nocode:object:manage')
)
const reviewItems = computed(() =>
  props.conversions.map(item => ({
    ...item,
    impacts: distinctFieldImpacts(item.impacts).filter(
      impact =>
        impact.blocking ||
        impact.sourceKind !== 'APPLICATION' ||
        !props.reviewedApplicationIds?.includes(impact.sourceId)
    ),
    conclusion: fieldConversionConclusion(item)
  }))
)
const previews = reactive<Record<string, FieldConversionRows>>({})
const errors = reactive<Record<string, string>>({})
const loading = reactive<Record<string, boolean>>({})
let generation = 0
watch(
  () => props.planId,
  () => {
    generation++
    for (const state of [previews, errors, loading]) Object.keys(state).forEach(key => delete state[key])
  }
)
async function preview(fieldId: string, pageNo = 1) {
  if (!canPreview.value || loading[fieldId]) return
  const request = generation
  loading[fieldId] = true
  errors[fieldId] = ''
  try {
    const result = await platform.dataCenter.conversionRows(props.planId, fieldId, pageNo, 20)
    if (request === generation) previews[fieldId] = result
  } catch (cause) {
    if (request === generation) errors[fieldId] = errorMessage(cause)
  } finally {
    if (request === generation) loading[fieldId] = false
  }
}
function maintainData(item: FieldConversion) {
  if (!canMaintainData.value || !props.objectId) return
  const query = new URLSearchParams({ id: props.objectId, tab: 'data', fieldId: item.fieldId })
  if (item.detailId) query.set('detailId', item.detailId)
  emit('navigate', `/nocode/object/editor?${query}`)
}
const columns = [
  { title: '记录', key: 'title', dataIndex: 'title', width: 180 },
  { title: '原值', key: 'oldValue', dataIndex: 'oldValue', width: 220 },
  { title: '转换后', key: 'newValue', dataIndex: 'newValue', width: 180 },
  { title: '状态', key: 'deleted', dataIndex: 'deleted', width: 100 }
]
const displayValue = (value: unknown) =>
  value == null ? '空' : typeof value === 'string' ? value : JSON.stringify(value)
</script>

<template>
  <section v-if="conversions.length" class="conversion-review">
    <h4>字段转换</h4>
    <a-card
      v-for="item in reviewItems"
      :key="item.fieldId"
      :title="`${item.sourceName} · ${item.fieldName}`"
      size="small"
      class="conversion-card"
    >
      <p>
        {{ conversionTypeLabel(item.fromFieldType, item.fromType) }} →
        {{ conversionTypeLabel(item.toFieldType, item.toType) }}
      </p>
      <a-alert
        class="conversion-conclusion"
        :type="item.conclusion.type"
        show-icon
        :message="item.conclusion.title"
        :description="item.conclusion.description"
      />
      <p v-if="item.deletedRows" class="count-line">其中 {{ item.deletedRows }} 个旧值属于已删除但仍保留的记录。</p>
      <p v-if="item.conclusion.type === 'error'" class="count-line">本列有 {{ item.affectedRows }} 个旧值。</p>
      <p v-if="conversionClearsColumn(item) && item.clearAllowed && item.failedRows" class="count-line">
        {{ item.failedRows }} 条记录不符合目标要求；选择清空时会清空本列全部旧值，不只处理这些不兼容值。
      </p>
      <div v-if="item.impacts.length" class="conversion-impacts" aria-label="需要处理的影响">
        <article v-for="impact in item.impacts" :key="fieldImpactKey(impact)" class="conversion-impact">
          <div class="impact-location">
            <a-tag :color="impact.blocking ? 'error' : 'warning'">{{ impact.blocking ? '需处理' : '提示' }}</a-tag>
            <strong>{{ impact.sourceName }} · {{ impact.location }}</strong>
            <a-button
              v-if="conversionImpactRoute(impact.route)"
              type="link"
              @click="emit('navigate', conversionImpactRoute(impact.route)!)"
            >
              前往处理
            </a-button>
          </div>
          <p>{{ impact.message }}</p>
        </article>
      </div>
      <template v-if="conversionClearsColumn(item) && item.affectedRows">
        <p v-if="item.clearAllowed && !canManage" class="count-line">
          清空历史值需要对象管理和对象修改权限，请联系有权限的管理员。
        </p>
        <p v-else-if="item.clearAllowed && !blocked" class="count-line">
          确认发布后执行清空，无需先到数据列表手动处理。
        </p>
        <p v-if="blocked && item.clearAllowed && canManage" class="muted count-line">
          当前发布计划还有未解决的阻断，请先处理发布检查中的问题。
        </p>
      </template>
      <template v-if="item.affectedRows || item.failedRows">
        <div class="records-action">
          <a-button v-if="canMaintainData" @click="maintainData(item)">查看对象数据</a-button>
          <a-button v-if="canPreview" :loading="loading[item.fieldId]" @click="preview(item.fieldId)">
            {{ previews[item.fieldId] ? '刷新受影响记录' : '查看受影响记录与原值' }}
          </a-button>
          <span v-else class="muted">查看原值需要数据表查询与预览权限，请联系有权限的管理员。</span>
        </div>
        <p v-if="item.masked" class="muted">原值按数据分类遮蔽，清空范围仍包含这些记录。</p>
        <a-alert v-if="errors[item.fieldId]" type="error" :message="errors[item.fieldId]" show-icon />
        <OsTablePage
          v-if="previews[item.fieldId]"
          row-key="id"
          :columns="columns"
          :data-source="previews[item.fieldId]!.rows"
          :loading="loading[item.fieldId]"
          :pagination="{
            current: previews[item.fieldId]!.pageNo,
            pageSize: previews[item.fieldId]!.pageSize,
            total: previews[item.fieldId]!.total,
            showSizeChanger: false,
            showTotal: (total: number) => `共 ${total} 条受影响记录`
          }"
          :scroll="{ x: 500 }"
          size="small"
          @change="page => preview(item.fieldId, page.current || 1)"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'title'">
              <div>{{ record.title || record.id }}</div>
              <small>
                记录 ID：{{ record.id }}
                <span v-if="record.parentId">· 主记录 ID：{{ record.parentId }}</span>
              </small>
            </template>
            <span v-else-if="column.key === 'oldValue'" class="conversion-value">
              {{ displayValue(record.oldValue) }}
            </span>
            <span v-else-if="column.key === 'newValue'" class="conversion-value">
              {{
                record.failureReason && !conversionClearsColumn(item)
                  ? '目标校验未通过'
                  : record.newValue !== undefined
                    ? displayValue(record.newValue)
                    : !conversionClearsColumn(item)
                      ? '按规则保留'
                      : '空值'
              }}
              <small v-if="record.failureReason" class="muted">{{ record.failureReason }}</small>
            </span>
            <a-tag v-else-if="column.key === 'deleted'" :color="record.deleted ? 'default' : 'blue'">
              {{ record.deleted ? '已删除保留' : '正常' }}
            </a-tag>
          </template>
        </OsTablePage>
      </template>
      <a-collapse ghost class="conversion-details">
        <a-collapse-panel key="rules" header="转换规则与列类型">
          <p>{{ item.fromType }} → {{ item.toType }}</p>
          <p v-if="item.conversionRule">{{ item.conversionRule }}</p>
        </a-collapse-panel>
      </a-collapse>
    </a-card>
    <p class="muted review-note">发布时重新检查数据和依赖；发布失败会整体回滚。</p>
  </section>
</template>

<style scoped>
.conversion-review,
.conversion-card {
  margin-top: 16px;
}
.conversion-impact {
  padding: 12px 0;
  border-top: 1px solid var(--border);
}
.conversion-impact p {
  margin: 6px 0 0;
}
.impact-location {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.conversion-impacts,
.conversion-details,
.records-action,
.review-note {
  margin-top: 12px;
}
.records-action {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.count-line {
  margin: 8px 0;
}
.conversion-value {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.muted {
  color: var(--text-secondary);
}
</style>
