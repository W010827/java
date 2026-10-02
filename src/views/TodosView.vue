<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { todoApi } from '../api'
import { todayStr, formatDate, errorText } from '../utils'
import Icon from '../components/Icon.vue'

const PRIORITIES = [
  { value: 'high', label: '紧急', cls: 'red' },
  { value: 'normal', label: '普通', cls: '' },
  { value: 'low', label: '次要', cls: 'gray' }
]
const WEIGHT = { high: 0, normal: 1, low: 2 }

const todos = ref([])
const loading = ref(false)
const filter = ref('pending')
const quickTitle = ref('')
const quickAdding = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({ title: '', detail: '', priority: 'normal', due_date: '' })

const pendingCount = computed(() => todos.value.filter((t) => t.status !== 'done').length)
const doneCount = computed(() => todos.value.filter((t) => t.status === 'done').length)
const overdueCount = computed(
  () =>
    todos.value.filter(
      (t) => t.status !== 'done' && t.due_date && formatDate(t.due_date) < todayStr()
    ).length
)

const visible = computed(() => {
  let arr = todos.value
  if (filter.value === 'pending') arr = arr.filter((t) => t.status !== 'done')
  if (filter.value === 'done') arr = arr.filter((t) => t.status === 'done')

  return [...arr].sort((a, b) => {
    const aDone = a.status === 'done'
    const bDone = b.status === 'done'
    if (aDone !== bDone) return aDone ? 1 : -1

    const ad = a.due_date ? formatDate(a.due_date) : ''
    const bd = b.due_date ? formatDate(b.due_date) : ''
    if (ad !== bd) {
      if (!ad) return 1
      if (!bd) return -1
      return ad < bd ? -1 : 1
    }
    return (WEIGHT[a.priority] ?? 1) - (WEIGHT[b.priority] ?? 1)
  })
})

function priorityOf(value) {
  return PRIORITIES.find((p) => p.value === value) || PRIORITIES[1]
}

function dueState(t) {
  if (!t.due_date) return null
  const d = formatDate(t.due_date)
  if (t.status === 'done') return { text: d, cls: 'gray' }
  if (d < todayStr()) return { text: `逾期 ${d}`, cls: 'red' }
  if (d === todayStr()) return { text: '今天到期', cls: 'orange' }
  return { text: d, cls: 'gray' }
}

