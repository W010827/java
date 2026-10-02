/**
 * 全局后台上传队列。
 *
 * 上传**不绑定在任何页面或弹窗上**：调用方 `startUpload()` 之后立即返回，
 * 用户可以关弹窗、点其他地方、切到别的菜单，上传照常进行，右下角的上传面板
 * 会一直显示进度。这正是这个模块存在的理由。
 *
 * 用法：
 *   import { startUpload } from '../uploads'
 *   startUpload({
 *     folder: 'software',          // 服务端业务目录：software / docs / misc
 *     file,                        // 浏览器 File 对象
 *     label: 'Postman',            // 面板上显示的标题，缺省用文件名
 *     onDone: async (path) => {    // 上传成功后的收尾（把文件路径写进业务表）
 *       await softwareApi.create({ name: 'Postman', file_path: path, ... })
 *     }
 *   })
 *
 * 约定：
 *   - onDone 抛错时，本模块会把刚上传的文件删掉，避免留下孤儿文件。
 *   - 业务表写成功后本模块只负责把对应页面的数据版本号 +1，由页面自己决定何时刷新
 *     （见 `revisions`），这样即使发起上传的页面已经切走也不会出错。
 *   - 失败的任务可以「重试」，File 对象一直留在内存里，不需要用户重新选文件。
 */
import { reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadWithProgress } from './cloud'
import { storageApi } from './api'
import { errorText, errorDetail } from './utils'

/** 面板里最多保留多少条已结束的记录，超出就把最旧的丢掉 */
const MAX_FINISHED = 12

let seq = 0

const state = reactive({
  /** 任务列表，最新的在最前面 */
  tasks: [],
  /** 面板是否收起 */
  collapsed: false
})

/**
 * 各业务表的数据版本号。上传写库成功后 +1；
 * 对应的列表页 watch 它就能自动刷新（页面没挂载时什么都不用做）。
 */
const revisions = reactive({ software: 0, docs: 0, misc: 0 })

/** 运行时对象（File / 回调 / xhr）刻意放在响应式之外，避免被 Proxy 包一层 */
const meta = new Map()

export const tasks = computed(() => state.tasks)
export const collapsed = computed(() => state.collapsed)
export const busyCount = computed(
  () => state.tasks.filter((t) => t.status === 'uploading' || t.status === 'saving').length
)
export const finishedCount = computed(
  () => state.tasks.filter((t) => t.status !== 'uploading' && t.status !== 'saving').length
)
export { revisions }

export function toggleCollapsed() {
  state.collapsed = !state.collapsed
}

function trimFinished() {
  let extra = state.tasks.length - MAX_FINISHED - busyCount.value
  if (extra <= 0) return
  // 从尾部（最旧）往前删已结束的任务
  for (let i = state.tasks.length - 1; i >= 0 && extra > 0; i--) {
    const t = state.tasks[i]
    if (t.status !== 'uploading' && t.status !== 'saving') {
      meta.delete(t.id)
      state.tasks.splice(i, 1)
      extra--
    }
  }
}

/** 开始一个后台上传，立即返回任务对象 */
export function startUpload(options) {
  const opt = options || {}
  const file = opt.file
  if (!file) throw new Error('startUpload 需要传入 file')

  const task = reactive({
    id: `up-${Date.now()}-${++seq}`,
    folder: opt.folder || 'misc',
    label: opt.label || file.name,
    fileName: file.name,
    size: Number(file.size) || 0,
    loaded: 0,
    percent: 0,
    speed: 0,
    status: 'uploading', // uploading | saving | done | error | cancelled
    error: '',
    path: '',
    startedAt: Date.now(),
    finishedAt: 0
  })

  meta.set(task.id, {
    file,
    onDone: typeof opt.onDone === 'function' ? opt.onDone : null,
    xhr: null
  })

  state.tasks.unshift(task)
  state.collapsed = false // 有新任务就自动展开，避免用户以为没反应
  run(task)
  return task
}

