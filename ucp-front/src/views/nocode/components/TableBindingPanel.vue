<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as NC from '@/types/nocode/enums'
import type { AdoptionPreflight, TableBinding } from '@/types/nocode/data-center'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { createRequestSession } from '@/nocode/request-session'
import { tableBindingDiagnostics } from '@/nocode/table-binding-diagnostics'

const binding = defineModel<TableBinding>({ required: true })
const props = defineProps<{ disabled?: boolean; tableName?: string }>()
const platform = useNocodePlatform()
const router = useRouter()
const preflight = ref<AdoptionPreflight>()
const checking = ref(false)
const checkError = ref('')
const session = createRequestSession()
const canQuery = computed(() => platform.hasPermission('nocode:table:query'))
const canCheck = computed(() =>
  ['nocode:object:query', 'nocode:object:create', 'nocode:object:adopt', 'nocode:table:query'].every(permission =>
    platform.hasPermission(permission)
  )
)
const diagnostics = computed(() => preflight.value && tableBindingDiagnostics(binding.value, preflight.value))
const tableLabel = computed(() => `${binding.value.schemaName}.${props.tableName || '待选择数据表'}`)
const directoryLink = computed(() => router.resolve({ path: '/nocode/table' }).href)

async function checkBinding() {
  const current = session.begin()
  preflight.value = undefined
  checkError.value = ''
  checking.value = false
  if (binding.value.source !== NC.ObjectSource.ADOPTED || !props.tableName || !canCheck.value) return
  checking.value = true
  try {
    const result = await platform.dataCenter.preflight(binding.value.schemaName, props.tableName)
    if (current()) preflight.value = result
  } catch (cause) {
    if (current()) checkError.value = errorMessage(cause)
  } finally {
    if (current()) checking.value = false
  }
}
watch(() => [binding.value.source, binding.value.schemaName, props.tableName, canCheck.value], checkBinding, {
  immediate: true
})
onBeforeUnmount(() => session.invalidate())
</script>

