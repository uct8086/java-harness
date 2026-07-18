<script setup>
import { ref, reactive } from 'vue'
import { ingestKnowledge, searchKnowledge } from '@/api/client'

// ---- Ingest ----
const content = ref('')
const metadataKey = ref('')
const metadataVal = ref('')
const ingesting = ref(false)
const ingestResult = ref(null)

async function ingest() {
  if (!content.value.trim() || ingesting.value) return
  ingesting.value = true
  ingestResult.value = null
  try {
    const meta = {}
    if (metadataKey.value.trim()) meta[metadataKey.value.trim()] = metadataVal.value
    const res = await ingestKnowledge(content.value, meta)
    ingestResult.value = res
    if (res.success) {
      content.value = ''
      metadataKey.value = ''
      metadataVal.value = ''
    }
  } catch (e) {
    ingestResult.value = { success: false, error: e.response?.data?.error || e.message }
  } finally {
    ingesting.value = false
  }
}

// ---- Search ----
const query = ref('')
const topK = ref(5)
const searching = ref(false)
const results = ref([])
const searchError = ref('')

async function search() {
  if (!query.value.trim() || searching.value) return
  searching.value = true
  searchError.value = ''
  results.value = []
  try {
    results.value = await searchKnowledge(query.value, topK.value)
  } catch (e) {
    searchError.value = e.response?.data?.message || e.message || '搜索失败'
  } finally {
    searching.value = false
  }
}
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>知识库</h1>
        <p class="muted">往 pgvector 向量库写入文档（Embedding + 语义检索 / RAG）。</p>
      </div>
    </header>

    <div class="cols">
      <!-- Ingest -->
      <section class="card block">
        <h2>写入文档</h2>
        <p class="muted small">文本会被 Embedding 模型量化后存入 pgvector，供对话 RAG 检索。</p>
        <div class="col" style="margin-top:12px">
          <textarea v-model="content" placeholder="文档内容…" rows="8" :disabled="ingesting"></textarea>
          <div class="row" style="gap:8px;">
            <input v-model="metadataKey" placeholder="元数据 key（可选，如 source）" :disabled="ingesting" class="grow" />
            <input v-model="metadataVal" placeholder="值（可选）" :disabled="ingesting" class="grow" />
          </div>
          <button class="btn btn-primary" :disabled="ingesting || !content.trim()" @click="ingest">
            {{ ingesting ? '写入中…' : '存入向量库' }}
          </button>
        </div>
        <div v-if="ingestResult" class="toast" :class="ingestResult.success ? 'ok' : 'err'">
          {{ ingestResult.success ? '✓ 已存入' : '✗ ' + (ingestResult.error || '失败') }}
        </div>
      </section>

      <!-- Search -->
      <section class="card block">
        <h2>语义搜索</h2>
        <p class="muted small">用自然语言搜索向量库中最相似的文档片段。</p>
        <div class="row" style="margin-top:12px;">
          <input v-model="query" placeholder="搜索关键词…" @keydown.enter="search" class="grow" :disabled="searching" />
          <select v-model="topK" style="width:80px;">
            <option v-for="k in [3,5,10,20]" :key="k" :value="k">{{ k }}</option>
          </select>
          <button class="btn btn-primary" :disabled="searching || !query.trim()" @click="search">
            {{ searching ? '搜索中…' : '搜索' }}
          </button>
        </div>
        <div v-if="searchError" class="toast err">{{ searchError }}</div>
        <div v-if="results.length" class="results">
          <div v-for="(r, i) in results" :key="i" class="result-item">
            <div class="badge badge-info">#{{ i + 1 }}</div>
            <div class="meta" v-if="r.metadata && Object.keys(r.metadata).length">
              <span v-for="(v, k) in r.metadata" :key="k" class="badge">{{ k }}: {{ v }}</span>
            </div>
            <pre class="content">{{ r.content }}</pre>
          </div>
        </div>
        <div v-else-if="!searching && searchError === '' && query.trim()" class="empty" style="padding:24px;">无匹配结果</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }

.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 820px) { .cols { grid-template-columns: 1fr; } }

.block { padding: 18px; }
.block h2 { margin: 0 0 4px; font-size: 16px; }
.small { font-size: 13px; }
.block textarea, .block input { width: 100%; }
.block textarea { resize: vertical; }

.toast { margin-top: 10px; padding: 8px 12px; border-radius: 8px; font-size: 14px; }
.toast.ok { background: #dcfce7; color: #166534; border: 1px solid #bbf7d0; }
.toast.err { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }

.results { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; max-height: 480px; overflow-y: auto; }
.result-item { padding: 10px 0; border-bottom: 1px dashed var(--border); }
.result-item:last-child { border-bottom: none; }
.meta { display: flex; gap: 6px; flex-wrap: wrap; margin: 6px 0 4px; }
.content { font-size: 13px; margin: 8px 0 0; white-space: pre-wrap; word-break: break-word; max-height: 200px; }
</style>
