<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getSession, signOut } from '../cloud'
import Icon from './Icon.vue'
import UploadTray from './UploadTray.vue'

const route = useRoute()
const router = useRouter()

const menus = [
  { name: 'dashboard', label: '数据看板', icon: 'dashboard' },
  { name: 'software', label: '软件仓库', icon: 'software' },
  { name: 'reports', label: '工作日报', icon: 'report' },
  { name: 'logs', label: '工作记录', icon: 'log' },
  { name: 'todos', label: '任务待办', icon: 'todo' },
  { name: 'documents', label: '文件资料库', icon: 'document' }
]

const account = ref('')
const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
})

const initial = computed(() => (account.value ? account.value[0].toUpperCase() : 'U'))
const shortAccount = computed(() => {
  const a = account.value
  if (!a) return '我的账号'
  return a.length > 22 ? `${a.slice(0, 20)}…` : a
})

onMounted(async () => {
  const session = await getSession()
  account.value = session?.user?.email || session?.user?.id || ''
})

async function onLogout() {
  await signOut()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">W</div>
        <div class="brand-text">
          <strong>工作管理台</strong>
          <span>Work Hub</span>
        </div>
      </div>

      <nav class="nav">
        <router-link
          v-for="m in menus"
          :key="m.name"
          :to="{ name: m.name }"
          class="nav-item"
          active-class="active"
        >
          <Icon :name="m.icon" :size="18" />
          <span>{{ m.label }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="user">
          <div class="avatar">{{ initial }}</div>
          <div class="user-meta">
            <strong :title="account">{{ shortAccount }}</strong>
            <span>已登录</span>
          </div>
        </div>
        <button class="logout" type="button" @click="onLogout">
          <Icon name="logout" :size="15" />
          <span>退出</span>
        </button>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="topbar-title">
          <h1>{{ route.meta.title }}</h1>
          <p>{{ route.meta.subtitle }}</p>
        </div>
        <div class="today">
          <Icon name="calendar" :size="15" />
          <span>{{ today }}</span>
        </div>
      </header>

      <div class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </div>
    </main>

    <!-- 后台上传进度：放在布局层，切换菜单不会中断，也不会被页面卸载带走 -->
    <UploadTray />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: 232px;
  flex: 0 0 232px;
  background: #fff;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 20px 20px 18px;
}

.brand-mark {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: linear-gradient(135deg, #3b82f6, #1d4ed8);
  color: #fff;
  font-weight: 700;
  font-size: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.28);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 15px;
  letter-spacing: 0.3px;
}

.brand-text span {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: 1.2px;
  text-transform: uppercase;
}

.nav {
  flex: 1;
  padding: 6px 12px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 42px;
  padding: 0 13px;
  border-radius: 10px;
  color: var(--text-2);
  font-size: 14px;
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: #f5f7fc;
  color: var(--text);
}

.nav-item.active {
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-weight: 600;
}

.side-foot {
  padding: 12px;
  border-top: 1px solid var(--border);
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 10px;
}

.avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 50%;
  background: #e8eefc;
  color: var(--primary-dark);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.user-meta strong {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-meta span {
  font-size: 11.5px;
  color: var(--text-3);
}

.logout {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: #fff;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.logout:hover {
  border-color: #f0c4c4;
  color: var(--danger);
  background: #fffafa;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 30px 18px;
  background: var(--bg);
  position: sticky;
  top: 0;
  z-index: 5;
}

.topbar-title h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 650;
  letter-spacing: 0.3px;
}

.topbar-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-3);
}

.today {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 13px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid var(--border);
  color: var(--text-2);
  font-size: 12.5px;
  white-space: nowrap;
}

.content {
  flex: 1;
  padding: 0 30px 34px;
}

@media (max-width: 900px) {
  .sidebar {
    width: 68px;
    flex: 0 0 68px;
  }
  .brand-text,
  .nav-item span,
  .user-meta,
  .logout span {
    display: none;
  }
  .brand,
  .nav-item,
  .user,
  .logout {
    justify-content: center;
  }
  .topbar,
  .content {
    padding-left: 16px;
    padding-right: 16px;
  }
}
</style>
