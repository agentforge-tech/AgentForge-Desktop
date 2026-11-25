<template>
  <el-dialog
    title="设置"
    :visible="visible"
    width="900px"
    top="6vh"
    :append-to-body="true"
    :close-on-click-modal="false"
    custom-class="workbench-settings-dialog"
    @close="$emit('close')"
  >
    <div class="settings-layout" v-loading="loading">
      <nav class="settings-nav" aria-label="工作台设置选项">
        <div class="settings-nav-label">配置</div>
        <button type="button" :class="{ active: tab === 'models' }" @click="tab = 'models'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2.5"/><path d="M9 2.8v2.4M15 2.8v2.4M9 18.8v2.4M15 18.8v2.4M2.8 9h2.4M2.8 15h2.4M18.8 9h2.4M18.8 15h2.4"/><path d="M9 10h2M13 10h2M9 14h2M13 14h2"/></svg>
          <span>模型配置</span>
        </button>
        <button type="button" :class="{ active: tab === 'agent' }" @click="tab = 'agent'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="7" width="14" height="12" rx="3"/><path d="M12 7V4M9 4h6M8.5 12h.01M15.5 12h.01M9 16h6M3 11v4M21 11v4"/></svg>
          <span>默认 Agent 配置</span>
        </button>
        <button type="button" :class="{ active: tab === 'system' }" @click="tab = 'system'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2"/><circle cx="15" cy="12" r="2"/><circle cx="10" cy="17" r="2"/></svg>
          <span>系统配置</span>
        </button>
      <button type="button" :class="{ active: tab === 'enhance' }" @click="tab = 'enhance'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.1 4.6 4.9.7-3.5 3.5.8 4.9-4.3-2.3-4.3 2.3.8-4.9L5 8.8l4.9-.7z"/></svg>
          <span>能力增强</span>
        </button>
      </nav>

      <div class="settings-content">
        <section v-show="tab === 'agent'" class="settings-section">
          <header class="settings-section-head"><b>默认 Agent 配置</b><span>管理当前默认 Agent 的运行参数</span></header>
          <el-form label-position="top" size="small" class="agent-config-form">
            <el-form-item label="当前 Agent" class="form-span-2">
              <el-select :value="aid" placeholder="选择 Agent" style="width: 100%" @change="$emit('select-agent', $event)">
                <el-option v-for="agent in agents" :key="agent.aid" :label="agent.name" :value="agent.aid" />
              </el-select>
            </el-form-item>
            <el-form-item label="名称"><el-input v-model="agentForm.name" placeholder="Agent 名称" /></el-form-item>
            <el-form-item label="记忆最大消息数"><el-input-number v-model="agentForm.chatMemoryMaxMessages" :min="1" :max="100000" /></el-form-item>
            <el-form-item label="描述" class="form-span-2"><el-input v-model="agentForm.description" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="可用模型">
              <el-select v-model="agentForm.modelIds" multiple placeholder="选择可用模型" style="width: 100%">
                <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="默认模型">
              <el-select v-model="agentForm.defaultModelId" placeholder="选择默认模型" style="width: 100%">
                <el-option v-for="model in selectableModels" :key="model.id" :label="model.name" :value="model.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="内置系统提示词" class="form-span-2">
              <el-input :value="agentForm.builtInSystemPrompt" type="textarea" :rows="12" readonly resize="vertical" />
              <div class="built-in-prompt-hint">由 Desktop 基础规则、当前本机环境和 Harness Runtime 工作区约束自动组成。</div>
            </el-form-item>
            <el-alert v-if="initError" class="form-span-2" :title="initError" type="warning" :closable="false" show-icon />
          </el-form>
          <div class="agent-config-actions">
            <el-button type="primary" :loading="saving" :disabled="!aid" @click="saveAgent">保存</el-button>
          </div>
        </section>

        <section v-show="tab === 'models'" class="settings-section">
          <header class="settings-section-head"><b>模型设置</b><span>管理模型连接与基础配置</span></header>
          <div class="drawer-model-list">
            <div v-for="model in models" :key="model.id" class="drawer-model-item">
              <div class="drawer-model-main">
                <b>{{ model.name }}</b>
                <span>{{ providerName(model.provider) }} · 端口 {{ resolveModelPort(model.url) }} · {{ model.modelName }}</span>
                <span class="drawer-model-url" :title="model.url">渠道 URL：{{ model.url || '未配置' }}</span>
              </div>
              <div class="drawer-model-actions">
                <el-button type="text" @click="editModel(model)">编辑</el-button>
                <el-button type="text" @click="copyModel(model)">复制</el-button>
                <el-button type="text" @click="testModel(model)">测试</el-button>
              </div>
            </div>
            <el-button class="add-model-btn" icon="el-icon-plus" @click="newModel">新增模型</el-button>
          </div>
        </section>

        <section v-show="tab === 'system'" class="settings-section">
          <header class="settings-section-head"><b>系统配置</b><span>管理桌面端系统级运行参数</span></header>
          <div class="system-setting-row">
            <div class="system-setting-copy">
              <b>交互模式支持切换</b>
              <span>允许在工作台中切换交互模式</span>
            </div>
            <el-checkbox v-model="systemForm.interactionModeSupport" aria-label="交互模式支持切换" />
          </div>
          <div class="system-setting-row">
            <div class="system-setting-copy">
              <b>任务完成提醒</b>
              <span>任务执行完成或失败时，应用图标跳动 / 任务栏闪烁提醒</span>
            </div>
            <el-checkbox v-model="systemForm.taskCompletionReminder" aria-label="任务完成提醒" />
          </div>
          <div class="system-config-actions">
            <el-button type="primary" :loading="savingSystem" @click="saveSystem">保存系统配置</el-button>
          </div>
        </section>

        <section v-show="tab === 'enhance'" class="settings-section">
          <header class="settings-section-head"><b>能力增强</b><span>检测工作台依赖的本地工具，缺失时可一键安装</span></header>

          <div class="enhance-env-row">
            <div class="system-setting-copy">
              <b>工作台系统环境</b>
              <span>{{ envSummary }} · 工具目录 {{ toolsDirLabel }}</span>
            </div>
            <el-button size="small" :loading="detecting" :disabled="!canDetect" @click="detectEnhancements">重新检测</el-button>
          </div>

          <div v-if="!canDetect" class="enhance-empty">当前环境不支持能力检测（请在桌面应用中使用）</div>

          <div v-else-if="detecting" class="enhance-empty">正在检测…</div>

          <div v-else-if="!enhancements.length" class="enhance-empty">暂无能力项，点击「重新检测」开始</div>

          <div v-else class="enhance-list">
            <div v-for="item in enhancements" :key="item.id" class="enhance-item">
              <div class="enhance-main">
                <div class="enhance-title">
                  <b>{{ item.name }}</b>
                  <span v-if="item.version" class="enhance-version">{{ item.version }}</span>
                  <span :class="['enhance-badge', 'is-' + item.status]">{{ statusLabel(item) }}</span>
                </div>
                <div class="enhance-desc">{{ item.message || item.capability }}</div>
                <div v-if="item.path" class="enhance-path" :title="item.path">{{ shortPath(item.path) }}</div>
              </div>
              <div class="enhance-actions">
                <el-button
                  v-if="item.status === 'unknown'"
                  size="small"
                  :loading="detecting"
                  @click="detectEnhancements"
                >检测</el-button>
                <el-button
                  v-else-if="item.status === 'ready' && item.effective === false"
                  size="small"
                  type="warning"
                  :loading="repairing"
                  @click="repairPath"
                >修复 PATH</el-button>
                <el-button
                  v-else-if="item.status === 'missing' || item.status === 'broken'"
                  size="small"
                  type="primary"
                  :loading="installingId === item.id"
                  :disabled="!item.canAutoInstall"
                  @click="installEnhancement(item)"
                >{{ item.canAutoInstall ? '一键安装' : '暂不支持' }}</el-button>
                <el-button
                  v-else-if="item.status === 'outdated' || (item.status === 'ready' && item.upgradeAvailable)"
                  size="small"
                  :type="item.status === 'outdated' ? 'primary' : 'default'"
                  :loading="installingId === item.id"
                  @click="installEnhancement(item, 'upgrade')"
                >{{ item.status === 'outdated' ? '一键升级' : '升级至 ' + item.recommendedVersion }}</el-button>
                <el-button v-else size="small" :loading="detecting" @click="detectEnhancements">检测</el-button>
              </div>
            </div>
          </div>

          <el-alert
            v-if="restartHint"
            class="enhance-restart"
            type="success"
            :title="restartHint"
            :closable="false"
            show-icon
          >
            <el-button size="mini" type="primary" :loading="restarting" @click="restartBackend">立即重启</el-button>
          </el-alert>
        </section>
      </div>
    </div>

    <el-dialog
      :title="modelDialogTitle"
      :visible.sync="modelEditorVisible"
      width="520px"
      append-to-body
      custom-class="settings-editor-dialog"
    >
      <el-form label-position="top" size="small" class="compact-editor-form">
        <div v-if="modelCopyFromName" class="model-copy-tip form-span-2">
          已复制「{{ modelCopyFromName }}」的配置，修改后保存将创建为新模型。
        </div>
        <el-form-item label="名称"><el-input v-model="modelForm.name" /></el-form-item>
        <el-form-item label="厂商">
          <el-select v-model="modelForm.provider" style="width: 100%"><el-option label="OpenAI" :value="1" /><el-option label="Anthropic" :value="2" /></el-select>
        </el-form-item>
        <el-form-item label="模型名称" class="form-span-2"><el-input v-model="modelForm.modelName" placeholder="deepseek-flash / claude-sonnet-4" /></el-form-item>
        <el-form-item label="Base URL" class="form-span-2"><el-input v-model="modelForm.url" /></el-form-item>
        <el-form-item label="API Key" class="form-span-2"><el-input v-model="modelForm.apiKey" show-password placeholder="请输入 API Key" /></el-form-item>
        <el-alert
          v-if="modelTestResult"
          class="form-span-2 model-test-result"
          :title="modelTestResult.message"
          :type="modelTestResult.success ? 'success' : 'warning'"
          :closable="false"
          show-icon
        />
      </el-form>
      <span slot="footer" class="model-editor-footer">
        <el-button :loading="testingModelForm" :disabled="savingModel" @click="testModelForm">测试连通性</el-button>
        <span class="model-editor-footer-spacer"></span>
        <el-button :disabled="testingModelForm" @click="modelEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingModel" :disabled="testingModelForm" @click="saveModel">保存模型</el-button>
      </span>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { fetchModels, upsertModel, testConnectivity } from '../api/model'
