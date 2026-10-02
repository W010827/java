/**
 * 内网自建后端的 API 客户端。
 *
 * 后端是服务器上那个 Python 服务（server/workhub.py），前端与它**同源**：
 *   - 页面由同一个服务/同一台 Nginx 提供
 *   - 所有接口都在 /api/ 前缀下
 *   - 登录用邮箱 + 密码，服务端签发 token，前端存 localStorage
 *
 * 之所以不再用云端 SDK：本应用要求数据完全留在内网，不经过公网。
 */

const TOKEN_KEY = 'workhub.token'

let token = ''
try {
  token = window.localStorage.getItem(TOKEN_KEY) || ''
} catch (e) {
  /* 隐私模式等场景下 localStorage 不可用，退化为仅内存保存 */
  token = ''
}

export function getToken() {
  return token
}

export function setToken(value) {
  token = value || ''
  try {
    if (token) window.localStorage.setItem(TOKEN_KEY, token)
    else window.localStorage.removeItem(TOKEN_KEY)
  } catch (e) {
    /* ignore */
  }
}

/** 统一错误对象：保留 kind / status / code，供 utils.errorText 精确翻译 */
export class ApiError extends Error {
  constructor(message, options) {
    super(message)
    this.name = 'ApiError'
    const opt = options || {}
    this.status = opt.status
    this.kind = opt.kind
    this.code = opt.code
  }
}

const KIND_BY_STATUS = {
  400: 'invalid-request',
  401: 'unauthenticated',
  403: 'permission-denied',
  404: 'not-found',
  409: 'already-exists',
  410: 'expired',
  413: 'payload-too-large',
  429: 'rate-limited',
  500: 'backend-unavailable',
  502: 'backend-unavailable',
  503: 'backend-unavailable'
}

/** 浏览器本地时间（yyyy-MM-ddTHH:mm:ss）。后端用它作为记录时间，避免服务器时钟不准。 */
function localIsoTime() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return (
    d.getFullYear() +
    '-' +
    p(d.getMonth() + 1) +
    '-' +
    p(d.getDate()) +
    'T' +
    p(d.getHours()) +
    ':' +
    p(d.getMinutes()) +
    ':' +
    p(d.getSeconds())
  )
}

function buildInit(method, options) {
  const opt = options || {}
  const headers = Object.assign({}, opt.headers || {})
  headers['X-Client-Time'] = localIsoTime()
  if (token) headers.Authorization = 'Bearer ' + token
  const init = { method: method, headers: headers }
  if (opt.raw) {
    init.body = opt.raw
  } else if (opt.body !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8'
    init.body = JSON.stringify(opt.body)
  }
  return init
}

/**
 * 发一个请求。
 * @param {string} method HTTP 方法
 * @param {string} url    形如 /api/software
 * @param {object} [options] { body, raw, headers }
 */
export async function request(method, url, options) {
  let res
  try {
    res = await fetch(url, buildInit(method, options))
  } catch (e) {
    throw new ApiError('无法连接服务器，请确认服务是否正常', { kind: 'network' })
  }

  const text = await res.text().catch(() => '')
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch (e) {
      data = text
    }
  }

  if (!res.ok) {
    const err = (data && data.error) || {}
    throw new ApiError(err.message || '请求失败（HTTP ' + res.status + '）', {
      status: res.status,
      kind: err.kind || KIND_BY_STATUS[res.status] || 'unknown',
      code: err.code
    })
  }
  return data
}

/**
 * 带上传进度的请求（大文件用，XMLHttpRequest 才有 progress 事件）。
 * @param {string} url
 * @param {File|Blob} file
 * @param {string} contentType
 * @param {(percent:number, loaded:number, total:number)=>void} [onProgress]
 * @param {(xhr:XMLHttpRequest)=>void} [onReady] 拿到 xhr 后可以调用 abort() 中断上传
 */
function uploadWithProgress(url, file, contentType, onProgress, onReady) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url, true)
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.setRequestHeader('X-Client-Time', localIsoTime())
    xhr.setRequestHeader('Content-Type', contentType)
    // 交给调用方持有，便于实现「取消上传」
    if (typeof onReady === 'function') onReady(xhr)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && typeof onProgress === 'function') {
        onProgress(Math.round((e.loaded / e.total) * 100), e.loaded, e.total)
      }
    }
    xhr.onload = () => {
      let data = null
      try {
        data = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch (e) {
        data = xhr.responseText
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(data)
        return
      }
      const err = (data && data.error) || {}
      reject(
        new ApiError(err.message || '上传失败（HTTP ' + xhr.status + '）', {
          status: xhr.status,
          kind: err.kind || KIND_BY_STATUS[xhr.status] || 'unknown',
          code: err.code
        })
      )
    }
    xhr.onerror = () =>
      reject(new ApiError('上传失败，网络连接中断', { kind: 'network' }))
    xhr.onabort = () => reject(new ApiError('上传已取消', { kind: 'cancelled' }))
    xhr.ontimeout = () => reject(new ApiError('上传超时', { kind: 'network' }))
    xhr.send(file)
  })
}

export { uploadWithProgress }

/* ==================== 登录状态 ==================== */

let sessionCache = null
let inflight = null

/** 取当前登录会话；未登录返回 null。首次成功后缓存，避免每次路由跳转都打接口。 */
export async function getSession() {
  if (!token) return null
  if (sessionCache) return sessionCache
  if (inflight) return inflight

  inflight = request('GET', '/api/auth/me')
    .then((data) => {
      sessionCache = data && data.user ? { user: data.user, token: token } : null
      return sessionCache
    })
    .catch((e) => {
      if (e && e.status === 401) {
        setToken('')
        sessionCache = null
      }
      return null
    })
    .then((value) => {
      inflight = null
      return value
    })

  return inflight
}

export async function signIn(email, password) {
  const data = await request('POST', '/api/auth/login', { body: { email: email, password: password } })
  setToken(data && data.token)
  sessionCache = data && data.user ? { user: data.user, token: token } : null
  return sessionCache
}

export async function signUp(email, password) {
  const data = await request('POST', '/api/auth/register', { body: { email: email, password: password } })
  setToken(data && data.token)
  sessionCache = data && data.user ? { user: data.user, token: token } : null
  return sessionCache
}

export async function signOut() {
  try {
    await request('POST', '/api/auth/logout')
  } catch (e) {
    /* 退出失败也要清本地状态 */
  }
  setToken('')
  sessionCache = null
}

export async function changePassword(oldPassword, newPassword) {
  return request('POST', '/api/auth/password', {
    body: { old: oldPassword, new: newPassword }
  })
}
