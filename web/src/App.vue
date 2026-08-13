<script setup>
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ref, computed, watch } from 'vue'
import { getCurrentUser, logout } from '@/api/client'

const collapsed = ref(false)
const user = ref(null)
const router = useRouter()
const route = useRoute()

// Login page renders standalone (no sidebar/chrome).
const isLoginPage = computed(() => route.name === 'login')

// Only show nav entries that are real, navigable pages (have an icon in meta).
// This excludes the login route and the catch-all 404 redirect route.
const navRoutes = computed(() =>
  router.options.routes.filter((r) => r.meta && r.meta.icon)
)

async function loadUser() {
  if (isLoginPage.value) {
    user.value = null
    return
  }
  try {
    user.value = await getCurrentUser()
  } catch (e) {
    user.value = null
  }
}

// Reload user info whenever the route changes (e.g. after login redirects to home).
watch(isLoginPage, loadUser, { immediate: true })

async function onLogout() {
  try {
    await logout()
  } finally {
    user.value = null
    router.replace('/login')
  }
}
</script>

<template>
  <RouterView v-if="isLoginPage" />

  <div v-else class="layout" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <span class="logo">◆</span>
        <span class="brand-text" v-show="!collapsed">UCT8086-AI</span>
      </div>

      <nav class="nav">
        <RouterLink v-for="r in navRoutes" :key="r.path" :to="r.path" class="nav-item">
          <span class="nav-icon">{{ r.meta.icon }}</span>
          <span class="nav-label" v-show="!collapsed">{{ r.meta.title }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-footer" v-if="user">
        <span class="user-name" v-show="!collapsed">{{ user.displayName || user.username }}</span>
        <button class="logout-btn" @click="onLogout" :title="collapsed ? '退出登录' : ''">
          <span v-show="!collapsed">退出</span>
          <span v-show="collapsed">⏻</span>
        </button>
      </div>

      <button class="collapse-btn" @click="collapsed = !collapsed">
        <span v-show="!collapsed">‹ 收起</span>
        <span v-show="collapsed">›</span>
      </button>
    </aside>

    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--bg);
  color: #e2e8f0;
  display: flex;
  flex-direction: column;
  transition: width .2s ease;
}
.layout.collapsed .sidebar { width: 64px; }

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 18px;
  font-weight: 700;
  font-size: 16px;
  border-bottom: 1px solid rgba(255,255,255,.08);
}
.logo { color: #818cf8; }

.nav {
  flex: 1;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  color: #cbd5e1;
  font-size: 14px;
  transition: background .15s, color .15s;
}
.nav-item:hover { background: rgba(255,255,255,.06); color: #fff; }
.nav-item.router-link-active { background: var(--primary); color: #fff; }
.nav-icon { font-size: 16px; width: 20px; text-align: center; }

.collapse-btn {
  margin: 10px;
  padding: 8px;
  background: rgba(255,255,255,.06);
  color: #94a3b8;
  border: none;
  border-radius: 8px;
  font-size: 13px;
}
.collapse-btn:hover { background: rgba(255,255,255,.12); color: #fff; }

.sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid rgba(255,255,255,.08);
}
.user-name {
  font-size: 13px;
  color: #cbd5e1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.logout-btn {
  padding: 5px 10px;
  background: rgba(255,255,255,.06);
  color: #94a3b8;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.logout-btn:hover { background: rgba(248,113,113,.15); color: #f87171; }

.main {
  flex: 1;
  overflow: auto;
  padding: 24px 28px;
}
</style>
