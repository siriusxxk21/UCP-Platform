import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import 'ant-design-vue/dist/reset.css'
import '../../src/style.css'
import App from './App.vue'
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: App }] })
createApp(RouterView).use(createPinia()).use(Antd).use(router).mount('#app')
