import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 线上部署时由平台注入 PORT，本地开发用 5180
const port = Number(process.env.PORT) || 5180

// 本地开发时接口转发目标。
//   默认 http://127.0.0.1:8098 —— 本地后端（WORKHUB_BASE=.devdata 启动的那份）
//   也可指定内网服务器，用真实数据调样式：
//     WORKHUB_API=http://<服务器地址>:8099 npm run dev
// 生产环境前端与后端同源，不走这里。
const apiTarget = process.env.WORKHUB_API || 'http://127.0.0.1:8098'

const hostOptions = {
  host: '0.0.0.0',
  port,
  // 允许通过反向代理域名访问（Vite 5.4.12+ 的 host 校验）
  allowedHosts: true
}

// 前端所有请求都是 /api/ 相对路径，转发这一条即可
// （下载走 /api/share 拿签名地址，同样在 /api 下，不需要额外规则）
const proxy = {
  '/api': {
    target: apiTarget,
    changeOrigin: true
  }
}

export default defineConfig({
  plugins: [vue()],
  // 部署在服务器根路径下，静态资源用绝对路径更稳
  base: '/',
  server: { ...hostOptions, proxy },
  preview: { ...hostOptions, proxy }
})
