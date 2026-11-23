<template>
  <div class="workbench">
    <aside class="session-sidebar">
      <div class="session-head">
        <div class="current-agent-summary">
          <div class="current-agent-avatar">
            <img v-if="currentAgentAvatar" :src="currentAgentAvatar" :alt="currentAgent ? currentAgent.name : 'AgentForge'" />
            <svg v-else class="agent-brand-mark" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 3l2.4 5.7L20 11l-5.6 2.3L12 19l-2.4-5.7L4 11l5.6-2.3L12 3z" />
            </svg>
          </div>
          <div class="current-agent-info">
            <b>{{ currentAgent ? currentAgent.name : 'AgentForge Desktop' }}</b>
          </div>
        </div>
        <el-button size="small" class="new-chat-btn" :class="{ active: !skillManagerOpen }" @click="newChat">
          <svg class="sidebar-action-icon" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="10" r="7.25" />
            <path d="M10 6.5v7M6.5 10h7" />
          </svg>
          新建任务
        </el-button>
        <el-button size="small" class="skill-manager-btn" :class="{ active: skillManagerOpen }" @click="openSkillManager">
          <svg class="sidebar-action-icon" viewBox="0 0 20 20" aria-hidden="true">
            <path d="m11.75 2.75.64 2.1a3.7 3.7 0 0 0 2.46 2.46l2.1.64-2.1.64a3.7 3.7 0 0 0-2.46 2.46l-.64 2.1-.64-2.1a3.7 3.7 0 0 0-2.46-2.46l-2.1-.64 2.1-.64a3.7 3.7 0 0 0 2.46-2.46l.64-2.1Z" />
            <path d="m5.15 11.75.36 1.19c.2.68.74 1.21 1.41 1.42l1.18.36-1.18.36c-.67.2-1.2.74-1.41 1.41l-.36 1.18-.36-1.18a2.12 2.12 0 0 0-1.41-1.41l-1.18-.36 1.18-.36c.67-.2 1.2-.74 1.41-1.42l.36-1.19Z" />
          </svg>
          技能管理
        </el-button>
      </div>

      <div class="session-scroll" :class="{ 'has-pinned-panel': pinnedTaskSessions.length, 'workspace-groups-collapsed': expandedWorkspaceIds.length === 0, 'tasks-collapsed': !normalTasksOpen }">
        <section v-if="pinnedTaskSessions.length" class="session-list-panel pinned-task-panel" :class="{ 'is-collapsed': !pinnedTasksOpen }">
          <button type="button" class="task-group-title pinned-task-title" @click="pinnedTasksOpen = !pinnedTasksOpen">
            <span>置顶任务 ({{ pinnedTaskSessions.length }})</span>
            <i :class="pinnedTasksOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="pinnedTasksOpen" class="session-panel-body">
            <div v-for="item in pinnedTaskSessions" :key="'pinned-task-' + item.id" class="session-item" :class="{ active: item.id === activeSessionId }" @click="openSession(item)">
              <div class="session-body">
                <span class="session-text" :title="item.title">{{ item.title }}</span>
                <i v-if="isSessionRunning(item)" class="el-icon-loading session-running-icon" title="任务运行中" aria-label="任务运行中"></i>
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

        <section class="session-list-panel normal-task-panel" :class="{ 'is-collapsed': !normalTasksOpen }">
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
                <i v-if="isSessionRunning(item)" class="el-icon-loading session-running-icon" title="任务运行中" aria-label="任务运行中"></i>
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

        <section class="session-list-panel workspace-session-section" :class="{ 'is-collapsed': !workspaceOpen, 'has-expanded-workspace': workspaceOpen && expandedWorkspaceIds.length > 0 }">
          <button type="button" class="task-group-title workspace-panel-title" @click="workspaceOpen = !workspaceOpen">
            <span>工作空间 ({{ workspaceGroups.length }})</span>
            <i :class="workspaceOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="workspaceOpen" class="session-panel-body">
          <div v-for="group in workspaceGroups" :key="'workspace-' + group.id" class="workspace-tree-group">
            <div class="workspace-tree-head" role="button" tabindex="0" @click="toggleWorkspaceGroup(group.id)" @keydown.enter="toggleWorkspaceGroup(group.id)">
              <i class="el-icon-folder"></i>
              <span class="workspace-tree-name" :title="group.folderPath">{{ group.name }}</span>
              <i class="workspace-tree-chevron" :class="expandedWorkspaceIds.includes(group.id) ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
              <el-dropdown class="workspace-more" trigger="click" placement="bottom-start" @command="handleWorkspaceCommand($event, group)" @click.native.stop>
                <span class="workspace-more-trigger" title="更多操作"><i class="el-icon-more"></i></span>
                <el-dropdown-menu slot="dropdown" class="workspace-action-menu">
                  <el-dropdown-item command="open" icon="el-icon-folder-opened">打开文件夹</el-dropdown-item>
                  <el-dropdown-item command="rename" icon="el-icon-edit">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" icon="el-icon-delete" divided>删除空间</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
              <button
                type="button"
                class="workspace-new-chat"
                title="在此项目空间新建会话"
                aria-label="在此项目空间新建会话"
                @click.stop="startWorkspaceChat(group)"
                @keydown.stop
              >
                <svg class="workspace-new-chat-icon" viewBox="0 0 16 16" aria-hidden="true">
                  <circle cx="8" cy="8" r="6.25" />
                  <path d="M8 5.25v5.5M5.25 8h5.5" />
                </svg>
              </button>
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
                  <i v-if="isSessionRunning(item)" class="el-icon-loading session-running-icon" title="任务运行中" aria-label="任务运行中"></i>
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

    <SkillManager v-if="skillManagerOpen" />
    <main v-else class="chat-main">
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
                <div class="agent-debug-user-bubble">
                  <UserMessageContent
                    :text="message.text"
                    :raw-text="message.rawText"
                    :resources="message.resources"
                    @preview="previewComposerResource"
                  />
                </div>
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
                  <img v-if="currentAgentAvatar" :src="currentAgentAvatar" :alt="currentAgent.name || 'AgentForge'" />
                  <svg v-else class="agent-brand-mark" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M12 3l2.4 5.7L20 11l-5.6 2.3L12 19l-2.4-5.7L4 11l5.6-2.3L12 3z" />
                  </svg>
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
          <div class="composer-surface" :class="{ 'is-history': hasComposerHistory }">
          <div class="composer" @dragover.prevent @drop.prevent="handleComposerDrop">
            <ComposerInput
              ref="composerInput"
              :value="draft"
              :disabled="!canChat || !!activeInterrupt"
              :placeholder="composerPlaceholder"
              :resources="composerResources"
              @input="onComposerInput"
              @enter="onEnter"
              @remove-resource="removeComposerResource"
              @preview-resource="previewComposerResource"
              @paste-files="handleComposerPaste"
            />
            <div class="composer-toolbar">
              <ComposerAddMenu
                :disabled="!canChat"
                @capture-selection="rememberComposerSelection"
                @pick-files="chooseComposerResources('files')"
                @pick-folder="chooseComposerResources('folder')"
              />
              <div class="composer-model-control">
                <div class="composer-model-display" aria-hidden="true">
                  <img v-if="selectedModelLogo" class="composer-selected-model-logo" :src="selectedModelLogo" :alt="selectedModel ? selectedModel.name : '模型'" />
                  <i v-else class="el-icon-cpu composer-selected-model-fallback"></i>
                  <span class="composer-model-display-name">{{ selectedModelName || '选择模型' }}</span>
                  <i class="el-icon-arrow-down composer-model-display-arrow"></i>
                </div>
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
              :class="{ stop: running && !draft.trim() && !composerResources.length }"
              :disabled="!canChat || !!activeInterrupt || (!running && !draft.trim() && !composerResources.length)"
              :title="primaryActionTitle"
              @click="onPrimaryAction"
            >
              <svg v-if="!running || draft.trim() || composerResources.length" class="send-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 19V5M5 12l7-7 7 7" />
              </svg>
              <span v-else class="stop-square"></span>
            </button>
          </div>
          <div v-if="showComposerWorkspace" class="composer-workspace-row">
            <WorkspacePicker
              :workspaces="workspaces"
              :selected-id="selectedWorkspaceId"
              :locked="!!sessionId"
              @select="selectWorkspace"
              @clear="clearWorkspace"
              @created="onWorkspaceCreated"
            />
          </div>
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
import SkillManager from '../../components/SkillManager.vue'
import ComposerInput from '../../components/ComposerInput.vue'
import ComposerAddMenu from '../../components/ComposerAddMenu.vue'
import ComposerResourceChip from '../../components/ComposerResourceChip.vue'
import UserMessageContent from '../../components/UserMessageContent.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { fetchAgents, upsertAgent } from '../../api/agent'
import { fetchModels } from '../../api/model'
import {
  stopChat,
  submitChatTask,
  streamChatTask,
  resumeChatInterrupt,
  appendChatMessage,
  cancelChatAppend
} from '../../api/chat'
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
  fetchSessionDetail,
  updateSessionContext,
  resolveSessionTitle
} from '../../api/session'
import { replaySessionMessages } from '../../utils/sessionReplay'
import { RENDER_MODES } from '../../modes'
import { request } from '../../api/client'
import { deleteWorkspace, fetchWorkspaces, renameWorkspace } from '../../api/workspace'
import agentLogo from '../../assets/agent-logo.png'
import deepseekLogo from '../../assets/deepseek.svg'
import openaiLogo from '../../assets/openai.svg'

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
  components: { AgentMessageContent, ConfigDrawer, InterruptCard, WorkspacePicker, SkillManager, ComposerInput, ComposerAddMenu, ComposerResourceChip, UserMessageContent },
  mixins: [chatAutoScroll],
  data: () => ({
    brandLogo: agentLogo,
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
    composerResources: [],
    running: false,
    stopping: false,
    controller: null,
    composing: false,
    sessionLoadVersion: 0,
    sessionRefreshTimer: null,
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
    skillManagerOpen: false,
    pendingAppends: [],
    deferredAppends: [],
    sessionContextTimer: null,
    pendingSessionContext: null,
    contextSyncPaused: false
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
    selectedModelName() {
      const model = this.selectedModel
      return model ? (model.modelName || model.name || '') : ''
    },
    currentAgentAvatar() {
      const avatar = this.currentAgent && this.currentAgent.avatar
      if (!avatar || avatar === '/logo.png') return this.brandLogo
      return avatar
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
    hasComposerHistory() {
      return this.messages.length > 0
    },
    showComposerWorkspace() {
      // 新建会话始终展示，方便选择工作空间；已有会话仅在工作空间会话中保留。
      if (!this.hasComposerHistory) return true
      return this.selectedWorkspaceId != null
    },
    composerPlaceholder() {
      if (!this.canChat) return '请先创建 Agent'
      if (!this.running) return '今天帮你做些什么？'
      return this.activeInterrupt ? '请先处理上方确认项' : '输入补充指令，将在下一轮处理…'
    },
    primaryActionTitle() {
      if (this.stopping) return '停止中…'
      if (this.running && (this.draft.trim() || this.composerResources.length)) return '发送补充指令'
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
    },
    draft() {
      this.scheduleSessionContextUpdate()
    },
    composerResources: {
      deep: true,
      handler() {
        this.scheduleSessionContextUpdate()
      }
    },
    selectedWorkspaceId() {
      this.scheduleSessionContextUpdate()
    }
  },
  created() {
    this.bootstrap()
    this.checkBackend()
    this.scheduleSessionRefresh()
  },
  beforeDestroy() {
    if (this.sessionRefreshTimer) clearTimeout(this.sessionRefreshTimer)
    this.flushSessionContextUpdate()
    if (this.controller) this.controller.abort()
  },
  methods: {
    modelLogo(model) {
      const identity = [model && model.name, model && model.modelName].filter(Boolean).join(' ').toLowerCase()
      if (identity.includes('gpt') || identity.includes('openai')) return openaiLogo
      return identity.includes('deepseek') ? deepseekLogo : ''
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
            avatar: '',
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
      this.scheduleSessionContextUpdate()
    },
    scheduleSessionRefresh() {
      this.sessionRefreshTimer = setTimeout(async () => {
        if (this.selectedAid && !this.sessionsLoading) await this.loadSessions({ silent: true })
        if (this._isBeingDestroyed || this._isDestroyed) return
        this.scheduleSessionRefresh()
      }, 5000)
    },
    async loadSessions({ silent = false } = {}) {
      if (!this.selectedAid || this.sessionsLoading) return
      const aid = this.selectedAid
      this.sessionsLoading = true
      try {
        const [top, normal] = await Promise.all([
          fetchTopSessionList(aid),
          fetchSessionList(aid, 30)
        ])
        if (aid !== this.selectedAid) return
        this.topSessions = top || []
        this.normalSessions = normal || []
      } catch (error) {
        if (!silent && aid === this.selectedAid) this.$message.error(error.message || '会话列表加载失败')
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
      if (!item) return
      this.flushSessionContextUpdate()
      this.contextSyncPaused = true
      const version = ++this.sessionLoadVersion
      if (this.controller) {
        this.controller.abort()
        this.controller = null
      }
      this.running = false
      this.stopping = false
      this.pendingAppends = []
      this.deferredAppends = []
      this.skillManagerOpen = false
      this.activeSessionId = item.id
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.selectedAidSessionModel = item.modelId
      this.selectedWorkspaceId = item.workspaceId == null ? null : item.workspaceId
      this.refreshModelSelection()
      this.draft = ''
      this.composerResources = []
      this.messages = []
      try {
        const [records, detail] = await Promise.all([
          fetchSessionMessages(item.id),
          fetchSessionDetail(item.id).catch(() => null)
        ])
        if (version !== this.sessionLoadVersion) return
        this.messages = replaySessionMessages(records)
        this.applySessionContext(detail && detail.context, item)
        const lastAssistant = [...this.messages].reverse().find((message) => message.role === 'assistant')
        const lastRecord = [...(records || [])].reverse().find((record) => record.roleType === 2)
        const status = Number(lastRecord && lastRecord.status)
        const live = [1, 2, 7].includes(status)
        const waiting = status === 8 || Boolean(lastAssistant && lastAssistant.status === 'waiting')
        this.running = waiting || Boolean(lastAssistant && (lastAssistant.parts || []).some(
          (part) => part.type === 'interrupt_card' && part.status === 'PENDING'
        ))
        if (live && lastAssistant && lastAssistant.messageId) {
          this.running = true
          const controller = new AbortController()
          this.controller = controller
          this.consumeTask(lastAssistant)
            .catch((error) => {
              if (error.name !== 'AbortError' && version === this.sessionLoadVersion) {
                lastAssistant.status = 'error'
                lastAssistant.error = error.message || '重连失败'
                this.finishParts(lastAssistant)
              }
            })
            .finally(() => {
              if (version !== this.sessionLoadVersion) return
              if (this.controller === controller) this.controller = null
              this.running = lastAssistant.status === 'waiting'
              if (!this.running) this.loadSessions()
            })
        }
        this.resetScrollFollow()
        this.scroll()
      } catch (error) {
        if (version === this.sessionLoadVersion) this.$message.error(error.message || '会话加载失败')
      } finally {
        if (version === this.sessionLoadVersion) {
          this.$nextTick(() => {
            if (version === this.sessionLoadVersion) this.contextSyncPaused = false
          })
        }
      }
    },

    newChat() {
      this.flushSessionContextUpdate()
      this.contextSyncPaused = false
      ++this.sessionLoadVersion
      if (this.controller) {
        this.controller.abort()
        this.controller = null
      }
      this.skillManagerOpen = false
      this.activeSessionId = null
      this.sessionId = ''
      this.sessionTitle = ''
      this.selectedAidSessionModel = null
      this.selectedWorkspaceId = null
      this.messages = []
      this.draft = ''
      this.composerResources = []
      this.pendingAppends = []
      this.deferredAppends = []
      this.running = false
      this.refreshModelSelection()
      this.$nextTick(() => {
        if (this.$refs.composerInput) this.$refs.composerInput.focus()
      })
    },
    openSkillManager() {
      if (this.running) {
        this.$message.warning('请先结束当前任务')
        return
      }
      this.skillManagerOpen = true
    },
    isSessionRunning(item) {
      if (!item) return false
      return Boolean(item.running) || (this.running && item.id === this.activeSessionId)
    },
    formatSessionTime(item) {
      const raw = item.lastMessageAt || item.modifyTime || item.createTime
      if (!raw) return ''
      const date = new Date(String(raw).replace(' ', 'T'))
      if (Number.isNaN(date.getTime())) return ''
      const elapsed = Math.max(0, Date.now() - date.getTime())
      const minute = 60 * 1000
      const hour = 60 * minute
      const day = 24 * hour
      if (elapsed < minute) return '刚刚'
      if (elapsed < hour) return `${Math.floor(elapsed / minute)}分钟前`
      if (elapsed < day) return `${Math.floor(elapsed / hour)}小时前`
      return `${Math.floor(elapsed / day)}天前`
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
      const editor = this.$refs.composerInput
      if (editor && editor.resizeShell) editor.resizeShell()
    },
    onComposerInput(value) {
      this.draft = value
      this.syncComposerResources(value)
    },
    parseComposerMarkers(text) {
      const pattern = /\[\[agentforge-resource:([^:\]]+):([^\]]+)\]\]/g
      const markers = []
      for (const match of String(text || '').matchAll(pattern)) {
        let path = match[2]
        try {
          path = decodeURIComponent(path)
        } catch (error) {
          // 保留原始编码，避免异常标记导致整个输入不可用
        }
        if (path) markers.push({ type: match[1], path })
      }
      return markers
    },
    syncComposerResources(text) {
      const next = []
      const seen = new Set()
      this.parseComposerMarkers(text).forEach((marker) => {
        if (seen.has(marker.path)) return
        seen.add(marker.path)
        const existing = this.composerResources.find((item) => item.path === marker.path)
        if (existing) {
          next.push(existing)
          return
        }
        const resource = this.normalizeResource({ path: marker.path, type: marker.type })
        if (resource) next.push(resource)
      })
      const unchanged =
        next.length === this.composerResources.length &&
        next.every((item, index) => item === this.composerResources[index])
      if (!unchanged) this.composerResources = next
    },
    activeComposerResources(text) {
      const paths = new Set(this.parseComposerMarkers(text).map((marker) => marker.path))
      return this.composerResources.filter((item) => paths.has(item.path))
    },
    buildSessionContext() {
      const workspace = this.workspaces.find((item) => item.id === this.selectedWorkspaceId)
      return {
        modelId: this.selectedModelId,
        message: this.draft || '',
        projectPath: workspace ? workspace.folderPath : '',
        files: this.composerResources.map((item) => ({
          path: item.path,
          name: item.name || '',
          fileType: item.fileType || '',
          size: item.size || 0,
          type: item.type || this.resourceType(item)
        }))
      }
    },
    scheduleSessionContextUpdate() {
      if (this.contextSyncPaused || !this.activeSessionId || !this.sessionId) return
      this.pendingSessionContext = {
        sessionId: this.activeSessionId,
        context: this.buildSessionContext()
      }
      if (this.sessionContextTimer) clearTimeout(this.sessionContextTimer)
      this.sessionContextTimer = setTimeout(() => {
        this.sessionContextTimer = null
        this.flushSessionContextUpdate()
      }, 500)
    },
    async flushSessionContextUpdate() {
      if (this.sessionContextTimer) {
        clearTimeout(this.sessionContextTimer)
        this.sessionContextTimer = null
      }
      const pending = this.pendingSessionContext
      this.pendingSessionContext = null
      if (!pending || !pending.sessionId) return
      try {
        await updateSessionContext(pending.sessionId, pending.context)
      } catch (error) {
        // 上下文保存失败不阻塞输入，下一次编辑会再次尝试
      }
    },
    applySessionContext(context, item) {
      const data = context && typeof context === 'object' ? context : null
      const modelId = data && data.modelId != null ? data.modelId : item.modelId
      this.selectedAidSessionModel = modelId == null ? null : Number(modelId)

      if (data && typeof data.projectPath === 'string') {
        const path = data.projectPath.trim()
        const workspace = path ? this.workspaces.find((entry) => entry.folderPath === path) : null
        if (workspace) {
          this.selectedWorkspaceId = workspace.id
        } else if (path && item.workspacePath === path && item.workspaceId != null) {
          this.selectedWorkspaceId = item.workspaceId
        } else {
          this.selectedWorkspaceId = null
        }
      } else {
        this.selectedWorkspaceId = item.workspaceId == null ? null : item.workspaceId
      }
      this.refreshModelSelection()

      const message = data ? String(data.message || '') : ''
      const files = data && Array.isArray(data.files) ? data.files : []
      const fileMap = new Map()
      files.forEach((raw) => {
        const resource = this.normalizeResource(raw)
        if (resource) fileMap.set(resource.path, resource)
      })
      const resources = []
      const seen = new Set()
      this.parseComposerMarkers(message).forEach((marker) => {
        if (seen.has(marker.path)) return
        seen.add(marker.path)
        const resource =
          fileMap.get(marker.path) ||
          this.normalizeResource({ path: marker.path, type: marker.type })
        if (resource) resources.push(resource)
      })
      this.composerResources = resources
      this.draft = message
    },
    onEnter() {
      if (this.running && (this.draft.trim() || this.composerResources.length)) {
        this.sendAppend()
        return
      }
      this.send()
    },
    onPrimaryAction() {
      if (this.stopping) return
      if (this.running && !this.draft.trim() && !this.composerResources.length) {
        this.stop()
        return
      }
      if (this.running) {
        this.sendAppend()
        return
      }
      this.send()
    },
    resourceMarker(resource) {
      const type = resource.type || this.resourceType(resource)
      return `[[agentforge-resource:${type}:${encodeURIComponent(resource.path)}]]`
    },
    resourceType(resource) {
      if (resource.type) return resource.type
      const name = String(resource.name || resource.path || '').toLowerCase()
      if (/\.(png|jpe?g|gif|webp|bmp|svg)$/.test(name)) return 'image'
      if (/\.pdf$/.test(name)) return 'pdf'
      if (/\.(html?|mhtml)$/.test(name)) return 'html'
      if (/\.(md|txt|docx?|xlsx?|pptx?)$/.test(name)) return 'document'
      return 'file'
    },
    normalizeResource(resource) {
      if (!resource || !resource.path) return null
      const normalized = { ...resource }
      normalized.type = this.resourceType(normalized)
      normalized.name =
        normalized.name ||
        String(normalized.path).split(/[\\/]/).filter(Boolean).pop() ||
        normalized.path
      return normalized
    },
    stripResourceMarkers(text) {
      return String(text || '')
        .replace(/\[\[agentforge-resource:[^:\]]+:[^\]]+\]\]/g, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim()
    },
    addComposerResources(resources, richSelection) {
      const added = []
      ;(resources || []).forEach((raw) => {
        const resource = this.normalizeResource(raw)
        if (!resource || this.composerResources.some((item) => item.path === resource.path)) return
        this.composerResources.push(resource)
        added.push(resource)
      })
      if (!added.length) return
      const markers = added.map((item) => this.resourceMarker(item)).join('')
      if (richSelection && this.$refs.composerInput) {
        this.$nextTick(() => this.$refs.composerInput.insertResourceMarkers(markers))
        return
      }
      this.draft = `${this.draft}${markers}`
      const lastPath = added[added.length - 1].path
      this.$nextTick(() => {
        this.autoGrow()
        if (this.$refs.composerInput) this.$refs.composerInput.focusAfterResource(lastPath)
      })
    },
    removeComposerResource(resourcePath) {
      if (!resourcePath) return
      this.composerResources = this.composerResources.filter((item) => item.path !== resourcePath)
      this.draft = this.draft
        .split(new RegExp(`\\[\\[agentforge-resource:[^:\\]]+:${this.escapeRegExp(encodeURIComponent(resourcePath))}\\]\\]`, 'g'))
        .join('')
      this.$nextTick(this.autoGrow)
    },
    previewComposerResource(resourcePath) {
      if (!resourcePath) return
      if (window.electronAPI && window.electronAPI.openPath) {
        window.electronAPI.openPath(resourcePath).then((error) => {
          if (error) this.$message.error(error)
        })
      } else {
        this.$message.info(resourcePath)
      }
    },
    escapeRegExp(value) {
      return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    },
    rememberComposerSelection() {
      if (this.$refs.composerInput && this.$refs.composerInput.saveSelection) {
        this.$refs.composerInput.saveSelection()
      }
    },
    async chooseComposerResources(command) {
      if (!this.canChat) {
        this.$message.warning('请先创建 Agent')
        return
      }
      try {
        if (!window.electronAPI) {
          this.$message.warning('浏览器模式不支持添加本地资源，请在桌面端使用')
          return
        }
        const resources = command === 'folder'
          ? await window.electronAPI.chooseResourceFolder()
          : command === 'files'
            ? await window.electronAPI.chooseResourceFiles()
            : await window.electronAPI.chooseResources()
        this.addComposerResources(resources, true)
      } catch (error) {
        this.$message.error(error.message || '读取本地资源失败')
      }
    },
    async handleComposerPaste(event) {
      this.rememberComposerSelection()
      const clipboard = event.clipboardData
      const files = Array.from((clipboard && clipboard.files) || [])
      if (!files.length) return
      const resources = files.map((file) => {
        try {
          const filePath = window.electronAPI && window.electronAPI.getPathForFile
            ? window.electronAPI.getPathForFile(file)
            : ''
          return filePath ? { path: filePath, name: file.name, size: file.size } : null
        } catch (_error) {
          return null
        }
      }).filter(Boolean)
      if (resources.length) {
        this.addComposerResources(resources, true)
        return
      }
      try {
        const resourcesFromClipboard = window.electronAPI && window.electronAPI.readClipboardResources
          ? await window.electronAPI.readClipboardResources()
          : []
        this.addComposerResources(resourcesFromClipboard, true)
      } catch (error) {
        this.$message.error(error.message || '读取剪贴板资源失败')
      }
    },
    handleComposerDrop(event) {
      this.rememberComposerSelection()
      const files = Array.from((event.dataTransfer && event.dataTransfer.files) || [])
      if (!files.length) return
      const resources = files.map((file) => {
        try {
          const filePath = window.electronAPI && window.electronAPI.getPathForFile
            ? window.electronAPI.getPathForFile(file)
            : ''
          return filePath ? { path: filePath, name: file.name, size: file.size } : null
        } catch (_error) {
          return null
        }
      }).filter(Boolean)
      if (resources.length) this.addComposerResources(resources, true)
    },
    createAnswer() {
      return {
        role: 'assistant',
        messageId: null,
        lastEventSeq: 0,
        seenEventSeq: new Set(),
        status: 'running',
        parts: [],
        content: '',
        error: '',
        startedAt: Date.now(),
        finishedAt: null,
        elapsedMs: null
      }
    },
    async consumeTask(answer, questionText, controller = this.controller) {
      if (!answer || !answer.messageId || !controller) return
      let retryCount = 0
      while (!controller.signal.aborted) {
        try {
          await streamChatTask(answer.messageId, answer.lastEventSeq || 0, {
            signal: controller.signal,
            onEvent: (event, sseId) => {
              const rawSeq = event && event.eventSeq != null ? event.eventSeq : sseId
              const eventSeq = Number(rawSeq)
              if (Number.isFinite(eventSeq) && eventSeq > 0) {
                if (answer.seenEventSeq.has(eventSeq)) return
                answer.seenEventSeq.add(eventSeq)
                answer.lastEventSeq = Math.max(answer.lastEventSeq || 0, eventSeq)
              }
              this.handleEvent(answer, event, questionText)
            }
          })
          retryCount = 0
          if (['success', 'error', 'cancelled', 'waiting'].includes(answer.status)) return
        } catch (error) {
          if (error.name === 'AbortError') throw error
          retryCount += 1
          if (retryCount > 8) throw error
          await new Promise((resolve) => setTimeout(resolve, Math.min(2000, 200 * retryCount)))
        }
      }
    },
    async send() {
      const text = this.draft.trim()
      const resources = this.activeComposerResources(text)
      if (!this.canChat) {
        this.$message.warning('请先创建 Agent 并配置模型')
        return
      }
      if (this.running) {
        await this.sendAppend()
        return
      }
      if (!text && !resources.length) return
      if (this.activeInterrupt) {
        this.$message.warning('请先处理上方的确认项')
        return
      }
      if (this.selectedModelId == null) {
        this.$message.warning('请先选择模型')
        return
      }
      const visibleText = this.stripResourceMarkers(text)
      this.draft = ''
      this.composerResources = []
      this.$nextTick(this.autoGrow)
      this.messages.push({ role: 'user', text: visibleText, rawText: text, resources })
      const answer = this.createAnswer()
      this.messages.push(answer)
      this.running = true
      const controller = new AbortController()
      this.controller = controller
      this.resetScrollFollow()
      this.scroll()
      try {
        const submitted = await submitChatTask({
          aid: this.selectedAid,
          sessionId: this.sessionId,
          message: text,
          modelId: this.selectedModelId,
          workspaceId: this.selectedWorkspaceId,
          projectPath: null,
          files: resources.map((item) => ({
            path: item.path,
            name: item.name,
            fileType: item.fileType || '',
            size: item.size || 0,
            type: item.type || this.resourceType(item)
          }))
        })
        answer.messageId = submitted && submitted.messageId
        if (submitted && submitted.sessionId) this.sessionId = submitted.sessionId
        if (submitted && submitted.sessionPk) this.activeSessionId = submitted.sessionPk
        if (submitted && submitted.title && !this.sessionTitle) this.sessionTitle = submitted.title
        if (submitted && submitted.sessionPk) {
          await this.loadSessions({ silent: true })
          this.scheduleSessionContextUpdate()
        }
        await this.consumeTask(answer, visibleText || '本地资源', controller)
      } catch (error) {
        if (error.name !== 'AbortError' && answer.status === 'running') {
          answer.status = 'error'
          answer.error = error.message || '请求失败'
          this.finishParts(answer)
        }
      } finally {
        if (this.controller === controller) {
          const waiting = answer.status === 'waiting'
          this.running = waiting
          this.controller = null
          if (!waiting) this.restoreDeferredAppends()
          this.scroll()
          if (!waiting) this.loadSessions()
        }
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
      const controller = new AbortController()
      this.controller = controller
      try {
        const resumed = await resumeChatInterrupt({
          batchId: payload.batchId,
          responses: payload.responses
        })
        if (resumed && resumed.messageId) answer.messageId = resumed.messageId
        await this.consumeTask(answer, undefined, controller)
      } catch (error) {
        if (error.name !== 'AbortError') {
          if (part) part.status = 'PENDING'
          answer.status = 'error'
          answer.error = error.message || '中断恢复失败'
          this.finishParts(answer)
        }
      } finally {
        if (this.controller === controller) {
          const waiting = answer.status === 'waiting'
          this.running = waiting
          this.controller = null
          if (!waiting) this.restoreDeferredAppends()
          this.scroll()
          if (!waiting) this.loadSessions()
        }
      }
    },
    async sendAppend() {
      if (this.stopping) return
      const text = this.draft.trim()
      const resources = this.activeComposerResources(text)
      if ((!text && !resources.length) || !this.sessionId) {
        if (!this.sessionId) this.$message.warning('请先发送一轮对话后再追加指令')
        return
      }
      if (this.activeInterrupt) {
        this.$message.warning('请先处理上方的确认项')
        return
      }
      const visibleText = this.stripResourceMarkers(text)
      try {
        const result = await appendChatMessage({
          aid: this.selectedAid,
          sessionId: this.sessionId,
          message: text,
          files: resources.map((item) => ({
            path: item.path,
            name: item.name,
            fileType: item.fileType || '',
            size: item.size || 0,
            type: item.type || this.resourceType(item)
          }))
        })
        if (!result || !result.queued) {
          this.$message.warning((result && result.reason) || '追加失败')
          return
        }
        this.draft = ''
        this.composerResources = []
        this.$nextTick(this.autoGrow)
        if (!this.controller) {
          this.pendingAppends.push({
            appendId: result.appendId,
            content: visibleText || resources.map((item) => item.name).join('、'),
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
      const answer = [...this.messages].reverse().find((message) => message.role === 'assistant')
      if (!answer || !answer.messageId) {
        this.$message.warning('本轮尚未提交完成，暂时无法停止')
        return
      }
      this.stopping = true
      try {
        const result = await stopChat({ messageId: answer.messageId })
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
    startWorkspaceChat(workspace) {
      if (!workspace) return
      this.newChat()
      this.selectedWorkspaceId = workspace.id
      if (!this.expandedWorkspaceIds.includes(workspace.id)) {
        this.expandedWorkspaceIds.push(workspace.id)
      }
      this.$nextTick(() => {
        if (this.$refs.composerInput) this.$refs.composerInput.focus()
      })
      this.scroll()
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
