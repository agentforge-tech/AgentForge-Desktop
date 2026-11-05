<template>
  <div class="page agent-config-page">
    <header class="page-header">
      <div>
        <h1>{{ form.aid ? '编辑 Agent' : '新建 Agent' }}</h1>
        <p>保存后会写入 config.json，并立即初始化 Agent。</p>
      </div>
      <div class="header-actions">
        <el-button @click="$router.push('/agents')">返回列表</el-button>
        <el-button v-if="form.aid" type="primary" plain @click="startDebug">开始调试</el-button>
        <el-button v-if="form.aid" type="danger" plain @click="remove">删除</el-button>
      </div>
    </header>

    <div v-loading="loading" class="config-layout">
      <div class="config-card">
        <el-form label-position="top" size="small">
          <el-form-item label="名称">
            <el-input v-model="form.name" placeholder="例如 AgentForge 助手" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="头像 URL">
            <el-input v-model="form.avatar" placeholder="可留空" />
          </el-form-item>
          <el-form-item label="可用模型">
            <el-select v-model="form.modelIds" multiple placeholder="选择工作台可切换的模型" style="width: 100%">
              <el-option v-for="model in models" :key="model.id" :label="model.name + ' · ' + model.modelName" :value="model.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="默认模型">
            <el-select v-model="form.defaultModelId" placeholder="选择默认模型" style="width: 100%">
              <el-option v-for="model in selectableModels" :key="model.id" :label="model.name + ' · ' + model.modelName" :value="model.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="系统提示词">
            <el-input v-model="form.systemPrompt" type="textarea" :rows="8" placeholder="你是一名专业的 AI 助手…" />
          </el-form-item>
          <el-form-item label="记忆最大消息数">
            <el-input-number v-model="form.chatMemoryMaxMessages" :min="1" :max="100000" />
          </el-form-item>
          <el-alert v-if="initError" :title="initError" type="warning" :closable="false" show-icon />
          <div class="form-actions">
            <el-button @click="$router.push('/agents')">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存并初始化</el-button>
          </div>
        </el-form>
      </div>

      <aside class="config-side">
        <div class="side-card">
          <h3>配置说明</h3>
          <p>可用模型决定工作台右上角模型下拉的候选集合；默认模型是首次进入时选中的模型。</p>
          <p>保存后写入 <code>~/.agentforge-desktop/config.json</code>，并清理运行时缓存后重新初始化。</p>
        </div>
        <div class="side-card" v-if="form.aid">
          <h3>当前标识</h3>
          <p><code>{{ form.aid }}</code></p>
        </div>
      </aside>
    </div>
  </div>
</template>

<script>
import { fetchModels } from '../../api/model'
import { getAgent, upsertAgent, deleteAgent } from '../../api/agent'

const emptyForm = () => ({
  aid: '',
  name: '',
  description: '',
  avatar: '',
  type: 1,
  modelIds: [],
  defaultModelId: null,
  systemPrompt: '你是一名专业的 AI 助手，请准确、清晰地回答用户问题。',
  chatMemoryType: 1,
  chatMemoryMaxMessages: 1000
})

export default {
  name: 'AgentConfigPage',
  data: () => ({
    models: [],
    loading: false,
    saving: false,
    initError: '',
    form: emptyForm()
  }),
  computed: {
    selectableModels() {
      if (!this.form.modelIds.length) return this.models
      return this.models.filter((model) => this.form.modelIds.includes(model.id))
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        this.models = (await fetchModels()) || []
        const aid = this.$route.query.aid
        if (aid) {
          const agent = await getAgent(aid)
          this.form = {
            aid: agent.aid,
            name: agent.name || '',
            description: agent.description || '',
            avatar: agent.avatar || '',
            type: agent.type || 1,
            modelIds: agent.modelIds || [],
            defaultModelId: agent.defaultModelId || null,
            systemPrompt: agent.systemPrompt || '',
            chatMemoryType: agent.chatMemoryType || 1,
            chatMemoryMaxMessages: agent.chatMemoryMaxMessages || 1000
          }
        } else if (this.models.length) {
          this.form.modelIds = this.models.map((model) => model.id)
          this.form.defaultModelId = this.models[0].id
        }
      } catch (error) {
        this.$message.error(error.message || '加载失败')
      } finally {
        this.loading = false
      }
    },
    async save() {
      this.saving = true
      this.initError = ''
      try {
        const result = await upsertAgent(this.form)
        this.form.aid = result.aid
        if (result.initOk === false) {
          this.initError = result.initError || 'Agent 初始化失败，请检查模型配置'
          this.$message.warning('已保存，但 Agent 初始化失败')
        } else {
          this.$message.success('已保存并初始化')
        }
        if (!this.$route.query.aid) {
          this.$router.replace({ path: '/agents/config', query: { aid: result.aid } }).catch(() => {})
        }
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async remove() {
      try {
        await this.$confirm('删除后该 Agent 配置将不可恢复，是否继续？', '删除 Agent', { type: 'warning' })
      } catch (_error) {
        return
      }
      try {
        await deleteAgent(this.form.aid)
        this.$message.success('已删除')
        this.$router.push('/agents').catch(() => {})
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    },
    startDebug() {
      this.$router.push({ path: '/workbench', query: { aid: this.form.aid } }).catch(() => {})
    }
  }
}
</script>
