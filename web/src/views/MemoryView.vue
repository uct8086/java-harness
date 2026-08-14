<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listMemory, updateMemory, deleteMemory, consolidateMemory, searchMemory } from '@/api/client'
import { formatTime } from '@/utils/format'

const entries = ref([])
const loading = ref(false)
const keyword = ref('')
const error = ref('')
const consolidating = ref(false)
const notice = ref('')

// 编辑状态
const editing = ref(null) // 正在编辑的记忆 id
const editForm = reactive({ category: '', content: '' })
const saving = ref(false)

async function load() {
  loading.value = true
  try { entries.value = await listMemory() } finally { loading.value = false }
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

async function doConsolidate() {
  consolidating.value = true
  notice.value = ''
  error.value = ''
  try {
    const r = await consolidateMemory()
    notice.value = `已从最近对话总结出 ${r.saved ?? 0} 条记忆`
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '总结失败'
  } finally {
    consolidating.value = false
  }
}

function startEdit(e) {
  editing.value = e.id
  editForm.category = e.category
  editForm.content = e.content
  error.value = ''
}

function cancelEdit() {
  editing.value = null
  editForm.category = editForm.content = ''
}

async function saveEdit() {
  error.value = ''
  if (!editForm.content.trim()) { error.value = '内容为必填'; return }
  saving.value = true
  try {
    const updated = await updateMemory(editing.value, editForm.category.trim() || 'general', editForm.content)
    const idx = entries.value.findIndex(x => x.id === editing.value)
    if (idx >= 0) entries.value[idx] = updated
    cancelEdit()
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(e) {
  if (!confirm(`删除这条记忆？\n[${e.category}] ${e.content}`)) return
  try {
    await deleteMemory(e.id)
    entries.value = entries.value.filter(x => x.id !== e.id)
  } catch (err) {
    error.value = err.response?.data?.message || err.message || '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>记忆</h1>
        <p class="muted">记忆由系统从你的对话中自动总结，可编辑或删除纠正。</p>
      </div>
      <div class="row">
        <input v-model="keyword" placeholder="搜索关键词…" @keydown.enter="search" />
        <button class="btn" @click="search">搜索</button>
        <button class="btn" @click="load">全部</button>
        <button class="btn btn-primary" :disabled="consolidating" @click="doConsolidate">
          {{ consolidating ? '总结中…' : '立即总结' }}
        </button>
      </div>
    </header>

    <div v-if="notice" class="alert alert-ok">{{ notice }}</div>
    <div v-if="error" class="alert">{{ error }}</div>

    <div v-if="loading && !entries.length" class="empty">加载中…</div>
    <div v-else-if="!entries.length" class="empty">
      暂无记忆。系统会在你对话后自动总结你的偏好与事实，也可以点击「立即总结」。
    </div>

    <div class="grid">
      <div v-for="e in entries" :key="e.id" class="card item">
        <template v-if="editing === e.id">
          <div class="col edit-form">
            <input v-model="editForm.category" placeholder="分类 (category)" />
            <textarea v-model="editForm.content" rows="3" placeholder="内容…"></textarea>
            <div class="row right">
              <button class="btn" @click="cancelEdit">取消</button>
              <button class="btn btn-primary" :disabled="saving" @click="saveEdit">
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="spread">
            <span class="badge badge-info">{{ e.category }}</span>
            <span class="muted small">{{ formatTime(e.createdAt) }}</span>
          </div>
          <p class="content">{{ e.content }}</p>
          <div class="row right actions">
            <button class="btn small" @click="startEdit(e)">编辑</button>
            <button class="btn small danger" @click="remove(e)">删除</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }
.alert { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; padding: 6px 10px; border-radius: 6px; font-size: 13px; }
.alert-ok { background: #f0fdf4; color: #166534; border-color: #bbf7d0; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.item { padding: 16px; }
.content { margin: 10px 0; white-space: pre-wrap; word-break: break-word; font-size: 14px; }
.small { font-size: 12px; }
.actions { border-top: 1px solid #f0f0f0; padding-top: 10px; }
.edit-form { gap: 8px; }
.edit-form input, .edit-form textarea { width: 100%; }
.row.right { justify-content: flex-end; }
.btn.small { padding: 3px 10px; font-size: 12px; }
.btn.danger { color: #991b1b; border-color: #fecaca; background: #fff; }
.btn.danger:hover { background: #fef2f2; }
</style>
