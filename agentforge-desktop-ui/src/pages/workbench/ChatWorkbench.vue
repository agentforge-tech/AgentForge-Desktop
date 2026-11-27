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
            <div class="current-agent-title">
              <b>{{ currentAgent ? currentAgent.name : appName }}</b>
              <span v-if="appTag" class="app-version-tag">{{ appTag }}</span>
            </div>
          </div>
        </div>
        <el-button size="small" class="new-chat-btn" :class="{ active: newChatHighlighted }" @click="newChat">
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

      <div ref="sessionScroll" class="session-scroll" :class="{ 'has-pinned-panel': pinnedTaskSessions.length, 'workspace-groups-collapsed': expandedWorkspaceIds.length === 0, 'tasks-collapsed': !normalTasksOpen }">
        <section v-if="pinnedTaskSessions.length" class="session-list-panel pinned-task-panel" :class="{ 'is-collapsed': !pinnedTasksOpen }">
          <button type="button" class="task-group-title pinned-task-title" @click="pinnedTasksOpen = !pinnedTasksOpen">
            <span>置顶任务 ({{ pinnedTaskSessions.length }})</span>
            <i :class="pinnedTasksOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
          </button>
          <div v-show="pinnedTasksOpen" class="session-panel-body">
            <div v-for="item in pinnedTaskSessions" :key="'pinned-task-' + item.id" class="session-item" :data-session-id="item.id" :class="{ active: item.id === activeSessionId }" @click="openSession(item)">
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
              <div v-for="item in group.items" :key="'normal-task-' + item.id" class="session-item" :data-session-id="item.id" :class="{ active: item.id === activeSessionId }" @click="openSession(item)">
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
            <div
              class="workspace-tree-head"
              :class="{ 'is-menu-open': openWorkspaceMenuId === group.id }"
              role="button"
              tabindex="0"
              @click="toggleWorkspaceGroup(group.id)"
              @keydown.enter="toggleWorkspaceGroup(group.id)"
            >
              <!-- 折叠态：闭合文件夹 -->
              <svg
                v-if="!expandedWorkspaceIds.includes(group.id)"
                class="workspace-tree-icon"
                viewBox="0 0 16 16"
                aria-hidden="true"
              >
                <path d="M2.4 12.2V3.75A1.35 1.35 0 0 1 3.75 2.4h1.1c.42 0 .82.17 1.1.47l.55.6c.28.3.68.47 1.1.47h3.9a1.35 1.35 0 0 1 1.35 1.35v6.91a1.35 1.35 0 0 1-1.35 1.35H3.75A1.35 1.35 0 0 1 2.4 12.2z" />
                <path d="M2.4 6.9h10.45" />
              </svg>
              <!-- 展开态：打开文件夹 -->
              <svg
                v-else
                class="workspace-tree-icon"
                viewBox="0 0 16 16"
                aria-hidden="true"
              >
                <path d="M2.2 11.5V3.45A1.25 1.25 0 0 1 3.45 2.2h.85c.47 0 .92.2 1.24.54l.42.45c.32.34.77.54 1.24.54h4.2a1.25 1.25 0 0 1 1.25 1.25v1.42" />
                <path d="M5.05 6.4h8.95l-1.7 5.1H2.2z" />
              </svg>
              <span class="workspace-tree-name" :title="group.folderPath">{{ group.name }}</span>
              <i class="workspace-tree-chevron" :class="expandedWorkspaceIds.includes(group.id) ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
              <el-dropdown
                class="workspace-more"
                trigger="click"
                placement="bottom-start"
                @command="handleWorkspaceCommand($event, group)"
                @visible-change="onWorkspaceMenuVisible(group.id, $event)"
                @click.native.stop
              >
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
                  <path d="M12.9 3.05H3.1a1.05 1.05 0 0 0-1.05 1.05v5.7c0 .58.47 1.05 1.05 1.05h.75v2.2l2.5-2.2h6.5a1.05 1.05 0 0 0 1.05-1.05v-5.7A1.05 1.05 0 0 0 12.9 3.05Z" />
                  <path d="M8 4.75v4.4M5.8 6.95h4.4" />
                </svg>
              </button>
            </div>
            <div v-show="expandedWorkspaceIds.includes(group.id)" class="workspace-tree-sessions">
              <div
                v-for="item in group.sessions"
                :key="item.id"
                class="session-item workspace-session-item"
                :data-session-id="item.id"
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
        <el-button size="mini" icon="el-icon-setting" circle title="工作台设置" @click="settingsOpen = true" />
      </div>
    </aside>

    <SkillManager v-if="skillManagerOpen" />
    <main v-show="!skillManagerOpen" class="chat-main">
      <header class="chat-header">
        <div class="chat-title">
          <span>{{ title }}</span>
        </div>
        <div class="chat-toolbar">
          <button
            type="button"
            class="task-board-trigger"
            :class="{ 'has-attention': boardWaiting.length > 0 }"
            :disabled="!backendConnected"
            :title="backendConnected ? '任务看板' : '本地服务未连接'"
            aria-label="任务看板"
            @click="openBoard"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <rect x="2.4" y="3.2" width="4.4" height="13.6" rx="1.4" />
              <rect x="7.8" y="3.2" width="4.4" height="8.8" rx="1.4" />
              <rect x="13.2" y="3.2" width="4.4" height="11.2" rx="1.4" />
            </svg>
            <!-- 角标优先显示"等我"的数量，没有才显示"在跑"的数量 -->
            <span
              v-if="boardBadgeCount"
              class="task-board-badge"
              :class="{ 'is-urgent': boardWaiting.length > 0 }"
            >{{ boardBadgeCount }}</span>
          </button>
          <el-select
            v-if="modeSwitchEnabled"
            v-model="mode"
            size="mini"
            class="mode-select"
            title="交互模式"
            @change="onModeChange"
          >
            <el-option v-for="item in renderModes" :key="item.key" :label="item.label" :value="item.key" />
          </el-select>
        </div>
      </header>

      <div class="conversation-stage" :class="{ 'has-messages': messages.length, 'has-navigator': hasNavigator }">
        <div class="chat-feed-shell">
        <section ref="feed" class="chat-feed agent-debug-feed" @scroll.passive="onFeedScroll">
        <div class="chat-inner agent-debug-chat-inner">
          <div v-if="!messages.length" class="welcome">
            <h2>{{ welcomeText }}</h2>
            <p v-if="!agents.length" class="welcome-tip">还没有 Agent，请先到「Agent 配置」创建。</p>
          </div>

          <template v-for="(message, index) in messages">
            <div
              v-if="message.role === 'user'"
              :key="'u-' + index"
              class="agent-debug-message-row agent-debug-message-row--user"
              :class="{ 'is-anchor-flash': navigatorFlashKey === 'u-' + index }"
              :data-anchor-key="'u-' + index"
            >
              <div class="agent-debug-user-block">
                <div class="agent-debug-user-bubble">
                  <UserMessageContent
                    :text="message.text"
                    :raw-text="message.rawText"
                    :resources="message.resources"
                    @preview="previewComposerResource"
                    @open-skill="openComposerSkill"
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

        <MessageNavigator
          v-if="hasNavigator"
          :anchors="anchorList"
          :active-key="navigatorActiveKey"
          :has-new="navigatorHasNew"
          :compact="navigatorCompact"
          @jump="scrollToAnchor"
          @jump-latest="jumpToLatest"
        />
        </div>

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
            <SkillShortcutPanel
              v-if="skillShortcutOpen"
              ref="skillShortcutPanel"
              :skills="availableSkills"
              :inserted-names="insertedSkillNames"
              :query="skillShortcutQuery"
              :loading="skillsLoading"
              @select="selectComposerSkill"
              @close="dismissSkillShortcut"
            />
            <ComposerInput
              ref="composerInput"
              :value="draft"
              :disabled="!canChat || !!activeInterrupt"
              :placeholder="composerPlaceholder"
              :resources="composerResources"
              :skills="composerSkills"
              :shortcut-handler="handleComposerShortcutKey"
              @input="onComposerInput"
              @caret-change="detectSkillShortcut"
              @enter="onEnter"
              @remove-resource="removeComposerResource"
              @preview-resource="previewComposerResource"
              @remove-skill="removeComposerSkill"
              @open-skill="openComposerSkill"
              @paste-files="handleComposerPaste"
            />
            <div class="composer-toolbar">
              <ComposerAddMenu
                :disabled="!canChat"
                @capture-selection="rememberComposerSelection"
                @pick-files="chooseComposerResources('files')"
                @pick-folder="chooseComposerResources('folder')"
              />
              <el-popover
                v-model="permissionOpen"
                placement="top-start"
                width="292"
                trigger="click"
                popper-class="composer-permission-popover"
                :disabled="running"
              >
                <div class="composer-permission-menu">
                  <p class="composer-permission-desc">{{ permissionModeDescription }}</p>
                  <div class="composer-permission-row">
                    <span>允许完全访问</span>
                    <el-switch v-model="permissionBypass" :disabled="running" active-color="#4f46e5" />
                  </div>
                </div>
                <button
                  slot="reference"
                  type="button"
                  class="composer-permission-btn"
                  :disabled="running"
                  :title="permissionModeDescription"
                >
                  <svg class="composer-permission-icon" viewBox="0 0 16 16" aria-hidden="true">
                    <circle cx="8" cy="8" r="6.25" />
                    <path d="m5.1 8.15 1.9 1.95 3.9-4.1" />
                  </svg>
                  <span>{{ permissionModeLabel }}</span>
                  <i class="el-icon-arrow-down composer-permission-caret"></i>
                </button>
              </el-popover>
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
              :class="{
                stop: stopping || (running && !draft.trim() && !composerResources.length),
                'is-stopping': stopping
              }"
              :disabled="stopping || !canChat || !!activeInterrupt || (!running && !draft.trim() && !composerResources.length)"
              :title="primaryActionTitle"
              :aria-busy="stopping ? 'true' : 'false'"
              :aria-label="primaryActionTitle"
              @click="onPrimaryAction"
            >
              <span v-if="stopping" class="stop-spinner" aria-hidden="true">
                <svg viewBox="0 0 20 20">
                  <circle class="stop-spinner-track" cx="10" cy="10" r="7.5" />
                  <circle class="stop-spinner-arc" cx="10" cy="10" r="7.5" />
                </svg>
              </span>
              <svg v-else-if="!running || draft.trim() || composerResources.length" class="send-icon" viewBox="0 0 24 24" aria-hidden="true">
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
      @system-saved="onSystemSettingsSaved"
    />

    <TaskBoardDialog
      :visible="boardOpen"
      :running="boardRunning"
      :waiting="boardWaiting"
      :done="boardDone"
      :workspaces="workspaces"
      :loading="boardLoading"
      :agent-name="currentAgent ? currentAgent.name : appName"
      @enter="enterFromBoard"
      @update:visible="onBoardVisibleChange"
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
import ComposerSkillChip from '../../components/ComposerSkillChip.vue'
import SkillShortcutPanel from '../../components/SkillShortcutPanel.vue'
import UserMessageContent from '../../components/UserMessageContent.vue'
import MessageNavigator from '../../components/MessageNavigator.vue'
import TaskBoardDialog from '../../components/TaskBoardDialog.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import chatAnchorNav from '../../utils/chatAnchorNav'
import { groupSessions, formatRelativeActivity } from '../../utils/taskBoardGroup'
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
// 资源类型判定必须与后端 DesktopResourceService、Electron describeResource 一致，
// 否则输入框、已发消息、重新进入会话三处图标会不一致。
import { inferResourceType, buildSkillMarker, createSkillMarkerRegExp } from '../../utils/resourceType'
import { fetchSkills } from '../../api/skill'
import {
  describeLocalResources,
  normalizeResource as normalizeResourceMeta,
  pathForFile
} from '../../utils/resourceMeta'
import chatCatchUp from '../../utils/chatCatchUp'
import { keepFeedbackVisible } from '../../utils/feedbackTiming'
import { RENDER_MODES, DEFAULT_RENDER_MODE } from '../../modes'
import { request } from '../../api/client'
import { deleteWorkspace, fetchWorkspaces, renameWorkspace } from '../../api/workspace'
import agentLogo from '../../assets/agent-logo.png'
import deepseekLogo from '../../assets/deepseek.svg'
import openaiLogo from '../../assets/openai.svg'
import { APP_NAME, APP_TAG } from '../../constants/appMeta'

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
  components: { AgentMessageContent, ConfigDrawer, InterruptCard, WorkspacePicker, SkillManager, ComposerInput, ComposerAddMenu, ComposerResourceChip, ComposerSkillChip, SkillShortcutPanel, UserMessageContent, MessageNavigator, TaskBoardDialog },
  // chatAnchorNav 需在 chatAutoScroll 之后：它复用 scrollToBottom / followScroll 状态
  // chatCatchUp 排最后：它读 followScroll / isUserScrolledUp，并在追平后负责落底收敛
  mixins: [chatAutoScroll, chatAnchorNav, chatCatchUp],
  data: () => ({
    brandLogo: agentLogo,
    appName: APP_NAME,
    appTag: APP_TAG,
    agents: [],
    models: [],
    selectedAid: '',
    currentAgent: null,
    selectedModelId: null,
    selectedAidSessionModel: null,
    permissionMode: 1,
    permissionOpen: false,
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
    mode: localStorage.getItem(MODE_KEY) || DEFAULT_RENDER_MODE,
    interactionModeSupport: false,
    settingsOpen: false,
    backendConnected: false,
    workspace: '',
    workspaces: [],
    selectedWorkspaceId: null,
    expandedWorkspaceIds: [],
    openWorkspaceMenuId: null,
    workspaceOpen: true,
    pinnedTasksOpen: true,
    normalTasksOpen: true,
    skillManagerOpen: false,
    // 输入框 `/` 唤起的 Skill 面板
    availableSkills: [],
    skillsLoading: false,
    skillShortcutOpen: false,
    skillShortcutQuery: '',
    // 用户按 Esc 主动关闭：在匹配片段消失前不再自动弹开
    skillShortcutDismissed: false,
    newChatActive: true,
    pendingAppends: [],
    deferredAppends: [],
    sessionContextTimer: null,
    pendingSessionContext: null,
    contextSyncPaused: false,
    // 任务看板：独立维护自己的列表与轮询，避免与左栏 loadSessions 互相干扰
    boardOpen: false,
    boardTopSessions: [],
    boardNormalSessions: [],
    boardLoading: false,
    boardTimer: null
  }),
  computed: {
    renderModes() {
      return RENDER_MODES
    },
    // 右上角交互模式切换是否可用，由系统配置 interactionModeSupport 控制。
    modeSwitchEnabled() {
      return this.interactionModeSupport === true
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
    /** 输入框当前引用的 Skill 列表，直接从正文标记解析，不额外维护一份状态。 */
    composerSkills() {
      const names = new Set()
      const result = []
      for (const match of String(this.draft || '').matchAll(createSkillMarkerRegExp())) {
        const name = this.decodeMarkerPart(match[1])
        if (!name || names.has(name)) continue
        names.add(name)
        const path = this.decodeMarkerPart(match[2])
        const meta = this.availableSkills.find((item) => item && item.name === name) || {}
        result.push({
          name,
          path: path || meta.path || '',
          description: meta.description || '',
          scope: meta.scope || ''
        })
      }
      return result
    },
    insertedSkillNames() {
      return this.composerSkills.map((item) => item.name)
    },
    canSelectSkill() {
      return this.canChat && !this.activeInterrupt
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
    permissionBypass: {
      get() {
        return this.permissionMode === 2
      },
      set(value) {
        this.permissionMode = value ? 2 : 1
      }
    },
    permissionModeLabel() {
      return this.permissionMode === 2 ? '完全访问' : '默认权限'
    },
    permissionModeDescription() {
      return this.permissionMode === 2
        ? '当前为完全访问，相关高危操作将不再请求你的确认。'
        : '当前为默认权限，相关高危操作超过范围会请求你的确认。'
    },
    primaryActionTitle() {
      if (this.stopping) return '停止中…'
      if (this.running && (this.draft.trim() || this.composerResources.length)) return '发送补充指令'
      if (this.running) return '停止'
      return '发送'
    },
    activeInterrupt() {
      // 追赶期一律不弹阻断式确认：回放会把历史上的 interrupt_requested 重推一遍，
      // 此时 PENDING 并不代表"现在需要你处理"，弹出会让用户误判当前待办。
      if (this.catchUpActive) return null
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
    },
    // 左侧导航为单选：技能管理打开时「新建任务」不再保持选中态。
    newChatHighlighted() {
      return this.newChatActive && !this.skillManagerOpen
    },
    // ---- 任务看板 ----
    // 分类依据是会话级 status（见 utils/taskBoardGroup），不能用 SessionVo.running：
    // 后者只在 status==1 时为 true，等待审批(5)/取消中(4) 都会被判成非运行态。
    boardSourceList() {
      const seen = new Set()
      const merged = []
      const push = (list) => {
        (list || []).forEach((item) => {
          if (!item || seen.has(item.id)) return
          seen.add(item.id)
          merged.push(item)
        })
      }
      push(this.boardTopSessions)
      push(this.boardNormalSessions)
      return merged
    },
    boardGroups() {
      return groupSessions(this.boardSourceList)
    },
    boardRunning() {
      return this.boardGroups.running
    },
    boardWaiting() {
      return this.boardGroups.waiting
    },
    boardDone() {
      return this.boardGroups.done
    },
    // 角标优先显示"等我"的数量：等待审批是唯一需要用户动手的一栏
    boardBadgeCount() {
      if (!this.backendConnected) return 0
      if (this.boardWaiting.length) return this.boardWaiting.length
      return this.boardRunning.length
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
    permissionMode() {
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
    },
    // 只监听长度：避免对流式内容做深度遍历（那会逐 token 触发重算）
    'messages.length'() {
      this.scheduleAnchorSync()
    }
  },
  created() {
    // 已向主进程核验过真实类型的路径，避免重复 IPC（不需要响应式）
    this.resourceTypeChecked = new Set()
    this.loadSystemSettings()
    this.bootstrap()
    this.checkBackend()
    this.scheduleSessionRefresh()
    // 看板数据常驻低频轮询：顶栏角标在未打开看板时也要准确
    this.scheduleBoardRefresh()
  },
  beforeDestroy() {
    if (this.sessionRefreshTimer) clearTimeout(this.sessionRefreshTimer)
    if (this.boardTimer) clearTimeout(this.boardTimer)
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
      // 看板只覆盖当前 Agent，切换时必须清空并关闭，否则会短暂串台到上一个 Agent 的任务
      this.boardTopSessions = []
      this.boardNormalSessions = []
      this.closeBoard()
      this.newChat()
      await this.applyAgent(found)
    },
    async applyAgent(agent) {
      this.currentAgent = agent
      this.models = (await fetchModels()) || []
      this.refreshModelSelection()
      await Promise.all([this.loadSessions(), this.loadWorkspaces()])
      // 切换 Agent 后立刻刷新看板数据，避免角标等到下一次轮询才准
      await this.refreshBoard({ silent: true })
      await this.loadAvailableSkills()
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
    /**
     * 加载左栏会话列表。
     *
     * 不传 limit：后端返回该 Agent 下全部非置顶会话，任务分组与工作空间分组才能完整展示，
     * 不会再出现"昨天的会话 / 工作空间里的会话被条数窗口截掉"的问题。
     */
    async loadSessions({ silent = false } = {}) {
      if (!this.selectedAid || this.sessionsLoading) return
      const aid = this.selectedAid
      this.sessionsLoading = true
      try {
        const [top, normal] = await Promise.all([
          fetchTopSessionList(aid),
          fetchSessionList(aid)
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
      this.newChatActive = false
      this.activeSessionId = item.id
      // 左栏与当前会话保持一致：展开所属工作空间/分组并滚动到该条目。
      // 否则从看板等入口跳进来时，右侧已打开会话、左侧却折叠着看不到它。
      this.revealSessionInSidebar(item)
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.selectedAidSessionModel = item.modelId
      this.selectedWorkspaceId = item.workspaceId == null ? null : item.workspaceId
      this.refreshModelSelection()
      this.draft = ''
      this.composerResources = []
      this.messages = []
      this.teardownCatchUp()
      this.resetAnchorNav()
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
          // events 列为空 → lastEventSeq 为 0 → 整段内容都要靠 SSE 回放，
          // 这是确定性的"追赶"场景，前置判定可避免首帧按 running 渲染后闪一下。
          if (!(lastAssistant.lastEventSeq > 0)) this.beginCatchUp(lastAssistant)
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
              if (!this.running) {
                this.loadSessions()
                this.notifyTaskDone(lastAssistant)
              }
            })
        }
        this.resetScrollFollow()
        // 顺序关键：先测量 → 轨道就位 → 再落底。
        // 原实现先 scroll 后 measure，会让位 padding 触发的重排把视口留在中途。
        this.measureNavigatorSize()
        this.commitAnchors({ rebuild: true })
        await this.$nextTick()
        if (version !== this.sessionLoadVersion) return
        // force：刚打开会话必然要落到最新，不能被残留的 isUserScrolledUp 挡住；
        // 只有用户真实滚动（wheel / touch）才中断收敛。
        this.settleBottom({ force: true })
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
      this.newChatActive = true
      this.activeSessionId = null
      this.sessionId = ''
      this.sessionTitle = ''
      this.selectedAidSessionModel = null
      this.selectedWorkspaceId = null
      this.permissionMode = 1
      this.permissionOpen = false
      this.messages = []
      this.draft = ''
      this.composerResources = []
      this.closeSkillShortcut()
      this.pendingAppends = []
      this.deferredAppends = []
      this.running = false
      this.teardownCatchUp()
      this.resetAnchorNav()
      this.refreshModelSelection()
      this.$nextTick(() => {
        if (this.$refs.composerInput) this.$refs.composerInput.focus()
      })
    },
    openSkillManager() {
      // 任务执行中也可查看技能：只切换右侧视图，不打断进行中的会话流。
      this.skillManagerOpen = !this.skillManagerOpen
      if (this.skillManagerOpen) this.closeSkillShortcut()
      else this.onSkillManagerClosed()
    },
    isSessionRunning(item) {
      if (!item) return false
      return Boolean(item.running) || (this.running && item.id === this.activeSessionId)
    },
    // ---- 任务看板 ----
    async openBoard() {
      if (!this.backendConnected) {
        this.$message.error('本地服务未连接，无法加载任务看板')
        return
      }
      this.boardOpen = true
      // 打开瞬间强制拉一次，避免"刚提交却看不到"的空窗
      await this.refreshBoard({ silent: true })
      this.scheduleBoardRefresh()
    },
    closeBoard() {
      this.boardOpen = false
      // 不停轮询：顶栏角标要在未打开看板时也保持准确，只降频（见 scheduleBoardRefresh）
      this.scheduleBoardRefresh()
    },
    onBoardVisibleChange(visible) {
      if (visible) {
        this.openBoard()
        return
      }
      this.closeBoard()
    },
    /**
     * 看板数据常驻低频轮询：角标的意义是"在我没打开看板时提示我"，
     * 因此不能只在弹窗打开时才更新数据。打开时 5s（跟左栏节奏一致），
     * 关闭时降到 15s，兼顾实时性与空转成本。
     */
    scheduleBoardRefresh() {
      if (this.boardTimer) clearTimeout(this.boardTimer)
      if (this._isBeingDestroyed || this._isDestroyed) return
      const interval = this.boardOpen ? 5000 : 15000
      this.boardTimer = setTimeout(async () => {
        if (this.selectedAid && this.backendConnected && !this.boardLoading) {
          await this.refreshBoard({ silent: true })
        }
        if (this._isBeingDestroyed || this._isDestroyed) return
        this.scheduleBoardRefresh()
      }, interval)
    },
    /**
     * 拉取看板数据。
     *
     * 不复用 loadSessions：后者有 sessionsLoading 门禁会吞掉左栏刷新，
     * 且会覆盖 topSessions/normalSessions，污染左栏口径。
     * 看板只需最近活跃的任务，这里显式取接口上限 50 条，
     * 只写自己的 boardTopSessions/boardNormalSessions。
     */
    async refreshBoard({ silent = false } = {}) {
      if (!this.selectedAid || this.boardLoading) return
      const aid = this.selectedAid
      this.boardLoading = true
      try {
        const [top, normal] = await Promise.all([
          fetchTopSessionList(aid),
          fetchSessionList(aid, 50)
        ])
        if (aid !== this.selectedAid) return
        this.boardTopSessions = top || []
        this.boardNormalSessions = normal || []
      } catch (error) {
        if (!silent) this.$message.error(error.message || '任务看板加载失败')
      } finally {
        this.boardLoading = false
      }
    },
    enterFromBoard(item) {
      this.closeBoard()
      if (!item) return
      // 已在当前会话且正在流：不重复 openSession，避免无意义的 abort + 重连闪断
      if (item.id === this.activeSessionId && this.running) {
        this.revealSessionInSidebar(item)
        return
      }
      this.openSession(item)
    },
    /**
     * 让左栏与当前会话保持一致：展开所属工作空间/分组，并滚动到该条目。
     *
     * 从看板进入时用户没有手动展开过左栏，若工作空间仍折叠，
     * 会出现"右侧已打开某会话，左侧却完全看不到它"的割裂感。
     */
    revealSessionInSidebar(item) {
      if (!item) return
      // 工作空间会话：必须展开对应分组，否则条目根本不渲染
      if (item.workspaceId != null) {
        if (!this.workspaceOpen) this.workspaceOpen = true
        if (!this.expandedWorkspaceIds.includes(item.workspaceId)) {
          this.expandedWorkspaceIds.push(item.workspaceId)
        }
      } else {
        // 置顶或普通任务：确保对应栏目是展开的
        if (item.topOrder > 0) this.pinnedTasksOpen = true
        else this.normalTasksOpen = true
      }
      this.scrollSessionIntoView(item)
    },
    /** 等 DOM 更新（分组展开）后再滚动，否则元素还不存在。 */
    scrollSessionIntoView(item) {
      this.$nextTick(() => {
        const container = this.$refs.sessionScroll
        if (!container) return
        const selector = `.session-item[data-session-id="${item.id}"]`
        const el = container.querySelector(selector)
        if (!el || typeof el.scrollIntoView !== 'function') return
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      })
    },
    formatSessionTime(item) {
      return formatRelativeActivity(item)
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
      this.detectSkillShortcut()
    },
    // ---- 输入框 Skill 唤起 ----
    /** 解码标记片段；异常编码时保留原文，避免整段输入不可用。 */
    decodeMarkerPart(value) {
      try {
        return decodeURIComponent(String(value || ''))
      } catch (_error) {
        return String(value || '')
      }
    },
    currentWorkspacePath() {
      const workspace = this.workspaces.find((item) => item.id === this.selectedWorkspaceId)
      return workspace ? workspace.folderPath || '' : ''
    },
    /**
     * 拉取 Skill 列表。
     *
     * 阶段之间没有缓存层：列表规模是「根目录 + 一层子目录」，且只在打开面板、
     * 切换工作空间、从技能管理页返回时拉取，代价可以忽略。
     */
    async loadAvailableSkills() {
      if (!this.canChat) {
        this.availableSkills = []
        return
      }
      this.skillsLoading = true
      try {
        const list = await fetchSkills({ workspacePath: this.currentWorkspacePath() })
        this.availableSkills = Array.isArray(list) ? list : []
      } catch (_error) {
        // 列表失败不应阻塞输入，面板显示为空即可
        this.availableSkills = []
      } finally {
        this.skillsLoading = false
      }
    },
    /** 根据光标前文本决定是否显示面板，以及当前筛选词。 */
    detectSkillShortcut() {
      if (!this.canSelectSkill) {
        this.closeSkillShortcut()
        return
      }
      const before = (this.$refs.composerInput && this.$refs.composerInput.getCaretTextBefore)
        ? this.$refs.composerInput.getCaretTextBefore(40)
        : ''
      // 行首或空白后的 "/query" 才算触发，避免 https:// 之类的误判
      const match = before.match(/(?:^|\s)\/([^\s/]*)$/)
      if (!match || match[1].length > 20) {
        this.skillShortcutDismissed = false
        this.closeSkillShortcut()
        return
      }
      // 用户 Esc 主动关闭后，只要触发片段还在就不重新弹开
      if (this.skillShortcutDismissed) return
      this.skillShortcutOpen = true
      this.skillShortcutQuery = match[1]
      // 首次打开时补拉列表，避免每次键入都请求
      if (!this.availableSkills.length && !this.skillsLoading) this.loadAvailableSkills()
    },
    closeSkillShortcut() {
      this.skillShortcutOpen = false
      this.skillShortcutQuery = ''
    },
    dismissSkillShortcut() {
      this.skillShortcutDismissed = true
      this.closeSkillShortcut()
    },
    /** 面板打开时优先消费键盘；返回 true 表示不再触发发送。 */
    handleComposerShortcutKey(event) {
      if (!this.skillShortcutOpen) return false
      const panel = this.$refs.skillShortcutPanel
      return Boolean(panel && panel.handleKey && panel.handleKey(event))
    },
    selectComposerSkill(skill) {
      if (!skill || !skill.name) return
      if (this.insertedSkillNames.includes(skill.name)) {
        this.$message.info('该 Skill 已选择')
        this.closeSkillShortcut()
        return
      }
      const removeLength = this.skillShortcutQuery.length + 1
      this.closeSkillShortcut()
      const input = this.$refs.composerInput
      if (!input) return
      // 先抹掉 "/query"，再把标记插到原光标处，最终顺序与用户书写一致
      if (input.deleteBeforeCaret) input.deleteBeforeCaret(removeLength)
      this.$nextTick(() => {
        if (input.insertResourceMarkers) {
          input.insertResourceMarkers(buildSkillMarker(skill.name, skill.path || ''))
        }
      })
    },
    removeComposerSkill(name) {
      if (!name) return
      const pattern = new RegExp(
        `\\[\\[agentforge-skill:${this.escapeRegExp(encodeURIComponent(name))}:[^\\]]+\\]\\]`, 'g')
      this.draft = this.draft.replace(pattern, '')
      this.$nextTick(this.autoGrow)
    },
    openComposerSkill(skill) {
      const path = skill && skill.path
      if (!path) return
      if (window.electronAPI && window.electronAPI.openPath) {
        window.electronAPI.openPath(path).then((error) => {
          if (error) this.$message.error(error)
        })
      } else {
        this.$message.info(path)
      }
    },
    onSkillManagerClosed() {
      // 导入/删除 Skill 后列表可能变化，回到工作台时刷新
      this.loadAvailableSkills()
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
      if (!unchanged) {
        this.composerResources = next
        // 标记里可能是旧数据写下的 file 类型，补一次真实类型校正
        if (next.some((item) => item.type === 'file' && !item.fileType)) {
          this.reconcileComposerResourceTypes()
        }
      }
    },
    activeComposerResources(text) {
      const paths = new Set(this.parseComposerMarkers(text).map((marker) => marker.path))
      return this.composerResources.filter((item) => paths.has(item.path))
    },
    buildSessionContext() {
      const workspace = this.workspaces.find((item) => item.id === this.selectedWorkspaceId)
      return {
        modelId: this.selectedModelId,
        mode: this.permissionMode,
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
      this.permissionMode = data && Number(data.mode) === 2 ? 2 : 1

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
      // 历史上下文可能存着旧版写入的类型，异步用真实文件系统结果纠正
      if (resources.some((item) => item.type === 'file' && !item.fileType)) {
        this.reconcileComposerResourceTypes(this.sessionLoadVersion)
      }
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
      return inferResourceType(resource)
    },
    normalizeResource(resource) {
      return normalizeResourceMeta(resource)
    },
    stripResourceMarkers(text) {
      return String(text || '')
        .replace(/\[\[agentforge-resource:[^:\]]+:[^\]]+\]\]/g, '')
        .replace(createSkillMarkerRegExp(), '')
        .replace(/\n{3,}/g, '\n\n')
        .trim()
    },
    /**
     * 把文本中标记的类型改写成资源的真实类型。
     *
     * 标记类型是历史数据的一部分：旧版本拖拽文件夹时只拿到路径，会写成 `:file:`，
     * 而同一份资源在 files 里可能已经是 folder。发送前统一改写，保证
     * 输入框、请求体、历史回放三处类型一致。
     */
    normalizeMarkerTypes(text, resources) {
      const list = resources || this.composerResources
      if (!list || !list.length) return text
      let result = String(text || '')
      list.forEach((item) => {
        const type = item.type || this.resourceType(item)
        const pattern = new RegExp(
          `\\[\\[agentforge-resource:[^:\\]]+:${this.escapeRegExp(encodeURIComponent(item.path))}\\]\\]`,
          'g'
        )
        result = result.replace(pattern, this.resourceMarker({ path: item.path, type }))
      })
      return result
    },
    /**
     * 纠正恢复出来的资源类型。
     *
     * 会话上下文里的 files 可能由旧版前端写入（无扩展名的文件夹被记成 file），
     * 这类资源无法靠扩展名判断，必须回主进程问一次真实类型，否则
     * 重新进入会话后输入框仍显示文件图标，而消息气泡已是文件夹图标。
     */
    async reconcileComposerResourceTypes(version) {
      if (!this.resourceTypeChecked) this.resourceTypeChecked = new Set()
      const uncertain = (this.composerResources || []).filter(
        (item) => item.type === 'file' && !item.fileType && !this.resourceTypeChecked.has(item.path)
      )
      if (!uncertain.length) return
      // 先登记再请求：避免连续输入时对同一路径反复发起 IPC
      uncertain.forEach((item) => this.resourceTypeChecked.add(item.path))
      const described = await describeLocalResources(uncertain.map((item) => item.path))
      if (version != null && version !== this.sessionLoadVersion) return
      const byPath = new Map(described.map((item) => [item.path, item]))
      let changed = false
      const next = this.composerResources.map((item) => {
        const real = byPath.get(item.path)
        if (!real || real.type === item.type) return item
        changed = true
        return { ...item, ...real }
      })
      if (!changed) return
      this.composerResources = next
      // 标记里也写回真实类型，避免下次序列化又退化成 file
      this.draft = this.normalizeMarkerTypes(this.draft, next)
    },
    addComposerResources(resources, richSelection) {
      // 关键顺序：push 会立刻触发 Chip 重绘（innerHTML 重建），
      // 必须在它之前确保光标已记录，否则插入点会退化成编辑器开头。
      if (richSelection && this.$refs.composerInput && this.$refs.composerInput.ensureSelectionSaved) {
        this.$refs.composerInput.ensureSelectionSaved()
      }
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
      // 粘贴文本时可继续交给编辑器处理；只有携带文件/图片时才接管
      if (!files.length) return
      // 与拖拽同源：items 与 files 顺序一致，可用 entry 判断目录
      const entries = Array.from((clipboard && clipboard.items) || []).map((item) => {
        try {
          return item && typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null
        } catch (_error) {
          return null
        }
      })
      const directoryHints = new Map()
      files.forEach((file, index) => {
        const filePath = pathForFile(file)
        const entry = entries[index]
        if (filePath && entry && entry.isDirectory) directoryHints.set(filePath, true)
      })
      const paths = files.map(pathForFile).filter(Boolean)
      if (paths.length) {
        // 类型以主进程真实结果为准，避免文件夹被当成普通文件
        this.addComposerResources(await describeLocalResources(paths, directoryHints), true)
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
    async handleComposerDrop(event) {
      this.rememberComposerSelection()
      const transfer = event.dataTransfer
      const files = Array.from((transfer && transfer.files) || [])
      if (!files.length) return
      // 目录判定线索：items 与 files 顺序一致，webkitGetAsEntry().isDirectory 是
      // 无需 IPC 即可拿到的最可靠信号，用于在旧 preload 下也能画出文件夹图标
      const entries = Array.from((transfer && transfer.items) || []).map((item) => {
        try {
          return item && typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null
        } catch (_error) {
          return null
        }
      })
      const directoryHints = new Map()
      files.forEach((file, index) => {
        const filePath = pathForFile(file)
        const entry = entries[index]
        if (filePath && entry && entry.isDirectory) directoryHints.set(filePath, true)
      })
      const paths = files.map(pathForFile).filter(Boolean)
      if (!paths.length) return
      // 目录与扩展名以主进程真实结果为准，主进程不可用时退回上面的目录提示
      this.addComposerResources(await describeLocalResources(paths, directoryHints), true)
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
        elapsedMs: null,
        reminderSent: false
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
              // 追赶判定必须在 handleEvent 之前：先定模式，再按该模式渲染
              this.noteCatchUpEvent(answer)
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
      const text = this.normalizeMarkerTypes(this.draft.trim())
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
      this.permissionOpen = false
      this.resetScrollFollow()
      this.scroll()
      // 立即出现新刻度：用户已提交即应看到"这一轮"存在
      this.commitAnchors()
      try {
        const submitted = await submitChatTask({
          aid: this.selectedAid,
          sessionId: this.sessionId,
          message: text,
          modelId: this.selectedModelId,
          mode: this.permissionMode,
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
          this.newChatActive = false
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
          // 兜底收尾：流自然结束时后端可能不发 resp_end（或早于落库），
          // 此时不走 finishParts，elapsedMs 为空 → 状态栏"已完成"右侧没有耗时。
          if (!waiting) this.settleAnswer(answer)
          this.scroll()
          if (!waiting) this.loadSessions()
          // 兜底：网络中断等场景可能收不到 error 事件，但仍应给出任务结束信号
          if (!waiting) this.notifyTaskDone(answer)
        }
      }
    },
    /**
     * 兜底收尾：把仍在"运行中"的 answer 归到终态并补齐耗时。
     *
     * 正常路径由 resp_end 事件触发 finishParts；但流自然结束时后端
     * 不保证下发该事件，缺了这一步 elapsedMs 就一直是 null，
     * 界面表现为"已完成"却没有耗时，切换会话重新回放后才补上。
     */
    settleAnswer(answer) {
      if (!answer || answer.status !== 'running') return
      promoteLastResponseSegment(answer)
      answer.status = 'success'
      this.finishParts(answer)
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
            this.notifyTaskDone(answer)
          }
          break
        case 'error':
          if (answer.status === 'cancelled') break
          finishResponseSegments(answer)
          answer.status = 'error'
          answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
          this.finishParts(answer)
          this.notifyTaskDone(answer)
          break
        default:
          break
      }
      // 追赶期不滚：积压事件成百上千条，逐条滚动会把视口反复拉扯；
      // 追平后由 chatCatchUp 统一做一次落底收敛。
      if (!this.isCatchUp(answer)) this.scroll()
      // 节流同步锚点：流式每个事件都会走到这里，200ms 合并一次
      this.scheduleAnchorSync()
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
    // 任务结束时的图标提醒：同一轮只上报一次；浏览器模式（无 electronAPI）静默跳过。
    notifyTaskDone(answer) {
      if (!answer || answer.reminderSent) return
      answer.reminderSent = true
      const api = window.electronAPI
      if (!api || typeof api.notifyTaskDone !== 'function') return
      // 提醒失败不影响业务：不弹错、不打断对话
      api.notifyTaskDone().catch(() => {})
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
          // 同 send()：自然结束可能没有 resp_end，需要兜底收尾补耗时。
          if (!waiting) this.settleAnswer(answer)
          this.scroll()
          if (!waiting) this.loadSessions()
          if (!waiting) this.notifyTaskDone(answer)
        }
      }
    },
    async sendAppend() {
      if (this.stopping) return
      const text = this.normalizeMarkerTypes(this.draft.trim())
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
      const startedAt = Date.now()
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
        // 后端停止常常几十毫秒就返回；补足最短可见时长，保证用户能看到"停止中"旋转态。
        await keepFeedbackVisible(startedAt)
        this.stopping = false
        this.commitAnchors()
      }
    },
    onModeChange(value) {
      localStorage.setItem(MODE_KEY, value)
    },
    async loadSystemSettings() {
      if (!(window.electronAPI && window.electronAPI.readSystemSettings)) {
        // 非 Electron 环境（如浏览器直开）按未开启处理，默认发布模式。
        this.applySystemSettings({ interactionModeSupport: false })
        return
      }
      try {
        this.applySystemSettings(await window.electronAPI.readSystemSettings())
      } catch (_error) {
        this.applySystemSettings({ interactionModeSupport: false })
      }
    },
    applySystemSettings(settings) {
      this.interactionModeSupport = settings?.interactionModeSupport === true
      // 配置关闭时回到默认发布模式，并清掉本地偏好，避免下次启动又恢复成调试模式。
      if (!this.interactionModeSupport) {
        localStorage.removeItem(MODE_KEY)
        this.mode = DEFAULT_RENDER_MODE
      }
    },
    onSystemSettingsSaved(settings) {
      this.applySystemSettings(settings)
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
    selectWorkspace(item) {
      if (this.sessionId || !item) return
      this.selectedWorkspaceId = item.id
      this.onComposerWorkspaceChanged()
    },
    clearWorkspace() {
      if (this.sessionId) return
      this.selectedWorkspaceId = null
      this.onComposerWorkspaceChanged()
    },
    /** 工作空间变化后，Skill 列表与面板都要跟着换。 */
    onComposerWorkspaceChanged() {
      this.closeSkillShortcut()
      this.loadAvailableSkills()
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
    onWorkspaceMenuVisible(id, visible) {
      if (visible) this.openWorkspaceMenuId = id
      else if (this.openWorkspaceMenuId === id) this.openWorkspaceMenuId = null
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
