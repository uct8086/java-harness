<script setup>
import { RouterLink, RouterView } from 'vue-router'
import { ref } from 'vue'

const collapsed = ref(false)
</script>

<template>
  <div class="layout" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <span class="logo">◆</span>
        <span class="brand-text" v-show="!collapsed">UCT8086-AI</span>
      </div>

      <nav class="nav">
        <RouterLink v-for="r in $router.options.routes" :key="r.path" :to="r.path" class="nav-item">
          <span class="nav-icon">{{ r.meta.icon }}</span>
          <span class="nav-label" v-show="!collapsed">{{ r.meta.title }}</span>
        </RouterLink>
      </nav>

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

.main {
  flex: 1;
  overflow: auto;
  padding: 24px 28px;
}
</style>
