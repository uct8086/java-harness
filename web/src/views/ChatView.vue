<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { chat, chatWithContext, listModels, listSessions, createSession, getSessionMessages } from '@/api/client'
import { formatNumber, formatCost } from '@/utils/format'
import Markdown from '@/components/Markdown.vue'

const sessions = ref([])
const sessionId = ref('')
const prompt = ref('')
const additionalContext = ref('')
const showContext = ref(false)
const loading = ref(false)
const errorMsg = ref('')
const messages = ref([])      // AgentMessage[] { role, content, toolCalls, toolCallId }
const lastResult = ref(null)  // last AgentLoopResult (tool calls + token usage)
const streamEl = ref(null)
const models = ref([])        // available models [{ id, provider }]
const selectedModel = ref('')

async function loadSessions() {
  try { sessions.value = await listSessions() } catch (e) { /* optional */ }
}

async function newSession() {
  const s = await createSession('Web 会话 ' + new Date().toLocaleTimeString('zh-CN', { hour12: false }))
  sessions.value.unshift(s)
  sessionId.value = s.id
}

async function loadMessages() {
  if (!sessionId.value) { messages.value = []; return }
  try {
    messages.value = await getSessionMessages(sessionId.value)
    await nextTick(); scrollBottom()
  } catch (e) {
    messages.value = []
  }
}

watch(sessionId, loadMessages)

async function ensureSession() {
  if (!sessionId.value) await newSession()
}

async function send() {
  if (!prompt.value.trim() || loading.value) return
  await ensureSession()
  loading.value = true
  errorMsg.value = ''
  lastResult.value = null
  const text = prompt.value
  prompt.value = ''
  const model = selectedModel.value || undefined
  // optimistic user bubble
  messages.value.push({ role: 'USER', content: text, toolCalls: [] })
  await nextTick(); scrollBottom()
  try {
    const ctx = showContext.value ? additionalContext.value.trim() : ''
    const data = ctx
      ? await chatWithContext(text, sessionId.value, ctx, model)
      : await chat(text, sessionId.value, model)
    lastResult.value = data
    if (data.success) {
      // Append the assistant reply directly from the result so it always renders,
      // even if the optional /sessions/{id}/messages history endpoint is unavailable.
      messages.value.push({
        role: 'ASSISTANT',
        content: data.response || '',
        toolCalls: data.toolCalls || []
      })
    } else {
      errorMsg.value = data.error || '模型返回失败'
    }
    await nextTick(); scrollBottom()
  } catch (e) {
    errorMsg.value = e.response?.data?.message || e.message || '请求失败'
  } finally {
    loading.value = false
  }
}

function scrollBottom() {
  if (streamEl.value) streamEl.value.scrollTop = streamEl.value.scrollHeight
}

function onKey(e) {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    send()
  }
}

async function loadModels() {
  try { models.value = await listModels() } catch (e) { /* models unavailable */ }
}

onMounted(() => { loadSessions(); loadModels() })
</script>

