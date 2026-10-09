<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons-vue'
import { getUserInfo, login } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { LegacyLoginResult } from '@/types/auth'
import { isPasswordChangeRequired, resolvePostLoginPath } from '@/utils/authFlow'

const router = useRouter()
const userStore = useUserStore()
const REMEMBERED_ACCOUNT_STORAGE_KEY = 'login.rememberedAccount'
const loading = ref(false)
const canvasScale = ref(1)
const isCompact = ref(false)
const rememberAccount = ref(false)

function updateCanvasScale() {
  isCompact.value = window.innerWidth < 1100
  canvasScale.value = isCompact.value ? 1 : Math.min(window.innerWidth / 1680, window.innerHeight / 945)
}

onMounted(() => {
  updateCanvasScale()
  window.addEventListener('resize', updateCanvasScale, { passive: true })
})

onBeforeUnmount(() => window.removeEventListener('resize', updateCanvasScale))

const formState = reactive({
  username: '',
  password: ''
})

/** 恢复用户主动保存的账号；密码和认证凭证不在该功能的存储边界内。 */
function restoreRememberedAccount() {
  const rememberedAccount = localStorage.getItem(REMEMBERED_ACCOUNT_STORAGE_KEY)
  if (!rememberedAccount) return

  formState.username = rememberedAccount
  rememberAccount.value = true
}

/** 在提交登录时同步用户的记忆选择，避免保存尚未确认要使用的输入内容。 */
function persistRememberedAccount() {
  const account = formState.username.trim()
  if (rememberAccount.value && account) {
    localStorage.setItem(REMEMBERED_ACCOUNT_STORAGE_KEY, account)
    return
  }

  localStorage.removeItem(REMEMBERED_ACCOUNT_STORAGE_KEY)
}

onMounted(restoreRememberedAccount)

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

function isLegacyLoginResult(value: unknown): value is LegacyLoginResult {
  return !!value && typeof value === 'object' && 'token' in value
}

async function handleLogin() {
  persistRememberedAccount()
  loading.value = true
  try {
    const params = {
      username: formState.username,
      password: formState.password
    } as any
    const res = await login(params)

    let menus: any[] = []
    if (!isLegacyLoginResult(res) && isPasswordChangeRequired(res)) {
      userStore.beginPasswordChange(res.passwordChangeToken)
      message.warning('请先修改初始密码后再继续使用')
      await router.replace('/change-password')
      return
    }
    if (isLegacyLoginResult(res)) {
      userStore.setToken(res.token)
      userStore.setUserInfo(res.userInfo as any)
      userStore.setAccess([], [])
      if (res.tenantInfo) {
        userStore.setTenantInfo(res.tenantInfo)
      }
      menus = userStore.normalizeMenus(res.menus)
      userStore.setMenus(menus, true)
    } else {
      userStore.clearPasswordChange()
      userStore.setAuthTokens(res.accessToken, res.refreshToken, res.expiresTime)
      const permissionInfo = await getUserInfo()
      userStore.setTenantInfo(null)
      menus = userStore.applyPermissionInfo(permissionInfo)
    }

    message.success('登录成功')

    // 密码临期软提醒
    if (userStore.passwordRemainDays !== null && userStore.passwordRemainDays <= userStore.passwordRemindDays) {
      Modal.warning({
        title: '密码即将过期',
        content: `您的密码将在 ${userStore.passwordRemainDays} 天后过期，建议尽快修改`,
        okText: '去修改',
        cancelText: '暂不',
        okCancel: true,
        onOk: () => router.push('/profile')
      })
    }

    // 登录后统一进入基础平台首页
    await router.replace(resolvePostLoginPath(menus))
  } catch (error: any) {
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div
      class="design-canvas"
      :class="{ 'is-compact': isCompact }"
      :style="isCompact ? undefined : { transform: `translate(-50%, -50%) scale(${canvasScale})` }"
    >
      <header class="page-brand">
        <img src="/logo/richuang.png" alt="日创os" class="logo-icon" />
        <span>日创os管理平台</span>
      </header>

      <main class="login-stage">
        <section class="form-panel">
          <div class="form-card">
            <div class="form-header">
              <div class="security-status">
                <span class="status-dot" />
                <span>SECURE ACCESS</span>
                <i />
                <span>安全访问</span>
              </div>
              <h2 class="form-title">欢迎登录</h2>
            </div>

            <a-form :model="formState" :rules="rules" class="login-form" @finish="handleLogin">
              <a-form-item name="username">
                <a-input
                  v-model:value="formState.username"
                  size="large"
                  placeholder="请输入用户名"
                  autocomplete="username"
                >
                  <template #prefix>
                    <UserOutlined class="input-prefix-icon" />
                  </template>
                </a-input>
              </a-form-item>

              <a-form-item name="password">
                <a-input-password
                  v-model:value="formState.password"
                  size="large"
                  placeholder="请输入密码"
                  autocomplete="current-password"
                >
                  <template #prefix>
                    <LockOutlined class="input-prefix-icon" />
                  </template>
                </a-input-password>
              </a-form-item>

              <div class="form-options">
                <a-checkbox v-model:checked="rememberAccount">记住账号</a-checkbox>
                <button type="button" class="forgot-password" @click="message.info('请联系系统管理员重置密码')">
                  忘记密码？
                </button>
              </div>

              <a-form-item class="submit-item">
                <a-button :loading="loading" block class="login-btn" html-type="submit" size="large" type="primary">
                  登 录
                </a-button>
              </a-form-item>
            </a-form>

            <div class="form-footer">
              <SafetyCertificateOutlined />
              <span>统一身份认证 · 数据传输加密</span>
            </div>
          </div>
        </section>
      </main>

      <footer class="page-footer">Copyright © 2026</footer>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  width: 100vw;
  height: 100vh;
  min-width: 320px;
  min-height: 620px;
  overflow: hidden;
  color: #fff;
  background: #03081d url('/images/login/devsecops-background-v1.png') center / cover no-repeat;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.design-canvas {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 1680px;
  height: 945px;
  overflow: hidden;
  background: transparent;
  transform-origin: center;
}

.page-brand {
  position: absolute;
  top: 49px;
  left: 47px;
  z-index: 2;
  display: flex;
  align-items: center;
  color: #000;
  font-size: 24px;
  font-weight: 700;
  line-height: 44px;
}

.logo-icon {
  width: 44px;
  height: 44px;
  margin-right: 14px;
  border-radius: 12px;
  box-shadow: 0 9px 28px rgba(76, 52, 237, 0.32);
}

.login-stage {
  position: absolute;
  inset: 0;
  z-index: 1;
}

.form-panel {
  position: absolute;
  top: 174px;
  left: 1115px;
  width: 422px;
  height: 592px;
  overflow: hidden;
  color: #111936;
  background:
    radial-gradient(circle at 92% 2%, rgba(106, 88, 235, 0.075), transparent 31%),
    linear-gradient(155deg, rgba(255, 255, 255, 0.995), rgba(248, 249, 253, 0.985));
  border: 1px solid rgba(226, 229, 241, 0.94);
  border-radius: 20px;
  box-shadow:
    0 32px 84px rgba(1, 4, 28, 0.42),
    0 10px 28px rgba(32, 23, 103, 0.12),
    0 0 0 1px rgba(255, 255, 255, 0.82) inset;
}

.form-panel::before {
  position: absolute;
  top: 0;
  right: 48px;
  left: 48px;
  height: 2px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(105, 82, 238, 0.48), transparent);
}

