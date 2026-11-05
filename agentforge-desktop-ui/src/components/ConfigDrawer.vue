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
        <div class="settings-nav-label">控制台</div>
        <button type="button" :class="{ active: tab === 'my-agents' }" @click="tab = 'my-agents'">
          <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2"/><path d="M3.5 19c.5-4 2.4-6 5.5-6s5 2 5.5 6M14 14c3.6-.7 5.7 1 6.5 4"/></svg>
          <span>我的 Agent</span>
        </button>
      </nav>

      <div class="settings-content">
        <section v-show="tab === 'my-agents'" class="settings-section">
          <header class="settings-section-head">
            <b>我的 Agent</b>
            <span>选择一个 Agent 开始对话，或进入它的详细配置</span>
          </header>
          <div class="my-agent-list">
            <button
              v-for="agent in agents"
              :key="agent.aid"
              type="button"
              class="my-agent-card"
              :class="{ active: agent.aid === aid }"
              @click="chooseAgent(agent)"
            >
              <span class="my-agent-avatar"><img :src="agent.avatar || '/logo.png'" :alt="agent.name || 'AgentForge'" /></span>
              <span class="my-agent-main"><b>{{ agent.name || '未命名 Agent' }}</b><small>{{ agent.description || '暂无描述' }}</small></span>
              <span v-if="agent.aid === aid" class="my-agent-current">当前</span>
              <span class="my-agent-edit" role="button" tabindex="0" @click.stop="editAgent(agent)" @keydown.enter.stop="editAgent(agent)">编辑</span>
            </button>
          </div>
          <div class="my-agent-footer">
            <el-button icon="el-icon-plus" type="primary" @click="createAgent">创建 Agent</el-button>
          </div>
        </section>

        <section v-show="tab === 'agent'" class="settings-section">
          <header class="settings-section-head"><b>默认 Agent 配置</b><span>管理当前默认 Agent 的运行参数</span></header>
          <el-form label-position="top" size="small" class="agent-summary-form">
            <el-form-item label="当前 Agent">
              <el-select :value="aid" placeholder="选择 Agent" style="width: 100%" @change="$emit('select-agent', $event)">
                <el-option v-for="agent in agents" :key="agent.aid" :label="agent.name" :value="agent.aid" />
              </el-select>
            </el-form-item>
          </el-form>
          <div v-if="currentAgent" class="agent-config-summary">
            <div class="agent-config-summary-head">
              <span class="my-agent-avatar"><img :src="currentAgent.avatar || '/logo.png'" :alt="currentAgent.name" /></span>
              <span class="my-agent-main"><b>{{ currentAgent.name }}</b><small>{{ currentAgent.description || '暂无描述' }}</small></span>
            </div>
            <dl>
              <div><dt>可用模型</dt><dd>{{ agentModelNames }}</dd></div>
              <div><dt>记忆上限</dt><dd>{{ agentForm.chatMemoryMaxMessages || 1000 }} 条</dd></div>
            </dl>
            <el-button type="primary" @click="editAgent(currentAgent)">编辑配置</el-button>
          </div>
        </section>

        <section v-show="tab === 'models'" class="settings-section">
          <header class="settings-section-head"><b>模型设置</b><span>选择对话模型或管理模型配置</span></header>
          <el-form label-position="top" size="small">
            <el-form-item label="当前对话模型">
              <el-select v-model="activeModelId" placeholder="选择模型" style="width: 100%" @change="$emit('select-model', $event)">
                <el-option
                  v-for="model in selectableModels"
                  :key="model.id"
                  :label="model.name + ' · ' + model.modelName"
                  :value="model.id"
                />
              </el-select>
            </el-form-item>
          </el-form>
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
      :title="agentForm.aid ? '编辑 Agent' : '新增 Agent'"
      :visible.sync="agentEditorVisible"
      width="560px"
      append-to-body
      custom-class="settings-editor-dialog"
    >
      <el-form label-position="top" size="small" class="compact-editor-form">
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
        <el-form-item label="系统提示词" class="form-span-2"><el-input v-model="agentForm.systemPrompt" type="textarea" :rows="4" /></el-form-item>
        <el-alert v-if="initError" class="form-span-2" :title="initError" type="warning" :closable="false" show-icon />
      </el-form>
      <span slot="footer">
        <el-button @click="agentEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAgent">保存并初始化</el-button>
      </span>
    </el-dialog>

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
        <el-form-item label="模型标识" class="form-span-2"><el-input v-model="modelForm.modelName" placeholder="deepseek-flash / claude-sonnet-4" /></el-form-item>
        <el-form-item label="Base URL" class="form-span-2"><el-input v-model="modelForm.url" /></el-form-item>
        <el-form-item label="API Key" class="form-span-2"><el-input v-model="modelForm.apiKey" show-password placeholder="留空则沿用已有密钥" /></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="modelEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingModel" @click="saveModel">保存模型</el-button>
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
  systemPrompt: '你是一名专业的 AI 助手，请准确、清晰地回答用户问题。',
  chatMemoryType: 1,
  chatMemoryMaxMessages: 1000
})

