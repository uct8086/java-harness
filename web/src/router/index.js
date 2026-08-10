import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '对话', icon: '💬' } },
  { path: '/sessions', name: 'sessions', component: () => import('@/views/SessionsView.vue'), meta: { title: '会话', icon: '🗂️' } },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolsView.vue'), meta: { title: '工具', icon: '🔧' } },
  { path: '/skills', name: 'skills', component: () => import('@/views/SkillsView.vue'), meta: { title: '技能', icon: '⚡' } },
  { path: '/memory', name: 'memory', component: () => import('@/views/MemoryView.vue'), meta: { title: '记忆', icon: '🧠' } },
  { path: '/knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { title: '知识库', icon: '📚' } },
  { path: '/mcp', name: 'mcp', component: () => import('@/views/McpView.vue'), meta: { title: 'MCP', icon: '🔌' } },
  { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置', icon: '⚙️' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${to.meta.title || ''} · UCT8086-AI`
})

export default router