.form-card {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  padding: 0 37px;
}

.form-header {
  padding-top: 39px;
  text-align: left;
}

.security-status {
  width: max-content;
  min-height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 34px;
  padding: 0 12px;
  color: #1caf6c;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.2px;
  background: rgba(238, 249, 244, 0.72);
  border: 1px solid rgba(38, 176, 111, 0.12);
  border-radius: 20px;
}

.security-status i {
  width: 1px;
  height: 14px;
  margin: 0 10px;
  background: #798198;
  transform: rotate(16deg);
}

.security-status span:last-child {
  color: #646d83;
}

.status-dot {
  width: 8px;
  height: 8px;
  margin-right: 8px;
  border-radius: 50%;
  background: #28b875;
  box-shadow: 0 0 0 4px rgba(40, 184, 117, 0.06);
}

.form-title {
  margin: 0;
  color: #0a1231;
  font-size: 36px;
  line-height: 43px;
  font-weight: 750;
}

.login-form :deep(.ant-form-item) {
  margin-bottom: 17px;
}
.login-form :deep(.ant-form-item:nth-child(2)) {
  margin-bottom: 24px;
}

.login-form :deep(.ant-input-affix-wrapper) {
  height: 52px;
  padding: 0 17px !important;
  color: #111936;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid #dce0eb;
  border-radius: 11px;
  box-shadow:
    0 3px 12px rgba(28, 35, 70, 0.035),
    0 1px 0 rgba(255, 255, 255, 0.8) inset;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background 0.2s ease;
}

.login-form :deep(.ant-input-affix-wrapper:hover) {
  background: #fff;
  border-color: #aaa0ee;
  box-shadow: 0 5px 16px rgba(67, 50, 164, 0.07);
}
.login-form :deep(.ant-input-affix-wrapper-focused) {
  background: #fff;
  border-color: #654be5;
  box-shadow:
    0 0 0 3px rgba(97, 72, 228, 0.1),
    0 8px 20px rgba(67, 50, 164, 0.08);
}
.login-form :deep(.ant-input) {
  color: #141c3b;
  font-size: 16px;
  background: transparent;
}
.login-form :deep(.ant-input::placeholder) {
  color: #8c96ae;
  font-size: 16px;
}
.login-form :deep(.ant-input-password .ant-input-suffix) {
  color: #7e88a5;
  font-size: 17px;
}

