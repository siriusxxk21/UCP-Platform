<script setup lang="ts">
import { computed } from 'vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import type { FieldConversionRows, FieldSwitchPreview } from '@/types/nocode/data-center'
import { conversionImpactRoute } from '@/nocode/field-conversion'
import { distinctFieldImpacts, fieldImpactKey } from '@/nocode/field-impact-presentation'
import { fieldTypes } from '@/nocode/object-draft'
import type { FieldConfigurationChange } from '@/nocode/field-change-summary'

const props = defineProps<{
  preview?: FieldSwitchPreview
  targetType: string
  loading?: boolean
  error?: string
  configurationError?: string | null
  confirmed: boolean
  rows?: FieldConversionRows
  rowsLoading?: boolean
  rowsError?: string
  canViewRows?: boolean
  canClear?: boolean
  canMaintainData?: boolean
  changes?: FieldConfigurationChange[]
}>()
const emit = defineEmits<{
  'update:confirmed': [value: boolean]
  page: [pageNo: number]
  retry: []
  navigate: [route: string]
  maintainData: []
}>()
const targetLabel = computed(() =>
  props.targetType === 'REFERENCE'
    ? '单选（对象引用）'
    : (fieldTypes.find(type => type.value === props.targetType)?.label ?? props.targetType)
)
const sourceLabel = computed(() =>
  props.preview?.sourceType === 'REFERENCE'
    ? '单选（对象引用）'
    : (fieldTypes.find(type => type.value === props.preview?.sourceType)?.label ??
      props.preview?.sourceType ??
      '原类型')
)
const blocked = computed(
  () => props.preview?.decision === 'BLOCKED' || props.preview?.deploymentState === 'MISSING_COLUMN'
)
const configurationMessage = computed(() => props.configurationError?.trim())
const impacts = computed(() => {
  return distinctFieldImpacts(props.preview?.impacts ?? []).filter(impact => {
    // 当前字段未补齐配置时，主结论已给出原因；独立的必填、依赖和物理结构阻断仍逐项展示。
    if (
      configurationMessage.value &&
      impact.sourceKind === 'OBJECT' &&
      impact.sourceId === props.preview?.objectId &&
      impact.fieldId === props.preview?.fieldId &&
      impact.location.trim() === '字段配置'
    ) {
      return false
    }
    return true
  })
})
const conclusion = computed(() => {
  const preview = props.preview
  if (blocked.value) {
    return {
      type: 'error' as const,
      title: '暂时不能转换',
      description: impacts.value.some(impact => impact.blocking)
        ? '请先处理下方列出的影响，再重新检查。'
        : preview?.explanation
    }
  }
  if (preview?.decision === 'CLEAR_COLUMN') {
    return {
      type: 'warning' as const,
      title: '确认清空本列后可转换',
      description: `发布时将清空本列全部${preview.valueRows == null ? '' : ` ${preview.valueRows} 个`}旧值，整条记录和其他列保留。`
    }
  }
  if (preview?.deploymentState === 'UNPUBLISHED') {
    return {
      type: 'success' as const,
      title: '尚未发布，可调整字段设计',
      description: '当前仅调整草稿，发布后生效。'
    }
  }
  return {
    type: 'success' as const,
    title: preview?.valueRows === 0 ? '本列没有历史值，可以转换' : '可以按规则转换',
    description:
      preview?.valueRows === 0
        ? '本次无需清空数据，发布时会再次检查。'
        : '已有值按转换规则保留，不清空本列；具体规则和格式变化可在下方展开查看。'
  }
})
const columns = [
  { title: '记录', key: 'record', width: 190 },
  { title: '原值', key: 'oldValue', width: 180 },
  { title: '转换后', key: 'after', width: 100 }
]
const displayValue = (value: unknown) =>
  value == null ? '空' : typeof value === 'string' ? value : JSON.stringify(value)
</script>