import { getAgent, upsertAgent } from '../api/agent'
import { invalidateServerInfo } from '../api/client'

const emptyModel = () => ({
  id: null,
  name: '',
  provider: 1,
  modelType: 0,
  modelName: '',
  url: '',
  apiKey: ''
})

const emptyAgent = () => ({
  aid: '',
  name: '',
  description: '',
  avatar: '',
  type: 1,
  modelIds: [],
  defaultModelId: null,
  systemPrompt: '',
  builtInSystemPrompt: '',
  chatMemoryType: 1,
  chatMemoryMaxMessages: 1000
})

export default {
  name: 'ConfigDrawer',
  props: {
    visible: { type: Boolean, default: false },
    aid: { type: String, default: '' },
    agents: { type: Array, default: () => [] }
  },
  data: () => ({
    tab: 'models',
    loading: false,
    saving: false,
    savingModel: false,
    testingModelForm: false,
    modelTestResult: null,
    models: [],
    agentForm: emptyAgent(),
    initError: '',
    modelEditorVisible: false,
    modelForm: emptyModel(),
    modelCopyFromName: '',
    savingSystem: false,
    systemForm: {
      interactionModeSupport: false,
      taskCompletionReminder: true
    },
    enhancements: [],
    enhancementsLoaded: false,
    detecting: false,
    installingId: '',
    restarting: false,
    repairing: false,
    restartHint: '',
    envInfo: { platform: '', arch: '', runtime: '' }
  }),
  computed: {
    modelDialogTitle() {
      if (this.modelCopyFromName) return '复制模型'
      return this.modelForm.id ? '编辑模型' : '新增模型'
    },
    selectableModels() {
      if (!this.agentForm.modelIds.length) return this.models
      return this.models.filter((model) => this.agentForm.modelIds.includes(model.id))
    },
    canDetect() {
      return Boolean(window.electronAPI && window.electronAPI.detectEnhancement)
    },
    envSummary() {
      const platformName = { darwin: 'macOS', win32: 'Windows', linux: 'Linux' }[this.envInfo.platform] || this.envInfo.platform
      const parts = [platformName, this.envInfo.arch].filter(Boolean)
      return parts.length ? parts.join(' · ') : '未知环境'
    },
    toolsDirLabel() {
      const item = this.enhancements.find((entry) => entry.path)
      if (!item) return '~/.agentforge-desktop/tools'
      const normalized = String(item.path).replace(/\\/g, '/')
      const marker = '/tools/'
      const index = normalized.indexOf(marker)
      if (index < 0) return '~/.agentforge-desktop/tools'
      // 只展示到工具根目录（…/tools），避免把二进制文件名当目录展示。
      return `…${normalized.slice(index, index + marker.length - 1)}`
    }
  },
  watch: {
    visible(value) {
      if (value) this.load()
    },
    aid() {
      if (this.visible) this.load()
    }
  },
  methods: {
    providerName(provider) {
      return provider === 2 ? 'Anthropic' : 'OpenAI'
    },
    resolveModelPort(url) {
      if (!url) return '未配置'
      try {
        const parsed = new URL(url)
        if (parsed.port) return parsed.port
        return parsed.protocol === 'https:' ? '443' : parsed.protocol === 'http:' ? '80' : '默认'
      } catch (_error) {
        const match = String(url).match(/:(\d+)(?:\/|$)/)
        return match ? match[1] : '未识别'
      }
    },
    async load() {
      this.loading = true
      this.initError = ''
      try {
        this.models = (await fetchModels()) || []
        this.systemForm.interactionModeSupport = false
        this.systemForm.taskCompletionReminder = true
        if (window.electronAPI && window.electronAPI.readSystemSettings) {
          const systemSettings = await window.electronAPI.readSystemSettings()
          this.systemForm.interactionModeSupport = systemSettings?.interactionModeSupport === true
          this.systemForm.taskCompletionReminder = systemSettings?.taskCompletionReminder !== false
        }
        if (this.aid) {
          const agent = await getAgent(this.aid)
          this.agentForm = {
            aid: agent.aid,
            name: agent.name || '',
            description: agent.description || '',
            modelIds: agent.modelIds || [],
            defaultModelId: agent.defaultModelId || null,
            systemPrompt: agent.systemPrompt || '',
            builtInSystemPrompt: agent.builtInSystemPrompt || '',
            chatMemoryType: agent.chatMemoryType || 1,
            chatMemoryMaxMessages: agent.chatMemoryMaxMessages || 1000
          }
        }
      } catch (error) {
        this.$message.error(error.message || '配置加载失败')
      } finally {
        this.loading = false
      }
      // 能力列表独立加载：其失败不应影响模型 / Agent 配置的可用性。
      await this.loadEnhancements()
    },
    async saveSystem() {
      if (!window.electronAPI || !window.electronAPI.saveSystemSettings) {
        this.$message.warning('当前环境不支持保存系统配置')
        return
      }
      this.savingSystem = true
      try {
        const saved = await window.electronAPI.saveSystemSettings(this.systemForm)
        if (saved) {
          this.systemForm.interactionModeSupport = saved.interactionModeSupport === true
          this.systemForm.taskCompletionReminder = saved.taskCompletionReminder !== false
        }
        this.$message.success('系统配置已保存')
        this.$emit('system-saved', saved || { ...this.systemForm })
      } catch (error) {
        this.$message.error(error.message || '系统配置保存失败')
      } finally {
        this.savingSystem = false
      }
    },
    // 能力增强：先渲染持久化快照避免闪烁，再异步检测校正（方案 §8.3）。
    statusLabel(item) {
      // 已安装但 Agent 的 shell 里找不到 → 不能显示"已支持"，否则用户会遇到"显示正常却执行失败"。
      if (item.status === 'ready' && item.effective === false) return '待修复'
      const map = {
        unknown: '未检测',
        ready: '已支持',
        missing: '未安装',
        outdated: '版本过低',
        broken: '不可用'
      }
      return map[item.status] || '未检测'
    },
    shortPath(value) {
      const normalized = String(value || '').replace(/\\/g, '/')
      const index = normalized.indexOf('/tools/')
      return index > 0 ? `…${normalized.slice(index)}` : normalized
    },
    async loadEnhancements() {
      if (!this.canDetect) {
        this.enhancementsLoaded = true
        return
      }
      try {
        const info = await window.electronAPI.getLocalServerInfo()
        if (info) {
          this.envInfo = {
            platform: info.platform || '',
            arch: info.arch || '',
            runtime: info.runtime || ''
          }
        }
      } catch (_error) {
        // 环境信息只是展示用，失败不影响检测。
      }
      try {
        this.enhancements = (await window.electronAPI.listEnhancements()) || []
      } catch (error) {
        this.$message.error(error.message || '能力检测失败')
      } finally {
        this.enhancementsLoaded = true
      }
    },
    async detectEnhancements() {
      if (!this.canDetect) return
      this.detecting = true
      try {
        const status = await window.electronAPI.detectEnhancement('rg')
        if (status) {
          const index = this.enhancements.findIndex((item) => item.id === status.id)
          if (index >= 0) this.$set(this.enhancements, index, status)
          else this.enhancements = [...this.enhancements, status]
        }
      } catch (error) {
        this.$message.error(error.message || '检测失败')
      } finally {
        this.detecting = false
      }
    },
    async installEnhancement(item, mode = 'auto') {
      if (!window.electronAPI || !window.electronAPI.installEnhancement) {
        this.$message.warning('当前环境不支持安装能力组件')
        return
      }
      this.installingId = item.id
      this.restartHint = ''
      try {
        const result = await window.electronAPI.installEnhancement(item.id, mode)
        if (result?.status) {
          const index = this.enhancements.findIndex((entry) => entry.id === result.status.id)
          if (index >= 0) this.$set(this.enhancements, index, result.status)
        }
        this.$message.success(result?.message || '安装完成')
        if (result?.needsRestart) {
          this.restartHint = `${result.message || '已安装'}，重启本地服务后生效`
        }
      } catch (error) {
        this.$message.error(error.message || '安装失败')
      } finally {
        this.installingId = ''
      }
    },
    // 修复 PATH：二进制已在但 Agent 的 shell 解析不到时，重写包装脚本让外部启动的后端也能用。
    async repairPath() {
      if (!window.electronAPI || !window.electronAPI.repairEnhancementPath) {
        this.$message.warning('当前环境不支持修复 PATH')
        return
      }
      this.repairing = true
      try {
        const result = await window.electronAPI.repairEnhancementPath('rg')
        if (result?.status) {
          const index = this.enhancements.findIndex((entry) => entry.id === result.status.id)
          if (index >= 0) this.$set(this.enhancements, index, result.status)
        }
        if (result?.shellOk) {
          this.$message.success('PATH 已修复，Agent 可直接使用 rg')
        } else {
          this.restartHint = 'PATH 已写入，需重启本地服务后生效'
          this.$message.warning('PATH 已写入，请重启本地服务后生效')
        }
      } catch (error) {
        this.$message.error(error.message || '修复失败')
      } finally {
        this.repairing = false
      }
    },
    async restartBackend() {
      if (!window.electronAPI || !window.electronAPI.restartBackend) {
        this.$message.warning('当前环境不支持重启本地服务')
        return
      }
      this.restarting = true
      try {
        await window.electronAPI.restartBackend()
        // 后端重启会更换端口与 token，必须让渲染进程重新获取连接信息。
        invalidateServerInfo()
        this.restartHint = ''
        this.$message.success('本地服务已重启')
        await this.detectEnhancements()
      } catch (error) {
        this.$message.error(error.message || '重启失败')
      } finally {
        this.restarting = false
      }
    },
    async saveAgent() {
      this.saving = true
      this.initError = ''
      try {
        const { systemPrompt: _systemPrompt, builtInSystemPrompt: _builtInPrompt, ...payload } = this.agentForm
        const result = await upsertAgent(payload)
        this.agentForm.aid = result.aid
        this.$message.success('配置已保存并初始化')
        if (result.initOk === false) {
          this.initError = result.initError || 'Agent 初始化失败，请检查模型配置'
        }
        this.$emit('saved', result)
        this.$emit('select-agent', result.aid)
        await this.load()
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.saving = false
      }
    },
    newModel() {
      this.modelForm = emptyModel()
      this.modelCopyFromName = ''
      this.modelTestResult = null
      this.modelEditorVisible = true
    },
    editModel(model) {
      this.modelForm = { ...model }
      this.modelCopyFromName = ''
      this.modelTestResult = null
      this.modelEditorVisible = true
    },
    copyModel(model) {
      this.modelForm = {
        ...model,
        id: null,
        name: this.nextCopyName(model.name)
      }
      this.modelCopyFromName = model.name || '未命名模型'
      this.modelTestResult = null
      this.modelEditorVisible = true
    },
    nextCopyName(name) {
      const sourceName = String(name || '未命名模型')
        .replace(/\s*[（(]复制(?:\s*\d+)?[）)]$/, '')
        .trim() || '未命名模型'
      const usedNames = new Set(this.models.map((model) => model.name))
      let candidate = `${sourceName}（复制）`
      let index = 2
      while (usedNames.has(candidate)) {
        candidate = `${sourceName}（复制 ${index}）`
        index += 1
      }
      return candidate
    },
    async testModelForm() {
      if (!this.modelForm.url || !this.modelForm.modelName) {
        this.$message.warning('请先填写 Base URL 和模型标识')
        return
      }
      this.testingModelForm = true
      this.modelTestResult = null
      const payload = {
        modelId: this.modelForm.id,
        provider: this.modelForm.provider,
        modelType: this.modelForm.modelType,
        modelName: this.modelForm.modelName,
        url: this.modelForm.url
      }
      if (this.modelForm.apiKey) payload.apiKey = this.modelForm.apiKey
      try {
        const result = await testConnectivity(payload)
        const success = Boolean(result && result.success)
        const detail = (result && result.message) || '服务端未返回详细信息'
        this.modelTestResult = {
          success,
          message: (success ? '连通性测试通过：' : '连通性测试未通过：') + detail
        }
      } catch (error) {
        this.modelTestResult = {
          success: false,
          message: '连通性测试未通过：' + (error.message || '请检查模型配置后重试')
        }
      } finally {
        this.testingModelForm = false
      }
    },
    async saveModel() {
      this.savingModel = true
      try {
        const copied = Boolean(this.modelCopyFromName)
        await upsertModel(this.modelForm)
        this.$message.success(copied ? '模型副本已创建' : '模型已保存')
        this.modelEditorVisible = false
        this.modelCopyFromName = ''
        await this.load()
        this.$emit('saved')
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.savingModel = false
      }
    },
    async testModel(model) {
      try {
        const result = await testConnectivity({ modelId: model.id })
        if (result.success) this.$message.success(result.message)
        else this.$message.warning(result.message)
      } catch (error) {
        this.$message.error(error.message || '测试失败')
      }
    }
  }
}
</script>