async function load() {
  loading.value = true
  try {
    todos.value = await todoApi.list()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function quickAdd() {
  const title = quickTitle.value.trim()
  if (!title) return
  quickAdding.value = true
  try {
    await todoApi.create({ title, priority: 'normal', due_date: todayStr() })
    quickTitle.value = ''
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    quickAdding.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', detail: '', priority: 'normal', due_date: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title || '',
    detail: row.detail || '',
    priority: row.priority || 'normal',
    due_date: row.due_date ? formatDate(row.due_date) : ''
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.title.trim()) return ElMessage.warning('请填写任务内容')
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      detail: form.detail.trim(),
      priority: form.priority,
      due_date: form.due_date || null
    }
    if (editingId.value) {
      await todoApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await todoApi.create(payload)
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

async function toggle(row) {
  const next = row.status !== 'done'
  try {
    await todoApi.setDone(row.id, next)
    row.status = next ? 'done' : 'pending'
    row.done_at = next ? new Date().toISOString() : null
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除任务「${row.title}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await todoApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
</script>

<template>
  <div>
    <div class="stats">
      <div class="card stat">
        <span class="stat-label">进行中</span>
        <strong>{{ pendingCount }}</strong>
      </div>
      <div class="card stat">
        <span class="stat-label">今天到期</span>
        <strong>{{ todos.filter((t) => t.status !== 'done' && t.due_date && formatDate(t.due_date) === todayStr()).length }}</strong>
      </div>
      <div class="card stat" :class="{ alert: overdueCount > 0 }">
        <span class="stat-label">已逾期</span>
        <strong>{{ overdueCount }}</strong>
      </div>
      <div class="card stat">
        <span class="stat-label">已完成</span>
        <strong>{{ doneCount }}</strong>
      </div>
    </div>

    <div class="card card-pad">
      <div class="quick">
        <el-input
          v-model="quickTitle"
          size="large"
          placeholder="快速添加一条任务，回车即可（默认今天到期）"
          @keyup.enter="quickAdd"
        >
          <template #prefix><Icon name="plus" :size="16" /></template>
        </el-input>
        <el-button size="large" type="primary" :loading="quickAdding" @click="quickAdd">
          添加
        </el-button>
        <el-button size="large" @click="openCreate">详细填写</el-button>
      </div>

      <div class="filters">
        <button type="button" :class="{ on: filter === 'pending' }" @click="filter = 'pending'">
          进行中 ({{ pendingCount }})
        </button>
        <button type="button" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          全部 ({{ todos.length }})
        </button>
        <button type="button" :class="{ on: filter === 'done' }" @click="filter = 'done'">
          已完成 ({{ doneCount }})
        </button>
        <el-button link :loading="loading" class="refresh" @click="load">
          <span class="btn-icon"><Icon name="refresh" :size="14" />刷新</span>
        </el-button>
      </div>

      <div v-if="loading && !todos.length" class="empty">正在加载…</div>

      <div v-else-if="!visible.length" class="empty">
        <div class="empty-title">
          {{ filter === 'done' ? '还没有已完成的任务' : '暂时没有待办' }}
        </div>
        <div>{{ filter === 'done' ? '完成的任务会出现在这里。' : '在上面输入框写一条，回车就加进来了。' }}</div>
      </div>

      <ul v-else class="todos">
        <li v-for="row in visible" :key="row.id" :class="{ done: row.status === 'done' }">
          <button type="button" class="check" @click="toggle(row)">
            <Icon v-if="row.status === 'done'" name="check" :size="13" />
          </button>

          <div class="todo-main">
            <div class="todo-line">
              <span class="todo-title">{{ row.title }}</span>
              <span class="pill" :class="priorityOf(row.priority).cls">
                {{ priorityOf(row.priority).label }}
              </span>
              <span v-if="dueState(row)" class="pill" :class="dueState(row).cls">
                {{ dueState(row).text }}
              </span>
            </div>
            <p v-if="row.detail" class="todo-detail">{{ row.detail }}</p>
          </div>

          <div class="todo-actions">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </div>
        </li>
      </ul>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑任务' : '新建任务'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="任务内容" required>
          <el-input v-model="form.title" placeholder="要做什么" maxlength="80" />
        </el-form-item>

        <el-form-item label="补充说明">
          <el-input
            v-model="form.detail"
            type="textarea"
            :rows="3"
            resize="vertical"
            placeholder="可选，写清楚背景或验收标准"
          />
        </el-form-item>

        <div class="two-col">
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option v-for="p in PRIORITIES" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="截止日期">
            <el-date-picker
              v-model="form.due_date"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="可不填"
              style="width: 100%"
            />
          </el-form-item>
        </div>
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
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.stat {
  padding: 15px 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.stat-label {
  font-size: 12.5px;
  color: var(--text-3);
}

.stat strong {
  font-size: 24px;
  font-weight: 650;
  letter-spacing: 0.5px;
}

.stat.alert strong {
  color: var(--danger);
}

.quick {
  display: flex;
  gap: 10px;
}

.quick .el-input {
  flex: 1;
}

.filters {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 16px 0 6px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.filters button {
  border: 0;
  background: transparent;
  padding: 6px 13px;
  border-radius: 9px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.filters button:hover {
  background: #f5f7fc;
}

.filters button.on {
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-weight: 600;
}

.refresh {
  margin-left: auto;
}

.todos {
  list-style: none;
  margin: 0;
  padding: 0;
}

.todos li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 13px 10px;
  border-radius: 10px;
  border-bottom: 1px solid #f2f4f9;
  transition: background 0.15s;
}

.todos li:last-child {
  border-bottom: 0;
}

.todos li:hover {
  background: #fafbfe;
}

.check {
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  border: 1.6px solid var(--border-strong);
  border-radius: 6px;
  background: #fff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.check:hover {
  border-color: var(--primary);
}

.todos li.done .check {
  background: var(--success);
  border-color: var(--success);
}

.todo-main {
  flex: 1;
  min-width: 0;
}

.todo-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.todo-title {
  font-size: 14px;
  font-weight: 500;
}

.todos li.done .todo-title {
  color: var(--text-3);
  text-decoration: line-through;
}

.todo-detail {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-3);
  white-space: pre-wrap;
}

.todo-actions {
  flex: 0 0 auto;
  opacity: 0;
  transition: opacity 0.15s;
}

.todos li:hover .todo-actions {
  opacity: 1;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

@media (max-width: 840px) {
  .stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .quick {
    flex-wrap: wrap;
  }
  .todo-actions {
    opacity: 1;
  }
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