<template>
  <section class="switch-impact" aria-label="字段变更影响">
    <div class="switch-heading">
      <strong>变更影响</strong>
      <a-button type="link" :disabled="loading" @click="emit('retry')">重新检查</a-button>
    </div>
    <a-alert
      v-if="configurationMessage"
      class="switch-conclusion"
      type="info"
      show-icon
      message="待补齐目标配置"
      :description="configurationMessage"
    />
    <a-spin v-else-if="loading" tip="正在检查历史值和依赖" />
    <a-alert v-else-if="error" type="error" show-icon :message="error" />
    <a-alert
      v-else-if="preview"
      class="switch-conclusion"
      :type="conclusion.type"
      show-icon
      :message="conclusion.title"
      :description="conclusion.description"
    />
    <slot name="target" />
    <template v-if="preview && !loading && !error">
      <p class="type-line">
        {{ preview.deploymentState === 'UNPUBLISHED' ? '原配置' : '已发布' }}：{{ sourceLabel }} → 当前草稿目标：{{
          targetLabel
        }}
      </p>
      <p v-if="preview.totalRows != null && preview.valueRows != null" class="count-line">
        共 {{ preview.totalRows }} 条记录，本列 {{ preview.valueRows }} 条有值
        <span v-if="preview.deletedRows">（其中 {{ preview.deletedRows }} 条已删除但可恢复）</span>
        。
      </p>
      <p v-else class="count-line">该字段尚无可核实的已发布物理列。</p>
      <p v-if="configurationMessage && preview.valueRows === 0" class="muted">本列无历史值；补齐配置后继续检查。</p>
      <p v-if="preview.failedRows" class="count-line">
        {{ preview.failedRows }} 条记录不符合目标要求（已按记录去重）。
      </p>
      <div v-if="impacts.length" class="impact-list" aria-label="需要处理的影响">
        <div v-for="impact in impacts" :key="fieldImpactKey(impact)" class="impact-item">
          <div class="impact-location">
            <a-tag :color="impact.blocking ? 'error' : 'warning'">{{ impact.blocking ? '需处理' : '提示' }}</a-tag>
            <strong>{{ impact.sourceName }} · {{ impact.location }}</strong>
            <a-button
              v-if="conversionImpactRoute(impact.route)"
              type="link"
              @click="emit('navigate', conversionImpactRoute(impact.route)!)"
            >
              新标签页处理
            </a-button>
          </div>
          <p class="count-line">{{ impact.message }}</p>
        </div>
      </div>
      <p v-if="blocked && impacts.some(impact => impact.route)" class="muted">
        处理完成后返回此页，点击“重新检查”；当前尚未应用的字段配置会保留。
      </p>
      <template v-if="(preview.valueRows ?? 0) > 0 || (preview.failedRows ?? 0) > 0">
        <div class="records-action">
          <a-button v-if="canMaintainData" @click="emit('maintainData')">新标签页处理数据</a-button>
          <a-button v-if="canViewRows" :loading="rowsLoading" @click="emit('page', 1)">
            {{ rows ? '刷新受影响记录' : '查看受影响记录与原值' }}
          </a-button>
          <span v-else class="muted">查看原值需要数据表预览权限；转换数量仍按完整物理列统计。</span>
        </div>
        <a-alert v-if="rowsError" type="error" show-icon :message="rowsError" />
        <OsTablePage
          v-if="rows"
          row-key="id"
          :columns="columns"
          :data-source="rows.rows"
          :pagination="{
            current: rows.pageNo,
            pageSize: rows.pageSize,
            total: rows.total,
            showSizeChanger: false,
            showTotal: (total: number) => `共 ${total} 条检查记录`
          }"
          :scroll="{ x: 510 }"
          size="small"
          @change="page => emit('page', page.current || 1)"
        >
          <template #bodyCell="{ column, record }">
            <span v-if="column.key === 'record'">
              {{ record.title || record.id }}
              <small class="muted">
                ID：{{ record.id }}{{ record.parentId ? ` · 主记录 ${record.parentId}` : '' }}
              </small>
              <small v-if="record.deleted" class="muted">已删除保留</small>
            </span>
            <span v-else-if="column.key === 'oldValue'" class="old-value">{{ displayValue(record.oldValue) }}</span>
            <span v-else-if="column.key === 'after'">
              {{
                record.failureReason && preview.decision !== 'CLEAR_COLUMN'
                  ? '目标校验未通过'
                  : record.newValue !== undefined
                    ? displayValue(record.newValue)
                    : preview.decision === 'CLEAR_COLUMN'
                      ? '空值'
                      : '按规则保留'
              }}
              <small v-if="record.failureReason" class="muted">{{ record.failureReason }}</small>
            </span>
          </template>
        </OsTablePage>
        <p v-if="preview.failedRows && preview.decision !== 'CLEAR_COLUMN'" class="muted">
          可在对象数据网格修正记录，或返回字段配置调整目标要求，再重新检查。数据维护保存立即生效；修改字段默认值不会补填历史空值。
        </p>
      </template>
      <div v-if="preview.decision === 'CLEAR_COLUMN' && !blocked" class="clear-choice">
        <a-checkbox :checked="confirmed" :disabled="!canClear" @update:checked="emit('update:confirmed', $event)">
          选择发布时清空本列 {{ preview.valueRows }} 个值并转换
        </a-checkbox>
        <p class="muted">保存草稿只保留方案，确认发布时才执行；清空范围包含逻辑删除记录，整条记录和其他列保留。</p>
        <p v-if="!canClear" class="muted">执行清空需要对象管理和对象修改权限。</p>
      </div>
      <a-collapse ghost class="switch-details storage-details">
        <a-collapse-panel v-if="changes?.length" key="changes" header="字段配置变更明细">
          <dl class="configuration-changes">
            <div v-for="change in changes" :key="change.label">
              <dt>{{ change.label }}</dt>
              <dd>{{ change.before }} → {{ change.after }}</dd>
            </div>
          </dl>
        </a-collapse-panel>
        <a-collapse-panel
          v-if="!configurationMessage && (preview.conversionRule || preview.conflicts?.length || preview.explanation)"
          key="rules"
          header="转换规则与检查明细"
        >
          <p v-if="preview.conversionRule" class="count-line">转换规则：{{ preview.conversionRule }}</p>
          <p
            v-if="
              preview.explanation &&
              preview.explanation !== preview.conversionRule &&
              !impacts.some(impact => impact.message === preview?.explanation)
            "
            class="count-line"
          >
            {{ preview.explanation }}
          </p>
          <ul v-if="preview.conflicts?.length" class="conflict-list">
            <li v-for="conflict in preview.conflicts" :key="conflict.code">
              {{ conflict.message }}：{{ conflict.count }} 条
            </li>
          </ul>
          <p v-if="(preview.conflicts?.length ?? 0) > 1" class="muted">
            同一条记录可能有多个问题，分类数量不能直接相加。
          </p>
        </a-collapse-panel>
        <a-collapse-panel v-if="preview.storage" key="storage" header="列属性与数据库变更">
          <a-descriptions size="small" :column="2" bordered>
            <a-descriptions-item label="当前物理类型">
              {{ preview.storage.actualType || '尚无可核实的物理列' }}
            </a-descriptions-item>
            <a-descriptions-item label="目标物理类型">
              {{ preview.storage.targetType || '待完善配置' }}
            </a-descriptions-item>
            <a-descriptions-item label="物理表" :span="2">
              {{ preview.storage.schemaName }}.{{ preview.storage.tableName }}
            </a-descriptions-item>
            <a-descriptions-item label="物理列">{{ preview.storage.columnName }}</a-descriptions-item>
            <a-descriptions-item label="实际允许空值">
              {{ preview.storage.nullable == null ? '未核实' : preview.storage.nullable ? '是' : '否' }}
            </a-descriptions-item>
            <a-descriptions-item label="实际主键">
              {{ preview.storage.primaryKey == null ? '未核实' : preview.storage.primaryKey ? '是' : '否' }}
            </a-descriptions-item>
            <a-descriptions-item label="实际生成列">
              {{ preview.storage.generated == null ? '未核实' : preview.storage.generated ? '是' : '否' }}
            </a-descriptions-item>
            <a-descriptions-item label="实际默认表达式" :span="2">
              {{ preview.storage.actualType ? preview.storage.defaultExpression || '无' : '未核实' }}
            </a-descriptions-item>
          </a-descriptions>
          <p class="count-line">
            方案通过复检并发布时，数据库结构变更：{{
              preview.storage.ddlRequired == null
                ? '需完善配置后核实'
                : preview.storage.ddlRequired
                  ? '会执行 DDL'
                  : '不需要列结构 DDL'
            }}。 当前检查和保存草稿不会修改物理列。
          </p>
          <p class="count-line">{{ preview.storage.ddlExplanation }}</p>
        </a-collapse-panel>
        <a-collapse-panel key="application" header="应用版本说明">
          <p class="count-line">
            兼容的旧版本可继续运行；不兼容时，发布检查会列出需要暂停的应用。暂停后分别在应用中心同步、调整并发布启用。
          </p>
        </a-collapse-panel>
      </a-collapse>
    </template>
    <p v-if="preview && !loading" class="muted">当前检查不修改数据。确认方案后仍需保存并发布，发布时会重新检查。</p>
    <p v-else-if="!loading && !configurationMessage && !error" class="muted">
      选择目标字段类型后，将检查历史值、转换规则和关联影响。
    </p>
  </section>
</template>

<style scoped>
.switch-impact {
  padding: 16px;
  margin: 20px 0;
  background: var(--bg-page, #fafafa);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
}
.switch-heading,
.records-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.count-line,
.type-line {
  margin: 8px 0;
}
.records-action,
.clear-choice,
.switch-details {
  margin-top: 14px;
}
.impact-list {
  margin: 14px 0;
}
.impact-item {
  padding: 12px 0;
  border-top: 1px solid var(--border, #e5e7eb);
}
.impact-location {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.impact-location .ant-btn {
  margin-left: auto;
}
.old-value {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.configuration-changes > div {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr);
  gap: 12px;
  padding: 6px 0;
}
.configuration-changes dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.configuration-changes dt {
  color: var(--ant-color-text-secondary, #6b7280);
}
.conflict-list {
  padding-left: 20px;
}
.muted {
  display: block;
  color: var(--ant-color-text-secondary, #6b7280);
  font-size: 12px;
}
</style>