<style scoped>
::v-deep .workbench-settings-dialog {
  max-width: calc(100vw - 40px);
  margin-bottom: 6vh;
  overflow: hidden;
  border-radius: 14px;
}

::v-deep .workbench-settings-dialog .el-dialog__header {
  padding: 18px 22px;
  border-bottom: 1px solid #e8ebf0;
}

::v-deep .workbench-settings-dialog .el-dialog__body {
  height: min(720px, 80vh);
  padding: 0;
}

.settings-layout {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 10px;
  border-right: 1px solid #e8ebf0;
  background: #f7f8fa;
}

.settings-nav-label {
  padding: 9px 12px 3px;
  color: #a0a8b5;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.settings-nav button {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #667085;
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  transition: background 0.15s, color 0.15s;
}

.settings-nav button.active {
  background: #eaf2fd;
  color: #3569ad;
  font-weight: 600;
}

.settings-nav-icon {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.settings-content {
  min-width: 0;
  overflow-y: auto;
  padding: 18px 22px 28px;
}

.settings-section-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 20px;
}

.settings-section-head b {
  color: #1f2937;
  font-size: 16px;
}

.settings-section-head span {
  color: #98a2b3;
  font-size: 12px;
}

.agent-config-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  column-gap: 16px;
  align-items: start;
}

