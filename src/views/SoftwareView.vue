<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { softwareApi, storageApi, saveAs } from '../api'
import { getSession } from '../cloud'
import { startUpload, revisions } from '../uploads'
import { formatSize, formatDateTime, errorText, errorDetail } from '../utils'
import Icon from '../components/Icon.vue'
import FilePicker from '../components/FilePicker.vue'

const PLATFORMS = ['Windows', 'macOS', 'Linux', 'Android', 'iOS', '其他']
const CATEGORIES = ['常用工具', '办公', '开发', '运维', '安全', '设计', '系统', '其他']
/** 超过这个体积就给出「可能要等几分钟」的提示 */
const BIG_FILE = 100 * 1024 * 1024
const TONES = [
  { bg: '#e8f0ff', fg: '#2563eb' },
  { bg: '#e9f8f0', fg: '#12a150' },
  { bg: '#fff2e5', fg: '#e08700' },
  { bg: '#f3ebff', fg: '#7c3aed' },
  { bg: '#e8f7fb', fg: '#0891b2' },
  { bg: '#fdecec', fg: '#e5484d' }
]

const list = ref([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const userId = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const pickedFile = ref(null)
const form = reactive({
  name: '',
  version: '',
  platform: 'Windows',
  category: '常用工具',
  description: ''
})

let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 320)
})
watch(category, load)

// 后台上传写完记录后会把这个数字 +1，列表自动刷新。
// 页面已切走时 watch 已被 Vue 自动停掉，回来时 onMounted 会重新拉，不会出错。
watch(() => revisions.software, load)

function toneOf(name) {
  let sum = 0
  for (const ch of String(name || 'x')) sum += ch.charCodeAt(0)
  return TONES[sum % TONES.length]
}

function initialOf(name) {
  const s = String(name || '').trim()
  if (!s) return '#'
  return /[a-zA-Z]/.test(s[0]) ? s[0].toUpperCase() : s[0]
}

