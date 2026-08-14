import axios from 'axios'

// All requests go through the Vite dev proxy (/api -> http://localhost:9081),
// so the same origin is used in both dev and production (if served by the backend).
const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  withCredentials: true, // send/receive the auth_token cookie
  headers: { 'Content-Type': 'application/json' }
})

// On 401 (unauthenticated), redirect to the login page.
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      const current = window.location.pathname
      if (current !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// ---------- Auth ----------
export const login = (username, password) =>
  http.post('/auth/login', { username, password }).then(r => r.data)

export const logout = () =>
  http.post('/auth/logout').then(r => r.data)

export const getCurrentUser = () =>
  http.get('/auth/me').then(r => r.data)

// ---------- Agent Engine ----------
export const chat = (prompt, sessionId) =>
  http.post('/chat', { prompt, sessionId }).then(r => r.data)

export const chatWithContext = (prompt, sessionId, additionalContext) =>
  http.post('/chat-with-context', { prompt, sessionId, additionalContext }).then(r => r.data)

// ---------- Sessions ----------
export const listSessions = () =>
  http.get('/sessions').then(r => r.data)

export const createSession = (name) =>
  http.post('/sessions', null, { params: name ? { name } : {} }).then(r => r.data)

export const deleteSession = (id) =>
  http.delete(`/sessions/${id}`).then(r => r.data)

export const getSessionMessages = (id) =>
  http.get(`/sessions/${id}/messages`).then(r => r.data)

// ---------- Tools ----------
export const listTools = () =>
  http.get('/tools').then(r => r.data)

// ---------- Permission ----------
export const getPermissionMode = () =>
  http.get('/permission/mode').then(r => r.data.mode)

export const setPermissionMode = (mode) =>
  http.put('/permission/mode', null, { params: { mode } }).then(r => r.data.mode)

// ---------- Cost ----------
export const getTotalCost = () =>
  http.get('/cost/total').then(r => r.data)

export const getSessionCost = (sessionId) =>
  http.get(`/cost/session/${sessionId}`).then(r => r.data)

// ---------- Skills ----------
export const listSkills = () =>
  http.get('/skills').then(r => r.data)

export const addSkill = (name, description, content) =>
  http.post('/skills', { name, description, content }).then(r => r.data)

export const getSkill = (name) =>
  http.get(`/skills/${name}`).then(r => r.data)

// ---------- Memory ----------
export const listMemory = () =>
  http.get('/memory').then(r => r.data)

export const addMemory = (category, content) =>
  http.post('/memory', { category, content }).then(r => r.data)

export const searchMemory = (keyword) =>
  http.get('/memory/search', { params: { keyword } }).then(r => r.data)

// ---------- Knowledge (Vector Store / pgvector) ----------
export const ingestKnowledge = (content, metadata = {}) =>
  http.post('/knowledge/ingest', { content, metadata }).then(r => r.data)

export const searchKnowledge = (q, topK = 5) =>
  http.get('/knowledge/search', { params: { q, topK } }).then(r => r.data)

// ---------- MCP (Model Context Protocol) ----------
export const listMcpServers = () =>
  http.get('/mcp/servers').then(r => r.data)

export const listMcpTools = () =>
  http.get('/mcp/tools').then(r => r.data)

export const addMcpServer = (name, type, url) =>
  http.post('/mcp/servers', { name, type, url }).then(r => r.data)

export const updateMcpServer = (id, data) =>
  http.put(`/mcp/servers/${id}`, data).then(r => r.data)

export const toggleMcpServer = (id) =>
  http.put(`/mcp/servers/${id}/toggle`).then(r => r.data)

export const deleteMcpServer = (id) =>
  http.delete(`/mcp/servers/${id}`).then(r => r.data)

export const refreshMcp = () =>
  http.post('/mcp/refresh').then(r => r.data)

export default http
