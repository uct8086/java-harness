<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { chat, chatWithContext, listSessions, createSession, getSessionMessages } from '@/api/client'
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

async function loadSessions() {
  try { sessions.value = await listSessions() } catch (e) { /* optional */ }
  // 默认选中列表里的第一个会话（若存在）
  if (sessions.value.length && !sessionId.value) {
    sessionId.value = sessions.value[0].id
  }
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
  // optimistic user bubble
  messages.value.push({ role: 'USER', content: text, toolCalls: [] })
  // placeholder assistant bubble for streaming.
  // NOTE: push a reactive object so mutating it later triggers view updates.
  // (Vue wraps array elements in a reactive proxy; mutating the pre-push raw
  // object would NOT trigger reactivity, so we must reference the proxy.)
  messages.value.push({ role: 'ASSISTANT', content: '', toolCalls: [] })
  const assistantMsg = messages.value[messages.value.length - 1]
  await nextTick(); scrollBottom()
  try {
    const ctx = showContext.value ? additionalContext.value.trim() : ''
    if (ctx) {
      // non-stream fallback for context mode
      const data = await chatWithContext(text, sessionId.value, ctx)
      finishAssistant(data)
    } else {
      // streaming via SSE (fetch + ReadableStream, because EventSource only supports GET)
      await sendStream(text, assistantMsg)
    }
  } catch (e) {
    const isAbort = e.name === 'AbortError'
    errorMsg.value = isAbort
      ? '请求超时或被中断，请重试'
      : (e.response?.data?.message || e.message || '请求失败')
    // remove empty placeholder if failed or aborted
    if (!assistantMsg.content) {
      messages.value = messages.value.filter(m => m !== assistantMsg)
    }
  } finally {
    loading.value = false
  }
}

async function sendStream(text, assistantMsg) {
  // Use AbortController so we can forcibly close the stream on the client side
  // (avoids the "Pending forever" symptom if the server fails to call emitter.complete()).
  const controller = new AbortController()
  const timeoutId = setTimeout(() => {
    controller.abort()
  }, 6 * 60 * 1000) // 6 min hard timeout (slightly above server's 5 min)

  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ prompt: text, sessionId: sessionId.value }),
    signal: controller.signal
  })
  if (!resp.ok) {
    clearTimeout(timeoutId)
    throw new Error(`HTTP ${resp.status}`)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let usage = ''
  let finalInputTokens = 0
  let finalOutputTokens = 0
  let finalTotalTokens = 0
  let finalCost = 0

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE events are separated by \n\n
      const parts = buffer.split('\n\n')
      buffer = parts.pop() // keep incomplete tail
      for (const part of parts) {
        const lines = part.split('\n')
        let eventName = 'message'
        let data = ''
        for (const line of lines) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        // Backend sends JSON-encoded data (e.g. {"text":"..."}); parse it.
        let payload = data
        try { payload = JSON.parse(data) } catch (e) { /* keep as string */ }

        if (eventName === 'token') {
          // payload is { text: "delta" } — append to the assistant bubble (typewriter effect)
          const delta = (typeof payload === 'object' && payload && 'text' in payload)
            ? payload.text
            : String(payload)
          assistantMsg.content += delta
          await nextTick(); scrollBottom()
        } else if (eventName === 'tool') {
          // payload is { name: "toolName" } — show a transient status
          const toolName = (typeof payload === 'object' && payload && 'name' in payload)
            ? payload.name
            : String(payload)
          assistantMsg.toolCalls = [...(assistantMsg.toolCalls || []), { name: toolName }]
        } else if (eventName === 'session') {
          // payload is { sessionId: "..." } — remember the session id
          if (typeof payload === 'object' && payload && payload.sessionId) {
            sessionId.value = payload.sessionId
          }
        } else if (eventName === 'done') {
          // payload is { sessionId, inputTokens, outputTokens, totalTokens, cost }
          if (typeof payload === 'object' && payload) {
            finalInputTokens = payload.inputTokens || 0
            finalOutputTokens = payload.outputTokens || 0
            finalTotalTokens = payload.totalTokens || (finalInputTokens + finalOutputTokens)
            finalCost = payload.cost || 0
            usage = `${finalInputTokens} in / ${finalOutputTokens} out tokens`
            if (payload.sessionId) sessionId.value = payload.sessionId
          } else {
            usage = String(payload)
          }
        } else if (eventName === 'error') {
          const msg = (typeof payload === 'object' && payload && 'message' in payload)
            ? payload.message
            : String(payload)
          throw new Error(msg)
        }
      }
    }
  } finally {
    clearTimeout(timeoutId)
    try { reader.cancel() } catch (e) { /* already closed */ }
  }

  // If the stream completed but no response event arrived, the placeholder stays empty.
  // The caller (send) will detect this and remove the empty placeholder.
  lastResult.value = {
    success: true,
    response: assistantMsg.content,
    turns: 0,
    toolCalls: assistantMsg.toolCalls || [],
    // Match the backend AgentLoopResult field name so the template (tokenUsage) works.
    tokenUsage: {
      inputTokens: finalInputTokens,
      outputTokens: finalOutputTokens,
      totalTokens: finalTotalTokens,
      cost: finalCost
    },
    _streamUsage: usage
  }
}

function finishAssistant(data) {
  lastResult.value = data
  const last = messages.value[messages.value.length - 1]
  if (data.success) {
    if (last && last.role === 'ASSISTANT') {
      last.content = data.response || ''
      last.toolCalls = data.toolCalls || []
    } else {
      messages.value.push({ role: 'ASSISTANT', content: data.response || '', toolCalls: data.toolCalls || [] })
    }
  } else {
    errorMsg.value = data.error || '模型返回失败'
    if (last && last.role === 'ASSISTANT' && !last.content) {
      messages.value.pop()
    }
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

onMounted(loadSessions)
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
          <div v-else class="muted small typing">思考中…</div>
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
