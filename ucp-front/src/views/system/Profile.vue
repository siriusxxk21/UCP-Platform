<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'
import { useUserStore } from '@/stores/user'
import type { User } from '@/api/system/user'
import { getProfile, updatePassword, updateProfile } from '@/api/system/user'
import { uploadFile as uploadInfraFile } from '@/api/infra/file'
import { CropperAvatar } from '@/components/cropper'
import { UserOutlined } from '@ant-design/icons-vue'

const userStore = useUserStore()
const router = useRouter()
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*\d)\S{8,32}$/i

const displayName = computed(() => {
  return profileData.value?.nickname
    || userStore.userInfo?.nickname
    || userStore.userInfo?.username
    || '个人中心'
})

const formRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const activeTab = ref('basic')
const loading = ref(false)
const submitting = ref(false)
const passwordSubmitting = ref(false)
const profileData = ref<User | null>(null)
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const formState = reactive({
  nickname: '',
  avatar: '',
  email: '',
  phone: ''
})

const passwordState = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// 确认密码校验
async function validateConfirmPassword(_rule: Rule, value: string) {
  if (value !== passwordState.newPassword) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

const rules: Record<string, Rule[]> = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

const passwordRules: Record<string, Rule[]> = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: PASSWORD_PATTERN, message: '密码长度为 8-32 位，且必须包含字母和数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

function buildFileProxyUrl(configId: number, path: string) {
  const apiBase = baseURL.replace(/\/$/, '')
  const filePath = encodeURI(path).replace(/#/g, '%23').replace(/\?/g, '%3F')
  return new URL(`${apiBase}/infra/file/${configId}/get/${filePath}`, window.location.origin).toString()
}

function getUploadedFileUrl(file: Awaited<ReturnType<typeof uploadInfraFile>>) {
  if (file.configId && file.path) return buildFileProxyUrl(file.configId, file.path)
  if (file.url) return file.url
  return ''
}

async function handleAvatarUpload({ file, filename }: { file: Blob; filename: string }) {
  const normalizedFilename = `${(filename || 'avatar').replace(/\.[^.]+$/, '')}.png`
  const targetFile = new File([file], normalizedFilename, { type: 'image/png' })
  const uploadedFile = await uploadInfraFile({
    file: targetFile,
    directory: 'avatar'
  })
  const avatarUrl = getUploadedFileUrl(uploadedFile)
  if (!avatarUrl) throw new Error('empty avatar url')
  formState.avatar = avatarUrl
  return avatarUrl
}

// 加载用户信息
async function loadProfile() {
  loading.value = true
  try {
    const data = await getProfile()
    profileData.value = data
    formState.nickname = data.nickname || ''
    formState.avatar = data.avatar || ''
    formState.email = data.email || ''
    formState.phone = data.phone || ''
  } catch (error) {
    message.error('加载用户信息失败')
  } finally {
    loading.value = false
  }
}

// 保存基本信息修改
async function handleSubmit() {
  try {
    await formRef.value?.validate()
    submitting.value = true

    await updateProfile({
      nickname: formState.nickname,
      avatar: formState.avatar,
      email: formState.email,
      phone: formState.phone
    })

    // 更新 store 中的用户信息
    if (userStore.userInfo) {
      userStore.setUserInfo({
        ...userStore.userInfo,
        nickname: formState.nickname,
        avatar: formState.avatar,
        email: formState.email,
        phone: formState.phone
      })
    }

    message.success('保存成功')
  } catch (error) {
    // 表单验证失败或接口错误
  } finally {
    submitting.value = false
  }
}

// 重置基本信息表单
function handleReset() {
  if (profileData.value) {
    formState.nickname = profileData.value.nickname || ''
    formState.avatar = profileData.value.avatar || ''
    formState.email = profileData.value.email || ''
    formState.phone = profileData.value.phone || ''
  }
}

// 修改密码
async function handlePasswordSubmit() {
  try {
    await passwordFormRef.value?.validate()
    passwordSubmitting.value = true

    await updatePassword({
      oldPassword: passwordState.oldPassword,
      newPassword: passwordState.newPassword
    })

    userStore.logout()
    message.success('密码修改成功，请使用新密码重新登录')
    await router.replace('/login')
  } catch (error) {
    // 表单验证失败或接口错误
  } finally {
    passwordSubmitting.value = false
  }
}

// 重置密码表单
function handlePasswordReset() {
  passwordState.oldPassword = ''
  passwordState.newPassword = ''
  passwordState.confirmPassword = ''
  passwordFormRef.value?.clearValidate()
}

onMounted(() => {
  loadProfile()
})
</script>

<template>
  <div class="profile-container">
    <div class="profile-header">
      <div class="profile-header-info">
        <a-avatar :src="formState.avatar" :size="44">
          <template #icon><UserOutlined /></template>
        </a-avatar>
        <div>
          <h2>{{ displayName }}</h2>
          <p>维护个人资料、联系方式和登录密码</p>
        </div>
      </div>
    </div>

    <a-card :bordered="false" class="profile-card">
      <a-tabs v-model:active-key="activeTab">
        <a-tab-pane key="basic" tab="基本信息">
          <a-spin :spinning="loading">
            <a-form
              ref="formRef"
              :model="formState"
              :rules="rules"
              :label-col="{ xs: { span: 24 }, sm: { span: 5 } }"
              :wrapper-col="{ xs: { span: 24 }, sm: { span: 15 } }"
              class="profile-form"
            >
              <a-form-item label="用户名">
                <span class="form-text">{{ userStore.userInfo?.username }}</span>
              </a-form-item>

              <a-form-item label="昵称" name="nickname">
                <a-input v-model:value="formState.nickname" placeholder="请输入昵称" />
              </a-form-item>

              <a-form-item label="头像">
                <div class="avatar-upload">
                  <CropperAvatar
                    v-model:value="formState.avatar"
                    :show-btn="false"
                    :size="2"
                    :upload-api="handleAvatarUpload"
                    :width="80"
                  />
                  <div class="avatar-actions">
                    <div class="avatar-buttons">
                      <a-button v-if="formState.avatar" @click="formState.avatar = ''">移除</a-button>
                    </div>
                    <a-input
                      v-model:value="formState.avatar"
                      class="avatar-url-input"
                      placeholder="也可以直接填写头像 URL"
                    />
                  </div>
                </div>
              </a-form-item>

              <a-form-item label="邮箱" name="email">
                <a-input v-model:value="formState.email" placeholder="请输入邮箱" />
              </a-form-item>

              <a-form-item label="手机号" name="phone">
                <a-input v-model:value="formState.phone" placeholder="请输入手机号" />
              </a-form-item>

              <a-form-item label="组织">
                <span class="form-text">{{ profileData?.orgName || '-' }}</span>
              </a-form-item>

              <a-form-item label="部门">
                <span class="form-text">{{ profileData?.deptName || '-' }}</span>
              </a-form-item>

              <a-form-item label="岗位">
                <span class="form-text">{{ profileData?.post || '-' }}</span>
              </a-form-item>

              <a-form-item :wrapper-col="{ xs: { span: 24 }, sm: { offset: 5, span: 15 } }">
                <a-space>
                  <a-button :loading="submitting" type="primary" @click="handleSubmit">保存修改</a-button>
                  <a-button @click="handleReset">重置</a-button>
                </a-space>
              </a-form-item>
            </a-form>
          </a-spin>
        </a-tab-pane>

        <a-tab-pane key="password" tab="修改密码">
          <a-form
            ref="passwordFormRef"
            :model="passwordState"
            :rules="passwordRules"
            :label-col="{ xs: { span: 24 }, sm: { span: 4 } }"
            :wrapper-col="{ xs: { span: 24 }, sm: { span: 16 } }"
            class="profile-form"
          >
            <a-form-item label="旧密码" name="oldPassword">
              <a-input-password v-model:value="passwordState.oldPassword" placeholder="请输入旧密码" />
            </a-form-item>

            <a-form-item label="新密码" name="newPassword">
              <a-input-password v-model:value="passwordState.newPassword" placeholder="请输入新密码" />
            </a-form-item>

            <a-form-item label="确认密码" name="confirmPassword">
              <a-input-password v-model:value="passwordState.confirmPassword" placeholder="请再次输入新密码" />
            </a-form-item>

            <a-form-item :wrapper-col="{ xs: { span: 24 }, sm: { offset: 4, span: 16 } }">
              <a-space>
                <a-button :loading="passwordSubmitting" type="primary" @click="handlePasswordSubmit">修改密码</a-button>
                <a-button @click="handlePasswordReset">重置</a-button>
              </a-space>
            </a-form-item>
          </a-form>
        </a-tab-pane>
      </a-tabs>
    </a-card>
  </div>
</template>

<style scoped>
.profile-container {
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 4px 0 24px;
}

.profile-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}

.profile-header-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.profile-header-info :deep(.ant-avatar) {
  flex-shrink: 0;
}

.profile-header h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
}

