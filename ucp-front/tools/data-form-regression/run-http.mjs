import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { FormAcceptance } from './http-acceptance.mjs'
import * as scenarios from './http-scenarios.mjs'

const acceptance = new FormAcceptance(process.env.FORM_ACCEPTANCE_API)
try {
  await acceptance.login()
  await scenarios.prepare(acceptance)
  await scenarios.calculations(acceptance)
  await scenarios.permissions(acceptance)
  await acceptance.record('P05b Excel 文件内容与字段边界', async () => {
    const result = spawnSync(
      process.env.FORM_ACCEPTANCE_PYTHON || 'python',
      [
        fileURLToPath(new URL('./verify-permission-export.py', import.meta.url)),
        resolve(acceptance.output, 'permission-export.xlsx')
      ],
      { encoding: 'utf8', env: { ...process.env, PYTHONIOENCODING: 'utf-8' }, windowsHide: true }
    )
    if (result.status !== 0) throw new Error(result.error?.message || result.stderr || 'Excel 验证失败')
    return JSON.parse(result.stdout)
  })
  await scenarios.departmentAndRoles(acceptance)
  await scenarios.sharedCalculation(acceptance)
  await scenarios.fixedDictionary(acceptance)
  await scenarios.formulaBoundaries(acceptance)
  await scenarios.relationAndMultiple(acceptance)
  console.log(
    JSON.stringify({
      passed: true,
      applicationId: acceptance.app.application.id,
      checks: acceptance.checks.length,
      output: acceptance.output
    })
  )
} finally {
  // 保留可复核的业务样例，关闭本次测试账号的登录能力并移除其角色。
  for (const user of acceptance.owned.users) {
    await acceptance.api('/system/permission/assign-user-role', { userId: user.id, roleIds: [] })
    await acceptance.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
    user.status = 1
    user.roleIds = []
  }
  acceptance.passwords = {}
  acceptance.tokens = {}
  await acceptance.persist()
}
