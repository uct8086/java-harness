<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  content: { type: String, default: '' }
})

const root = ref(null)
marked.setOptions({ gfm: true, breaks: true })

const html = computed(() => {
  if (!props.content) return ''
  const raw = marked.parse(props.content)
  // content may come from agent output / skills; sanitize before injecting as HTML
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
})

async function addCopyButtons() {
  await nextTick()
  const el = root.value
  if (!el) return
  el.querySelectorAll('pre').forEach((pre) => {
    if (pre.querySelector('.copy-btn')) return
    const code = pre.querySelector('code')
    const btn = document.createElement('button')
    btn.className = 'copy-btn'
    btn.type = 'button'
    btn.textContent = '复制'
    btn.addEventListener('click', () => {
      const text = code ? code.innerText : pre.innerText
      navigator.clipboard.writeText(text).then(() => {
        btn.textContent = '已复制'
        setTimeout(() => (btn.textContent = '复制'), 1500)
      }).catch(() => {})
    })
    pre.appendChild(btn)
  })
}

watch(html, addCopyButtons, { immediate: true })
</script>

<template>
  <div class="markdown" ref="root" v-html="html"></div>
</template>
