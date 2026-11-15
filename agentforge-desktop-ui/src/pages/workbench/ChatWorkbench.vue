<template>
  <div class="workbench">
    <aside class="session-sidebar">
      <div class="session-head">
        <div class="current-agent-summary">
          <div class="current-agent-avatar">
            <img :src="currentAgent && currentAgent.avatar ? currentAgent.avatar : '/logo.png'" :alt="currentAgent ? currentAgent.name : 'AgentForge'" />
          </div>
          <div class="current-agent-info">
            <b>{{ currentAgent ? currentAgent.name : '未选择 Agent' }}</b>
          </div>
        </div>
        <el-button size="small" icon="el-icon-plus" class="new-chat-btn" @click="newChat">新建对话</el-button>
      </div>

      <div class="session-scroll">
        <div v-if="topSessions.length" class="session-section">
          <div class="section-title">置顶会话</div>
          <div
            v-for="item in topSessions"
            :key="'top-' + item.id"
            class="session-item"
            :class="{ active: item.id === activeSessionId }"
            @click="openSession(item)"
          >
            <div class="session-body">
              <span class="session-text" :title="item.title">{{ item.title }}</span>
              <span class="session-time">{{ formatSessionTime(item) }}</span>
            </div>
            <span class="session-actions">
              <i class="el-icon-edit" title="重命名" @click.stop="renameSession(item)"></i>
              <i class="el-icon-bottom" title="取消置顶" @click.stop="toggleTop(item, false)"></i>
              <i class="el-icon-delete" title="删除" @click.stop="removeSession(item)"></i>
            </span>
          </div>
        </div>

        <div class="session-section">
          <div class="section-title">历史会话</div>
          <div
            v-for="item in normalSessions"
            :key="item.id"
            class="session-item"
            :class="{ active: item.id === activeSessionId }"
            @click="openSession(item)"
          >
            <div class="session-body">
              <span class="session-text" :title="item.title">{{ item.title }}</span>
              <span class="session-time">{{ formatSessionTime(item) }}</span>
            </div>
            <span class="session-actions">
              <i class="el-icon-edit" title="重命名" @click.stop="renameSession(item)"></i>
              <i class="el-icon-top" title="置顶" @click.stop="toggleTop(item, true)"></i>
              <i class="el-icon-delete" title="删除" @click.stop="removeSession(item)"></i>
            </span>
          </div>
          <div v-if="!topSessions.length && !normalSessions.length" class="session-empty">
            {{ sessionsLoading ? '加载中…' : '暂无历史会话' }}
          </div>
        </div>
      </div>

      <div class="session-footer">
        <span class="status-dot" :class="{ ok: backendConnected }"></span>
        <span class="status-text">{{ backendConnected ? '本地服务已连接' : '本地服务未连接' }}</span>
        <el-button type="text" size="mini" @click="openDataDir">打开目录</el-button>
      </div>
    </aside>

    <main class="chat-main">
      <header class="chat-header">
        <div class="chat-title">
          <span>{{ title }}</span>
          <span v-if="currentAgent" class="chat-agent-name">{{ currentAgent.name }}</span>
        </div>
        <div class="chat-toolbar">
          <el-select v-model="mode" size="mini" class="mode-select" @change="onModeChange">
            <el-option v-for="item in renderModes" :key="item.key" :label="item.label" :value="item.key" />
          </el-select>
          <el-button size="mini" icon="el-icon-setting" circle title="工作台设置" @click="settingsOpen = true" />
        </div>
      </header>

      <div class="conversation-stage" :class="{ 'has-messages': messages.length }">
        <section ref="feed" class="chat-feed agent-debug-feed" @scroll.passive="onFeedScroll">
        <div class="chat-inner agent-debug-chat-inner">
          <div v-if="!messages.length" class="welcome">
            <h2>{{ welcomeText }}</h2>
            <p v-if="!agents.length" class="welcome-tip">还没有 Agent，请先到「Agent 配置」创建。</p>
          </div>

          <template v-for="(message, index) in messages">
            <div v-if="message.role === 'user'" :key="'u-' + index" class="agent-debug-message-row agent-debug-message-row--user">
              <div class="agent-debug-user-block">
                <div class="agent-debug-user-bubble">{{ message.text }}</div>
                <div class="agent-debug-message-actions">
                  <button class="agent-debug-mini-btn" type="button" title="复制" @click="copy(message.text)">
                    <i class="el-icon-document-copy"></i>
                  </button>
                </div>
              </div>
            </div>
            <div v-else :key="'a-' + index" class="agent-debug-message-row agent-debug-message-row--assistant">
              <div class="agent-debug-bot-identity">
                <div class="agent-debug-bot-avatar">
                  <img v-if="currentAgent && currentAgent.avatar" :src="currentAgent.avatar" :alt="currentAgent.name || 'AgentForge'" />
                  <img v-else src="/logo.png" alt="AgentForge" />
                </div>
                <span class="agent-debug-bot-name">{{ currentAgent ? currentAgent.name : 'AgentForge' }}</span>
              </div>
              <div class="agent-debug-bot-body">
                <AgentMessageContent :message="message" :mode="mode" />
              </div>
            </div>
          </template>
        </div>
        </section>

        <transition name="fade">
          <button v-if="isUserScrolledUp && messages.length" class="scroll-bottom" @click="scrollToBottom">
            <i class="el-icon-bottom"></i> 回到底部
          </button>
        </transition>

        <div class="composer-wrap">
          <div class="composer">
            <textarea
              ref="composerInput"
              v-model="draft"
              rows="1"
              :disabled="!canChat"
              :placeholder="canChat ? '今天帮你做些什么？' : '请先创建 Agent'"
              @input="autoGrow"
              @compositionstart="composing = true"
              @compositionend="composing = false"
              @keydown.enter="onEnter"
            ></textarea>
            <div class="composer-toolbar">
              <button class="workspace-picker" type="button" :title="projectPath || '使用默认工作空间'" @click="chooseWorkspace">
                <i class="el-icon-folder"></i>
                <span>{{ projectPath ? projectName : '选择工作空间' }}</span>
                <i class="el-icon-arrow-down"></i>
              </button>
            </div>
            <button class="send-btn" :class="{ stop: running }" :disabled="!canChat || (!running && !draft.trim())" @click="running ? stop() : send()">
              <i v-if="!running" class="el-icon-top"></i>
              <span v-else class="stop-square"></span>
            </button>
          </div>
        </div>
        <div class="composer-tip">
          AI 生成内容可能不准确，请核对重要信息
        </div>
      </div>
    </main>

    <ConfigDrawer
      :visible="settingsOpen"
      :aid="selectedAid"
      :agents="agents"
      :selected-model-id="selectedModelId"
      @close="settingsOpen = false"
      @select-agent="selectAgent"
      @select-model="selectedModelId = $event"
      @saved="onSettingsSaved"
    />
  </div>
