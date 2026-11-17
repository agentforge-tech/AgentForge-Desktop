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
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="5" width="14" height="14" rx="3"/><path d="M9 9h6v6H9zM9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3"/></svg>
          <span>模型配置</span>
        </button>
        <button type="button" :class="{ active: tab === 'agent' }" @click="tab = 'agent'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/></svg>
          <span>默认 Agent 配置</span>
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
                <el-button type="text" @click="testModel(model)">测试</el-button>
              </div>
            </div>
            <el-button class="add-model-btn" icon="el-icon-plus" @click="newModel">新增模型</el-button>
          </div>

        </section>
      </div>
    </div>

    <el-dialog
      :title="modelForm.id ? '编辑模型' : '新增模型'"
      :visible.sync="modelEditorVisible"
      width="520px"
      append-to-body
      custom-class="settings-editor-dialog"
    >
      <el-form label-position="top" size="small" class="compact-editor-form">
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
  avatar: '/logo.png',
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
    modelForm: emptyModel()
  }),
  computed: {
    selectableModels() {
      if (!this.agentForm.modelIds.length) return this.models
      return this.models.filter((model) => this.agentForm.modelIds.includes(model.id))
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
      this.modelTestResult = null
      this.modelEditorVisible = true
    },
    editModel(model) {
      this.modelForm = { ...model }
      this.modelTestResult = null
      this.modelEditorVisible = true
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
        await upsertModel(this.modelForm)
        this.$message.success('模型已保存')
        this.modelEditorVisible = false
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

.settings-nav button:hover {
  background: #eef3fa;
  color: #3b6fb6;
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

.agent-config-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid #edf0f4;
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
