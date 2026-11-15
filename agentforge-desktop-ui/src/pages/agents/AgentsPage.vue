<template>
  <div class="page agents-page">
    <header class="page-header">
      <div>
        <h1>Agent 配置</h1>
        <p>Agent 配置保存在 conf/config.json，保存后立即生效。</p>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="createAgent">新建 Agent</el-button>
    </header>

    <div v-loading="loading" class="agent-grid">
      <div v-for="agent in agents" :key="agent.aid" class="agent-card">
        <div class="agent-card-head">
          <div class="agent-avatar">{{ (agent.name || 'A').slice(0, 1) }}</div>
          <div class="agent-meta">
            <b>{{ agent.name }}</b>
            <span>{{ agent.description || '暂无描述' }}</span>
          </div>
        </div>
        <div class="agent-info">
          <span>默认模型：{{ modelName(agent.defaultModelId) }}</span>
          <span>可选模型：{{ (agent.modelIds || []).length }} 个</span>
        </div>
        <div class="agent-card-actions">
          <el-button size="mini" @click="editAgent(agent)">编辑</el-button>
          <el-button size="mini" type="primary" @click="debugAgent(agent)">开始调试</el-button>
          <el-button size="mini" type="text" class="danger-text" @click="removeAgent(agent)">删除</el-button>
        </div>
      </div>
      <div v-if="!loading && !agents.length" class="agent-empty">
        暂无 Agent，点击右上角新建。
      </div>
    </div>
  </div>
</template>

<script>
import { fetchAgents, deleteAgent } from '../../api/agent'
import { fetchModels } from '../../api/model'

export default {
  name: 'AgentsPage',
  data: () => ({
    agents: [],
    models: [],
    loading: false
  }),
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const [agents, models] = await Promise.all([fetchAgents(), fetchModels()])
        this.agents = agents || []
        this.models = models || []
      } catch (error) {
        this.$message.error(error.message || '加载失败')
      } finally {
        this.loading = false
      }
    },
    modelName(id) {
      const model = this.models.find((item) => item.id === id)
      return model ? model.name : '未设置'
    },
    createAgent() {
      this.$router.push('/agents/config').catch(() => {})
    },
    editAgent(agent) {
      this.$router.push({ path: '/agents/config', query: { aid: agent.aid } }).catch(() => {})
    },
    debugAgent(agent) {
      this.$router.push({ path: '/workbench', query: { aid: agent.aid } }).catch(() => {})
    },
    async removeAgent(agent) {
      try {
        await this.$confirm(`确定删除 Agent「${agent.name}」吗？`, '删除 Agent', { type: 'warning' })
      } catch (_error) {
        return
      }
      try {
        await deleteAgent(agent.aid)
        this.$message.success('已删除')
        await this.load()
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    }
  }
}
</script>
