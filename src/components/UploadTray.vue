<script setup>
import { computed } from 'vue'
import {
  tasks,
  collapsed,
  busyCount,
  finishedCount,
  toggleCollapsed,
  cancelTask,
  retryTask,
  dismissTask,
  clearFinished
} from '../uploads'
import { formatSize, formatSpeed, formatDuration } from '../utils'
import Icon from './Icon.vue'

const visible = computed(() => tasks.value.length > 0)

/** 已完成的任务数，用来决定要不要显示「清除已完成」 */
const hasFinished = computed(() => finishedCount.value > 0)

function onToggle() {
  toggleCollapsed()
}

function statusText(task) {
  if (task.status === 'uploading') return `${task.percent}%`
  if (task.status === 'saving') return '正在保存…'
  if (task.status === 'done') return '已完成'
  if (task.status === 'cancelled') return '已取消'
  return '失败'
}

/** 剩余时间：拿「还没传的字节数 ÷ 当前速度」估一个 */
function remainingText(task) {
  if (task.status !== 'uploading' || task.speed <= 0) return ''
  const left = task.size - task.loaded
  if (left <= 0) return ''
  return `剩余 ${formatDuration(left / task.speed)}`
}

function metaText(task) {
  if (task.status === 'uploading') {
    const parts = [`${formatSize(task.loaded)} / ${formatSize(task.size)}`]
    const speed = formatSpeed(task.speed)
    if (speed) parts.push(speed)
    const rest = remainingText(task)
    if (rest) parts.push(rest)
    return parts.join(' · ')
  }
  if (task.status === 'done') return formatSize(task.size)
  if (task.status === 'error') return task.error
  return `${formatSize(task.size)}`
}

function barStatus(task) {
  if (task.status === 'done') return 'success'
  if (task.status === 'error' || task.status === 'cancelled') return 'exception'
  return ''
}

function percentOf(task) {
  if (task.status === 'done') return 100
  return task.percent
}
</script>

<template>
  <div v-if="visible" class="tray" :class="{ collapsed }">
    <header class="tray-head" @click="onToggle">
      <span class="tray-badge" :class="{ busy: busyCount > 0 }">
        <Icon :name="busyCount > 0 ? 'upload' : 'check'" :size="13" />
      </span>
      <div class="tray-title">
        <strong v-if="busyCount > 0">正在上传 {{ busyCount }} 个文件</strong>
        <strong v-else>上传已完成</strong>
        <span v-if="busyCount > 0">切页面不受影响，可以继续做其他事</span>
        <span v-else>共 {{ tasks.length }} 条记录</span>
      </div>
      <button class="tray-icon-btn" type="button" :title="collapsed ? '展开' : '收起'" @click.stop="onToggle">
        <Icon name="chevron" :size="15" />
      </button>
    </header>

    <div v-show="!collapsed" class="tray-body">
      <ul class="tray-list">
        <li v-for="task in tasks" :key="task.id" class="tray-item" :class="task.status">
          <div class="item-top">
            <span class="item-name" :title="task.fileName">{{ task.label || task.fileName }}</span>
            <span
              class="item-status"
              :class="task.status"
            >{{ statusText(task) }}</span>
          </div>

          <el-progress
            :percentage="percentOf(task)"
            :stroke-width="5"
            :show-text="false"
            :status="barStatus(task)"
          />

          <div class="item-foot">
            <span class="item-meta" :class="{ error: task.status === 'error' }">{{ metaText(task) }}</span>
            <div class="item-actions">
              <el-button
                v-if="task.status === 'uploading'"
                link
                size="small"
                @click="cancelTask(task.id)"
              >
                取消
              </el-button>
              <el-button
                v-else-if="task.status === 'error' || task.status === 'cancelled'"
                link
                type="primary"
                size="small"
                @click="retryTask(task.id)"
              >
                重试
              </el-button>
              <el-button
                v-if="task.status !== 'uploading' && task.status !== 'saving'"
                link
                size="small"
                @click="dismissTask(task.id)"
              >
                关闭
              </el-button>
            </div>
          </div>
        </li>
      </ul>

      <div v-if="hasFinished" class="tray-foot">
        <el-button link size="small" @click="clearFinished">清除已完成</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tray {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1500;
  width: 366px;
  max-width: calc(100vw - 32px);
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.16);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.tray-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 12px 12px 14px;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid transparent;
}

.tray.collapsed .tray-head {
  border-bottom-color: transparent;
}

.tray:not(.collapsed) .tray-head {
  border-bottom-color: var(--border);
}

.tray-badge {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e9f8f0;
  color: #12a150;
}

.tray-badge.busy {
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.tray-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.tray-title strong {
  font-size: 13.5px;
  font-weight: 600;
}

.tray-title span {
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tray-icon-btn {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s, transform 0.2s;
}

.tray-icon-btn:hover {
  background: #f2f4f9;
  color: var(--text);
}

.tray.collapsed .tray-icon-btn {
  transform: rotate(180deg);
}

.tray-body {
  display: flex;
  flex-direction: column;
  max-height: 46vh;
  overflow: hidden;
}

.tray-list {
  margin: 0;
  padding: 4px 0;
  list-style: none;
  overflow-y: auto;
}

.tray-item {
  padding: 10px 14px;
  border-bottom: 1px solid #f4f6fa;
}

.tray-item:last-child {
  border-bottom: none;
}

.item-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 7px;
}

.item-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 550;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-status {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.item-status.done {
  color: #12a150;
}

.item-status.error {
  color: var(--danger);
}

.item-status.cancelled {
  color: var(--text-3);
}

.item-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  min-height: 20px;
}

.item-meta {
  flex: 1;
  min-width: 0;
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-meta.error {
  color: var(--danger);
}

.item-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 2px;
}

.item-actions :deep(.el-button + .el-button) {
  margin-left: 2px;
}

.tray-foot {
  padding: 6px 12px 10px;
  border-top: 1px solid #f4f6fa;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 560px) {
  .tray {
    right: 12px;
    left: 12px;
    bottom: 12px;
    width: auto;
  }
}
</style>
