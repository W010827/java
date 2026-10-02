/**
 * 业务数据访问层。
 *
 * 全部走内网自建后端（server/workhub.py）的 /api 接口，
 * 对外暴露的方法签名与视图组件约定保持一致，替换实现时视图无需改动。
 */

import { request } from './cloud'

function toQuery(params) {
  const parts = []
  Object.keys(params || {}).forEach((k) => {
    const v = params[k]
    if (v === undefined || v === null || v === '') return
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
  })
  return parts.length ? '?' + parts.join('&') : ''
}

/* ==================== 软件仓库 ==================== */

export const softwareApi = {
  list(options) {
    const opt = options || {}
    return request(
      'GET',
      '/api/software' + toQuery({ keyword: opt.keyword, category: opt.category })
    )
  },
  create(row) {
    return request('POST', '/api/software', { body: row })
  },
  update(id, patch) {
    return request('PUT', '/api/software/' + id, { body: patch })
  },
  remove(id) {
    return request('DELETE', '/api/software/' + id)
  },
  bumpDownload(id) {
    return request('POST', '/api/software/' + id + '/download')
  }
}

/* ==================== 工作日报 ==================== */

export const reportApi = {
  list(options) {
    const opt = options || {}
    return request(
      'GET',
      '/api/reports' + toQuery({ from: opt.from, to: opt.to, limit: opt.limit })
    )
  },
  async getByDate(date) {
    return request('GET', '/api/reports/one' + toQuery({ date: date }))
  },
  save(date, payload) {
    return request('POST', '/api/reports', { body: Object.assign({ report_date: date }, payload) })
  },
  remove(id) {
    return request('DELETE', '/api/reports/' + id)
  }
}

/* ==================== 工作记录 ==================== */

export const logApi = {
  list(options) {
    const opt = options || {}
    return request('GET', '/api/logs' + toQuery({ keyword: opt.keyword, limit: opt.limit }))
  },
  create(row) {
    return request('POST', '/api/logs', { body: row })
  },
  update(id, patch) {
    return request('PUT', '/api/logs/' + id, { body: patch })
  },
  remove(id) {
    return request('DELETE', '/api/logs/' + id)
  }
}

/* ==================== 任务待办 ==================== */

export const todoApi = {
  list(limit) {
    return request('GET', '/api/todos' + toQuery({ limit: limit }))
  },
  create(row) {
    return request('POST', '/api/todos', { body: row })
  },
  update(id, patch) {
    return request('PUT', '/api/todos/' + id, { body: patch })
  },
  setDone(id, done) {
    return request('PUT', '/api/todos/' + id, {
      body: {
        status: done ? 'done' : 'pending',
        done_at: done ? new Date().toISOString() : null
      }
    })
  },
  remove(id) {
    return request('DELETE', '/api/todos/' + id)
  }
}

/* ==================== 文件资料库 ==================== */

export const docApi = {
  list(options) {
    const opt = options || {}
    return request(
      'GET',
      '/api/documents' + toQuery({ keyword: opt.keyword, category: opt.category })
    )
  },
  create(row) {
    return request('POST', '/api/documents', { body: row })
  },
  update(id, patch) {
    return request('PUT', '/api/documents/' + id, { body: patch })
  },
  remove(id) {
    return request('DELETE', '/api/documents/' + id)
  },
  bumpDownload(id) {
    return request('POST', '/api/documents/' + id + '/download')
  }
}

/* ==================== 文件存储 ==================== */

/**
 * 上传不再走这里 —— 大文件上传统一交给 src/uploads.js 的全局后台队列
 * （startUpload），这样才能脱离弹窗/页面在后台继续传。
 * 这里只保留与「已存在的文件」相关的操作。
 */
export const storageApi = {
  /** 生成临时下载地址（带签名，默认 10 分钟有效），返回可直接点击的绝对地址 */
  async signedUrl(path, ttl) {
    const data = await request('GET', '/api/share' + toQuery({ path: path, ttl: ttl || 600 }))
    const url = (data && data.url) || ''
    if (!url) throw new Error('无法生成下载地址')
    return new URL(url, window.location.href).href
  },

  async remove(paths) {
    const list = (Array.isArray(paths) ? paths : [paths]).filter(Boolean)
    if (!list.length) return
    const qs = list.map((p) => 'path=' + encodeURIComponent(p)).join('&')
    return request('DELETE', '/api/files?' + qs)
  }
}

/** 触发浏览器下载 */
export async function saveAs(path, fileName) {
  const url = await storageApi.signedUrl(path)
  if (!url) throw new Error('无法生成下载地址')
  const a = document.createElement('a')
  a.href = url
  a.download = fileName || ''
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
