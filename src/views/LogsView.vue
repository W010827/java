<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logApi } from '../api'
import { todayStr, formatDate, friendlyDay, parseTags, joinTags, errorText } from '../utils'
import Icon from '../components/Icon.vue'

const logs = ref([])
const loading = ref(false)
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({ log_date: todayStr(), title: '', content: '', tags: '' })

let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 320)
})

async function load() {
  loading.value = true
  try {
    logs.value = await logApi.list({ keyword: keyword.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editingId.value = null
  Object.assign(form, { log_date: todayStr(), title: '', content: '', tags: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    log_date: formatDate(row.log_date),
    title: row.title || '',
    content: row.content || '',
    tags: row.tags || ''
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.title.trim()) return ElMessage.warning('请填写记录标题')
  saving.value = true
  try {
    const payload = {
      log_date: form.log_date || todayStr(),
      title: form.title.trim(),
      content: form.content.trim(),
      tags: joinTags(parseTags(form.tags))
    }
    if (editingId.value) {
      await logApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await logApi.create(payload)
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除记录「${row.title}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await logApi.remove(row.id)
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
        <el-input v-model="keyword" placeholder="搜索标题、内容或标签" clearable style="width: 260px">
          <template #prefix><Icon name="search" :size="15" /></template>
        </el-input>
        <el-button :loading="loading" @click="load">
          <span class="btn-icon"><Icon name="refresh" :size="15" />刷新</span>
        </el-button>
      </div>
      <el-button type="primary" @click="openCreate">
        <span class="btn-icon"><Icon name="plus" :size="16" />写一条记录</span>
      </el-button>
    </div>

    <div v-if="loading && !logs.length" class="card empty">正在加载…</div>

    <div v-else-if="!logs.length" class="card empty">
      <div class="empty-title">还没有工作记录</div>
      <div>随手记下做过的事、踩过的坑、想清楚的问题，日后回看很有用。</div>
    </div>

    <div v-else class="list">
      <article v-for="row in logs" :key="row.id" class="card log-card">
        <div class="log-side">
          <strong>{{ friendlyDay(row.log_date) }}</strong>
          <span>{{ formatDate(row.log_date) }}</span>
        </div>

        <div class="log-main">
          <header>
            <h3>{{ row.title }}</h3>
            <div class="actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </header>

          <p v-if="row.content" class="log-content">{{ row.content }}</p>

          <div v-if="parseTags(row.tags).length" class="log-tags">
            <span v-for="t in parseTags(row.tags)" :key="t" class="pill gray">#{{ t }}</span>
          </div>
        </div>
      </article>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑记录' : '写一条记录'"
      width="580px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="日期">
          <el-date-picker
            v-model="form.log_date"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="一句话概括这件事" maxlength="80" />
        </el-form-item>

        <el-form-item label="详细内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            resize="vertical"
            placeholder="过程、结论、遗留问题……"
          />
        </el-form-item>

        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="用逗号或空格分隔，例如：随州, 应急通信, 排查" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editingId ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.log-card {
  display: flex;
  gap: 20px;
  padding: 18px 20px;
}

.log-side {
  flex: 0 0 84px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-top: 2px;
}

.log-side strong {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--primary-dark);
}

.log-side span {
  font-size: 11.5px;
  color: var(--text-3);
}

.log-main {
  flex: 1;
  min-width: 0;
}

.log-main header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.log-main h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.actions {
  flex: 0 0 auto;
  opacity: 0;
  transition: opacity 0.15s;
}

.log-card:hover .actions {
  opacity: 1;
}

.log-content {
  margin: 8px 0 0;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.log-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 11px;
}

@media (max-width: 720px) {
  .log-card {
    flex-direction: column;
    gap: 8px;
  }
  .log-side {
    flex-direction: row;
    gap: 8px;
    align-items: baseline;
  }
  .actions {
    opacity: 1;
  }
}
</style>
