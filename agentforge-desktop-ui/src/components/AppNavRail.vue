<template>
  <aside class="nav-rail">
    <div class="nav-brand" title="AgentForge Desktop">
      <span class="nav-logo">AF</span>
    </div>
    <nav class="nav-items">
      <button class="nav-btn" :class="{ active: isActive('/workbench') }" title="工作台" @click="go('/workbench')">
        <i class="el-icon-chat-dot-round"></i>
        <span>工作台</span>
      </button>
      <button class="nav-btn" :class="{ active: isActive('/models') }" title="模型配置" @click="go('/models')">
        <i class="el-icon-cpu"></i>
        <span>模型</span>
      </button>
      <button class="nav-btn" :class="{ active: isActive('/agents') }" title="Agent 配置" @click="go('/agents')">
        <i class="el-icon-s-operation"></i>
        <span>Agent</span>
      </button>
    </nav>
    <div class="nav-status" :class="{ ok: connected }" :title="statusText">
      <span class="dot"></span>
    </div>
  </aside>
</template>

<script>
import { request } from '../api/client'

export default {
  name: 'AppNavRail',
  data: () => ({
    connected: false,
    runtime: '',
    workspace: ''
  }),
  computed: {
    statusText() {
      if (!this.connected) return '后端未连接'
      return `已连接 · ${this.runtime || 'java'}\n${this.workspace || ''}`
    }
  },
  created() {
    this.check()
    setInterval(this.check, 10000)
  },
  methods: {
    isActive(prefix) {
      return this.$route.path === prefix || this.$route.path.startsWith(prefix + '/')
    },
    go(path) {
      if (this.$route.path !== path) this.$router.push(path).catch(() => {})
    },
    async check() {
      try {
        const data = await request('/api/local/health')
        this.connected = Boolean(data && data.ready)
        this.workspace = data.workspace || ''
        if (window.electronAPI && window.electronAPI.getLocalServerInfo) {
          const info = await window.electronAPI.getLocalServerInfo()
          this.runtime = info.runtime || ''
        }
      } catch (_error) {
        this.connected = false
      }
    }
  }
}
</script>
