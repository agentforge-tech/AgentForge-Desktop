<template>
  <div class="workbench">
    <aside class="session-sidebar">
      <div class="session-head">
        <div class="current-agent-summary">
          <div class="current-agent-avatar">
            <img :src="currentAgent && currentAgent.avatar ? currentAgent.avatar : '/logo.png'" :alt="currentAgent ? currentAgent.name : 'AgentForge'" />
          </div>
          <div class="current-agent-info">
            <b>{{ currentAgent ? currentAgent.name : 'AgentForge Desktop' }}</b>
          </div>
        </div>
        <el-button size="small" icon="el-icon-circle-plus-outline" class="new-chat-btn" @click="newChat">新建任务</el-button>
      </div>

      <div class="session-scroll" :class="{ 'has-pinned-panel': pinnedTaskSessions.length }">
        <section v-if="pinnedTaskSessions.length" class="session-list-panel pinned-task-panel" :class="{ 'is-collapsed': !pinnedTasksOpen }">
          <button type="button" class="task-group-title pinned-task-title" @click="pinnedTasksOpen = !pinnedTasksOpen">
            <span>置顶任务 ({{ pinnedTaskSessions.length }})</span>
            <i :class="pinnedTasksOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="pinnedTasksOpen" class="session-panel-body">
            <div v-for="item in pinnedTaskSessions" :key="'pinned-task-' + item.id" class="session-item" :class="{ active: item.id === activeSessionId }" @click="openSession(item)">
              <div class="session-body">
                <span class="session-text" :title="item.title">{{ item.title }}</span>
                <span class="session-time">{{ formatSessionTime(item) }}</span>
              </div>
              <el-dropdown class="session-more" trigger="click" placement="bottom-start" @command="handleSessionCommand($event, item)" @click.native.stop>
                <span class="session-more-trigger" title="更多操作"><i class="el-icon-more"></i></span>
                <el-dropdown-menu slot="dropdown" class="session-action-menu">
                  <el-dropdown-item command="open" icon="el-icon-folder-opened">打开文件夹</el-dropdown-item>
                  <el-dropdown-item command="rename" icon="el-icon-edit">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" icon="el-icon-delete" divided>删除会话</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
              <button type="button" class="session-pin-action" title="取消置顶" @click.stop="toggleTop(item, false)"><i class="el-icon-bottom"></i></button>
            </div>
          </div>
        </section>

        <section class="session-list-panel normal-task-panel">
          <button type="button" class="task-group-title normal-task-title" :class="{ 'has-pinned': pinnedTaskSessions.length }" @click="normalTasksOpen = !normalTasksOpen">
            <span>任务 ({{ normalTaskSessions.length }})</span>
            <i :class="normalTasksOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="normalTasksOpen" class="session-panel-body">
            <template v-for="group in normalTaskGroups">
              <div :key="'normal-task-group-' + group.key" class="session-date-group-title">{{ group.label }}</div>
              <div v-for="item in group.items" :key="'normal-task-' + item.id" class="session-item" :class="{ active: item.id === activeSessionId }" @click="openSession(item)">
              <div class="session-body">
                <span class="session-text" :title="item.title">{{ item.title }}</span>
                <span class="session-time">{{ formatSessionTime(item) }}</span>
              </div>
              <el-dropdown class="session-more" trigger="click" placement="bottom-start" @command="handleSessionCommand($event, item)" @click.native.stop>
                <span class="session-more-trigger" title="更多操作"><i class="el-icon-more"></i></span>
                <el-dropdown-menu slot="dropdown" class="session-action-menu">
                  <el-dropdown-item command="open" icon="el-icon-folder-opened">打开文件夹</el-dropdown-item>
                  <el-dropdown-item command="rename" icon="el-icon-edit">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" icon="el-icon-delete" divided>删除会话</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
              <button type="button" class="session-pin-action" title="置顶" @click.stop="toggleTop(item, true)"><i class="el-icon-top"></i></button>
              </div>
            </template>
            <div v-if="!normalTaskSessions.length" class="session-empty">{{ sessionsLoading ? '加载中…' : '暂无任务' }}</div>
          </div>
        </section>

        <section class="session-list-panel workspace-session-section" :class="{ 'is-collapsed': !workspaceOpen }">
          <button type="button" class="task-group-title workspace-panel-title" @click="workspaceOpen = !workspaceOpen">
            <span>工作空间 ({{ workspaceGroups.length }})</span>
            <i :class="workspaceOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="workspaceOpen" class="session-panel-body">
          <div v-for="group in workspaceGroups" :key="'workspace-' + group.id" class="workspace-tree-group">
            <div class="workspace-tree-head" role="button" tabindex="0" @click="toggleWorkspaceGroup(group.id)" @keydown.enter="toggleWorkspaceGroup(group.id)">
              <i class="el-icon-folder"></i>
              <span class="workspace-tree-name" :title="group.folderPath">{{ group.name }}</span>
              <el-dropdown class="workspace-more" trigger="click" placement="bottom-start" @command="handleWorkspaceCommand($event, group)" @click.native.stop>
                <span class="workspace-more-trigger" title="更多操作"><i class="el-icon-more"></i></span>
                <el-dropdown-menu slot="dropdown" class="workspace-action-menu">
                  <el-dropdown-item command="open" icon="el-icon-folder-opened">打开文件夹</el-dropdown-item>
                  <el-dropdown-item command="rename" icon="el-icon-edit">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" icon="el-icon-delete" divided>删除空间</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
              <i :class="expandedWorkspaceIds.includes(group.id) ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
            </div>
            <div v-show="expandedWorkspaceIds.includes(group.id)" class="workspace-tree-sessions">
              <div
                v-for="item in group.sessions"
                :key="item.id"
                class="session-item workspace-session-item"
                :class="{ active: item.id === activeSessionId }"
                @click="openSession(item)"
              >
                <div class="session-body">
                  <span class="session-text" :title="item.title">{{ item.title }}</span>
                  <span class="session-time">{{ formatSessionTime(item) }}</span>
                </div>
                <el-dropdown class="session-more" trigger="click" placement="bottom-start" @command="handleSessionCommand($event, item)" @click.native.stop>
                  <span class="session-more-trigger" title="更多操作"><i class="el-icon-more"></i></span>
                  <el-dropdown-menu slot="dropdown" class="session-action-menu">
                    <el-dropdown-item command="open" icon="el-icon-folder-opened">打开文件夹</el-dropdown-item>
                    <el-dropdown-item command="rename" icon="el-icon-edit">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" icon="el-icon-delete" divided>删除会话</el-dropdown-item>
                  </el-dropdown-menu>
                </el-dropdown>
                <button
                  type="button"
                  class="session-pin-action"
                  :title="item.topOrder > 0 ? '取消置顶' : '置顶'"
                  @click.stop="toggleTop(item, !(item.topOrder > 0))"
                ><i :class="item.topOrder > 0 ? 'el-icon-bottom' : 'el-icon-top'"></i></button>
              </div>
              <div v-if="!group.sessions.length" class="workspace-tree-empty">暂无会话</div>
            </div>
          </div>
          <div v-if="!workspaceGroups.length" class="session-empty">暂无工作空间</div>
          </div>
        </section>
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
          <div v-if="pendingAppends.length" class="append-queue" aria-live="polite">
            <div class="append-queue-head">
              <span class="append-queue-status"><i></i>后续指令</span>
              <span class="append-queue-hint">{{ pendingAppends.length }} 条等待插入</span>
            </div>
            <transition-group name="append-list" tag="div" class="append-queue-list">
              <div v-for="(item, index) in pendingAppends" :key="item.appendId" class="append-pill">
                <span class="append-pill-index">↳</span>
                <span class="append-pill-content" :title="item.content">{{ item.content }}</span>
                <span class="append-pill-waiting">第 {{ index + 1 }} 位</span>
                <button type="button" title="取消追加" @click="cancelAppend(item.appendId)"><i class="el-icon-close"></i></button>
              </div>
            </transition-group>
          </div>
          <InterruptCard
            v-if="activeInterrupt"
            class="desktop-composer-interrupt"
            :part="activeInterrupt"
            compact
            @submit="submitInterrupt"
          />
          <div class="composer">
            <textarea
              ref="composerInput"
              v-model="draft"
              rows="1"
              :disabled="!canChat || !!activeInterrupt"
              :placeholder="composerPlaceholder"
              @input="autoGrow"
              @compositionstart="composing = true"
              @compositionend="composing = false"
              @keydown.enter="onEnter"
            ></textarea>
            <div class="composer-toolbar">
              <WorkspacePicker
                :workspaces="workspaces"
                :selected-id="selectedWorkspaceId"
                :locked="!!sessionId"
                @select="selectWorkspace"
                @clear="clearWorkspace"
                @created="onWorkspaceCreated"
              />
              <div class="composer-model-control">
                <img v-if="selectedModelLogo" class="composer-selected-model-logo" :src="selectedModelLogo" :alt="selectedModel ? selectedModel.name : '模型'" />
                <i v-else class="el-icon-cpu composer-selected-model-fallback"></i>
                <el-select
                  v-model="selectedModelId"
                  class="composer-model-select"
                  size="mini"
                  popper-class="composer-model-popper"
                  :disabled="running || !!activeInterrupt"
                  placeholder="选择模型"
                  title="当前对话模型"
                >
                <el-option
                  v-for="model in availableModels"
                  :key="model.id"
                  :label="model.modelName || model.name"
                  :value="model.id"
                >
                  <div class="composer-model-option">
                    <span class="composer-model-option-icon">
                      <img v-if="modelLogo(model)" :src="modelLogo(model)" :alt="model.name" />
                      <i v-else class="el-icon-cpu"></i>
                    </span>
                    <span class="composer-model-option-text">
                      <b>{{ model.name || model.modelName }}</b>
                      <small v-if="model.modelName && model.modelName !== model.name">{{ model.modelName }}</small>
                    </span>
                    <i v-if="model.id === selectedModelId" class="el-icon-check composer-model-option-check"></i>
                  </div>
                </el-option>
                </el-select>
              </div>
            </div>
            <button
              class="send-btn"
              :class="{ stop: running && !draft.trim() }"
              :disabled="!canChat || !!activeInterrupt || (!running && !draft.trim())"
              :title="primaryActionTitle"
              @click="onPrimaryAction"
            >
              <i v-if="!running || draft.trim()" class="el-icon-top"></i>
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
      @close="settingsOpen = false"
      @select-agent="selectAgent"
      @saved="onSettingsSaved"
    />
  </div>
