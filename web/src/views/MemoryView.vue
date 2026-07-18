<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listMemory, addMemory, searchMemory } from '@/api/client'
import { formatTime } from '@/utils/format'

const entries = ref([])
const loading = ref(false)
const keyword = ref('')
const form = reactive({ category: '', content: '' })
const saving = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try { entries.value = await listMemory() } finally { loading.value = false }
}

async function submit() {
  error.value = ''
  if (!form.content.trim()) { error.value = '内容为必填'; return }
  saving.value = true
  try {
    const e = await addMemory(form.category.trim() || 'general', form.content)
    entries.value.unshift(e)
    form.category = form.content = ''
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function search() {
  loading.value = true
  try {
    entries.value = keyword.value.trim()
      ? await searchMemory(keyword.value.trim())
      : await listMemory()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>记忆</h1>
        <p class="muted">持久化记忆条目（MEMORY.md）。</p>
      </div>
      <div class="row">
        <input v-model="keyword" placeholder="搜索关键词…" @keydown.enter="search" />
        <button class="btn" @click="search">搜索</button>
        <button class="btn" @click="load">全部</button>
      </div>
    </header>

    <section class="card form">
      <h2>添加记忆</h2>
      <div class="col">
        <input v-model="form.category" placeholder="分类 (category)" />
        <textarea v-model="form.content" placeholder="内容…" rows="3"></textarea>
        <div class="row">
          <div v-if="error" class="alert">{{ error }}</div>
          <button class="btn btn-primary grow" style="justify-content:center;" :disabled="saving" @click="submit">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </section>

    <div v-if="loading && !entries.length" class="empty">加载中…</div>
    <div v-else-if="!entries.length" class="empty">暂无记忆</div>

    <div class="grid">
      <div v-for="e in entries" :key="e.id" class="card item">
        <div class="spread">
          <span class="badge badge-info">{{ e.category }}</span>
          <span class="muted small">{{ formatTime(e.createdAt) }}</span>
        </div>
        <p class="content">{{ e.content }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }
.form { padding: 18px; }
.form h2 { margin: 0 0 12px; font-size: 16px; }
.form input, .form textarea { width: 100%; }
.alert { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; padding: 6px 10px; border-radius: 6px; font-size: 13px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.item { padding: 16px; }
.content { margin: 10px 0 0; white-space: pre-wrap; word-break: break-word; font-size: 14px; }
.small { font-size: 12px; }
</style>
