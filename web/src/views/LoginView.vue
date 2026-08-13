<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/client'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect)
  } catch (e) {
    error.value = e.response?.data?.error || '登录失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="onSubmit">
      <div class="brand">
        <span class="logo">◆</span>
        <span class="brand-text">UCT8086-AI</span>
      </div>
      <h2>登录</h2>

      <label>用户名</label>
      <input v-model="username" type="text" placeholder="请输入用户名" autocomplete="username" />

      <label>密码</label>
      <input v-model="password" type="password" placeholder="请输入密码" autocomplete="current-password" />

      <p v-if="error" class="error">{{ error }}</p>

      <button type="submit" class="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--panel-soft);
}
.login-card {
  width: 380px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 36px 32px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: var(--shadow);
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
  margin-bottom: 8px;
}
.logo { color: var(--primary); }
h2 { margin: 0 0 12px; font-size: 22px; color: var(--text); }
label { font-size: 13px; color: var(--text-muted); margin-top: 8px; }
input {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text);
  font-size: 14px;
}
input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.error { color: var(--danger); font-size: 13px; margin: 4px 0 0; }
.submit {
  margin-top: 16px;
  padding: 11px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}
.submit:hover { background: var(--primary-hover); }
.submit:disabled { opacity: .6; cursor: not-allowed; }
</style>
