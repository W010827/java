<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '../api'
import { todayStr, formatDate, friendlyDay, errorText, toDateStr } from '../utils'
import Icon from '../components/Icon.vue'

const reports = ref([])
const loading = ref(false)
const saving = ref(false)
const selectedDate = ref(todayStr())

const form = reactive({ done_today: '', plan_tomorrow: '', issues: '' })

const weekday = computed(() => {
  const d = new Date(`${selectedDate.value}T00:00:00`)
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleDateString('zh-CN', { weekday: 'long' })
})

const currentReport = computed(() =>
  reports.value.find((r) => formatDate(r.report_date) === selectedDate.value)
)

const monthCount = computed(() => {
  const prefix = selectedDate.value.slice(0, 7)
  return reports.value.filter((r) => formatDate(r.report_date).startsWith(prefix)).length
})

function excerpt(row) {
  const text = String(row.done_today || row.plan_tomorrow || row.issues || '').trim()
  if (!text) return '（无内容）'
  return text.split('\n')[0].slice(0, 26)
}

function loadInto(date) {
  const found = reports.value.find((r) => formatDate(r.report_date) === date)
  form.done_today = found?.done_today || ''
  form.plan_tomorrow = found?.plan_tomorrow || ''
  form.issues = found?.issues || ''
}

watch(selectedDate, (d) => loadInto(d))

async function load() {
  loading.value = true
  try {
    reports.value = await reportApi.list()
    loadInto(selectedDate.value)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function gotoToday() {
  selectedDate.value = todayStr()
}

async function save() {
  const payload = {
    done_today: form.done_today.trim(),
    plan_tomorrow: form.plan_tomorrow.trim(),
    issues: form.issues.trim()
  }
  if (!payload.done_today && !payload.plan_tomorrow && !payload.issues) {
    return ElMessage.warning('内容还是空的，先写点东西再保存')
  }
  saving.value = true
  try {
    await reportApi.save(selectedDate.value, payload)
    ElMessage.success('日报已保存')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}

async function removeCurrent() {
  const row = currentReport.value
  if (!row) return ElMessage.warning('这一天还没有日报')
  try {
    await ElMessageBox.confirm(`确定删除 ${selectedDate.value} 的日报？删除后无法恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await reportApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

function exportAll() {
  if (!reports.value.length) return ElMessage.warning('还没有日报可以导出')

  const lines = ['# 工作日报汇总', '', `导出时间：${toDateStr()}`, '']
  for (const row of reports.value) {
    const d = formatDate(row.report_date)
    const w = new Date(`${d}T00:00:00`).toLocaleDateString('zh-CN', { weekday: 'long' })
    lines.push(`## ${d} ${w}`, '')
    if (row.done_today) lines.push('**今日完成**', '', row.done_today, '')
    if (row.plan_tomorrow) lines.push('**明日计划**', '', row.plan_tomorrow, '')
    if (row.issues) lines.push('**问题与需要支持**', '', row.issues, '')
    lines.push('---', '')
  }

  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `工作日报_${todayStr()}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已导出为 Markdown 文件')
}
</script>

<template>
  <div class="toolbar-row">
    <div class="toolbar">
      <el-date-picker
        v-model="selectedDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        style="width: 168px"
      />
      <el-button @click="gotoToday">
        <span class="btn-icon"><Icon name="calendar" :size="15" />回到今天</span>
      </el-button>
      <el-button :loading="loading" @click="load">
        <span class="btn-icon"><Icon name="refresh" :size="15" />刷新</span>
      </el-button>
    </div>
    <div class="toolbar">
      <el-button @click="exportAll">
        <span class="btn-icon"><Icon name="download" :size="15" />导出全部</span>
      </el-button>
      <el-button type="primary" :loading="saving" @click="save">
        <span class="btn-icon"><Icon name="check" :size="16" />保存日报</span>
      </el-button>
    </div>
  </div>

  <div class="layout">
    <aside class="card list-side">
      <div class="list-head">
        <span class="section-title">历史日报</span>
        <span class="muted">本月 {{ monthCount }} 篇</span>
      </div>

      <div v-if="loading && !reports.length" class="empty">正在加载…</div>
      <div v-else-if="!reports.length" class="empty">
        <div class="empty-title">还没有日报</div>
        <div>右边写一篇，从今天开始记录。</div>
      </div>

      <ul v-else class="list">
        <li
          v-for="row in reports"
          :key="row.id"
          :class="{ on: formatDate(row.report_date) === selectedDate }"
          @click="selectedDate = formatDate(row.report_date)"
        >
          <div class="item-top">
            <strong>{{ friendlyDay(row.report_date) }}</strong>
            <span>{{ formatDate(row.report_date) }}</span>
          </div>
          <p>{{ excerpt(row) }}</p>
        </li>
      </ul>
    </aside>

    <section class="card editor">
      <header class="editor-head">
        <div>
          <h2>{{ friendlyDay(selectedDate) }}</h2>
          <p class="muted">{{ selectedDate }} · {{ weekday }}</p>
        </div>
        <div class="editor-state">
          <span v-if="currentReport" class="pill green">已填写</span>
          <span v-else class="pill gray">未填写</span>
          <el-button v-if="currentReport" link type="danger" @click="removeCurrent">删除</el-button>
        </div>
      </header>

      <div class="editor-body">
        <div class="field">
          <label>今日完成</label>
          <el-input
            v-model="form.done_today"
            type="textarea"
            :rows="6"
            resize="vertical"
            placeholder="今天做了哪些事？一行一条，写清楚结果。"
          />
        </div>

        <div class="field">
          <label>明日计划</label>
          <el-input
            v-model="form.plan_tomorrow"
            type="textarea"
            :rows="4"
            resize="vertical"
            placeholder="明天准备推进什么。"
          />
        </div>

        <div class="field">
          <label>问题与需要支持</label>
          <el-input
            v-model="form.issues"
            type="textarea"
            :rows="3"
            resize="vertical"
            placeholder="遇到的阻塞、需要协调的事项（没有可留空）。"
          />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 268px 1fr;
  gap: 16px;
  align-items: start;
}

.list-side {
  padding: 16px 6px 8px;
  position: sticky;
  top: 88px;
  max-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}

.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 0 12px 12px;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0 6px 8px;
  overflow-y: auto;
}

.list li {
  padding: 11px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}

.list li:hover {
  background: #f5f7fc;
}

.list li.on {
  background: var(--primary-soft);
}

.item-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.item-top strong {
  font-size: 13.5px;
  font-weight: 600;
}

.list li.on .item-top strong {
  color: var(--primary-dark);
}

.item-top span {
  font-size: 11.5px;
  color: var(--text-3);
}

.list li p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.editor {
  padding: 22px 24px 26px;
}

.editor-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
}

.editor-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
}

.editor-state {
  display: flex;
  align-items: center;
  gap: 10px;
}

.editor-body {
  padding-top: 18px;
}

.field {
  margin-bottom: 18px;
}

.field label {
  display: block;
  margin-bottom: 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-2);
}

@media (max-width: 940px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .list-side {
    position: static;
    max-height: 300px;
  }
}
</style>
