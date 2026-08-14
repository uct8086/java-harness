<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import {
  listMcpServers, listMcpTools,
  addMcpServer, updateMcpServer, toggleMcpServer, deleteMcpServer,
  refreshMcp
} from '@/api/client'

const servers = ref([])
const mcpTools = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const editing = ref(null)     // server being edited, or null for new
const tab = ref('servers')    // 'servers' | 'tools'

const form = reactive({
  name: '',
  type: 'streamable-http',
  url: ''
})

// ---- Load ----

async function load() {
  loading.value = true
  try {
    const [s, t] = await Promise.all([listMcpServers(), listMcpTools()])
    servers.value = s
    mcpTools.value = t
  } finally {
    loading.value = false
  }
}

// ---- Form helpers ----

function openAdd() {
  editing.value = null
  form.name = ''
  form.type = 'streamable-http'
  form.url = ''
  error.value = ''
}

function openEdit(s) {
  editing.value = s.id
  form.name = s.name
  form.type = s.type || 'streamable-http'
  form.url = s.url || ''
  error.value = ''
}

function cancelEdit() {
  editing.value = null
  error.value = ''
}

// ---- Submit ----

async function submit() {
  error.value = ''
  if (!form.name.trim()) {
    error.value = '名称不能为空'
    return
  }
  if (form.type === 'streamable-http' && !form.url.trim()) {
    error.value = 'Streamable HTTP 模式需要填写 URL'
    return
  }

  saving.value = true
  try {
    if (editing.value) {
      await updateMcpServer(editing.value, {
        name: form.name.trim(),
        type: form.type,
        url: form.url.trim()
      })
    } else {
      await addMcpServer(
        form.name.trim(),
        form.type,
        form.url.trim()
      )
    }
    cancelEdit()
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function doToggle(s) {
  try {
    await toggleMcpServer(s.id)
    await load()
  } catch (e) { /* ignore */ }
}

async function doDelete(s) {
  if (!confirm(`确定要删除 MCP 服务器 "${s.name}"？`)) return
  try {
    await deleteMcpServer(s.id)
    await load()
  } catch (e) { /* ignore */ }
}

const connectedCount = computed(() =>
  servers.value.filter(s => s.status === 'connected').length
)

async function doRefresh() {
  refreshing.value = true
  try {
    await refreshMcp()
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '重连失败'
  } finally {
    refreshing.value = false
  }
}

const refreshing = ref(false)

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>MCP 管理</h1>
        <p class="muted">
          Model Context Protocol — 管理外部工具服务器。
          {{ connectedCount }}/{{ servers.length }} 已连接。
        </p>
      </div>
      <div class="row">
        <button class="btn" @click="doRefresh" :disabled="refreshing">{{ refreshing ? '重连中…' : '重新连接' }}</button>
        <button class="btn" @click="load" :disabled="loading">刷新列表</button>
      </div>
    </header>

    <div class="tabs">
      <button :class="{ active: tab === 'servers' }" @click="tab = 'servers'">服务器 ({{ servers.length }})</button>
      <button :class="{ active: tab === 'tools' }" @click="tab = 'tools'">活跃工具 ({{ mcpTools.length }})</button>
    </div>

    <!-- Servers Tab -->
    <div v-if="tab === 'servers'" class="cols">
      <!-- Server List -->
      <section class="list">
        <div v-if="loading && !servers.length" class="empty">加载中…</div>
        <div v-else-if="!servers.length" class="empty">暂无 MCP 服务器配置</div>
        <div
          v-for="s in servers"
          :key="s.id"
          class="card item"
          :class="{ active: editing === s.id }"
        >
          <div class="spread">
            <div class="row" style="gap:8px;">
              <span
                class="dot"
                :style="{ background: s.status === 'connected' ? '#22c55e' : s.status === 'error' ? '#ef4444' : '#94a3b8' }"
                :title="s.status === 'connected' ? '已连接' : s.status === 'error' ? '连接失败' : '未连接'"
              ></span>
              <strong class="mono">{{ s.name }}</strong>
            </div>
            <div class="row" style="gap:6px;">
              <span class="badge" :class="s.type === 'streamable-http' ? 'badge-info' : 'badge-success'">
                {{ s.type }}
              </span>
              <span class="badge" :class="s.enabled ? 'badge-success' : 'badge-warning'" style="cursor:pointer;"
                    @click="doToggle(s)">
                {{ s.enabled ? '启用' : '禁用' }}
              </span>
            </div>
          </div>
          <div class="muted small" style="margin-top:6px;">
            {{ s.url }}
          </div>
          <div class="row" style="margin-top:8px; gap:6px;">
            <button class="btn btn-sm" @click="openEdit(s)">编辑</button>
            <button class="btn btn-sm btn-danger" @click="doDelete(s)">删除</button>
          </div>
          <div v-if="s.error" class="alert" style="margin-top:6px;">{{ s.error }}</div>
        </div>
      </section>

      <!-- Add / Edit Form -->
      <section class="card form">
        <div class="spread">
          <h2>{{ editing ? '编辑服务器' : '添加服务器' }}</h2>
          <button v-if="editing" class="btn btn-sm" @click="cancelEdit">取消</button>
        </div>

        <div class="col">
          <label>名称</label>
          <input v-model="form.name" placeholder="e.g. filesystem, github" />

          <label>Streamable HTTP URL</label>
          <input v-model="form.url" placeholder="http://remote-server:8080/mcp" />

          <div v-if="error" class="alert">{{ error }}</div>
          <button class="btn btn-primary" :disabled="saving" @click="submit">
            {{ saving ? '保存中…' : (editing ? '更新' : '添加') }}
          </button>

          <div class="muted small hint">
            💡 添加/修改服务器后，点击「重新连接」即可生效，无需重启。
          </div>
        </div>
      </section>
    </div>

    <!-- Tools Tab -->
    <div v-if="tab === 'tools'">
      <div v-if="!mcpTools.length" class="empty">暂无活跃的 MCP 工具</div>
      <div class="grid">
        <div v-for="t in mcpTools" :key="t.name" class="card item">
          <span class="mono" style="font-weight:600;">{{ t.name }}</span>
          <p class="muted small" style="margin:6px 0 0;">{{ t.description }}</p>
        </div>
      </div>
    </div>

    <!-- Status bar -->
    <div class="status-bar muted small">
      <span>配置保存在 <code>.uct8086/mcp-servers/{userId}.json</code></span>
      <span>·</span>
      <span>配置变更点击<strong>「重新连接」</strong>即刻生效</span>
      <span>·</span>
      <span>{{ mcpTools.length }} 个工具可用</span>
    </div>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }

.tabs { display: flex; gap: 0; border-bottom: 1px solid var(--border); }
.tabs button {
  padding: 8px 20px; border: none; background: none; color: var(--text-muted);
  font-size: 14px; cursor: pointer; border-bottom: 2px solid transparent; transition: .15s;
}
.tabs button:hover { color: var(--text); }
.tabs button.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }

