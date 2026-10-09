<script setup lang="ts">
import { computed } from 'vue'
import { RelationType } from '@/types/nocode/enums'
const props = defineProps<{ kind: string }>()
const example = computed(
  () =>
    ({
      [RelationType.REFERENCE]: {
        title: '选择一条资料，可被重复选择',
        source: '采购单',
        target: '供应商',
        rows: [
          ['采购单 A', '星辰供应商'],
          ['采购单 B', '星辰供应商']
        ],
        hint: '每张采购单选一个供应商；同一供应商可以被多张采购单选择。两边各自保存。'
      },
      [RelationType.MASTER_DETAIL]: {
        title: '归属某条主记录，独立管理从记录',
        source: '合同',
        target: '项目',
        rows: [
          ['合同 A', '项目一'],
          ['合同 B', '项目一']
        ],
        hint: '每份合同必须归属一个项目；一个项目可有多份合同。合同仍是独立对象，删除项目时按下方规则处理合同。'
      },
      [RelationType.ONE_TO_ONE]: {
        title: '双方最多一条对应记录',
        source: '员工资料',
        target: '工位档案',
        rows: [
          ['员工甲', '工位 101'],
          ['员工乙', '工位 102']
        ],
        hint: '员工选择一个工位，已被选择的工位不能再被其他员工选择。适合唯一对应资料。'
      },
      [RelationType.MANY_TO_MANY]: {
        title: '双方都可以关联多条记录',
        source: '项目',
        target: '成员',
        rows: [
          ['项目一', '张三、李四'],
          ['项目二', '李四、王五']
        ],
        hint: '一个项目选多位成员，同一成员也能参与多个项目。取消关联不等于删除成员。'
      }
    })[props.kind] || null
)
</script>
<template>
  <div v-if="example" class="relation-example">
    <strong>{{ example.title }}</strong>
    <table>
      <thead>
        <tr>
          <th>{{ example.source }}</th>
          <th>关联的{{ example.target }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in example.rows" :key="index">
          <td>{{ row[0] }}</td>
          <td>{{ row[1] }}</td>
        </tr>
      </tbody>
    </table>
    <p>{{ example.hint }}</p>
  </div>
</template>
<style scoped>
.relation-example {
  max-width: 480px;
  padding: 12px;
  border-radius: 8px;
  background: var(--bg-secondary, #f7f8fc);
}
table {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
}
th,
td {
  border: 1px solid var(--border, #e5e7eb);
  padding: 8px 12px;
  text-align: left;
}
p {
  margin: 0;
  line-height: 1.6;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