.agent-config-form .form-span-2 {
  grid-column: 1 / -1;
}

.built-in-prompt-hint {
  margin-top: 6px;
  color: #9099a8;
  font-size: 11px;
  line-height: 1.5;
}

::v-deep .agent-config-form textarea[readonly] {
  color: #667085;
  background: #f7f8fa;
}

.system-setting-row {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid #e5e9ef;
  border-radius: 8px;
  background: #fff;
}

.system-setting-row + .system-setting-row {
  margin-top: 12px;
}

.system-setting-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.system-setting-copy b {
  color: #344054;
  font-size: 13px;
  font-weight: 600;
}

.system-setting-copy span {
  color: #8a94a3;
  font-size: 12px;
}

.system-config-actions,
.agent-config-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #edf0f4;
}

.enhance-env-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid #e5e9ef;
  border-radius: 8px;
  background: #f7f9fc;
}

.enhance-empty {
  padding: 22px 14px;
  color: #98a2b3;
  font-size: 12px;
  text-align: center;
}

.enhance-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.enhance-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid #e5e9ef;
  border-radius: 8px;
  background: #fff;
}

.enhance-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.enhance-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.enhance-title b {
  color: #344054;
  font-size: 13px;
  font-weight: 600;
}

.enhance-version {
  color: #7c8698;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.enhance-badge {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 17px;
}

