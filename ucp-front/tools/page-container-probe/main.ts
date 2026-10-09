import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import FcDesigner from '@form-create/antd-designer'
import App from './App.vue'

createApp(App).use(Antd).use(FcDesigner).use(FcDesigner.formCreate).mount('#app')
