<script setup>
import { ref, onMounted } from 'vue'
import { getPermissionMode, setPermissionMode, getTotalCost } from '@/api/client'
import { formatNumber, formatCost } from '@/utils/format'

const MODES = [
  { value: 'DEFAULT', desc: '写入/执行前询问（日常开发默认）' },
  { value: 'AUTO', desc: '允许所有操作不询问（沙箱环境）' },
  { value: 'PLAN_MODE', desc: '阻止所有写入（大型重构先审阅）' },
  { value: 'READ_ONLY', desc: '仅允许读取操作' }
]

const mode = ref('')
const saving = ref(false)
const cost = ref(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    mode.value = await getPermissionMode()
    cost.value = await getTotalCost()
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    mode.value = await setPermissionMode(mode.value)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="settings">
    <header class="page-head">
      <h1>设置</h1>
      <p class="muted">权限模式与全局 Token / 费用统计。</p>
    </header>

    <section class="card block">
      <h2>权限模式</h2>
      <p class="muted small">控制 Agent 处理潜在破坏性操作的方式。</p>
      <div class="modes">
        <label v-for="m in MODES" :key="m.value" class="mode" :class="{ active: mode === m.value }">
          <input type="radio" v-model="mode" :value="m.value" />
          <div>
            <div class="mode-name">{{ m.value }}</div>
            <div class="muted small">{{ m.desc }}</div>
          </div>
        </label>
      </div>
      <button class="btn btn-primary" :disabled="saving" @click="save" style="margin-top: 12px;">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </section>

    <section class="card block">
      <h2>累计费用</h2>
      <div class="stats" v-if="cost">
        <div class="stat"><div class="stat-label">输入 Tokens</div><div class="stat-value">{{ formatNumber(cost.inputTokens) }}</div></div>
        <div class="stat"><div class="stat-label">输出 Tokens</div><div class="stat-value">{{ formatNumber(cost.outputTokens) }}</div></div>
        <div class="stat"><div class="stat-label">合计 Tokens</div><div class="stat-value">{{ formatNumber(cost.totalTokens) }}</div></div>
        <div class="stat"><div class="stat-label">费用</div><div class="stat-value">{{ formatCost(cost.cost) }}</div></div>
      </div>
      <div v-else class="empty">加载中…</div>
    </section>
  </div>
</template>

<style scoped>
.settings { max-width: 820px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }
.block { padding: 20px; }
.block h2 { margin: 0 0 6px; font-size: 16px; }
.small { font-size: 13px; }
.modes { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; margin-top: 14px; }
.mode { display: flex; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; }
.mode:hover { background: var(--panel-soft); }
.mode.active { border-color: var(--primary); background: var(--primary-soft); }
.mode-name { font-weight: 600; font-size: 14px; }
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-top: 14px; }
.stat { background: var(--panel-soft); border: 1px solid var(--border); border-radius: 8px; padding: 14px; }
.stat-label { font-size: 12px; color: var(--text-muted); }
.stat-value { font-size: 20px; font-weight: 700; margin-top: 4px; }
</style>