<template>
  <div class="binding-panel">
    <a-space wrap class="binding-summary">
      <a-tag>{{ binding.source === NC.ObjectSource.ADOPTED ? '绑定已有表' : '平台新建表' }}</a-tag>
      <span>Schema：{{ binding.schemaName }}</span>
      <span>主键：{{ binding.keyColumn }}</span>
      <span v-if="binding.parentColumn">父键：{{ binding.parentColumn }}</span>
      <a-tag :color="binding.readOnly ? 'orange' : 'blue'">
        {{ binding.readOnly ? '草稿：只读映射' : '草稿：允许业务写入' }}
      </a-tag>
    </a-space>
    <a-form v-if="binding.source === NC.ObjectSource.ADOPTED" layout="vertical" :disabled="disabled">
      <a-form-item label="结构管理">
        <a-radio-group v-model:value="binding.structureMode" @change="binding.repairBaseFields = false">
          <a-radio :value="NC.StructureMode.RETAIN">保留原结构</a-radio>
          <a-radio
            :value="NC.StructureMode.MANAGED"
            :disabled="disabled || !!diagnostics?.structuralRestrictions.length"
          >
            由平台管理结构变更
          </a-radio>
        </a-radio-group>
        <p v-if="diagnostics?.structuralRestrictions.length" class="binding-help">
          暂不能接管结构：{{
            diagnostics.structuralRestrictions.join('；')
          }}。满足读取和主键要求后，可保留原结构按只读方式使用。
        </p>
      </a-form-item>
      <a-space direction="vertical">
        <a-checkbox
          v-model:checked="binding.readOnly"
          :disabled="disabled || (binding.readOnly && !!diagnostics?.readOnlyReasons.length)"
        >
          按只读方式使用此表
        </a-checkbox>
        <a-checkbox
          v-if="binding.structureMode === NC.StructureMode.MANAGED"
          v-model:checked="binding.repairBaseFields"
          :disabled="disabled || (!binding.repairBaseFields && !!diagnostics && !diagnostics.repairPossible)"
        >
          发布时补齐缺失的底座公共字段
        </a-checkbox>
      </a-space>
      <p class="binding-help">
        {{
          binding.structureMode === NC.StructureMode.RETAIN
            ? '保留原列和约束，通过字段映射使用数据。'
            : '父键、关系约束、索引和公共字段变更将列入发布预览，确认发布后才执行。'
        }}
        这里只修改草稿；保存时复核绑定，发布时复核并执行结构变更。允许业务写入后，还需应用已使用可写的对象版本，且操作者具有应用数据权限。
      </p>
      <a-alert
        v-if="binding.repairBaseFields"
        type="info"
        show-icon
        message="只补缺失列，不覆盖原值。历史操作者留空，缺失的创建和更新时间记为本次补齐时间；发布记录保存执行人。"
      />
    </a-form>
    <section v-if="binding.source === NC.ObjectSource.ADOPTED" class="binding-check" aria-live="polite">
      <a-space wrap class="binding-check-heading">
        <strong>当前表能力检查</strong>
        <span>{{ tableLabel }}</span>
        <a-button v-if="tableName && canCheck" size="small" :loading="checking" @click="checkBinding">
          {{ checking ? '正在检查' : '重新检查' }}
        </a-button>
        <a v-if="canQuery" :href="directoryLink" target="_blank" rel="noopener noreferrer">打开数据表目录</a>
      </a-space>
      <a-alert v-if="checkError" type="warning" show-icon message="本次检查未完成" :description="checkError" />
      <p v-else-if="!tableName" class="binding-help">选择已有表后可检查其权限和结构。</p>
      <p v-else-if="!canCheck" class="binding-help">
        当前账号没有完整的纳管预检权限（数据对象查询、新建、纳管及数据表查询），尚未检查表能力。保存与发布仍由服务端复核，不能以未检查作为可写依据。
      </p>
      <template v-if="diagnostics">
        <a-alert
          v-for="(reason, index) in diagnostics.blockers"
          :key="index"
          type="error"
          show-icon
          :message="reason"
          class="binding-notice"
        />
        <a-alert
          :type="diagnostics.blockers.length || diagnostics.readOnlyReasons.length ? 'warning' : 'info'"
          show-icon
          :message="
            diagnostics.blockers.length
              ? '请先处理上方配置限制'
              : diagnostics.effectiveReadOnly
                ? '当前配置将按只读方式使用'
                : diagnostics.repairActive && diagnostics.missingColumns.length
                  ? '发布时补齐公共字段并复核后，可允许应用写入'
                  : '本次检查未发现固有只读限制'
          "
          class="binding-notice"
        >
          <template #description>
            <ul v-if="diagnostics.readOnlyReasons.length" class="binding-reasons">
              <li v-for="reason in diagnostics.readOnlyReasons" :key="reason">{{ reason }}</li>
            </ul>
            <span v-else-if="binding.readOnly">你选择了只读映射，应用将不能通过此对象修改原表记录。</span>
            <span v-else>保存和发布时会再次检查；本次检查不代表已获得结构管理或应用数据操作权限。</span>
            <p v-if="!binding.readOnly && diagnostics.readOnlyReasons.length" class="binding-help">
              虽然草稿选择了允许写入，但这些限制仍存在，服务端会保留只读能力。可勾选只读并继续配置字段映射；结构错误需先处理。
            </p>
          </template>
        </a-alert>
        <p v-if="diagnostics.missingColumns.length" class="binding-help">
          缺失公共列：{{
            diagnostics.missingColumns.join('、')
          }}。仅在选择平台管理结构、勾选补齐且检查通过后，于确认发布时新增。
        </p>
        <p v-if="diagnostics.mismatchedColumns.length" class="binding-help">
          已有公共列不符合规范，补齐功能只新增缺失列。请由原表负责人修正上方列出的类型或非空约束后重新检查；也可取消补齐并保留只读映射。
        </p>
        <p v-if="diagnostics.claimed" class="binding-help">
          此表已有关联对象。当前绑定可继续核验和配置，是否属于本对象及是否重复绑定由保存时检查。
        </p>
      </template>
      <p class="binding-help">
        检查只读取当前物理结构；原表在外部发生变更后请重新检查。 在数据表目录按表名“{{
          tableName || '待选择'
        }}”搜索并查看结构详情。数据库权限、触发器和原表结构问题由原表负责人处理；业务记录的编辑和权限在应用中心配置。
      </p>
    </section>
  </div>
</template>

<style scoped>
.binding-panel {
  margin-bottom: 16px;
  padding: 12px 16px;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
}
.binding-summary {
  margin-bottom: 12px;
}
.binding-help {
  color: var(--color-text-secondary, #6b7280);
  margin: 12px 0 0;
  line-height: 1.7;
}
.binding-check {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border, #e5e7eb);
}
.binding-check-heading {
  margin-bottom: 12px;
}
.binding-notice + .binding-notice {
  margin-top: 8px;
}
.binding-reasons {
  padding-left: 20px;
  margin: 4px 0;
}
</style>