export default {
  name: 'ConfigDrawer',
  props: {
    visible: { type: Boolean, default: false },
    aid: { type: String, default: '' },
    agents: { type: Array, default: () => [] },
    selectedModelId: { type: [Number, String], default: null }
  },
  data: () => ({
    tab: 'models',
    loading: false,
    saving: false,
    savingModel: false,
    models: [],
    agentForm: emptyAgent(),
    agentEditorVisible: false,
    initError: '',
    modelEditorVisible: false,
    modelForm: emptyModel(),
    activeModelId: null
  }),
  computed: {
    currentAgent() {
      return this.agents.find((agent) => agent.aid === this.aid) || null
    },
    agentModelNames() {
      const names = this.agentModels.map((model) => model.name)
      return names.length ? names.join('、') : '未配置'
    },
    agentModels() {
      const ids = this.agentForm.modelIds || []
      return this.models.filter((model) => ids.includes(model.id))
    },
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
    },
    selectedModelId: {
      immediate: true,
      handler(value) {
        this.activeModelId = value
      }
    }
  },
  methods: {
    chooseAgent(agent) {
      if (agent.aid !== this.aid) this.$emit('select-agent', agent.aid)
    },
    createAgent() {
      const ids = this.models.map((model) => model.id)
      this.agentForm = { ...emptyAgent(), modelIds: ids, defaultModelId: ids[0] || null }
      this.initError = ''
      this.agentEditorVisible = true
    },
    async editAgent(agent) {
      try {
        const detail = await getAgent(agent.aid)
        this.agentForm = {
          ...emptyAgent(),
          ...detail,
          modelIds: detail.modelIds || []
        }
        this.initError = ''
        this.agentEditorVisible = true
      } catch (error) {
        this.$message.error(error.message || 'Agent 加载失败')
      }
    },
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
        const result = await upsertAgent(this.agentForm)
        this.agentForm.aid = result.aid
        this.$message.success('配置已保存并初始化')
        if (result.initOk === false) {
          this.initError = result.initError || 'Agent 初始化失败，请检查模型配置'
        }
        this.$emit('saved', result)
        this.$emit('select-agent', result.aid)
        this.agentEditorVisible = false
        await this.load()
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.saving = false
      }
    },
    newModel() {
      this.modelForm = emptyModel()
      this.modelEditorVisible = true
    },
    editModel(model) {
      this.modelForm = { ...model, apiKey: '' }
      this.modelEditorVisible = true
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

.my-agent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.my-agent-card {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #e5e9f0;
  border-radius: 11px;
  background: #fff;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}

.my-agent-card:hover {
  border-color: #a8c3e8;
  box-shadow: 0 4px 14px rgba(44, 83, 130, 0.08);
}

.my-agent-card.active {
  border-color: #8eb3e5;
  background: #f5f9ff;
}

.my-agent-avatar {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  overflow: hidden;
  border-radius: 11px;
  background: #f4f8ff;
}

.my-agent-avatar img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.my-agent-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.my-agent-main b,
.my-agent-main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.my-agent-main b {
  color: #27364a;
  font-size: 13.5px;
}

.my-agent-main small {
  color: #98a2b3;
  font-size: 11.5px;
}

.my-agent-current {
  padding: 2px 8px;
  border-radius: 10px;
  background: #e6f0fc;
  color: #3b6fb6;
  font-size: 11px;
}

.my-agent-edit {
  padding: 5px 8px;
  border-radius: 6px;
  color: #667791;
  font-size: 12px;
}

.my-agent-edit:hover,
.my-agent-edit:focus {
  outline: none;
  background: #e7f0fb;
  color: #3569ad;
}

.my-agent-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.agent-config-summary {
  padding: 18px;
  border: 1px solid #e5e9f0;
  border-radius: 12px;
  background: #fafbfd;
}

.agent-config-summary-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

.agent-config-summary dl {
  margin: 0 0 18px;
}

.agent-config-summary dl > div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 12px;
  padding: 9px 0;
  border-top: 1px solid #edf0f4;
  font-size: 12px;
}

.agent-config-summary dt {
  color: #98a2b3;
}

.agent-config-summary dd {
  margin: 0;
  color: #475467;
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