<template>
  <div class="chat">
    <header class="page-head">
      <div>
        <h1>对话</h1>
        <p class="muted">按会话持久化的对话历史。</p>
      </div>
      <div class="row">
        <select v-model="sessionId" class="grow">
          <option value="">（选择会话）</option>
          <option v-for="s in sessions" :key="s.id" :value="s.id">
            {{ s.name || s.id.slice(0, 8) }}
          </option>
        </select>
        <button class="btn" @click="newSession">＋ 新建</button>
        <button class="btn" @click="loadMessages" :disabled="!sessionId">刷新</button>
      </div>
    </header>

    <div class="stream card" ref="streamEl">
      <div v-if="!messages.length && !loading" class="empty">选择或新建一个会话开始对话</div>

      <template v-for="(m, i) in messages" :key="i">
        <div v-if="m.role === 'USER'" class="bubble user">
          <div class="bubble-body">{{ m.content }}</div>
        </div>

        <div v-else-if="m.role === 'ASSISTANT'" class="bubble assistant">
          <div class="bubble-role">AI</div>
          <Markdown v-if="m.content" :content="m.content" class="bubble-body" />
          <div v-else class="muted small">（无文本回复）</div>
          <div v-if="m.toolCalls?.length" class="tool-chips">
            <span v-for="(tc, j) in m.toolCalls" :key="j" class="badge badge-info mono">🔧 {{ tc.name }}</span>
          </div>
        </div>

        <div v-else-if="m.role === 'TOOL'" class="bubble tool">
          <span class="badge">工具结果</span>
          <pre>{{ m.content }}</pre>
        </div>

        <details v-else class="bubble system">
          <summary class="muted small">系统消息</summary>
          <pre>{{ m.content }}</pre>
        </details>
      </template>

      <div v-if="loading" class="bubble assistant">
        <div class="bubble-role">AI</div>
        <div class="muted small typing">思考中…</div>
      </div>
    </div>

    <div v-if="errorMsg" class="alert">{{ errorMsg }}</div>

    <details v-if="lastResult" class="card last-result" open>
      <summary>
        本轮详情：{{ lastResult.success ? '成功' : '失败' }} · 轮次 {{ lastResult.turns }} ·
        合计 {{ formatNumber(lastResult.tokenUsage?.totalTokens) }} tokens ·
        {{ formatCost(lastResult.tokenUsage?.cost) }}
      </summary>
      <div v-if="lastResult.error" class="alert" style="margin:10px 0">{{ lastResult.error }}</div>
      <details v-if="lastResult.toolCalls?.length" style="margin-top:10px">
        <summary>工具调用 ({{ lastResult.toolCalls.length }})</summary>
        <div v-for="(t, i) in lastResult.toolCalls" :key="i" class="tool-item">
          <div class="spread">
            <span class="mono" style="font-weight:600">{{ t.toolName }}</span>
            <span class="badge" :class="t.isError ? 'badge-danger' : 'badge-success'">
              {{ t.isError ? '错误' : '成功' }} · {{ t.durationMs }}ms
            </span>
          </div>
          <pre class="tool-args">{{ t.arguments }}</pre>
          <pre class="tool-result">{{ t.result }}</pre>
        </div>
      </details>
    </details>

    <section class="card composer">
      <div v-if="models.length" style="margin-bottom:8px;">
        <select v-model="selectedModel">
          <option value="">默认模型</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.id }}</option>
        </select>
      </div>
      <textarea
        v-model="prompt"
        @keydown="onKey"
        placeholder="输入提示词…（Ctrl/⌘ + Enter 发送）"
        rows="3"
      ></textarea>
      <div class="spread" style="margin-top:8px;">
        <button class="btn btn-link" @click="showContext = !showContext">
          {{ showContext ? '▾' : '▸' }} 附加上下文
        </button>
        <button class="btn btn-primary" :disabled="loading || !prompt.trim()" @click="send">
          {{ loading ? '运行中…' : '发送' }}
        </button>
      </div>
      <textarea
        v-if="showContext"
        v-model="additionalContext"
        placeholder="附加上下文（可选）…"
        rows="3"
        style="margin-top:8px;"
      ></textarea>
    </section>
  </div>
</template>

<style scoped>
.chat {
  height: 100%;
  max-width: 920px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }

.stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bubble { display: flex; flex-direction: column; max-width: 88%; }
.bubble.user { align-self: flex-end; align-items: flex-end; }
.bubble.assistant { align-self: flex-start; }
.bubble.tool { align-self: flex-start; max-width: 70%; }
.bubble.system { align-self: center; max-width: 90%; }

.bubble-role {
  font-size: 11px; font-weight: 700; color: var(--primary);
  margin-bottom: 4px; letter-spacing: .5px;
}
.bubble.user .bubble-body {
  background: var(--primary); color: #fff;
  padding: 10px 14px; border-radius: 14px 14px 4px 14px;
  white-space: pre-wrap; word-break: break-word; font-size: 14.5px;
}
.bubble.assistant .bubble-body {
  background: var(--panel); border: 1px solid var(--border);
  padding: 10px 14px; border-radius: 4px 14px 14px 14px;
}
.bubble.tool pre { margin-top: 6px; font-size: 12px; max-height: 160px; }
.tool-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }

.typing { animation: blink 1.2s ease-in-out infinite; }
@keyframes blink { 0%, 100% { opacity: .4; } 50% { opacity: 1; } }

.btn-link { background: none; border: none; color: var(--primary); padding: 4px; font-size: 13px; }
.alert { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; padding: 10px 14px; border-radius: 8px; font-size: 14px; }

.last-result { padding: 14px 16px; }
.last-result summary { cursor: pointer; font-weight: 600; font-size: 14px; }
.tool-item { padding: 10px 0; border-bottom: 1px dashed var(--border); }
.tool-item:last-child { border-bottom: none; }
.tool-args { margin: 8px 0 6px; font-size: 12px; max-height: 120px; }
.tool-result { margin: 0; font-size: 12px; max-height: 200px; }

.composer { padding: 14px; }
.composer textarea { width: 100%; resize: vertical; }
.small { font-size: 13px; }
</style>
