/** 唯一 id：优先用浏览器的 crypto，兜底手写一个（本地非安全上下文时用得上） */
export function uuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n <= 0) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 传输速度：1.2 MB/s（速度未知时返回空串，由调用方决定是否隐藏） */
export function formatSpeed(bytesPerSecond) {
  const n = Number(bytesPerSecond) || 0
  if (n <= 0) return ''
  return `${formatSize(n)}/s`
}

/** 剩余时长：45 秒 / 3 分 20 秒 */
export function formatDuration(seconds) {
  const s = Math.max(0, Math.round(Number(seconds) || 0))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const r = s % 60
  return r ? `${m} 分 ${r} 秒` : `${m} 分`
}

function pad(n) {
  return String(n).padStart(2, '0')
}

/** 本地日期 yyyy-MM-dd（不要用 toISOString，会因时区差一天） */
export function toDateStr(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function todayStr() {
  return toDateStr()
}

export function formatDate(value) {
  if (!value) return '—'
  const s = String(value)
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/)
  return m ? `${m[1]}-${m[2]}-${m[3]}` : s
}

export function formatDateTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 把日期转成「今天 / 昨天 / 3 天前 / yyyy-MM-dd」 */
export function friendlyDay(dateStr) {
  if (!dateStr) return ''
  const target = new Date(`${formatDate(dateStr)}T00:00:00`)
  if (Number.isNaN(target.getTime())) return String(dateStr)
  const now = new Date()
  const base = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diff = Math.round((base - target) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  if (diff === 2) return '前天'
  if (diff > 2 && diff < 7) return `${diff} 天前`
  if (diff < 0) return formatDate(dateStr)
  return formatDate(dateStr)
}

/** 逗号分隔的标签字符串 <-> 数组 */
export function parseTags(text) {
  return String(text || '')
    .split(/[,，\s]+/)
    .map((t) => t.trim())
    .filter(Boolean)
}

export function joinTags(list) {
  return (list || []).join(',')
}

/**
 * 接口错误的用户可读文案。
 * 以服务端返回的 error.kind 为准（稳定契约），**不要**去 match message 文本。
 */
const KIND_TEXT = {
  unauthenticated: '登录状态已失效，请重新登录',
  'permission-denied': '没有权限执行该操作',
  'not-found': '找不到对应的数据或文件',
  'invalid-request': '请求内容不合法，请检查后重试',
  'already-exists': '已存在相同记录',
  expired: '链接已过期，请重新获取',
  'payload-too-large': '文件太大，服务器拒绝了本次上传',
  'rate-limited': '操作太频繁，请稍后再试',
  'backend-unavailable': '服务暂时不可用，请稍后重试',
  internal: '服务器内部错误，请查看服务器日志',
  cancelled: '操作已取消',
  network: '网络异常，请稍后重试'
}

export function errorText(e) {
  if (!e) return '操作失败，请稍后重试'

  // 按 HTTP 状态先兜一层：这几类最影响用户判断下一步怎么做
  if (e.status === 413) return '文件太大，超出了服务端允许的上传体积'
  if (e.status === 401 || e.status === 403) return '登录状态已失效或没有权限，请重新登录'

  const kind = e.kind
  if (kind && KIND_TEXT[kind]) {
    const tail = e.code && e.code !== String(e.status) ? `（${e.code}）` : ''
    return KIND_TEXT[kind] + tail
  }

  // 非 CloudError 的兜底：OAuth 错误体、原生异常
  const msg = e.message || e.error_description || String(e || '')
  if (/failed to fetch|network|load failed|timeout|aborted/i.test(msg)) return '网络异常，请稍后重试'
  if (/duplicate|23505/i.test(msg)) return '已存在相同记录'
  if (/permission|42501|denied/i.test(msg)) return '没有权限执行该操作'
  if (/not exist|42P01/i.test(msg)) return '数据表不存在，请先完成初始化'
  return msg || '操作失败，请稍后重试'
}

/** 上传失败时给排查用的原始信息（用户复制/截图就能定位问题） */
export function errorDetail(e) {
  if (!e) return ''
  const parts = []
  if (e.kind) parts.push(`kind=${e.kind}`)
  if (e.status) parts.push(`status=${e.status}`)
  if (e.code) parts.push(`code=${e.code}`)
  const msg = e.message || e.error_description
  if (msg) parts.push(String(msg))
  return parts.join(' · ')
}