.profile-header p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}

.profile-card {
  overflow: hidden;
}

.profile-form {
  max-width: 760px;
  margin: 0 auto;
  padding: 24px 0 8px;
}

.profile-card :deep(.ant-card-body) {
  padding: 20px 24px 24px;
}

.profile-card :deep(.ant-tabs-nav) {
  margin-bottom: 4px;
}

.profile-card :deep(.ant-tabs-content-holder) {
  min-height: 480px;
}

.profile-card :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.profile-card :deep(.ant-form-item-label) {
  font-weight: 500;
}

.profile-card :deep(.ant-input),
.profile-card :deep(.ant-input-affix-wrapper) {
  width: 100%;
}

.form-text {
  color: var(--text-primary);
  font-size: 14px;
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.avatar-actions {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.avatar-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.avatar-url-input {
  width: min(100%, 420px);
}

@media (max-width: 640px) {
  .profile-container {
    padding: 0 0 16px;
  }

  .profile-header {
    margin-bottom: 12px;
  }

  .profile-header h2 {
    font-size: 20px;
  }

  .profile-card :deep(.ant-card-body) {
    padding: 16px;
  }

  .profile-form {
    padding-top: 16px;
  }

  .avatar-upload {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .avatar-actions {
    width: 100%;
  }

  .avatar-url-input {
    width: 100%;
  }
}
</style>
