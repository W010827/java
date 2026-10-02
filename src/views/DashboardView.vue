<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { softwareApi, reportApi, todoApi, docApi, logApi } from '../api'
import { todayStr, toDateStr, formatDate, friendlyDay, errorText } from '../utils'
import Icon from '../components/Icon.vue'

echarts.use([BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const loading = ref(true)
const software = ref([])
const reports = ref([])
const todos = ref([])
const docs = ref([])
const logs = ref([])

const reportChartRef = ref(null)
const todoChartRef = ref(null)
const swChartRef = ref(null)
let reportChart = null
let todoChart = null
let swChart = null

const AXIS_COLOR = '#939bad'
const GRID_COLOR = '#eef1f7'

const monthPrefix = todayStr().slice(0, 7)

const stats = computed(() => {
  const totalDownloads = software.value.reduce((s, r) => s + (Number(r.download_count) || 0), 0)
  const monthReports = reports.value.filter((r) =>
    formatDate(r.report_date).startsWith(monthPrefix)
  ).length
  const pending = todos.value.filter((t) => t.status !== 'done')
  const overdue = pending.filter((t) => t.due_date && formatDate(t.due_date) < todayStr()).length
  return {
    softwareCount: software.value.length,
    totalDownloads,
    monthReports,
    streak: currentStreak(),
    pending: pending.length,
    overdue,
    docsCount: docs.value.length
  }
})

function currentStreak() {
  const set = new Set(reports.value.map((r) => formatDate(r.report_date)))
  const base = new Date()
  let count = 0
  for (let i = 0; i < 400; i++) {
    const day = toDateStr(new Date(base.getFullYear(), base.getMonth(), base.getDate() - i))
    if (set.has(day)) count += 1
    else if (i === 0) continue // 今天还没写不算断，继续往前看
    else break
  }
  return count
}

function lastDays(n) {
  const out = []
  const base = new Date()
  for (let i = n - 1; i >= 0; i--) {
    out.push(toDateStr(new Date(base.getFullYear(), base.getMonth(), base.getDate() - i)))
  }
  return out
}

const activities = computed(() => {
  const items = []
  for (const r of reports.value.slice(0, 4)) {
    items.push({
      icon: 'report',
      tag: '日报',
      title: `${friendlyDay(r.report_date)}的日报`,
      desc: String(r.done_today || r.plan_tomorrow || '').split('\n')[0].slice(0, 46) || '（无内容）',
      time: r.updated_at || r.created_at
    })
  }
  for (const l of logs.value.slice(0, 4)) {
    items.push({
      icon: 'log',
      tag: '记录',
      title: l.title,
      desc: String(l.content || '').split('\n')[0].slice(0, 46) || '（无正文）',
      time: l.created_at
    })
  }
  return items.sort((a, b) => new Date(b.time) - new Date(a.time)).slice(0, 6)
})

async function loadAll() {
  loading.value = true
  try {
    const [sw, rp, td, dc, lg] = await Promise.all([
      softwareApi.list(),
      reportApi.list(),
      todoApi.list(),
      docApi.list(),
      logApi.list({ limit: 6 })
    ])
    software.value = sw
    reports.value = rp
    todos.value = td
    docs.value = dc
    logs.value = lg
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function renderReportChart() {
  if (!reportChartRef.value) return
  if (!reportChart) reportChart = echarts.init(reportChartRef.value)

  const days = lastDays(14)
  const submitted = new Set(reports.value.map((r) => formatDate(r.report_date)))

  reportChart.setOption({
    grid: { left: 10, right: 10, top: 26, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#fff',
      borderColor: '#e6e9f0',
      textStyle: { color: '#1c2333', fontSize: 12 },
      formatter: (params) => {
        const p = params[0]
        return `${p.axisValue}<br/>${p.value ? '已提交日报' : '未提交'}`
      }
    },
    xAxis: {
      type: 'category',
      data: days.map((d) => d.slice(5)),
      axisLine: { lineStyle: { color: GRID_COLOR } },
      axisTick: { show: false },
      axisLabel: { color: AXIS_COLOR, fontSize: 11 }
    },
    yAxis: { type: 'value', max: 1.2, show: false },
    series: [
      {
        type: 'bar',
        barWidth: '46%',
        data: days.map((d) => (submitted.has(d) ? 1 : 0)),
        itemStyle: {
          borderRadius: [5, 5, 0, 0],
          color: (params) => (params.value ? '#2563eb' : '#e6eaf3')
        }
      }
    ]
  })
}

function renderTodoChart() {
  if (!todoChartRef.value) return
  if (!todoChart) todoChart = echarts.init(todoChartRef.value)

  const pending = todos.value.filter((t) => t.status !== 'done')
  const overdue = pending.filter((t) => t.due_date && formatDate(t.due_date) < todayStr()).length
  const done = todos.value.filter((t) => t.status === 'done').length
  const normal = pending.length - overdue

  const data = [
    { value: done, name: '已完成', itemStyle: { color: '#12a150' } },
    { value: normal, name: '进行中', itemStyle: { color: '#2563eb' } },
    { value: overdue, name: '已逾期', itemStyle: { color: '#e5484d' } }
  ].filter((d) => d.value > 0)

  todoChart.setOption({
    tooltip: {
      trigger: 'item',
      backgroundColor: '#fff',
      borderColor: '#e6e9f0',
      textStyle: { color: '#1c2333', fontSize: 12 },
      formatter: '{b}：{c} 项'
    },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#5a6478', fontSize: 12 }
    },
    series: [
      {
        type: 'pie',
        radius: ['52%', '72%'],
        center: ['50%', '43%'],
        itemStyle: { borderColor: '#fff', borderWidth: 3 },
        label: { show: false },
        emphasis: { scale: true, scaleSize: 4 },
        data: data.length ? data : [{ value: 1, name: '暂无任务', itemStyle: { color: '#e6eaf3' } }]
      }
    ]
  })
}

function renderSoftwareChart() {
  if (!swChartRef.value) return
  if (!swChart) swChart = echarts.init(swChartRef.value)

  const top = [...software.value]
    .sort((a, b) => (Number(b.download_count) || 0) - (Number(a.download_count) || 0))
    .slice(0, 5)
    .reverse()

  swChart.setOption({
    grid: { left: 6, right: 34, top: 8, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#fff',
      borderColor: '#e6e9f0',
      textStyle: { color: '#1c2333', fontSize: 12 },
      formatter: (params) => `${params[0].name}<br/>下载 ${params[0].value} 次`
    },
    xAxis: { type: 'value', show: false },
    yAxis: {
      type: 'category',
      data: top.map((r) => (r.name || '').slice(0, 12)),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#5a6478', fontSize: 12 }
    },
    series: [
      {
        type: 'bar',
        barWidth: 13,
        data: top.map((r) => Number(r.download_count) || 0),
        itemStyle: { color: '#2563eb', borderRadius: [0, 7, 7, 0] },
        label: { show: true, position: 'right', color: AXIS_COLOR, fontSize: 11 }
      }
    ]
  })
}

function renderAll() {
  renderReportChart()
  renderTodoChart()
  renderSoftwareChart()
}

function onResize() {
  reportChart?.resize()
  todoChart?.resize()
  swChart?.resize()
}

onMounted(async () => {
  await loadAll()
  await nextTick()
  renderAll()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  reportChart?.dispose()
  todoChart?.dispose()
  swChart?.dispose()
})
</script>

<template>
  <div v-loading="loading">
    <section class="stats">
      <article class="card stat">
        <div class="stat-icon blue"><Icon name="software" :size="18" /></div>
        <div class="stat-body">
          <span>软件仓库</span>
          <strong>{{ stats.softwareCount }}</strong>
          <em>累计下载 {{ stats.totalDownloads }} 次</em>
        </div>
      </article>

      <article class="card stat">
        <div class="stat-icon green"><Icon name="report" :size="18" /></div>
        <div class="stat-body">
          <span>本月日报</span>
          <strong>{{ stats.monthReports }}</strong>
          <em>连续记录 {{ stats.streak }} 天</em>
        </div>
      </article>

      <article class="card stat" :class="{ warn: stats.overdue > 0 }">
        <div class="stat-icon orange"><Icon name="todo" :size="18" /></div>
        <div class="stat-body">
          <span>待办进行中</span>
          <strong>{{ stats.pending }}</strong>
          <em>{{ stats.overdue > 0 ? `${stats.overdue} 项已逾期` : '暂无逾期' }}</em>
        </div>
      </article>

      <article class="card stat">
        <div class="stat-icon purple"><Icon name="document" :size="18" /></div>
        <div class="stat-body">
          <span>文件资料</span>
          <strong>{{ stats.docsCount }}</strong>
          <em>已归档资料份数</em>
        </div>
      </article>
    </section>

    <section class="card chart-card wide">
      <header class="chart-head">
        <span class="section-title">最近 14 天日报提交情况</span>
        <span class="muted">共 {{ reports.length }} 篇日报</span>
      </header>
      <div ref="reportChartRef" class="chart" style="height: 208px"></div>
    </section>

    <section class="chart-row">
      <div class="card chart-card">
        <header class="chart-head">
          <span class="section-title">任务完成情况</span>
        </header>
        <div ref="todoChartRef" class="chart" style="height: 236px"></div>
      </div>

      <div class="card chart-card">
        <header class="chart-head">
          <span class="section-title">软件下载排行</span>
        </header>
        <div v-if="!software.length" class="empty" style="height: 236px; padding-top: 74px">
          <div class="empty-title">还没有软件</div>
          <div>去软件仓库上传第一个安装包。</div>
        </div>
        <div v-else ref="swChartRef" class="chart" style="height: 236px"></div>
      </div>
    </section>

    <section class="card chart-card">
      <header class="chart-head">
        <span class="section-title">最近动态</span>
      </header>

      <div v-if="!activities.length" class="empty">
        <div class="empty-title">还没有动态</div>
        <div>写过日报或工作记录后，这里会显示最近的动作。</div>
      </div>

      <ul v-else class="feed">
        <li v-for="(a, i) in activities" :key="i">
          <span class="feed-icon"><Icon :name="a.icon" :size="15" /></span>
          <div class="feed-main">
            <div class="feed-title">
              <span class="pill gray">{{ a.tag }}</span>
              <strong>{{ a.title }}</strong>
            </div>
            <p>{{ a.desc }}</p>
          </div>
        </li>
      </ul>
    </section>
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
  padding: 17px 18px;
  display: flex;
  align-items: flex-start;
  gap: 13px;
}

.stat-icon {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-icon.blue {
  background: #e8f0ff;
  color: #2563eb;
}
.stat-icon.green {
  background: #e9f8f0;
  color: #12a150;
}
.stat-icon.orange {
  background: #fff2e5;
  color: #e08700;
}
.stat-icon.purple {
  background: #f3ebff;
  color: #7c3aed;
}

.stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.stat-body > span {
  font-size: 12.5px;
  color: var(--text-3);
}

.stat-body strong {
  font-size: 25px;
  font-weight: 650;
  letter-spacing: 0.5px;
  margin: 3px 0 2px;
}

.stat-body em {
  font-style: normal;
  font-size: 12px;
  color: var(--text-3);
}

.stat.warn .stat-body em {
  color: var(--danger);
}

.chart-card {
  padding: 18px 20px 14px;
  margin-bottom: 16px;
}

.chart-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.chart {
  width: 100%;
}

.chart-row {
  display: grid;
  grid-template-columns: 1fr 1.25fr;
  gap: 16px;
}

.feed {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
}

.feed li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 4px;
  border-bottom: 1px solid #f2f4f9;
}

.feed li:last-child {
  border-bottom: 0;
}

.feed-icon {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  background: #f2f5fb;
  color: var(--text-2);
  display: flex;
  align-items: center;
  justify-content: center;
}

.feed-main {
  min-width: 0;
  flex: 1;
}

.feed-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.feed-title strong {
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feed-main p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (max-width: 1000px) {
  .stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
