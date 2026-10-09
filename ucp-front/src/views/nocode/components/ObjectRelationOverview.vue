<script setup lang="ts">
import { computed } from 'vue'
import type { ObjectDetail, ObjectRelation } from '@/types/nocode/data-center'
import { MemberState, RelationType } from '@/types/nocode/enums'
import { label } from '@/nocode/data-center'

/** 只解释当前对象已配置的关系；明细发出的引用挂在对应明细下。 */
const props = defineProps<{
  name: string
  details: ObjectDetail[]
  relations: ObjectRelation[]
  targetNames: Record<string, string>
}>()
const active = computed(() => props.details.filter(d => d.state === MemberState.ACTIVE))
const key = (detail: ObjectDetail) => detail.id || `detail:${detail.code}`
const from = (source: string | null) => props.relations.filter(r => (r.sourceDetailId || null) === source)
const orphans = computed(() =>
  props.relations.filter(r => r.sourceDetailId && !active.value.some(d => key(d) === r.sourceDetailId))
)
const target = (r: ObjectRelation) => props.targetNames[r.targetObjectId] || `目标对象 ${r.targetObjectId}`
const cardinality = (r: ObjectRelation) =>
  r.kind === RelationType.ONE_TO_ONE ? '1 对 1' : r.kind === RelationType.MANY_TO_MANY ? '多 对 多' : '多 对 1'
</script>
<template>
  <section class="relation-overview" aria-label="对象关系与保存边界">
    <div class="relation-guide">
      <article>
        <h4>内部明细：一张单据的一部分</h4>
        <p>例如采购单里的采购明细，随主记录一起保存和校验，删除主记录时一并处理。</p>
      </article>
      <article>
        <h4>关联对象：独立管理的资料</h4>
        <p>
          例如采购单引用供应商。默认分别维护，也可在表单中配置一起填写；解除关联不等于删除独立资料。删除仍按关系规则处理，查看或修改需相应资料的权限。
        </p>
      </article>
      <article>
        <h4>数据视图：把相关信息放在一起看</h4>
        <p>
          可在主记录下配置多组子表、关联字段和汇总；各组子表分别分页。选择明细粒度时，一行展示一条明细，并可返回整单。
        </p>
      </article>
    </div>
    <div class="relation-tree">
      <div class="relation-root">
        {{ name || '当前对象' }}
        <a-tag>主记录</a-tag>
      </div>
      <ul>
        <li v-for="r in from(null)" :key="r.id || r.code" class="relation-link" data-source="main">
          <span class="relation-source-card">
            {{ name || '当前对象' }}
            <small>{{ r.name }}</small>
          </span>
          <span class="relation-edge">
            {{ cardinality(r) }}
            <b>⟶</b>
            <small>{{ label(r.kind) }}</small>
          </span>
          <strong class="relation-target-card">
            {{ target(r) }}
            <small>独立资料</small>
          </strong>
        </li>
        <li v-for="detail in active" :key="key(detail)" class="relation-detail" :data-source="key(detail)">
          <div>
            <strong>{{ detail.name }}</strong>
            <a-tag>内部明细 · 随主记录保存</a-tag>
          </div>
          <ul v-if="from(key(detail)).length">
            <li v-for="r in from(key(detail))" :key="r.id || r.code" class="relation-link">
              <span class="relation-source-card">
                每条{{ detail.name }}
                <small>{{ r.name }}</small>
              </span>
              <span class="relation-edge">
                选择 1 条
                <b>⟶</b>
              </span>
              <strong class="relation-target-card">
                {{ target(r) }}
                <small>独立资料</small>
              </strong>
            </li>
          </ul>
        </li>
      </ul>
      <p v-if="!active.length && !relations.length" class="relation-empty">
        当前只有主记录。可根据业务增加内部明细，或关联其他独立对象。
      </p>
      <a-alert
        v-if="orphans.length"
        type="warning"
        show-icon
        :message="`有 ${orphans.length} 个关系的来源明细已移除，请修正后再发布`"
      />
    </div>
  </section>
</template>
<style scoped>
.relation-overview {
  margin-top: 20px;
}
.relation-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}
.relation-guide article {
  padding: 16px;
  background: var(--bg-secondary, #f7f8fc);
  border-radius: 8px;
}
.relation-guide h4 {
  margin: 0 0 8px;
}
.relation-guide p,
.relation-empty {
  margin: 0;
  color: var(--text-secondary, #64748b);
  line-height: 1.7;
}
.relation-tree {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  padding: 20px;
}
.relation-root {
  font-weight: 600;
  font-size: 16px;
}
.relation-tree ul {
  list-style: none;
  padding: 0 0 0 20px;
  margin: 14px 0 0 8px;
  border-left: 2px solid #e7e3fa;
}
.relation-tree li {
  margin-top: 12px;
}
.relation-link {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.relation-link span {
  color: var(--text-secondary, #64748b);
}
.relation-source-card,
.relation-target-card {
  display: grid;
  gap: 6px;
  min-width: 140px;
  padding: 14px;
  border: 1px solid #cec5f3;
  border-radius: 8px;
  background: var(--bg-container, #fff);
}
.relation-target-card {
  border-color: #b8dccc;
}
.relation-source-card small,
.relation-target-card small {
  color: var(--text-secondary);
  font-weight: normal;
}
.relation-edge {
  display: grid;
  text-align: center;
  min-width: 100px;
  font-size: 12px;
}
.relation-edge b {
  color: var(--primary-color, #6544c0);
  font-size: 28px;
}
.relation-detail > div {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.relation-empty {
  margin-top: 12px;
}
@media (max-width: 800px) {
  .relation-guide {
    grid-template-columns: 1fr;
  }
}
</style>
