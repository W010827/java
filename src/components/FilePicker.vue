<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { formatSize } from '../utils'
import Icon from './Icon.vue'

defineProps({
  modelValue: { type: Object, default: null },
  hint: { type: String, default: '选择文件' }
})
const emit = defineEmits(['update:modelValue'])

const inputRef = ref(null)
const dragging = ref(false)

// 拖拽经过子元素时会连续触发 dragenter/dragleave，用计数避免高亮闪烁
let dragDepth = 0

function pick() {
  inputRef.value?.click()
}

function onChange(e) {
  const file = e.target.files && e.target.files[0]
  emit('update:modelValue', file || null)
  // 清空 input，保证同一个文件再次选择也能触发 change
  e.target.value = ''
}

// 只响应「文件」的拖拽，拖文本/链接进来时不干扰
function hasFiles(e) {
  const types = e.dataTransfer && e.dataTransfer.types
  return !!types && Array.from(types).includes('Files')
}

function onDragEnter(e) {
  if (!hasFiles(e)) return
  dragDepth++
  dragging.value = true
}

function onDragOver(e) {
  if (!hasFiles(e)) return
  // 配合 .prevent：光标显示为「复制」，松开即接收
  e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave() {
  // 这里不再判断 hasFiles：部分浏览器 dragleave 时 types 已清空，判断会导致高亮卡住
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dragging.value = false
}

function onDrop(e) {
  resetDrag()
  const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]
  if (file) emit('update:modelValue', file)
}

function resetDrag() {
  dragDepth = 0
  dragging.value = false
}

// 文件拖到页面空白处时，浏览器默认会直接打开它 —— 全局拦掉
function blockWindowDrop(e) {
  e.preventDefault()
  if (e.type === 'drop') resetDrag()
}

onMounted(() => {
  window.addEventListener('dragover', blockWindowDrop)
  window.addEventListener('drop', blockWindowDrop)
})

onUnmounted(() => {
  window.removeEventListener('dragover', blockWindowDrop)
  window.removeEventListener('drop', blockWindowDrop)
})
</script>

<template>
  <div
    class="picker"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <input ref="inputRef" type="file" class="native" @change="onChange" />

    <div v-if="!modelValue" class="drop" :class="{ 'is-dragging': dragging }" @click="pick">
      <Icon name="upload" :size="20" />
      <span>{{ dragging ? '松开即可添加文件' : hint }}</span>
      <em>{{ dragging ? '文件将作为本次上传的内容' : '点击选取，或把文件拖到这里' }}</em>
    </div>

    <div v-else class="picked" :class="{ 'is-dragging': dragging }">
      <div class="file-icon"><Icon name="document" :size="18" /></div>
      <div class="file-meta">
        <strong :title="modelValue.name">{{ modelValue.name }}</strong>
        <span>{{ dragging ? '松开即可替换为拖入的文件' : formatSize(modelValue.size) }}</span>
      </div>
      <div class="file-actions">
        <el-button link type="primary" @click="pick">换一个</el-button>
        <el-button link type="danger" @click="emit('update:modelValue', null)">移除</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.native {
  display: none;
}

.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 104px;
  border: 1.5px dashed var(--border-strong);
  border-radius: 12px;
  background: #fafbfe;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.drop:hover {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.drop span {
  font-size: 13.5px;
  font-weight: 500;
}

.drop em {
  font-style: normal;
  font-size: 12px;
  color: var(--text-3);
}

/* 拖拽悬停态：实线边框 + 高亮，明确「松手即上传」 */
.drop.is-dragging {
  border-style: solid;
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary-dark);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.drop.is-dragging em {
  color: var(--primary-dark);
  opacity: 0.75;
}

.picked {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fafbfe;
  transition: all 0.15s;
}

.picked.is-dragging {
  border-color: var(--primary);
  background: var(--primary-soft);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.file-icon {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 9px;
  background: var(--primary-soft);
  color: var(--primary-dark);
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.file-meta strong {
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-meta span {
  font-size: 12px;
  color: var(--text-3);
}

.picked.is-dragging .file-meta span {
  color: var(--primary-dark);
  font-weight: 500;
}

.file-actions {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}
</style>
