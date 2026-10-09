import antfu from '@antfu/eslint-config'
import autoImportGlobals from './.eslintrc-auto-import.json' with { type: 'json' }
import nocodeConfig, { nocodeFiles } from './eslint.nocode.config.js'

const baseConfig = await antfu({
  vue: true,
  typescript: true,

  // 注入 unplugin-auto-import 自动生成的全局变量声明
  languageOptions: {
    globals: autoImportGlobals.globals ?? {}
  },
  // 关闭部分过于严格的规则，适配现有代码风格
  rules: {
    // 允许 console（开发调试用）
    'no-console': 'warn',

    // 允许单行箭头函数不加括号
    'arrow-parens': ['error', 'as-needed'],

    // 关闭强制 import 排序（现有代码未使用）
    'perfectionist/sort-imports': 'off',
    'import/order': 'off',

    // Vue 组件名允许单个单词（现有页面组件大量使用）
    'vue/multi-word-component-names': 'off',

    // 允许 any 类型（现有代码中存在，逐步改善）
    '@typescript-eslint/no-explicit-any': 'warn',

    // 关闭强制使用 const 解构（现有代码风格不统一）
    'prefer-destructuring': 'off',

    // 允许空函数（placeholder 场景）
    '@typescript-eslint/no-empty-function': 'warn',

    // 关闭 ts 注释要求（现有代码不统一）
    '@typescript-eslint/ban-ts-comment': 'off',

    // 允许非空断言 !（现有代码中存在）
    '@typescript-eslint/no-non-null-assertion': 'warn'
  },

  // 忽略自动生成的文件
  ignores: [
    'dist/**',
    'node_modules/**',
    'src/auto-imports.d.ts',
    'components.d.ts',
    '*.min.js',
    // Playwright E2E 测试目录（使用 test/expect 全局，由 @playwright/test 管理）
    'tests/**/*',
    '!tests/unit/',
    '!tests/unit/nocode-transport.test.ts',
    'playwright.config.ts'
  ]
})

const isGlobalIgnore = config => Object.keys(config).every(key => key === 'name' || key === 'ignores')

export default [
  // 全局产物忽略保持生效；其余底座配置仅跳过已交给领域规则的文件。
  ...baseConfig.map(config =>
    isGlobalIgnore(config) ? config : { ...config, ignores: [...(config.ignores || []), ...nocodeFiles] }
  ),
  ...(await nocodeConfig)
    .filter(config => !isGlobalIgnore(config))
    .map(config => ({
      ...config,
      // 内层数组是 AND 条件，保留 Vue、TS、测试各自的原始匹配范围。
      files: config.files
        ? config.files.flatMap(pattern => nocodeFiles.map(scope => [scope, ...[pattern].flat()]))
        : nocodeFiles
    }))
]