.input-prefix-icon {
  margin-right: 12px;
  color: #7e88a5;
  font-size: 20px;
}

.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #3f4963;
  font-size: 15px;
}

.form-options :deep(.ant-checkbox-wrapper) {
  color: #3f4963;
  font-size: 15px;
}
.form-options :deep(.ant-checkbox-inner) {
  width: 20px;
  height: 20px;
  border-radius: 5px;
}
.form-options :deep(.ant-checkbox:not(.ant-checkbox-checked) .ant-checkbox-inner) {
  background: #fff;
  border-color: #aeb6c8;
}

.forgot-password {
  padding: 0;
  color: #5238e4;
  font: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.forgot-password:hover {
  color: #3923c3;
}
.submit-item {
  margin-top: 25px;
  margin-bottom: 0 !important;
}

.login-btn {
  height: 54px;
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 5px;
  background: linear-gradient(105deg, #442bd2 0%, #6549ee 52%, #4b30dc 100%);
  border: 0;
  border-radius: 11px;
  box-shadow:
    0 12px 28px rgba(75, 50, 220, 0.31),
    0 1px 0 rgba(255, 255, 255, 0.2) inset;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    background 0.2s ease;
}

.login-btn:hover {
  background: linear-gradient(105deg, #3d25c6, #5c40e4 52%, #4329d0);
  box-shadow: 0 15px 32px rgba(75, 50, 220, 0.36);
  transform: translateY(-1px);
}
.login-btn:active {
  box-shadow: 0 8px 20px rgba(75, 50, 220, 0.28);
  transform: translateY(0);
}

.form-footer {
  min-height: 70px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: auto -37px 0;
  color: #75809a;
  font-size: 15px;
  background: linear-gradient(180deg, rgba(247, 248, 252, 0.3), rgba(240, 242, 248, 0.72));
  border-top: 1px solid rgba(220, 224, 235, 0.82);
}

.form-footer :deep(.anticon) {
  color: #7a86a4;
  font-size: 19px;
}

.login-form :deep(.ant-input-affix-wrapper input:-webkit-autofill),
.login-form :deep(.ant-input-affix-wrapper input:-webkit-autofill:hover),
.login-form :deep(.ant-input-affix-wrapper input:-webkit-autofill:focus) {
  -webkit-box-shadow: 0 0 0 30px #fff inset !important;
  -webkit-text-fill-color: #141c3b !important;
}

.page-footer {
  position: absolute;
  right: 0;
  bottom: 48px;
  left: 0;
  z-index: 2;
  color: rgba(198, 203, 231, 0.58);
  font-size: 14px;
  text-align: center;
}

@media (max-width: 1099px) {
  /* 使用正常文档流，软键盘、横屏和错误提示出现时仍能滚到登录按钮。 */
  .login-page {
    height: 100dvh;
    min-height: 0;
    min-width: 0;
    overflow-y: auto;
  }

  .design-canvas.is-compact {
    position: relative;
    top: auto;
    left: auto;
    width: 100%;
    height: auto;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    gap: 24px;
    padding: calc(24px + env(safe-area-inset-top, 0px)) 16px calc(24px + env(safe-area-inset-bottom, 0px));
    transform: none;
    overflow: visible;
  }

  .design-canvas.is-compact .page-footer {
    display: none;
  }

  .design-canvas.is-compact .page-brand {
    position: static;
    color: white;
    font-size: 20px;
    line-height: 38px;
  }
  .design-canvas.is-compact .logo-icon {
    width: 38px;
    height: 38px;
  }
  .design-canvas.is-compact .login-stage {
    position: static;
    display: flex;
    flex: 1;
    align-items: center;
    justify-content: center;
  }

  .design-canvas.is-compact .form-panel {
    position: relative;
    top: auto;
    left: auto;
    width: min(422px, 100%);
    height: auto;
    min-height: 0;
    transform: none;
  }
  .form-card {
    height: auto;
  }
  .form-footer {
    margin-top: 24px;
    padding: 18px 8px;
  }
}

@media (max-width: 520px) {
  .design-canvas.is-compact .page-brand {
    font-size: 18px;
  }
  .design-canvas.is-compact .form-panel {
    width: 100%;
  }
  .form-card {
    padding: 0 25px;
  }
  .form-header {
    padding-top: 27px;
  }
  .security-status {
    margin-bottom: 27px;
  }
  .form-title {
    font-size: 30px;
    line-height: 38px;
  }
  .login-form :deep(.ant-input-affix-wrapper) {
    height: 50px;
  }
  .login-btn {
    height: 52px;
  }
  .form-footer {
    min-height: 62px;
    margin-right: -25px;
    margin-left: -25px;
    font-size: 13px;
  }
}
</style>
