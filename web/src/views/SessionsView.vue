<script setup>
import { ref, onMounted } from 'vue'
import { listSessions, createSession, deleteSession } from '@/api/client'
import { formatTime, formatNumber } from '@/utils/format'

const sessions = ref([])
const loading = ref(false)
const newName = ref('')
const PAGE_SIZE = 20
const hasMore = ref(false)

async function load() {
  loading.value = true
  try {
    sessions.value = await listSessions(0, PAGE_SIZE)
    hasMore.value = sessions.value.length === PAGE_SIZE
  } finally { loading.value = false }
}

async function loadMore() {
  if (loading.value) return
  loading.value = true
  try {
    const more = await listSessions(sessions.value.length, PAGE_SIZE)
    sessions.value.push(...more)
    hasMore.value = more.length === PAGE_SIZE
  } finally { loading.value = false }
}

async function create() {
  if (!newName.value.trim()) return
  const s = await createSession(newName.value.trim())
  sessions.value.unshift(s)
  newName.value = ''
}

async function remove(id) {
  if (!confirm('确认删除该会话？')) return
  await deleteSession(id)
  sessions.value = sessions.value.filter(s => s.id !== id)
}

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>会话</h1>
        <p class="muted">管理对话会话。</p>
      </div>
      <div class="row">
        <input v-model="newName" placeholder="会话名称" @keydown.enter="create" />
        <button class="btn btn-primary" @click="create">＋ 新建</button>
        <button class="btn" @click="load" :disabled="loading">刷新</button>
      </div>
    </header>

    <div v-if="loading && !sessions.length" class="empty">加载中…</div>
    <div v-else-if="!sessions.length" class="empty">暂无会话</div>

    <div class="grid">
      <div v-for="s in sessions" :key="s.id" class="card item">
        <div class="spread">
          <strong>{{ s.name || '（未命名）' }}</strong>
          <button class="btn btn-danger" @click="remove(s.id)">删除</button>
        </div>
        <div class="mono muted small id">{{ s.id }}</div>
        <div class="row muted small wrap" style="margin-top: 8px; gap: 16px;">
          <span>消息 {{ formatNumber(s.messageCount) }}</span>
          <span>创建 {{ formatTime(s.createdAt) }}</span>
          <span>更新 {{ formatTime(s.updatedAt) }}</span>
        </div>
      </div>
    </div>

    <div v-if="hasMore" class="load-more">
      <button class="btn" :disabled="loading" @click="loadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
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
.id { font-size: 12px; margin-top: 6px; word-break: break-all; }
.small { font-size: 13px; }
.load-more { display: flex; justify-content: center; padding: 8px 0; }
</style>
