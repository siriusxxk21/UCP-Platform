<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { changeRequiredPassword, getUserInfo } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { resolvePostLoginPath } from '@/utils/authFlow'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)

const formState = reactive({
  newPassword: '',
  confirmPassword: '',
})

// 与后端 UserProfileUpdatePasswordReqVO 保持一致：8-32 位且含字母和数字
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*\d)\S{8,32}$/i

const rules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: PASSWORD_PATTERN, message: '密码长度为 8-32 位，且必须包含字母和数字', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        value === formState.newPassword ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
}

// 新密码实时强度提示
const passwordChecks = computed(() => [
  { label: '8-32 个字符', pass: /^\S{8,32}$/.test(formState.newPassword) },
  { label: '包含字母', pass: /[a-z]/i.test(formState.newPassword) },
  { label: '包含数字', pass: /\d/.test(formState.newPassword) },
])

async function handleSubmit() {
  if (!passwordChecks.value.every(c => c.pass)) {
    message.warning('新密码不满足安全要求，请检查后重试')
    return
  }
  if (!userStore.passwordChangeToken) {
    message.warning('改密凭证无效或已过期，请重新登录')
    await router.replace('/login')
    return
  }
  loading.value = true
  try {
    const loginResult = await changeRequiredPassword(userStore.passwordChangeToken, formState.newPassword)
    userStore.clearPasswordChange()
    userStore.setAuthTokens(loginResult.accessToken, loginResult.refreshToken, loginResult.expiresTime)
    const permissionInfo = await getUserInfo()
    userStore.setTenantInfo(null)
    const menus = userStore.applyPermissionInfo(permissionInfo)
    message.success('密码修改成功，已安全登录')
    await router.replace(resolvePostLoginPath(menus))
  }
  catch (error: any) {
    console.error('修改密码失败:', error)
    // 响应丢失时无法确认服务端是否已完成改密，清理预认证及可能的半成品会话，安全回退到登录页。
    userStore.logout()
    message.warning('无法建立新会话，请重新登录')
    await router.replace('/login')
  }
  finally {
    loading.value = false
  }
}

function handleLogout() {
  userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="change-password-page">
    <!-- 背景装饰层（与登录页视觉一致） -->
    <div class="bg-decor">
      <div class="grid-overlay" />
      <div class="floating-shapes">
        <div class="shape shape-hexagon shape-1" />
        <div class="shape shape-circle shape-2" />
        <div class="shape shape-square shape-3" />
        <div class="shape shape-ring shape-4" />
      </div>
      <div class="glow glow-top" />
      <div class="glow glow-bottom" />
    </div>

    <!-- 改密卡片 -->
    <div class="password-card">
      <div class="card-icon">
        <SafetyCertificateOutlined />
      </div>
      <h2 class="card-title">请修改密码</h2>
      <p class="card-subtitle">
        为保障账号安全，您正在使用初始密码或密码已过期，<br />请先设置新密码后再继续使用系统
      </p>

      <a-form :model="formState" :rules="rules" class="password-form" @finish="handleSubmit">
        <a-form-item name="newPassword">
          <a-input-password
            v-model:value="formState.newPassword"
            size="large"
            placeholder="请输入新密码（8-32 位，含字母和数字）"
            autocomplete="new-password"
          >
            <template #prefix>
              <LockOutlined class="input-prefix-icon" />
            </template>
          </a-input-password>
        </a-form-item>

        <!-- 强度提示 -->
        <div v-if="formState.newPassword" class="strength-hints">
          <span
            v-for="check in passwordChecks"
            :key="check.label"
            class="hint-item"
            :class="{ pass: check.pass }"
          >
            {{ check.pass ? '✓' : '○' }} {{ check.label }}
          </span>
        </div>

        <a-form-item name="confirmPassword">
          <a-input-password
            v-model:value="formState.confirmPassword"
            size="large"
            placeholder="请再次输入新密码"
            autocomplete="new-password"
          >
            <template #prefix>
              <LockOutlined class="input-prefix-icon" />
            </template>
          </a-input-password>
        </a-form-item>

        <a-form-item class="submit-item">
          <a-button :loading="loading" block class="submit-btn" html-type="submit" size="large" type="primary">
            确认修改
          </a-button>
        </a-form-item>
      </a-form>

      <div class="card-footer">
        <a class="logout-link" @click="handleLogout">退出登录</a>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 页面容器（与登录页一致的主题背景） ---------- */
.change-password-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #0f0d2e 0%, #1e1b4b 30%, #312e81 60%, #4338ca 100%);
  overflow: hidden;
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

/* ---------- 背景装饰层 ---------- */
.bg-decor {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}

.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 60px 60px;
  mask-image: radial-gradient(ellipse 80% 60% at 50% 50%, black 30%, transparent 70%);
  -webkit-mask-image: radial-gradient(ellipse 80% 60% at 50% 50%, black 30%, transparent 70%);
}

