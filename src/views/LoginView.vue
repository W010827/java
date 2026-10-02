<script setup>
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { signIn, signUp } from '../cloud'
import Icon from '../components/Icon.vue'

const route = useRoute()
const router = useRouter()

const mode = ref('login') // login | register
const loading = ref(false)

const form = reactive({ email: '', password: '', confirm: '' })

const submitText = computed(() => (mode.value === 'register' ? '注册并登录' : '登录'))

const highlights = [
  { icon: 'software', title: '软件仓库', desc: '常用软件与安装包集中存放，随时取用' },
  { icon: 'report', title: '工作日报', desc: '每天一页，完成事项与明日计划' },
  { icon: 'log', title: '工作记录', desc: '随手记下过程、结论与待跟进' },
  { icon: 'todo', title: '任务待办', desc: '今天要做什么，打开就知道' }
]

function switchMode(next) {
  mode.value = next
  form.password = ''
  form.confirm = ''
}

function friendly(e) {
  const kind = e && e.kind
  if (kind === 'network') return '无法连接服务器，请确认后端服务是否正常'
  if (kind === 'unauthenticated') return '邮箱或密码不正确'
  if (kind === 'already-exists') return '该邮箱已注册，请直接登录'
  if (kind === 'permission-denied') return '没有权限执行该操作'
  if (kind === 'rate-limited') return '操作太频繁，请稍后再试'
  if (kind === 'backend-unavailable') return '服务器暂时不可用，请稍后重试'
  return (e && (e.message || e.error_description)) || '操作失败，请稍后重试'
}

function enter() {
  ElMessage.success('登录成功')
  const redirect = route.query.redirect
  const target =
    typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/dashboard'
  router.replace(target)
}

async function submit() {
  if (loading.value) return
  const email = form.email.trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    ElMessage.warning('请填写正确的邮箱地址')
    return
  }
  if (!form.password) {
    ElMessage.warning('请填写密码')
    return
  }
  if (form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (mode.value === 'register' && form.confirm !== form.password) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  loading.value = true
  try {
    if (mode.value === 'register') await signUp(email, form.password)
    else await signIn(email, form.password)
    enter()
  } catch (e) {
    ElMessage.error(friendly(e))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <section class="brand-side">
        <div class="brand-top">
          <div class="brand-mark">W</div>
          <span>Work Hub</span>
        </div>
        <h1>工作管理台</h1>
        <p class="brand-desc">软件、日报、记事、待办、资料，一个人的工作台。</p>
        <ul class="highlights">
          <li v-for="h in highlights" :key="h.title">
            <span class="hi-icon"><Icon :name="h.icon" :size="16" /></span>
            <span class="hi-text">
              <strong>{{ h.title }}</strong>
              <em>{{ h.desc }}</em>
            </span>
          </li>
        </ul>
      </section>

      <section class="form-side">
        <div class="tabs">
          <button type="button" :class="{ on: mode === 'login' }" @click="switchMode('login')">
            登录
          </button>
          <button type="button" :class="{ on: mode === 'register' }" @click="switchMode('register')">
            注册
          </button>
        </div>

        <h2 class="form-title">{{ mode === 'login' ? '欢迎回来' : '创建你的账号' }}</h2>
        <p class="form-sub">
          {{
            mode === 'login'
              ? '数据保存在单位内网服务器上，不经过公网。'
              : '首次使用请先注册一个账号，密码自己设置。'
          }}
        </p>

        <form @submit.prevent="submit">
          <div class="field">
            <label>邮箱</label>
            <el-input
              v-model="form.email"
              size="large"
              placeholder="you@example.com"
              autocomplete="username"
            />
          </div>

          <div class="field">
            <label>{{ mode === 'register' ? '设置密码' : '密码' }}</label>
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="至少 6 位"
              :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
            />
          </div>

          <div v-if="mode === 'register'" class="field">
            <label>确认密码</label>
            <el-input
              v-model="form.confirm"
              type="password"
              size="large"
              show-password
              placeholder="再输入一次"
              autocomplete="new-password"
            />
          </div>

          <el-button
            class="submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="loading"
          >
            {{ submitText }}
          </el-button>
        </form>

        <p class="tip">
          本系统部署在单位内网服务器上，账号、数据与上传的文件全部保存在服务器本机。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  background:
    radial-gradient(900px 460px at 12% 8%, #e3ecff 0%, rgba(227, 236, 255, 0) 60%),
    radial-gradient(760px 420px at 92% 92%, #e6f4ff 0%, rgba(230, 244, 255, 0) 62%),
    var(--bg);
}

.login-card {
  width: 100%;
  max-width: 960px;
  min-height: 580px;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 22px;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(20, 27, 45, 0.12);
}

/* ---- 左侧品牌区 ---- */
.brand-side {
  padding: 44px 42px;
  color: #fff;
  background: linear-gradient(155deg, #3b82f6 0%, #2563eb 42%, #1d3f9e 100%);
  display: flex;
  flex-direction: column;
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  letter-spacing: 1.6px;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.82);
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.34);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0;
}

.brand-side h1 {
  margin: 30px 0 10px;
  font-size: 29px;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand-desc {
  margin: 0 0 30px;
  font-size: 13.5px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.82);
}

.highlights {
  list-style: none;
  margin: auto 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.highlights li {
  display: flex;
  align-items: flex-start;
  gap: 11px;
}

.hi-icon {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.16);
  display: flex;
  align-items: center;
  justify-content: center;
}

.hi-text {
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}

.hi-text strong {
  font-size: 13.5px;
  font-weight: 600;
}

.hi-text em {
  font-style: normal;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.72);
}

/* ---- 右侧表单区 ---- */
.form-side {
  padding: 38px 40px 30px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tabs {
  display: inline-flex;
  padding: 3px;
  background: #f2f4f9;
  border-radius: 10px;
  width: fit-content;
}

.tabs button {
  border: 0;
  background: transparent;
  padding: 6px 20px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.tabs button.on {
  background: #fff;
  color: var(--primary-dark);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.form-title {
  margin: 24px 0 6px;
  font-size: 20px;
  font-weight: 650;
}

.form-sub {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--text-3);
  line-height: 1.6;
}

.field {
  margin-bottom: 15px;
}

.field label {
  display: block;
  margin-bottom: 7px;
  font-size: 13px;
  color: var(--text-2);
  font-weight: 500;
}

.submit {
  width: 100%;
  margin-top: 6px;
  border-radius: 10px;
  letter-spacing: 1px;
}

.tip {
  margin: 18px 0 0;
  padding: 10px 12px;
  border-radius: 9px;
  background: #eef4ff;
  border: 1px solid #d3e2fb;
  color: #2c4f8a;
  font-size: 12.5px;
  line-height: 1.6;
}

@media (max-width: 880px) {
  .login-card {
    grid-template-columns: 1fr;
    max-width: 460px;
    min-height: 0;
  }
  .brand-side {
    padding: 30px 30px 26px;
  }
  .brand-side h1 {
    margin: 18px 0 8px;
    font-size: 23px;
  }
  .brand-desc {
    margin-bottom: 0;
  }
  .highlights {
    display: none;
  }
  .form-side {
    padding: 28px 30px 26px;
  }
}
</style>
