<script setup>
import { ref, onMounted } from 'vue'
import { listTools } from '@/api/client'

const tools = ref([])
const loading = ref(false)
const filter = ref('')

async function load() {
  loading.value = true
  try { tools.value = await listTools() } finally { loading.value = false }
}

const filtered = () =>
  tools.value.filter(t => {
    const q = filter.value.trim().toLowerCase()
    if (!q) return true
    return t.name?.toLowerCase().includes(q) || t.description?.toLowerCase().includes(q) || t.category?.toLowerCase().includes(q)
  })

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>工具</h1>
        <p class="muted">Agent 可调用的工具注册表。</p>
      </div>
      <div class="row">
        <input v-model="filter" placeholder="过滤名称/描述/类别…" />
        <button class="btn" @click="load" :disabled="loading">刷新</button>
      </div>
    </header>

    <div v-if="loading && !tools.length" class="empty">加载中…</div>
    <div v-else-if="!filtered().length" class="empty">无匹配工具</div>

    <div class="grid">
      <div v-for="t in filtered()" :key="t.name" class="card item">
        <div class="spread">
          <span class="mono" style="font-weight:600;">{{ t.name }}</span>
          <div class="row">
            <span class="badge badge-info">{{ t.category }}</span>
            <span class="badge" :class="t.isReadOnly ? 'badge-success' : 'badge-warning'">
              {{ t.isReadOnly ? '只读' : '可写' }}
            </span>
          </div>
        </div>
        <p class="muted small" style="margin: 8px 0 0;">{{ t.description }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.item { padding: 16px; }
.small { font-size: 13px; }
</style>
