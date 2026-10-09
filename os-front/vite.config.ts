import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { execSync } from 'child_process'
import Components from 'unplugin-vue-components/vite'
import AutoImport from 'unplugin-auto-import/vite'
import { AntDesignXVueResolver } from 'ant-design-x-vue/resolver'

function readGitValue(command: string, fallback = 'unknown') {
  try {
    return (
      execSync(command, {
        cwd: __dirname,
        encoding: 'utf-8',
        stdio: ['ignore', 'pipe', 'ignore']
      }).trim() || fallback
    )
  } catch {
    return fallback
  }
}

function readBuildValue(env: Record<string, string>, key: string, fallback = 'unknown', gitCommand?: string) {
  return process.env[key] || env[key] || (gitCommand ? readGitValue(gitCommand, fallback) : fallback)
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 加载环境变量
  const env = loadEnv(mode, process.cwd(), '')
  const appBuildInfo = {
    commit: readBuildValue(env, 'VITE_APP_COMMIT', 'unknown', 'git rev-parse --short HEAD'),
    commitTime: readBuildValue(env, 'VITE_APP_COMMIT_TIME', 'unknown', 'git log -1 --format=%cI'),
    buildTime: readBuildValue(env, 'VITE_APP_BUILD_TIME', new Date().toISOString())
  }

  return {
    define: {
      __APP_BUILD_INFO__: JSON.stringify(appBuildInfo)
    },
    plugins: [
      vue(),
      // 自动导入 Vue3 常用 API，消除重复的 import 语句
      AutoImport({
        imports: [
          'vue', // ref, reactive, computed, watch, onMounted 等
          'vue-router', // useRouter, useRoute 等
          'pinia' // defineStore, storeToRefs 等
        ],
        // 生成 TypeScript 类型声明文件
        dts: 'src/auto-imports.d.ts',
        // 生成 ESLint globals 声明，避免 'no-undef' 报错
        eslintrc: {
          enabled: true,
          filepath: '.eslintrc-auto-import.json'
        },
        // 仅对 .vue 和 .ts 文件生效
        include: [/\.[tj]sx?$/, /\.vue$/]
      }),
      // 自动导入 Ant Design X Vue 组件
      Components({
        resolvers: [AntDesignXVueResolver()]
      })
    ],
    resolve: {
      alias: {
        '@': resolve(__dirname, './src')
      }
    },
    server: {
      host: '0.0.0.0',
      // 开发环境禁用缓存，确保热更新后的界面立即可见
      headers: {
        'Cache-Control': 'no-store',
        Pragma: 'no-cache'
      },
      proxy: {
        '/api': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true
        },
        // WebSocket 代理配置
        '/ws': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          ws: true
        },
        '/infra/ws': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
