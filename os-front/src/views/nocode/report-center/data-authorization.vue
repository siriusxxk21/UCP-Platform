<template>
  <a-result
    title="数据集无需单独配置对象权限"
    sub-title="报表中心的数据集默认可读取当前租户下的所有可用数据对象。数据集和仪表板的访问仍由协作权限控制。"
  >
    <template #extra>
      <router-link to="/nocode/report-center/datasets">返回数据集</router-link>
    </template>
  </a-result>
</template>