</template>

<script>
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import ConfigDrawer from '../../components/ConfigDrawer.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { postSse } from '../../utils/sse'
import { fetchAgents, upsertAgent } from '../../api/agent'
import { fetchModels } from '../../api/model'
import { stopChat } from '../../api/chat'
import {
  fetchSessionList,
  fetchTopSessionList,
  setSessionTop,
  changeSessionTitle,
  deleteSession,
  fetchSessionMessages,
  resolveSessionTitle
} from '../../api/session'
import { replaySessionMessages } from '../../utils/sessionReplay'
import { RENDER_MODES } from '../../modes'
import { request } from '../../api/client'

const COMPOSER_MAX_HEIGHT = 140
const MODE_KEY = 'agentforge.render.mode'
const PROJECT_PATH_KEY = 'agentforge.projectPath'

export default {
  name: 'ChatWorkbench',
  components: { AgentMessageContent, ConfigDrawer },
  mixins: [chatAutoScroll],
  data: () => ({
    agents: [],
    models: [],
    selectedAid: '',
    currentAgent: null,
    selectedModelId: null,
    selectedAidSessionModel: null,
    topSessions: [],
    normalSessions: [],
    sessionsLoading: false,
    activeSessionId: null,
    sessionId: '',
    sessionTitle: '',
    messages: [],
    draft: '',
    running: false,
    controller: null,
    composing: false,
    mode: localStorage.getItem(MODE_KEY) || 'debug',
    settingsOpen: false,
    backendConnected: false,
    workspace: '',
    projectPath: localStorage.getItem(PROJECT_PATH_KEY) || ''
  }),
  computed: {
    renderModes() {
      return RENDER_MODES
    },
    availableModels() {
      if (!this.currentAgent) return this.models
      const ids = this.currentAgent.modelIds || []
      if (!ids.length) return this.models
      return this.models.filter((model) => ids.includes(model.id))
    },
    canChat() {
      return Boolean(this.selectedAid && this.currentAgent && this.availableModels.length)
    },
    projectName() {
      if (!this.projectPath) return ''
      const parts = this.projectPath.replace(/[\\/]+$/, '').split(/[\\/]/)
      return parts[parts.length - 1] || this.projectPath
    },
    title() {
      return this.sessionTitle || (this.messages.find((item) => item.role === 'user') || {}).text || '新对话'
    },
    welcomeText() {
      const config = (this.currentAgent && this.currentAgent.experConfig) || {}
      return config.welcomeText || '今天想解决什么问题？'
    }
  },
  watch: {
    '$route.query.aid'(value) {
      if (value && value !== this.selectedAid) this.selectAgent(value)
    },
    mode(value) {
      localStorage.setItem(MODE_KEY, value)
    },
    selectedModelId(value) {
      this.watchModelSelection(value)
    }
  },
  created() {
    this.bootstrap()
    this.checkBackend()
  },
  beforeDestroy() {
    if (this.controller) this.controller.abort()
  },
  methods: {
    async bootstrap() {
      try {
        let list = (await fetchAgents()) || []
        if (!list.length) {
          const initialModels = (await fetchModels()) || []
          const modelIds = initialModels.map((model) => model.id)
          await upsertAgent({
            name: 'AgentForge Desktop',
            description: 'AgentForge 默认 AI 助手',
            avatar: '/logo.png',
            type: 1,
            modelIds,
            defaultModelId: modelIds[0] || null,
            systemPrompt: '',
            chatMemoryType: 1,
            chatMemoryMaxMessages: 1000
          })
          list = (await fetchAgents()) || []
        }
        this.agents = list
        const routeAid = this.$route.query.aid
        const saved = localStorage.getItem('agentforge.selectedAid')
        const found = list.find((item) => item.aid === (routeAid || saved)) || list[0]
        if (!found) return
        this.selectedAid = found.aid
        await this.applyAgent(found)
      } catch (error) {
        this.$message.error(error.message || 'Agent 加载失败')
      }
    },
    async checkBackend() {
      try {
        const data = await request('/api/local/health')
        this.backendConnected = Boolean(data && data.ready)
        this.workspace = data.workspace || ''
      } catch (_error) {
        this.backendConnected = false
      }
    },
    async selectAgent(aid) {
      const found = this.agents.find((item) => item.aid === aid)
      if (!found) return
      this.selectedAid = aid
      localStorage.setItem('agentforge.selectedAid', aid)
      this.newChat()
      await this.applyAgent(found)
    },
    async applyAgent(agent) {
      this.currentAgent = agent
      this.models = (await fetchModels()) || []
      this.refreshModelSelection()
      await this.loadSessions()
    },
    refreshModelSelection() {
      const ids = this.availableModels.map((item) => item.id)
      const saved = Number(localStorage.getItem(`agentforge.model.${this.selectedAid}`))
      const preferred = [this.selectedAidSessionModel, saved, this.currentAgent && this.currentAgent.defaultModelId, ids[0]]
      const found = preferred.find((id) => id != null && ids.includes(Number(id)))
      this.selectedModelId = found == null ? null : Number(found)
    },
    watchModelSelection(value) {
      if (value != null && this.selectedAid) {
        localStorage.setItem(`agentforge.model.${this.selectedAid}`, String(value))
      }
    },
    async loadSessions() {
      if (!this.selectedAid) return
      this.sessionsLoading = true
      try {
        const [top, normal] = await Promise.all([
          fetchTopSessionList(this.selectedAid),
          fetchSessionList(this.selectedAid, 30)
        ])
        this.topSessions = top || []
        this.normalSessions = normal || []
      } catch (error) {
        this.$message.error(error.message || '会话列表加载失败')
      } finally {
        this.sessionsLoading = false
      }
    },
    async openSession(item) {
      if (this.running || !item) return
      this.activeSessionId = item.id
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.selectedAidSessionModel = item.modelId
      this.refreshModelSelection()
      try {
        const records = await fetchSessionMessages(item.id)
        this.messages = replaySessionMessages(records)
        this.resetScrollFollow()
        this.scroll()
      } catch (error) {
        this.$message.error(error.message || '会话加载失败')
      }
    },
    newChat() {
      if (this.running) this.stop()
      this.activeSessionId = null
      this.sessionId = ''
      this.sessionTitle = ''
      this.selectedAidSessionModel = null
      this.messages = []
      this.draft = ''
      this.refreshModelSelection()
      this.$nextTick(() => {
        if (this.$refs.composerInput) this.$refs.composerInput.focus()
      })
    },
    formatSessionTime(item) {
      const raw = item.lastMessageAt || item.modifyTime || item.createTime
      if (!raw) return ''
      return String(raw).replace('T', ' ').replace(/\..*$/, '').slice(0, 16)
    },
    async renameSession(item) {
      try {
        const { value } = await this.$prompt('请输入新的会话名称', '重命名会话', {
          inputValue: item.title,
          inputValidator: (val) => (val && val.trim() ? true : '会话名称不能为空')
        })
        const updated = await changeSessionTitle(item.id, value.trim())
        item.title = updated.title
        if (this.activeSessionId === item.id) this.sessionTitle = updated.title
        this.$message.success('已重命名')
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') this.$message.error(error.message || '重命名失败')
      }
    },
    async toggleTop(item, isTop) {
      try {
        await setSessionTop(item.id, isTop)
        await this.loadSessions()
        this.$message.success(isTop ? '已置顶' : '已取消置顶')
      } catch (error) {
        this.$message.error(error.message || '操作失败')
      }
    },
    async removeSession(item) {
      try {
        await this.$confirm('删除后该会话及其消息将不可恢复，是否继续？', '删除会话', { type: 'warning' })
      } catch (_error) {
        return
      }
      try {
        await deleteSession(item.id)
        if (this.activeSessionId === item.id) this.newChat()
        await this.loadSessions()
        this.$message.success('已删除')
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    },
    autoGrow() {
      const el = this.$refs.composerInput
      if (!el) return
      el.style.height = 'auto'
      const height = el.scrollHeight
      el.style.height = Math.min(height, COMPOSER_MAX_HEIGHT) + 'px'
      el.style.overflowY = height > COMPOSER_MAX_HEIGHT ? 'auto' : 'hidden'
    },
    onEnter(event) {
      if (this.composing || event.isComposing || event.keyCode === 229) return
      if (event.shiftKey) return
      event.preventDefault()
      this.send()
    },
    createAnswer() {
      return {
        role: 'assistant',
        status: 'running',
        parts: [],
        content: '',
        error: '',
        startedAt: Date.now(),
        finishedAt: null,
        elapsedMs: null
      }
    },
    async send() {
      const text = this.draft.trim()
      if (!this.canChat) {
        this.$message.warning('请先创建 Agent 并配置模型')
        return
      }
      if (!text || this.running) return
      if (this.selectedModelId == null) {
        this.$message.warning('请先选择模型')
        return
      }
      this.draft = ''
      this.$nextTick(this.autoGrow)
      this.messages.push({ role: 'user', text })
      const answer = this.createAnswer()
      this.messages.push(answer)
      this.running = true
      this.controller = new AbortController()
      this.resetScrollFollow()
      this.scroll()
      try {
        await postSse(
          '/api/chat/stream',
          {
            aid: this.selectedAid,
            sessionId: this.sessionId,
            message: text,
            modelId: this.selectedModelId,
            projectPath: this.projectPath || null
          },
          {
            signal: this.controller.signal,
            onEvent: (event) => this.handleEvent(answer, event, text)
          }
        )
      } catch (error) {
        if (error.name !== 'AbortError') {
          answer.status = 'error'
          answer.error = error.message || '请求失败'
          this.finishParts(answer)
        }
      } finally {
        if (answer.status === 'running') {
          answer.status = 'success'
          this.finishParts(answer)
        }
        this.running = false
        this.controller = null
        this.scroll()
        this.loadSessions()
      }
    },
    handleEvent(answer, event, questionText) {
      switch (event.event) {
        case 'session':
          if (event.content) {
            this.sessionId = event.content
            if (!this.sessionTitle) this.sessionTitle = resolveSessionTitle(questionText)
          }
          break
        case 'think':
          this.applyThink(answer, event)
          break
        case 'resp':
          answer.content += event.content || ''
          break
        case 'tool_call':
          this.closeOpenThink(answer)
          this.applyTool(answer, event)
          break
        case 'resp_end':
          answer.status = 'success'
          this.finishParts(answer)
          break
        case 'error':
          answer.status = 'error'
          answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
          this.finishParts(answer)
          break
        default:
          break
      }
      this.scroll()
    },
    applyThink(answer, event) {
      const last = answer.parts[answer.parts.length - 1]
      const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
      const text = event.content || ''
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
        return
      }
      if (open) {
        if (text) open.content = text
        open.status = 'end'
        open.duration = Date.now() - (open.startedAt || Date.now())
      } else if (text) {
        answer.parts.push({ type: 'think', content: text, status: 'end', startedAt: Date.now(), duration: 0 })
      }
    },
    closeOpenThink(answer) {
      const last = answer.parts[answer.parts.length - 1]
      if (last && last.type === 'think' && last.status === 'thinking') {
        last.status = 'end'
        last.duration = Date.now() - (last.startedAt || Date.now())
      }
    },
    applyTool(answer, event) {
      const content = event.content || {}
      let part = answer.parts.find((item) => item.type === 'tool_card' && item.toolCallId === content.toolCallId)
      if (!part) {
        part = {
          type: 'tool_card',
          toolCallId: content.toolCallId || '',
          toolName: content.toolName || 'unknown',
          description: '',
          inputParams: null,
          outputResult: null,
          duration: null,
          isFinish: false,
          startedAt: Date.now()
        }
        answer.parts.push(part)
      }
      part.toolCallId = content.toolCallId || part.toolCallId
      part.toolName = content.toolName || part.toolName
      if (content.description) part.description = content.description
      if (content.inputParams != null) part.inputParams = content.inputParams
      if (content.outputResult != null) part.outputResult = content.outputResult
      const duration = Number(content.duration)
      if (Number.isFinite(duration) && duration > 0) part.duration = duration
      part.isFinish = !!event.isFinish
      if (part.isFinish && !(part.duration > 0)) {
        part.duration = Math.max(100, Date.now() - (part.startedAt || Date.now()))
      }
    },
    finishParts(answer) {
      answer.parts.forEach((part) => {
        if (part.type === 'think' && part.status === 'thinking') {
          part.status = 'end'
          part.duration = Date.now() - (part.startedAt || Date.now())
        }
        if (part.type === 'tool_card' && !part.isFinish) {
          part.isFinish = true
          if (!(part.duration > 0)) part.duration = Math.max(100, Date.now() - (part.startedAt || Date.now()))
        }
      })
      if (!answer.finishedAt) answer.finishedAt = Date.now()
      if (!answer.elapsedMs) answer.elapsedMs = answer.finishedAt - (answer.startedAt || answer.finishedAt)
    },
    async stop() {
      if (!this.running) return
      if (this.sessionId) stopChat(this.sessionId).catch(() => {})
      if (this.controller) this.controller.abort()
      this.running = false
    },
    onModeChange(value) {
      localStorage.setItem(MODE_KEY, value)
    },
    async onSettingsSaved(savedItem) {
      try {
        const list = (await fetchAgents()) || []
        this.agents = list
        const targetAid = savedItem && savedItem.aid ? savedItem.aid : this.selectedAid
        const found = list.find((item) => item.aid === targetAid) || list[0]
        if (!found) return
        this.selectedAid = found.aid
        this.currentAgent = found
        this.models = (await fetchModels()) || []
        this.refreshModelSelection()
        await this.loadSessions()
        this.checkBackend()
      } catch (error) {
        this.$message.error(error.message || '配置刷新失败')
      }
    },
    async copy(text) {
      if (!text) return
      try {
        await navigator.clipboard.writeText(text)
        this.$message.success('已复制')
      } catch (_error) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    },
    async openDataDir() {
      if (window.electronAPI && window.electronAPI.openPath) {
        await window.electronAPI.openPath(this.workspace)
      } else {
        this.$message.info(this.workspace || '浏览器模式不支持打开目录')
      }
    },
    async chooseWorkspace() {
      if (!window.electronAPI || !window.electronAPI.chooseDirectory) {
        this.$message.info('浏览器模式不支持选择本地目录')
        return
      }
      const selected = await window.electronAPI.chooseDirectory(this.projectPath || this.workspace)
      if (!selected) return
      this.projectPath = selected
      localStorage.setItem(PROJECT_PATH_KEY, selected)
    }
  }
}
</script>