.shape {
  position: absolute;
  opacity: 0.06;
  animation: floatShape 15s ease-in-out infinite;
}

.shape-hexagon {
  width: 180px;
  height: 160px;
  clip-path: polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%);
  background: rgba(99, 102, 241, 0.15);
}

.shape-circle {
  width: 200px;
  height: 200px;
  border-radius: 50%;
  border: 2px solid rgba(167, 139, 250, 0.3);
  background: radial-gradient(circle, rgba(167, 139, 250, 0.08), transparent 70%);
}

.shape-square {
  width: 120px;
  height: 120px;
  border-radius: 12px;
  border: 2px solid rgba(99, 102, 241, 0.25);
  transform: rotate(15deg);
}

.shape-ring {
  width: 140px;
  height: 140px;
  border-radius: 50%;
  border: 3px dashed rgba(255, 255, 255, 0.12);
}

.shape-1 { top: 10%; left: 8%; }
.shape-2 { top: 18%; right: 10%; width: 160px; height: 160px; animation-delay: -3s; }
.shape-3 { bottom: 18%; left: 12%; animation-delay: -6s; }
.shape-4 { bottom: 12%; right: 18%; animation-delay: -9s; }

@keyframes floatShape {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  25% { transform: translateY(-20px) rotate(3deg); }
  50% { transform: translateY(-10px) rotate(-2deg); }
  75% { transform: translateY(-25px) rotate(1deg); }
}

.glow {
  position: absolute;
  width: 500px;
  height: 500px;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.15;
}

.glow-top { top: -150px; left: -100px; background: #6366f1; }
.glow-bottom { bottom: -150px; right: -100px; background: #4338ca; }

/* ---------- 改密卡片（玻璃拟态） ---------- */
.password-card {
  position: relative;
  z-index: 1;
  width: 420px;
  max-width: 92vw;
  padding: 40px 40px 28px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(12px);
  box-shadow:
    0 25px 60px rgba(0, 0, 0, 0.4),
    0 0 0 1px rgba(255, 255, 255, 0.08);
  text-align: center;
}

.card-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  color: #fff;
  border-radius: 14px;
  background: linear-gradient(135deg, #4338ca, #6366f1);
  box-shadow: 0 8px 20px rgba(67, 56, 202, 0.35);
}

.card-title {
  font-size: 24px;
  font-weight: 600;
  color: #1f2329;
  margin: 0 0 8px;
  letter-spacing: 1px;
}

.card-subtitle {
  font-size: 13px;
  color: #86909c;
  line-height: 1.7;
  margin: 0 0 24px;
}

/* ---------- 表单 ---------- */
.password-form {
  text-align: left;
}

.password-form :deep(.ant-form-item) {
  margin-bottom: 18px;
}

.password-form :deep(.ant-input-affix-wrapper) {
  border-radius: 8px;
  border-color: #e5e7eb;
  padding: 8px 12px;
  transition: all 0.25s ease;
}

.password-form :deep(.ant-input-affix-wrapper:hover) {
  border-color: #a5b4fc;
}

.password-form :deep(.ant-input-affix-wrapper-focused) {
  border-color: #4338ca;
  box-shadow: 0 0 0 3px rgba(67, 56, 202, 0.1);
}

.input-prefix-icon {
  color: #9ca3af;
  font-size: 16px;
}

/* 强度提示 */
.strength-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin: -10px 0 16px;
  padding: 0 2px;
}

.hint-item {
  font-size: 12px;
  color: #86909c;
  transition: color 0.2s ease;
}

.hint-item.pass {
  color: #52c41a;
}

/* 提交按钮 */
.submit-item {
  margin-top: 8px;
  margin-bottom: 0 !important;
}

.submit-btn {
  height: 44px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4338ca, #6366f1);
  border: none;
  box-shadow: 0 4px 16px rgba(67, 56, 202, 0.3);
  transition: all 0.3s ease;
}

.submit-btn:hover {
  background: linear-gradient(135deg, #3730a3, #4f46e5);
  box-shadow: 0 6px 24px rgba(67, 56, 202, 0.45);
  transform: translateY(-1px);
}

.submit-btn:active {
  transform: translateY(0);
}

/* 底部 */
.card-footer {
  margin-top: 16px;
}

.logout-link {
  font-size: 13px;
  color: #86909c;
  cursor: pointer;
  transition: color 0.2s ease;
}

.logout-link:hover {
  color: #4338ca;
}

/* ---------- 响应式适配 ---------- */
@media (max-width: 768px) {
  .password-card {
    padding: 32px 24px 24px;
  }

  .floating-shapes {
    display: none;
  }
}
</style>