.enhance-badge.is-ready {
  background: #e7f6ec;
  color: #1f8a4c;
}

.enhance-badge.is-missing,
.enhance-badge.is-broken {
  background: #fdeaea;
  color: #c0453b;
}

.enhance-badge.is-outdated {
  background: #fff4e0;
  color: #ad6d11;
}

.enhance-badge.is-ready.is-ineffective {
  background: #fff4e0;
  color: #ad6d11;
}

.enhance-badge.is-unknown {
  background: #eef1f5;
  color: #79849a;
}

.enhance-desc {
  color: #8a94a3;
  font-size: 12px;
}

.enhance-path {
  overflow: hidden;
  color: #9aa4b2;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.enhance-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.enhance-restart {
  margin-top: 14px;
}

::v-deep .agent-config-form .el-input-number {
  width: 100%;
}

.drawer-model-url {
  max-width: 520px;
  overflow: hidden;
  color: #8a96a8 !important;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10.5px !important;
  text-overflow: ellipsis;
  white-space: nowrap;
}

::v-deep .settings-editor-dialog {
  max-width: calc(100vw - 40px);
  margin-top: 9vh !important;
  overflow: hidden;
  border-radius: 12px;
}

::v-deep .settings-editor-dialog .el-dialog__header {
  padding: 16px 20px 13px;
  border-bottom: 1px solid #edf0f4;
}

