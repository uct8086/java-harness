import { createRouter, createWebHistory } from 'vue-router'
import { getCurrentUser } from '@/api/client'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录', public: true } },
  { path: '/', name: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '对话', icon: '💬' } },
  { path: '/sessions', name: 'sessions', component: () => import('@/views/SessionsView.vue'), meta: { title: '会话', icon: '🗂️' } },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolsView.vue'), meta: { title: '工具', icon: '🔧' } },
  { path: '/skills', name: 'skills', component: () => import('@/views/SkillsView.vue'), meta: { title: '技能', icon: '⚡' } },
  { path: '/memory', name: 'memory', component: () => import('@/views/MemoryView.vue'), meta: { title: '记忆', icon: '🧠' } },
  { path: '/knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { title: '知识库', icon: '📚' } },
  { path: '/mcp', name: 'mcp', component: () => import('@/views/McpView.vue'), meta: { title: 'MCP', icon: '🔌' } },
  { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置', icon: '⚙️' } },
  // Catch-all: unknown paths redirect to home (guard still applies for auth).
  { path: '/:pathMatch(.*)*', name: 'not-found', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// Global guard: redirect to /login when not authenticated (except public routes).
// The redirect carries the intended destination so login can return the user there.
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  try {
    await getCurrentUser()
    return true
  } catch (e) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
})

router.afterEach((to) => {
  document.title = `${to.meta.title || ''} · UCT8086-AI`
})

export default router
