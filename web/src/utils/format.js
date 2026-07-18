// Small formatting helpers shared across views.

export function formatTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', { hour12: false })
}

export function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

export function formatNumber(n) {
  if (n == null) return '-'
  return Number(n).toLocaleString('en-US')
}

export function formatCost(c) {
  if (c == null) return '-'
  return `$${Number(c).toFixed(4)}`
}

const STATUS_BADGE = {
  PENDING: 'badge-warning',
  RUNNING: 'badge-info',
  COMPLETED: 'badge-success',
  FAILED: 'badge-danger',
  CANCELLED: 'badge'
}

export function statusBadgeClass(status) {
  return STATUS_BADGE[status] || 'badge'
}
