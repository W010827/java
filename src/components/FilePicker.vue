<script setup>
import { ref } from 'vue'
import { formatSize } from '../utils'
import Icon from './Icon.vue'

defineProps({
  modelValue: { type: Object, default: null },
  hint: { type: String, default: '选择文件' }
})
const emit = defineEmits(['update:modelValue'])

const inputRef = ref(null)

function pick() {
  inputRef.value?.click()
}

function onChange(e) {
  const file = e.target.files && e.target.files[0]
  emit('update:modelValue', file || null)
  // 清空 input，保证同一个文件再次选择也能触发 change
  e.target.value = ''
}
</script>

<template>
  <div class="picker">
    <input ref="inputRef" type="file" class="native" @change="onChange" />

    <div v-if="!modelValue" class="drop" @click="pick">
      <Icon name="upload" :size="20" />
      <span>{{ hint }}</span>
      <em>点击此处从本机选取</em>
    </div>

    <div v-else class="picked">
      <div class="file-icon"><Icon name="document" :size="18" /></div>
      <div class="file-meta">
        <strong :title="modelValue.name">{{ modelValue.name }}</strong>
        <span>{{ formatSize(modelValue.size) }}</span>
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

.picked {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fafbfe;
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

.file-actions {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}
</style>
