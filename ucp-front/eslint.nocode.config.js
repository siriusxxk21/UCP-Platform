import antfu from '@antfu/eslint-config'

// 默认入口和专项入口共用同一范围，避免 IDE 使用另一套规则。
export const nocodeFiles = [
  'src/api/nocode/**/*.ts',
  'src/types/nocode/**/*.ts',
  'src/nocode/**/*.ts',
  'src/views/nocode/**/*.vue',
  'src/views/drive/business.vue',
  'src/views/drive/space.vue',
  'src/views/drive/components/PermissionDrawer.vue',
  'src/views/drive/components/BusinessFileNavigation.vue',
  'src/views/drive/permission-batch.ts',
  'src/views/drive/driver/drive-driver.ts',
  'src/views/drive/driver/drive-gateway.ts',
  'src/views/drive/components/DriveFolderBrowser.vue',
  'src/views/drive/components/DriveWorkspace.vue',
  'src/views/drive/components/EntryDetailDrawer.vue',
  'src/views/drive/trash.vue',
  'src/views/drive/recent.vue',
  'src/views/drive/favorite.vue',
  'src/views/drive/shared.vue',
  'src/views/drive/vuefinder-runtime.ts',
  'src/components/UserSelector/index.vue',
  'src/components/UserSelector/userSelection.ts',
  'src/api/drive/space.ts',
  'src/types/drive/index.ts',
  'src/views/bpm/processInstance/detail/MaterialFormView.vue',
  'tests/unit/nocode-transport.test.ts'
]

// nocode 沿用 Prettier 排版；ESLint 检查 TS/Vue 正确性与未使用代码。
// 独立入口不改变底座其他模块的既有 lint 规则。
export default antfu(
  {
    typescript: true,
    vue: true,
    stylistic: false,
    formatters: false,
    perfectionist: false,
    rules: {
      'one-var': 'off',
      'prefer-template': 'off',
      'import/consistent-type-specifier-style': 'off',
      'import/newline-after-import': 'off',
      'ts/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports', disallowTypeAnnotations: false }
      ],
      // some 保留窄联合类型的比较；这些写法偏好不承担正确性验证。
      'unicorn/prefer-includes': 'off',
      'unicorn/new-for-builtins': 'off',
      'regexp/prefer-w': 'off',
      'regexp/use-ignore-case': 'off',
      'regexp/no-unused-capturing-group': 'off',
      'ts/method-signature-style': 'off',
      'test/prefer-lowercase-title': 'off',
      // setup 的延迟闭包允许引用后置变量；保留警告，复核时确认实际调用顺序。
      'ts/no-use-before-define': ['warn', { functions: false, classes: false, variables: true }],
      'unicorn/error-message': 'warn',
      // 本项目使用 const + 同名 type 表达枚举，重复值声明由 vue-tsc 检查。
      'ts/no-redeclare': 'off',
      'vue/padding-line-between-blocks': 'off',
      'vue/html-indent': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/attributes-order': 'off',
      'vue/html-self-closing': 'off',
      'vue/define-macros-order': 'off',
      'vue/prefer-template': 'off',
      // 保留现有公开组件名、事件名和第三方桥接 prop 协议。
      'vue/component-definition-name-casing': 'off',
      'vue/custom-event-name-casing': 'off',
      'vue/prop-name-casing': 'off',
      'no-console': 'warn',
      'ts/no-explicit-any': 'warn',
      'ts/no-non-null-assertion': 'warn',
      'ts/no-empty-function': 'warn'
    }
  },
  {
    files: ['**/*.test.ts'],
    rules: { 'import/first': 'off' }
  }
)
