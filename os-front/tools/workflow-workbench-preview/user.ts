/** 仅用于内存表单夹具；不读取正式登录会话。 */
export const useUserStore = () => ({
  user: { id: 'preview', nickname: '模拟成员' },
  permissions: ['*:*:*'],
  roles: [],
  hasPermission: () => true
})