async function load() {
  loading.value = true
  try {
    list.value = await softwareApi.list({ keyword: keyword.value, category: category.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const session = await getSession()
  userId.value = session?.user?.id || ''
  await load()
})

function openCreate() {
  editingId.value = null
  pickedFile.value = null
  Object.assign(form, {
    name: '',
    version: '',
    platform: 'Windows',
    category: '常用工具',
    description: ''
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  pickedFile.value = null
  Object.assign(form, {
    name: row.name || '',
    version: row.version || '',
    platform: row.platform || 'Windows',
    category: row.category || '常用工具',
    description: row.description || ''
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) return ElMessage.warning('请填写软件名称')
  if (!editingId.value && !pickedFile.value) return ElMessage.warning('请选择要上传的安装包')

  const file = pickedFile.value
  const id = editingId.value
  const payload = {
    name: form.name.trim(),
    version: form.version.trim(),
    platform: form.platform,
    category: form.category,
    description: form.description.trim()
  }

  // 选了文件：对话框立刻收起，上传交给后台队列，用户可以马上做别的事
  if (file) {
    if (!userId.value) return ElMessage.warning('登录状态已失效，请重新登录')

    dialogVisible.value = false
    startUpload({
      folder: 'software',
      file,
      label: payload.name || file.name,
      // 上传成功后才写记录，保证 file_path 指向真实存在的文件；
      // 写记录失败时 uploads 会把文件删掉，不留孤儿
      onDone: async (path) => {
        const body = Object.assign({}, payload, {
          file_path: path,
          file_name: file.name,
          file_size: file.size
        })
        if (id) await softwareApi.update(id, body)
        else await softwareApi.create(body)
      }
    })
    ElMessage.info('已开始后台上传，可以继续做其他事情')
    return
  }

  // 没选文件（编辑时只改信息）：走原来的同步保存
  saving.value = true
  try {
    if (id) await softwareApi.update(id, payload)
    else await softwareApi.create(payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
    console.error('[保存失败]', errorDetail(e), e)
  } finally {
    saving.value = false
  }
}

async function download(row) {
  if (!row.file_path) return ElMessage.warning('该软件没有关联安装包')
  row._loading = true
  try {
    await saveAs(row.file_path, row.file_name || row.name)
    await softwareApi.bumpDownload(row.id, row.download_count)
    row.download_count = (Number(row.download_count) || 0) + 1
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    row._loading = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」？关联的安装包会一并删除，无法恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await softwareApi.remove(row.id)
    if (row.file_path) {
      try {
        await storageApi.remove([row.file_path])
      } catch {
        /* 文件已不在时忽略 */
      }
    }
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
</script>

<template>
  <div>
    <div class="toolbar-row">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索名称或说明" clearable style="width: 240px">
          <template #prefix><Icon name="search" :size="15" /></template>
        </el-input>
        <el-select v-model="category" placeholder="全部分类" clearable style="width: 142px">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-button :loading="loading" @click="load">
          <span class="btn-icon"><Icon name="refresh" :size="15" />刷新</span>
        </el-button>
      </div>
      <el-button type="primary" @click="openCreate">
        <span class="btn-icon"><Icon name="upload" :size="16" />上传软件</span>
      </el-button>
    </div>

    <div v-if="loading && !list.length" class="card empty">正在加载…</div>

    <div v-else-if="!list.length" class="card empty">
      <div class="empty-title">还没有收录任何软件</div>
      <div>点击右上角「上传软件」，把常用的安装包放进来，之后换机器直接从这里下载。</div>
    </div>

    <div v-else class="grid">
      <article v-for="row in list" :key="row.id" class="card app-card">
        <header class="app-head">
          <div
            class="app-avatar"
            :style="{ background: toneOf(row.name).bg, color: toneOf(row.name).fg }"
          >
            {{ initialOf(row.name) }}
          </div>
          <div class="app-title">
            <strong>{{ row.name }}</strong>
            <span v-if="row.version">v{{ row.version }}</span>
          </div>
        </header>

        <p class="app-desc">{{ row.description || '暂无说明' }}</p>

        <div class="app-tags">
          <span v-if="row.platform" class="pill">{{ row.platform }}</span>
          <span v-if="row.category" class="pill gray">{{ row.category }}</span>
        </div>

        <footer class="app-foot">
          <div class="app-stat">
            <span>{{ formatSize(row.file_size) }}</span>
            <span class="dot">·</span>
            <span>{{ row.download_count || 0 }} 次下载</span>
          </div>
          <div class="app-actions">
            <el-button
              type="primary"
              size="small"
              :loading="row._loading"
              @click="download(row)"
            >
              <span class="btn-icon"><Icon name="download" :size="14" />下载</span>
            </el-button>
            <el-button size="small" @click="openEdit(row)"><Icon name="edit" :size="14" /></el-button>
            <el-button size="small" @click="remove(row)"><Icon name="trash" :size="14" /></el-button>
          </div>
        </footer>

        <div class="app-time">{{ formatDateTime(row.created_at) }}</div>
      </article>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑软件' : '上传软件'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="软件名称" required>
          <el-input v-model="form.name" placeholder="例如：Postman" maxlength="60" />
        </el-form-item>

        <div class="two-col">
          <el-form-item label="版本号">
            <el-input v-model="form.version" placeholder="例如：10.20.0" maxlength="30" />
          </el-form-item>
          <el-form-item label="适用平台">
            <el-select v-model="form.platform" style="width: 100%">
              <el-option v-for="p in PLATFORMS" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item label="说明">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="这个软件是做什么用的、安装注意事项等"
          />
        </el-form-item>

        <el-form-item :label="editingId ? '替换安装包（不选则保留原文件）' : '安装包'">
          <FilePicker v-model="pickedFile" hint="选择安装包文件，不限大小" />
          <div v-if="pickedFile && pickedFile.size > BIG_FILE" class="upload-note">
            文件较大（{{ formatSize(pickedFile.size) }}）。点「上传」后弹窗会立即关闭，
            上传在后台继续，你可以切到其他页面，右下角会一直显示进度。
          </div>
          <div v-else-if="pickedFile" class="upload-note quiet">
            已选 {{ formatSize(pickedFile.size) }}，点「上传」后在后台进行，可以继续做其他事
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editingId ? '保存' : '上传' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(282px, 1fr));
  gap: 16px;
}

.app-card {
  padding: 18px;
  display: flex;
  flex-direction: column;
  transition: box-shadow 0.18s, transform 0.18s;
}

.app-card:hover {
  box-shadow: var(--shadow);
  transform: translateY(-1px);
}

.app-head {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 12px;
}

.app-avatar {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 17px;
}

.app-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.app-title strong {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-title span {
  font-size: 11.5px;
  color: var(--text-3);
  flex: 0 0 auto;
}

.app-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
  min-height: 42px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.app-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.app-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 13px;
  border-top: 1px solid var(--border);
}

.app-stat {
  font-size: 12px;
  color: var(--text-3);
  display: flex;
  gap: 5px;
  white-space: nowrap;
}

.dot {
  color: var(--border-strong);
}

.app-actions {
  display: flex;
  gap: 2px;
  flex: 0 0 auto;
}

.app-actions :deep(.el-button + .el-button) {
  margin-left: 2px;
}

.app-time {
  margin-top: 9px;
  font-size: 11.5px;
  color: var(--text-3);
}

.upload-note {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: #fff8e8;
  border: 1px solid #ffe2ab;
  color: #8a5c00;
  font-size: 12px;
  line-height: 1.6;
}

.upload-note.quiet {
  background: var(--bg);
  border-color: var(--border);
  color: var(--text-3);
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

@media (max-width: 560px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