</template>

<script>
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import ConfigDrawer from '../../components/ConfigDrawer.vue'
import InterruptCard from '../../components/InterruptCard.vue'
import WorkspacePicker from '../../components/WorkspacePicker.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { postSse } from '../../utils/sse'
import { fetchAgents, upsertAgent } from '../../api/agent'
import { fetchModels } from '../../api/model'
import { stopChat, resumeChatInterrupt, appendChatMessage, cancelChatAppend } from '../../api/chat'
import { reduceInterruptEvent, reduceInterruptAnswers } from '../../utils/interruptEventReducer'
import { reduceAppendEvent } from '../../utils/appendEventReducer'
import {
  appendResponseSegment,
  closeOpenResponseSegment,
  finishResponseSegments,
  promoteLastResponseSegment,
  shouldAppendResponseSegment
} from '../../utils/responseSegmentReducer'
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
import { deleteWorkspace, fetchWorkspaces, renameWorkspace } from '../../api/workspace'

const COMPOSER_MAX_HEIGHT = 140
const MODE_KEY = 'agentforge.render.mode'

function parseSessionDate(item) {
  const raw = item.lastMessageAt || item.modifyTime || item.createTime
  if (!raw) return null
  const date = new Date(String(raw).replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? null : date
}

export default {
  name: 'ChatWorkbench',
  components: { AgentMessageContent, ConfigDrawer, InterruptCard, WorkspacePicker },
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
    stopping: false,
    controller: null,
    composing: false,
    mode: localStorage.getItem(MODE_KEY) || 'debug',
    settingsOpen: false,
    backendConnected: false,
    workspace: '',
    workspaces: [],
    selectedWorkspaceId: null,
    expandedWorkspaceIds: [],
    workspaceOpen: true,
    pinnedTasksOpen: true,
    normalTasksOpen: true,
    pendingAppends: [],
    deferredAppends: []
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
    selectedModel() {
      return this.availableModels.find((model) => model.id === this.selectedModelId) || null
    },
    selectedModelLogo() {
      return this.modelLogo(this.selectedModel)
    },
    canChat() {
      return Boolean(this.selectedAid && this.currentAgent && this.availableModels.length)
    },
    pinnedTaskSessions() {
      return this.topSessions.filter((item) => item.workspaceId == null)
    },
    normalTaskSessions() {
      return this.normalSessions.filter((item) => item.workspaceId == null)
    },
    normalTaskGroups() {
      const now = new Date()
      const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
      const yesterdayStart = todayStart - 24 * 60 * 60 * 1000
      const groups = [
        { key: 'today', label: '今日', items: [] },
        { key: 'yesterday', label: '昨日', items: [] },
        { key: 'earlier', label: '更早', items: [] }
      ]
      this.normalTaskSessions.forEach((item) => {
        const date = parseSessionDate(item)
        const time = date ? date.getTime() : 0
        if (time >= todayStart) groups[0].items.push(item)
        else if (time >= yesterdayStart) groups[1].items.push(item)
        else groups[2].items.push(item)
      })
      return groups.filter((group) => group.items.length)
    },
    workspaceGroups() {
      return this.workspaces.map((workspace) => ({
        ...workspace,
        sessions: [...this.topSessions, ...this.normalSessions].filter((item) => item.workspaceId === workspace.id)
      }))
    },
    composerPlaceholder() {
      if (!this.canChat) return '请先创建 Agent'
      if (!this.running) return '今天帮你做些什么？'
      return this.activeInterrupt ? '请先处理上方确认项' : '输入补充指令，将在下一轮处理…'
    },
    primaryActionTitle() {
      if (this.stopping) return '停止中…'
      if (this.running && this.draft.trim()) return '发送补充指令'
      if (this.running) return '停止'
      return '发送'
    },
    activeInterrupt() {
      for (let index = this.messages.length - 1; index >= 0; index -= 1) {
        const message = this.messages[index]
        if (!message || message.role !== 'assistant') continue
        const part = (message.parts || []).find(
          (item) => item.type === 'interrupt_card' && item.status === 'PENDING'
        )
        if (part) return part
      }
      return null
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
    modelLogo(model) {
      const identity = [model && model.name, model && model.modelName].filter(Boolean).join(' ').toLowerCase()
      return identity.includes('deepseek') ? '/model-logos/deepseek.svg' : ''
    },
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
      await Promise.all([this.loadSessions(), this.loadWorkspaces()])
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
    async loadWorkspaces() {
      try {
        this.workspaces = (await fetchWorkspaces()) || []
        const ids = this.workspaces.map((item) => item.id)
        this.expandedWorkspaceIds = this.expandedWorkspaceIds.filter((id) => ids.includes(id))
      } catch (error) {
        this.$message.error(error.message || '工作空间加载失败')
      }
    },
    async openSession(item) {
      if (this.running || !item) return
      this.activeSessionId = item.id
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.selectedAidSessionModel = item.modelId
      this.selectedWorkspaceId = item.workspaceId == null ? null : item.workspaceId
      this.refreshModelSelection()
      try {
        const records = await fetchSessionMessages(item.id)
        this.messages = replaySessionMessages(records)
        const lastAssistant = [...this.messages].reverse().find((message) => message.role === 'assistant')
        this.running = Boolean(lastAssistant && (lastAssistant.parts || []).some(
          (part) => part.type === 'interrupt_card' && part.status === 'PENDING'
        ))
        this.pendingAppends = []
        this.deferredAppends = []
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
      this.selectedWorkspaceId = null
      this.messages = []
      this.draft = ''
      this.pendingAppends = []
      this.deferredAppends = []
      this.running = false
      this.controller = null
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
    async handleSessionCommand(command, item) {
      if (command === 'rename') {
        await this.renameSession(item)
        return
      }
      if (command === 'open') {
        await this.openSessionFolder(item)
        return
      }
      if (command === 'delete') await this.removeSession(item)
    },
    async openSessionFolder(item) {
      const root = String(this.workspace || '').replace(/[\\/]$/, '')
      const boundWorkspace = item.workspaceId == null
        ? null
        : this.workspaces.find((workspace) => workspace.id === item.workspaceId)
      const target = item.workspacePath || (boundWorkspace && boundWorkspace.folderPath) || (root ? `${root}/workspace` : '')
      if (window.electronAPI && window.electronAPI.openPath) {
        const error = await window.electronAPI.openPath(target)
        if (error) this.$message.error(error)
      } else {
        this.$message.info(target || '浏览器模式不支持打开目录')
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
      if (this.running && this.draft.trim()) {
        this.sendAppend()
        return
      }
      this.send()
    },
    onPrimaryAction() {
      if (this.stopping) return
      if (this.running && !this.draft.trim()) {
        this.stop()
        return
      }
      if (this.running) {
        this.sendAppend()
        return
      }
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
      if (this.running) {
        await this.sendAppend()
        return
      }
      if (!text) return
      if (this.activeInterrupt) {
        this.$message.warning('请先处理上方的确认项')
        return
      }
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
            workspaceId: this.selectedWorkspaceId,
            projectPath: null
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
          promoteLastResponseSegment(answer)
          answer.status = 'success'
          this.finishParts(answer)
        }
        const waiting = answer.status === 'waiting'
        this.running = waiting
        this.controller = null
        if (!waiting) this.restoreDeferredAppends()
        this.scroll()
        if (!waiting) this.loadSessions()
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
          closeOpenResponseSegment(answer)
          this.applyThink(answer, event)
          break
        case 'resp':
          if (shouldAppendResponseSegment(answer, event)) {
            this.closeOpenThink(answer)
            appendResponseSegment(answer, event)
          }
          break
        case 'tool_call':
          closeOpenResponseSegment(answer)
          this.closeOpenThink(answer)
          this.applyTool(answer, event)
          reduceInterruptAnswers(answer, event)
          break
        case 'interrupt':
          closeOpenResponseSegment(answer)
          this.closeOpenThink(answer)
          this.applyInterrupt(answer, event)
          break
        case 'user_message':
          closeOpenResponseSegment(answer)
          reduceAppendEvent(this, answer, event)
          break
        case 'resp_end':
          if (answer.status !== 'cancelled') {
            promoteLastResponseSegment(answer)
            answer.status = 'success'
            this.finishParts(answer)
          }
          break
        case 'error':
          if (answer.status === 'cancelled') break
          finishResponseSegments(answer)
          answer.status = 'error'
          answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
          this.finishParts(answer)
          break
        default:
          break
      }
      this.scroll()
    },
    applyInterrupt(answer, event) {
      const part = reduceInterruptEvent(answer, event)
      if (event.type === 'interrupt_requested') {
        answer.status = 'waiting'
        this.running = true
      } else if (event.type === 'interrupt_resuming' || event.type === 'interrupt_resumed') {
        answer.status = 'running'
      } else if (event.type === 'interrupt_resume_failed') {
        answer.status = 'error'
        answer.error = (event.content && event.content.error) || '中断恢复失败'
        this.finishParts(answer)
      } else if (event.type === 'interrupt_cancelled') {
        answer.status = 'cancelled'
        this.finishParts(answer)
      }
      return part
    },
    applyThink(answer, event) {
      const last = answer.parts[answer.parts.length - 1]
      const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
      const text = event.content || ''
      if (!String(text).trim() && !open) return
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
        return
      }
      if (open) {
        if (String(text).trim()) open.content = text
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
      finishResponseSegments(answer)
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
    async submitInterrupt(payload) {
      if (!payload || !payload.batchId) return
      const answer = [...this.messages].reverse().find((message) => message.role === 'assistant')
      if (!answer) return
      const part = payload.part || (answer.parts || []).find((item) => item.type === 'interrupt_card' && item.batchId === payload.batchId)
      if (part) part.status = 'RESUMING'
      answer.status = 'running'
      this.running = true
      this.controller = new AbortController()
      try {
        await resumeChatInterrupt(
          { batchId: payload.batchId, responses: payload.responses },
          {
            signal: this.controller.signal,
            onEvent: (event) => this.handleEvent(answer, event)
          }
        )
      } catch (error) {
        if (error.name !== 'AbortError') {
          if (part) part.status = 'PENDING'
          answer.status = 'error'
          answer.error = error.message || '中断恢复失败'
          this.finishParts(answer)
        }
      } finally {
        if (answer.status === 'running') {
          promoteLastResponseSegment(answer)
          answer.status = 'success'
          this.finishParts(answer)
        }
        const waiting = answer.status === 'waiting'
        this.running = waiting
        this.controller = null
        if (!waiting) this.restoreDeferredAppends()
        this.scroll()
        if (!waiting) this.loadSessions()
      }
    },
    async sendAppend() {
      if (this.stopping) return
      const text = this.draft.trim()
      if (!text || !this.sessionId) {
        if (!this.sessionId) this.$message.warning('请先发送一轮对话后再追加指令')
        return
      }
      if (this.activeInterrupt) {
        this.$message.warning('请先处理上方的确认项')
        return
      }
      try {
        const result = await appendChatMessage({
          aid: this.selectedAid,
          sessionId: this.sessionId,
          message: text
        })
        if (!result || !result.queued) {
          this.$message.warning((result && result.reason) || '追加失败')
          return
        }
        this.draft = ''
        this.$nextTick(this.autoGrow)
        if (!this.controller) {
          this.pendingAppends.push({
            appendId: result.appendId,
            content: text,
            queuePosition: result.queuePosition
          })
        }
      } catch (error) {
        this.$message.error(error.message || '追加消息失败')
      }
    },
    async cancelAppend(appendId) {
      try {
        const result = await cancelChatAppend({
          aid: this.selectedAid,
          sessionId: this.sessionId,
          appendId
        })
        if (!result || !result.canceled) {
          this.$message.info('该指令已经生效或本轮已经结束')
        }
        this.pendingAppends = this.pendingAppends.filter((item) => item.appendId !== appendId)
      } catch (_error) {
        this.$message.error('取消追加失败')
      }
    },
    restoreDeferredAppends() {
      if (!this.deferredAppends.length) return
      const text = this.deferredAppends.splice(0).map((item) => item.content).filter(Boolean).join('\n\n')
      if (!text) return
      this.draft = this.draft.trim() ? this.draft + '\n\n' + text : text
      this.$nextTick(this.autoGrow)
      this.$message.info('未赶上本轮的补充内容已放回输入框')
    },
    async stop() {
      if (!this.running || this.stopping) return
      if (!this.sessionId) {
        this.$message.warning('本轮尚未建立会话，暂时无法停止')
        return
      }
      this.stopping = true
      const answer = [...this.messages].reverse().find((message) => message.role === 'assistant')
      try {
        const result = await stopChat({ sessionId: this.sessionId })
        const statusName = result && result.statusName
        if (!statusName) {
          this.$message.warning('当前没有可停止的任务')
          return
        }
        this.pendingAppends = []
        if (answer) {
          ;(answer.parts || []).forEach((part) => {
            if (part.type === 'interrupt_card' && (part.status === 'PENDING' || part.status === 'RESUMING')) {
              part.status = 'CANCELLED'
            }
          })
        }
        if (statusName === 'CANCELLED') {
          if (answer) {
            finishResponseSegments(answer)
            answer.status = 'cancelled'
            answer.error = '用户已停止'
            this.finishParts(answer)
          }
          this.running = false
        } else if (statusName === 'TIMEOUT_CANCELLED') {
          if (this.controller) this.controller.abort()
          if (answer) {
            finishResponseSegments(answer)
            answer.status = 'cancelled'
            answer.error = '停止超时，任务已强制收口'
            this.finishParts(answer)
          }
          this.running = false
          this.controller = null
        } else if (statusName === 'SUCCEEDED') {
          if (answer) {
            promoteLastResponseSegment(answer)
            answer.status = 'success'
            this.finishParts(answer)
          }
          this.running = false
        } else if (statusName === 'FAILED') {
          if (answer) {
            finishResponseSegments(answer)
            answer.status = 'error'
            answer.error = '生成失败'
            this.finishParts(answer)
          }
          this.running = false
        }
      } catch (error) {
        this.$message.error(error.message || '停止失败')
      } finally {
        this.stopping = false
      }
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
    selectWorkspace(item) {
      if (this.sessionId || !item) return
      this.selectedWorkspaceId = item.id
    },
    clearWorkspace() {
      if (this.sessionId) return
      this.selectedWorkspaceId = null
    },
    onWorkspaceCreated(item) {
      if (!item) return
      const index = this.workspaces.findIndex((workspace) => workspace.id === item.id)
      if (index >= 0) this.$set(this.workspaces, index, item)
      else this.workspaces.unshift(item)
      this.selectWorkspace(item)
      if (!this.expandedWorkspaceIds.includes(item.id)) this.expandedWorkspaceIds.push(item.id)
    },
    toggleWorkspaceGroup(id) {
      const index = this.expandedWorkspaceIds.indexOf(id)
      if (index >= 0) this.expandedWorkspaceIds.splice(index, 1)
      else this.expandedWorkspaceIds.push(id)
    },
    async handleWorkspaceCommand(command, workspace) {
      if (command === 'open') {
        if (window.electronAPI && window.electronAPI.openPath) {
          const error = await window.electronAPI.openPath(workspace.folderPath)
          if (error) this.$message.error(error)
        } else {
          this.$message.info(workspace.folderPath || '浏览器模式不支持打开目录')
        }
        return
      }
      if (command === 'rename') {
        try {
          const { value } = await this.$prompt('请输入新的工作空间名称', '重命名', {
            inputValue: workspace.name,
            inputValidator: (val) => (val && val.trim() ? true : '工作空间名称不能为空')
          })
          const updated = await renameWorkspace(workspace.id, value.trim())
          Object.assign(workspace, updated)
          this.$message.success('已重命名')
        } catch (error) {
          if (error !== 'cancel' && error !== 'close') this.$message.error(error.message || '重命名失败')
        }
        return
      }
      if (command === 'delete') {
        try {
          await this.$confirm('删除后工作空间内的会话将回到任务列表，本地文件不会被删除。是否继续？', '删除空间', { type: 'warning' })
          await deleteWorkspace(workspace.id)
          if (this.selectedWorkspaceId === workspace.id) this.selectedWorkspaceId = null
          await Promise.all([this.loadWorkspaces(), this.loadSessions()])
          this.$message.success('已删除')
        } catch (error) {
          if (error !== 'cancel' && error !== 'close') this.$message.error(error.message || '删除失败')
        }
      }
    }
  }
}
</script>