function run(task) {
  const m = meta.get(task.id)
  if (!m) return

  const url =
    '/api/upload' +
    '?folder=' +
    encodeURIComponent(task.folder) +
    '&name=' +
    encodeURIComponent(task.fileName)

  task.status = 'uploading'
  task.error = ''
  task.percent = 0
  task.loaded = 0
  task.speed = 0
  task.path = ''
  task.startedAt = Date.now()
  task.finishedAt = 0

  let lastLoaded = 0
  let lastAt = Date.now()

  uploadWithProgress(
    url,
    m.file,
    m.file.type || 'application/octet-stream',
    (percent, loaded) => {
      task.percent = percent
      task.loaded = loaded
      const now = Date.now()
      const dt = now - lastAt
      // 每半秒算一次瞬时速度，再做个平滑，免得数字乱跳
      if (dt >= 500) {
        const inst = (loaded - lastLoaded) / (dt / 1000)
        task.speed = task.speed > 0 ? task.speed * 0.6 + inst * 0.4 : inst
        lastLoaded = loaded
        lastAt = now
      }
    },
    (xhr) => {
      m.xhr = xhr
    }
  )
    .then(async (data) => {
      m.xhr = null
      const path = (data && data.path) || ''
      task.path = path
      task.percent = 100
      task.loaded = task.size

      if (m.onDone) {
        // 文件已落盘，接下来是写业务表 —— 这一步通常很快
        task.status = 'saving'
        try {
          await m.onDone(path, task)
        } catch (e) {
          // 记录没写成功，把刚上传的文件清掉，别留下没人引用的孤儿文件
          if (path) {
            try {
              await storageApi.remove([path])
            } catch {
              /* 清理失败不影响主流程 */
            }
          }
          throw e
        }
      }

      task.status = 'done'
      task.finishedAt = Date.now()
      task.speed = 0
      ElMessage.success(`「${task.label}」上传完成`)
      // 通知对应列表页刷新（页面没挂载就什么也不做，回来时会重新拉）
      revisions[task.folder] = (revisions[task.folder] || 0) + 1
      trimFinished()
    })
    .catch((e) => {
      m.xhr = null
      task.finishedAt = Date.now()
      task.speed = 0
      if (e && e.kind === 'cancelled') {
        task.status = 'cancelled'
      } else {
        task.status = 'error'
        task.error = errorText(e)
        ElMessage.error(`「${task.label}」上传失败：${task.error}`)
        // 完整错误留在控制台，排查时可直接复制
        console.error('[上传失败]', errorDetail(e), e)
      }
      trimFinished()
    })
}

/** 取消一个正在上传的任务 */
export function cancelTask(id) {
  const m = meta.get(id)
  if (m && m.xhr) {
    m.xhr.abort()
    m.xhr = null
  }
}

/** 重试：失败/取消的任务，File 还在内存里，直接重跑 */
export function retryTask(id) {
  const task = state.tasks.find((t) => t.id === id)
  const m = meta.get(id)
  if (!task || !m) return
  // 放到最前面，方便看到
  state.tasks.splice(state.tasks.indexOf(task), 1)
  state.tasks.unshift(task)
  run(task)
}

/** 从面板移除一条记录（进行中的不允许，要先取消） */
export function dismissTask(id) {
  const i = state.tasks.findIndex((t) => t.id === id)
  if (i < 0) return
  const t = state.tasks[i]
  if (t.status === 'uploading' || t.status === 'saving') return
  meta.delete(id)
  state.tasks.splice(i, 1)
}

/** 清掉所有已结束的记录 */
export function clearFinished() {
  for (let i = state.tasks.length - 1; i >= 0; i--) {
    const t = state.tasks[i]
    if (t.status !== 'uploading' && t.status !== 'saving') {
      meta.delete(t.id)
      state.tasks.splice(i, 1)
    }
  }
}

/** 有任务在传时，关闭/刷新页面前给个提示，避免用户以为它还能继续 */
let unloadHandler = null
watch(busyCount, (n) => {
  if (typeof window === 'undefined') return
  if (n > 0) {
    if (!unloadHandler) {
      unloadHandler = (e) => {
        e.preventDefault()
        e.returnValue = ''
      }
      window.addEventListener('beforeunload', unloadHandler)
    }
  } else if (unloadHandler) {
    window.removeEventListener('beforeunload', unloadHandler)
    unloadHandler = null
  }
})
