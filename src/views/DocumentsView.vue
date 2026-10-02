<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, storageApi, saveAs } from '../api'
import { getSession } from '../cloud'
import { startUpload, revisions } from '../uploads'
import { formatSize, formatDateTime, errorText, errorDetail, parseTags, joinTags } from '../utils'
import Icon from '../components/Icon.vue'
import FilePicker from '../components/FilePicker.vue'

const CATEGORIES = ['工作文档', '制度规范', '技术资料', '模板', '合同协议', '其他']

const list = ref([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const userId = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const pickedFile = ref(null)
const form = reactive({ title: '', category: '工作文档', tags: '', note: '' })

let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 320)
})
watch(category, load)

// 后台上传写完记录后自增，列表自动刷新；页面切走时 watch 自动停用
watch(() => revisions.docs, load)

async function load() {
  loading.value = true
  try {
    list.value = await docApi.list({ keyword: keyword.value, category: category.value })
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
  Object.assign(form, { title: '', category: '工作文档', tags: '', note: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  pickedFile.value = null
  Object.assign(form, {
    title: row.title || '',
    category: row.category || '工作文档',
    tags: row.tags || '',
    note: row.note || ''
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.title.trim()) return ElMessage.warning('请填写资料标题')
  if (!editingId.value && !pickedFile.value) return ElMessage.warning('请选择要上传的文件')

  const file = pickedFile.value
  const id = editingId.value
  const payload = {
    title: form.title.trim(),
    category: form.category,
    tags: joinTags(parseTags(form.tags)),
    note: form.note.trim()
  }

  // 选了文件：对话框立刻收起，上传交给后台队列
  if (file) {
    if (!userId.value) return ElMessage.warning('登录状态已失效，请重新登录')

    dialogVisible.value = false
    startUpload({
      folder: 'docs',
      file,
      label: payload.title || file.name,
      onDone: async (path) => {
        const body = Object.assign({}, payload, {
          file_path: path,
          file_name: file.name,
          file_size: file.size,
          mime_type: file.type || ''
        })
        if (id) await docApi.update(id, body)
        else await docApi.create(body)
      }
    })
    ElMessage.info('已开始后台上传，可以继续做其他事情')
    return
  }

  // 没选文件（编辑时只改信息）：走同步保存
  saving.value = true
  try {
    if (id) await docApi.update(id, payload)
    else await docApi.create(payload)
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
  if (!row.file_path) return ElMessage.warning('该资料没有关联文件')
  row._loading = true
  try {
    await saveAs(row.file_path, row.file_name || row.title)
    await docApi.bumpDownload(row.id, row.download_count)
    row.download_count = (Number(row.download_count) || 0) + 1
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    row._loading = false
  }
}

async function copyLink(row) {
  if (!row.file_path) return ElMessage.warning('该资料没有关联文件')
  try {
    const url = await storageApi.signedUrl(row.file_path)
    await navigator.clipboard.writeText(url)
    ElMessage.success('已复制临时链接（10 分钟内有效）')
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？文件会一并删除，无法恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await docApi.remove(row.id)
    if (row.file_path) {
      try {
        await storageApi.remove([row.file_path])
      } catch {
        /* 忽略 */
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
        <el-input v-model="keyword" placeholder="搜索标题、文件名或标签" clearable style="width: 250px">
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
        <span class="btn-icon"><Icon name="upload" :size="16" />上传资料</span>
      </el-button>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" style="width: 100%">
        <template #empty>
          <div class="empty">
            <div class="empty-title">资料库还是空的</div>
            <div>把常用的文档、规范、模板传上来，需要时随手就能找到。</div>
          </div>
        </template>

        <el-table-column label="资料" min-width="260">
          <template #default="{ row }">
            <div class="doc-cell">
              <div class="doc-icon"><Icon name="document" :size="17" /></div>
              <div class="doc-meta">
                <strong>{{ row.title }}</strong>
                <span>{{ row.file_name || '无文件' }}</span>
                <em v-if="row.note">{{ row.note }}</em>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="118">
          <template #default="{ row }">
            <span v-if="row.category" class="pill gray">{{ row.category }}</span>
          </template>
        </el-table-column>

        <el-table-column label="标签" min-width="130">
          <template #default="{ row }">
            <span v-for="t in parseTags(row.tags)" :key="t" class="tag">#{{ t }}</span>
          </template>
        </el-table-column>

        <el-table-column label="大小" width="96">
          <template #default="{ row }">
            <span class="muted">{{ formatSize(row.file_size) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="下载" width="76">
          <template #default="{ row }">
            <span class="muted">{{ row.download_count || 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">
            <span class="muted">{{ formatDateTime(row.created_at) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="196" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="row._loading"
              @click="download(row)"
            >
              下载
            </el-button>
            <el-button link type="primary" @click="copyLink(row)">链接</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑资料' : '上传资料'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="资料标题" required>
          <el-input v-model="form.title" placeholder="便于以后搜索的名称" maxlength="60" />
        </el-form-item>

        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="用逗号或空格分隔" />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="form.note"
            type="textarea"
            :rows="2"
            maxlength="150"
            placeholder="可选，记录版本、用途等"
          />
        </el-form-item>

        <el-form-item :label="editingId ? '替换文件（不选则保留原文件）' : '文件'">
          <FilePicker v-model="pickedFile" hint="选择要归档的文件" />
          <div v-if="pickedFile" class="upload-note">
            点「上传」后弹窗会立即关闭，上传在后台继续 —— 你可以切到其他页面，
            右下角会一直显示进度。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editingId ? '保存' : '上传' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.doc-cell {
  display: flex;
  align-items: flex-start;
  gap: 11px;
}

.doc-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 9px;
  background: var(--primary-soft);
  color: var(--primary-dark);
  display: flex;
  align-items: center;
  justify-content: center;
}

.doc-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}

.doc-meta strong {
  font-size: 13.5px;
  font-weight: 600;
}

.doc-meta span {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-meta em {
  font-style: normal;
  font-size: 12px;
  color: var(--text-2);
  margin-top: 2px;
}

.tag {
  display: inline-block;
  margin-right: 6px;
  font-size: 12px;
  color: var(--primary-dark);
}

.upload-note {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
