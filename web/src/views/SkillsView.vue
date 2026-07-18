<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listSkills, addSkill } from '@/api/client'
import Markdown from '@/components/Markdown.vue'

const skills = ref([])
const loading = ref(false)
const selected = ref(null)

const form = reactive({ name: '', description: '', content: '' })
const saving = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try { skills.value = await listSkills() } finally { loading.value = false }
}

async function submit() {
  error.value = ''
  if (!form.name.trim() || !form.content.trim()) {
    error.value = '名称和内容为必填'
    return
  }
  saving.value = true
  try {
    const s = await addSkill(form.name.trim(), form.description.trim(), form.content)
    skills.value.unshift(s)
    form.name = form.description = form.content = ''
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="view">
    <header class="page-head">
      <div>
        <h1>技能</h1>
        <p class="muted">按需加载的 .md 知识技能。</p>
      </div>
      <button class="btn" @click="load" :disabled="loading">刷新</button>
    </header>

    <div class="cols">
      <section class="card form">
        <h2>添加技能</h2>
        <div class="col">
          <input v-model="form.name" placeholder="技能名称 (name)" />
          <input v-model="form.description" placeholder="描述 (description)" />
          <textarea v-model="form.content" placeholder="内容 (Markdown)…" rows="8"></textarea>
          <div v-if="error" class="alert">{{ error }}</div>
          <button class="btn btn-primary" :disabled="saving" @click="submit">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </section>

      <section class="list">
        <div v-if="loading && !skills.length" class="empty">加载中…</div>
        <div v-else-if="!skills.length" class="empty">暂无技能</div>
        <div
          v-for="s in skills"
          :key="s.name"
          class="card item"
          :class="{ active: selected === s }"
          @click="selected = s"
        >
          <div class="spread">
            <strong class="mono">{{ s.name }}</strong>
          </div>
          <p class="muted small">{{ s.description }}</p>
        </div>
      </section>
    </div>

    <section v-if="selected" class="card detail">
      <div class="spread">
        <h2 class="mono">{{ selected.name }}</h2>
        <button class="btn" @click="selected = null">关闭</button>
      </div>
      <p class="muted">{{ selected.description }}</p>
      <Markdown :content="selected.content" class="skill-content" />
    </section>
  </div>
</template>

<style scoped>
.view { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head h1 { margin: 0 0 4px; font-size: 22px; }
.page-head p { margin: 0; font-size: 13px; }
.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 820px) { .cols { grid-template-columns: 1fr; } }
.form { padding: 18px; }
.form h2 { margin: 0 0 12px; font-size: 16px; }
.form textarea, .form input { width: 100%; }
.list { display: flex; flex-direction: column; gap: 10px; }
.item { padding: 14px; cursor: pointer; transition: border-color .15s; }
.item:hover { border-color: var(--primary); }
.item.active { border-color: var(--primary); background: var(--primary-soft); }
.small { font-size: 13px; margin: 6px 0 0; }
.alert { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; padding: 8px 12px; border-radius: 8px; font-size: 13px; }
.detail { padding: 18px; }
.detail h2 { margin: 0; font-size: 16px; }
.detail pre { margin-top: 12px; max-height: 360px; }
</style>
