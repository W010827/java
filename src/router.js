import { createRouter, createWebHashHistory } from 'vue-router'
import { getSession } from './cloud'
import AppLayout from './components/AppLayout.vue'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('./views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('./views/DashboardView.vue'),
        meta: { title: '数据看板', subtitle: '整体情况一目了然' }
      },
      {
        path: 'software',
        name: 'software',
        component: () => import('./views/SoftwareView.vue'),
        meta: { title: '软件仓库', subtitle: '上传常用软件，随时下载安装' }
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('./views/ReportsView.vue'),
        meta: { title: '工作日报', subtitle: '每天一页，记录做了什么' }
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('./views/LogsView.vue'),
        meta: { title: '工作记录', subtitle: '随手记下过程与结论' }
      },
      {
        path: 'todos',
        name: 'todos',
        component: () => import('./views/TodosView.vue'),
        meta: { title: '任务待办', subtitle: '今天要做什么，先看这里' }
      },
      {
        path: 'documents',
        name: 'documents',
        component: () => import('./views/DocumentsView.vue'),
        meta: { title: '文件资料库', subtitle: '文档资料归档与取用' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  // 采用 hash 路由，静态托管刷新任意页面都不会 404
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const session = await getSession()
  if (!session) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

export default router