.cols { display: grid; grid-template-columns: 1fr 360px; gap: 16px; }
@media (max-width: 880px) { .cols { grid-template-columns: 1fr; } }

.list { display: flex; flex-direction: column; gap: 10px; }
.item { padding: 14px; }
.item.active { border-color: var(--primary); background: var(--primary-soft); }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }

.form { padding: 18px; align-self: start; }
.form h2 { margin: 0; font-size: 16px; }
.form label { font-size: 13px; font-weight: 600; color: var(--text-muted); display: block; margin-top: 12px; }
.form input { width: 100%; margin-top: 4px; }

.hint { margin-top: 12px; line-height: 1.5; }

.radio-label {
  display: flex; align-items: center; gap: 6px; padding: 8px 14px;
  border: 1px solid var(--border); border-radius: 8px; cursor: pointer; font-size: 13px;
}
.radio-label:hover { background: var(--panel-soft); }
.radio-label.active { border-color: var(--primary); background: var(--primary-soft); }
.radio-label input { width: auto; margin: 0; }

.dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }

.btn-sm { padding: 4px 10px; font-size: 12px; }
.btn-danger { color: #dc2626; border-color: #fecaca; background: #fef2f2; }
.btn-danger:hover { background: #fee2e2; }

.alert { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; padding: 8px 12px; border-radius: 8px; font-size: 13px; margin-top: 12px; }

.status-bar {
  display: flex; gap: 10px; align-items: center; padding: 10px 14px;
  background: var(--panel-soft); border-radius: 8px;
}
.status-bar code { font-size: 12px; }
</style>