::v-deep .settings-editor-dialog .el-dialog__body {
  max-height: 68vh;
  overflow-y: auto;
  padding: 16px 20px 8px;
}

::v-deep .settings-editor-dialog .el-dialog__footer {
  padding: 10px 20px 14px;
  border-top: 1px solid #edf0f4;
}

.compact-editor-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  column-gap: 14px;
  align-items: start;
}

.compact-editor-form .form-span-2 {
  grid-column: 1 / -1;
}

.model-test-result {
  margin-bottom: 12px;
}

.model-editor-footer {
  display: flex;
  align-items: center;
  width: 100%;
}

.model-editor-footer-spacer {
  flex: 1;
}

::v-deep .compact-editor-form .el-form-item {
  margin-bottom: 12px;
}

::v-deep .compact-editor-form .el-form-item__label {
  padding-bottom: 4px;
  color: #5f6b7c;
  font-size: 12px;
  line-height: 18px;
}

::v-deep .compact-editor-form .el-input-number {
  width: 100%;
}

::v-deep .compact-editor-form .el-textarea__inner {
  line-height: 1.55;
}

@media (max-width: 720px) {
  .settings-layout {
    grid-template-columns: 170px minmax(0, 1fr);
  }

  .settings-content {
    padding: 16px;
  }
}
</style>
