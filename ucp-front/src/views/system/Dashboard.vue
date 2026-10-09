<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { CodeOutlined, EyeOutlined, ProjectOutlined, UserOutlined } from '@ant-design/icons-vue'

const lineChartRef = ref<HTMLDivElement>()
const router = useRouter()
const pieChartRef = ref<HTMLDivElement>()
let lineChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

onMounted(() => {
  // 折线图
  if (lineChartRef.value) {
    lineChart = echarts.init(lineChartRef.value)
    lineChart.setOption({
      xAxis: {
        type: 'category',
        data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
      },
      yAxis: {
        type: 'value'
      },
      series: [
        {
          data: [120, 200, 150, 80, 70, 110, 130],
          type: 'line',
          smooth: true,
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(67, 56, 202, 0.3)' },
                { offset: 1, color: 'rgba(67, 56, 202, 0.05)' }
              ]
            }
          },
          itemStyle: {
            color: '#4338CA'
          }
        }
      ]
    })
  }

  // 饼图
  if (pieChartRef.value) {
    pieChart = echarts.init(pieChartRef.value)
    pieChart.setOption({
      tooltip: {
        trigger: 'item'
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      series: [
        {
          type: 'pie',
          radius: '50%',
          data: [
            { value: 1048, name: 'Web项目' },
            { value: 735, name: '移动端' },
            { value: 580, name: '小程序' },
            { value: 484, name: '后台服务' },
            { value: 300, name: '其他' }
          ],
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          }
        }
      ]
    })
  }

  // 响应式
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  lineChart?.dispose()
  pieChart?.dispose()
})

function handleResize() {
  lineChart?.resize()
  pieChart?.resize()
}
</script>

<template>
  <div class="dashboard">
    <a-card class="record-history-entry" title="表格更新">
      <template #extra>
        <a-button type="primary" @click="router.push('/nocode-app/record-history')">查看更新汇总</a-button>
      </template>
      按业务或员工检索全部业务表的新增、修改和删除，查看范围内的完整修改过程及截止时刻的全表内容。
    </a-card>
    <a-row :gutter="[16, 16]">
      <a-col :xs="12" :md="6">
        <a-card>
          <a-statistic title="用户总数" :value="1128" :value-style="{ color: '#3f8600' }">
            <template #prefix>
              <UserOutlined />
            </template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card>
          <a-statistic title="项目数量" :value="93" :value-style="{ color: '#cf1322' }">
            <template #prefix>
              <ProjectOutlined />
            </template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card>
          <a-statistic title="代码生成数" :value="1560" :value-style="{ color: '#4338CA' }">
            <template #prefix>
              <CodeOutlined />
            </template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card>
          <a-statistic title="今日访问" :value="128" :value-style="{ color: '#722ed1' }">
            <template #prefix>
              <EyeOutlined />
            </template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="[16, 16]" style="margin-top: 24px">
      <a-col :xs="24" :md="12">
        <a-card title="代码生成趋势">
          <div ref="lineChartRef" style="height: 300px" />
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12">
        <a-card title="项目类型分布">
          <div ref="pieChartRef" style="height: 300px" />
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="16" style="margin-top: 24px">
      <a-col :span="24">
        <a-card title="最近活动">
          <a-timeline>
            <a-timeline-item color="green">
              <p>创建新项目 - 用户管理系统 2024-01-15 10:30</p>
            </a-timeline-item>
            <a-timeline-item color="green">
              <p>生成代码 - 订单管理模块 2024-01-15 09:15</p>
            </a-timeline-item>
            <a-timeline-item color="blue">
              <p>更新模板 - SpringBoot 3.2 模板 2024-01-14 16:45</p>
            </a-timeline-item>
            <a-timeline-item color="red">
              <p>删除项目 - 测试项目 2024-01-14 14:20</p>
            </a-timeline-item>
          </a-timeline>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<style scoped>
.record-history-entry {
  margin-bottom: 20px;
}
.dashboard {
  padding: 0;
}
</style>
