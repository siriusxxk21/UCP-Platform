import { createApp } from 'vue'
import App from './App.vue'
import router, { registerDynamicRoutes } from './router'
import { createPinia } from 'pinia'
import { useUserStore } from './stores/user'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import AntdX from 'ant-design-x-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import './style.css'
import './styles/mobile.css'
import { registerAccessDirectives } from './directives/access'
import { getUserInfo } from './api/auth'
import { initializeRealtimeRuntime } from './realtime'
import { installAppRecovery } from './utils/appRecovery'
import { isAuthSnapshotConsistent } from './utils/authFlow'
import request from './utils/request'
import { hasPermission } from './utils/access'
import { createObjectApi } from './api/nocode/object'
import { createDataCenterApi } from './api/nocode/data-center'
import { createObjectDataApi } from './api/nocode/object-data'
import { createApplicationApi } from './api/nocode/application'
import { createRuntimeApi } from './api/nocode/runtime'
import { createWorkApi } from './api/nocode/work'
import { createBusinessFileApi } from './api/nocode/business-file'
import { createRecordFolderApi } from './api/nocode/record-folder'
import { createTaskCenterApi } from './api/nocode/task-center'
import { createReportCenterApi } from './api/nocode/report-center'
import { createApplicationDashboardRuntimeApi } from './api/nocode/application-dashboard-runtime'
import { getSimpleUserList } from './api/system/user'
import { getDepartmentTree } from './api/system/department'
import { nocodePlatformKey } from './nocode/platform'
import { registerBusinessForm } from './components/BusinessForm/registry'
import { bindVueFinderApp } from './views/drive/vuefinder-runtime'
registerBusinessForm('/nocode-app/process-record', () => import('./views/nocode/application/process-record.vue'))

declare const __APP_BUILD_INFO__: {
  commit: string
  commitTime: string
  buildTime: string
}

dayjs.locale('zh-cn')

const app = createApp(App)
bindVueFinderApp(app)

// 尽早接管发版后旧页面加载不到 chunk 的异常，避免菜单导航静默失效。
installAppRecovery(router, __APP_BUILD_INFO__)

// 1. 先安装 Pinia
app.use(createPinia())
registerAccessDirectives(app)
app.provide(nocodePlatformKey, {
  objects: createObjectApi(request),
  dataCenter: createDataCenterApi(request),
  objectData: createObjectDataApi(request),
  applications: createApplicationApi(request),
  runtime: createRuntimeApi(request),
  work: createWorkApi(request),
  bizFiles: createBusinessFileApi(request),
  recordFolders: createRecordFolderApi(request),
  taskCenter: createTaskCenterApi(request),
  reportCenter: createReportCenterApi(request),
  applicationDashboards: createApplicationDashboardRuntimeApi(request),
  directory: {
    users: async keyword =>
      (await getSimpleUserList(keyword)).map(user => ({ label: user.nickname || user.username, value: user.id })),
    departments: getDepartmentTree
  },
  hasPermission
})

// 2. 恢复用户状态并注册动态路由（处理页面刷新场景）
const userStore = useUserStore()

// 浏览器可能通过 BFCache 恢复旧的内存状态；发现它与当前持久化认证状态不一致时重载，
// 避免后退按钮重新展示已退出用户的系统页面。
window.addEventListener('pageshow', event => {
  if (!event.persisted) return
  const storedToken = localStorage.getItem('token') || ''
  const storedPasswordChangeToken = sessionStorage.getItem('passwordChangeToken') || ''
  if (
    !isAuthSnapshotConsistent(
      { token: userStore.token, passwordChangeToken: userStore.passwordChangeToken },
      { token: storedToken, passwordChangeToken: storedPasswordChangeToken }
    )
  ) {
    window.location.reload()
  }
})
initializeRealtimeRuntime({
  // 每次实际建连时读取，确保刷新 Token 或重新登录后不复用旧凭证。
  getToken: () => userStore.token || undefined
})

// 同步其他标签页的退出或账号切换，避免继续使用旧内存凭证。
window.addEventListener('storage', event => {
  if (event.storageArea === localStorage && (event.key === 'refreshToken' || event.key === null)) {
    if ((localStorage.getItem('refreshToken') || '') !== userStore.refreshToken) window.location.reload()
  }
})

async function bootstrap() {
  let permissionInfoRefreshed = false

  // 已登录浏览器的菜单缓存可能早于平台升级；每次启动以服务端权限和菜单为准。
  // 失败时仍沿用现有缓存恢复路径，真实数据访问继续由服务端鉴权。
  if (userStore.token) {
    try {
      userStore.applyPermissionInfo(await getUserInfo())
      permissionInfoRefreshed = true
    } catch (error) {
      console.warn('[App Init] 恢复用户权限失败:', error)
    }
  }

  if (userStore.token && userStore.menus.length > 0 && !permissionInfoRefreshed) {
    console.log('[App Init] 恢复动态路由...')
    registerDynamicRoutes(userStore.menus)
  }

  // 3. 安装路由（此时动态路由已注册）
  app.use(router)

  // 4. 安装 Antd（不再通过全局配置，而是 App.vue 中包裹 a-config-provider）
  app.use(Antd)

  // 5. 安装 AntdX (CSS-in-JS 样式注入)
  app.use(AntdX)

  app.mount('#app')
}

void bootstrap()
