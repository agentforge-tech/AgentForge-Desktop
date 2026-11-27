<template>
  <el-dialog
    title="任务看板"
    :visible="visible"
    width="1080px"
    top="7vh"
    :append-to-body="true"
    :destroy-on-close="true"
    custom-class="task-board-dialog"
    @close="$emit('update:visible', false)"
  >
    <div class="task-board-subhead">
      <span>{{ agentName || '当前 Agent' }}</span>
      <span v-if="totalCount" class="task-board-subhead-total">共 {{ totalCount }} 个任务</span>
    </div>

    <div v-loading="loading" class="task-board-body">
      <!-- 三栏全空：整体空态，替换三栏 -->
      <div v-if="allEmpty && !loading" class="task-board-overall-empty">
        <i class="el-icon-folder-opened"></i>
        <p>还没有任务，回到工作台开始第一个吧</p>
      </div>

      <template v-else>
        <section class="board-column board-column--running">
          <header class="board-column-head">
            <span class="board-column-title">运行中</span>
            <span class="board-column-count">{{ running.length }}</span>
          </header>
          <div class="board-list">
            <article
              v-for="item in running"
              :key="'run-' + item.id"
              class="board-item"
            >
              <div class="board-item-title" :title="item.title">{{ item.title }}</div>
              <div class="board-item-workspace">
                <span v-if="item.workspaceId != null" class="board-workspace-chip" :title="item.workspacePath">
                  <svg viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M2.4 12.2V3.75A1.35 1.35 0 0 1 3.75 2.4h1.1c.42 0 .82.17 1.1.47l.55.6c.28.3.68.47 1.1.47h3.9a1.35 1.35 0 0 1 1.35 1.35v6.91a1.35 1.35 0 0 1-1.35 1.35H3.75A1.35 1.35 0 0 1 2.4 12.2z" />
                    <path d="M2.4 6.9h10.45" />
                  </svg>
                  <span class="board-workspace-name">{{ workspaceName(item) }}</span>
                </span>
                <span v-else class="board-workspace-plain">默认空间</span>
              </div>
              <div class="board-item-foot">
                <span class="board-meta">
                  <i class="el-icon-loading board-running-icon" aria-label="任务运行中"></i>
                  {{ relativeTime(item) }}<template v-if="item.messageCount"> · {{ item.messageCount }} 轮</template>
                </span>
                <button type="button" class="board-enter-btn" @click="$emit('enter', item)">进入会话</button>
              </div>
            </article>
            <p v-if="!running.length" class="board-empty">当前没有运行中的任务</p>
          </div>
          <p v-if="running.length" class="board-column-note">
            切换会话只断开实时显示，任务会在后台继续执行；重新进入会自动补全过程。
          </p>
        </section>

        <section class="board-column board-column--waiting">
          <header class="board-column-head">
            <span class="board-column-title">等待审批</span>
            <span class="board-column-count board-column-count--urgent">{{ waiting.length }}</span>
          </header>
          <div class="board-list">
            <article
              v-for="item in waiting"
              :key="'wait-' + item.id"
              class="board-item is-waiting"
            >
              <div class="board-item-title" :title="item.title">{{ item.title }}</div>
              <div class="board-item-workspace">
                <span v-if="item.workspaceId != null" class="board-workspace-chip" :title="item.workspacePath">
                  <svg viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M2.4 12.2V3.75A1.35 1.35 0 0 1 3.75 2.4h1.1c.42 0 .82.17 1.1.47l.55.6c.28.3.68.47 1.1.47h3.9a1.35 1.35 0 0 1 1.35 1.35v6.91a1.35 1.35 0 0 1-1.35 1.35H3.75A1.35 1.35 0 0 1 2.4 12.2z" />
                    <path d="M2.4 6.9h10.45" />
                  </svg>
                  <span class="board-workspace-name">{{ workspaceName(item) }}</span>
                </span>
                <span v-else class="board-workspace-plain">默认空间</span>
              </div>
              <div class="board-item-foot">
                <span class="board-meta board-meta--waiting">{{ waitingMeta(item) }}</span>
                <button type="button" class="board-enter-btn is-urgent" @click="$emit('enter', item)">进入会话</button>
              </div>
            </article>
            <p v-if="!waiting.length" class="board-empty">没有等待你审批的任务</p>
          </div>
        </section>

        <section class="board-column board-column--done">
          <header class="board-column-head">
            <span class="board-column-title">已完成</span>
            <span class="board-column-count">{{ done.length }}</span>
          </header>
          <div class="board-list">
            <article
              v-for="item in done"
              :key="'done-' + item.id"
              class="board-item"
            >
              <div class="board-item-head">
                <div class="board-item-title" :title="item.title">{{ item.title }}</div>
                <span
                  v-if="statusLabel(item)"
                  class="board-status"
                  :class="statusClass(item)"
                >{{ statusLabel(item) }}</span>
              </div>
              <div class="board-item-workspace">
                <span v-if="item.workspaceId != null" class="board-workspace-chip" :title="item.workspacePath">
                  <svg viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M2.4 12.2V3.75A1.35 1.35 0 0 1 3.75 2.4h1.1c.42 0 .82.17 1.1.47l.55.6c.28.3.68.47 1.1.47h3.9a1.35 1.35 0 0 1 1.35 1.35v6.91a1.35 1.35 0 0 1-1.35 1.35H3.75A1.35 1.35 0 0 1 2.4 12.2z" />
                    <path d="M2.4 6.9h10.45" />
                  </svg>
                  <span class="board-workspace-name">{{ workspaceName(item) }}</span>
                </span>
                <span v-else class="board-workspace-plain">默认空间</span>
              </div>
              <div class="board-item-foot">
                <span class="board-meta">
                  {{ relativeTime(item) }}<template v-if="item.messageCount"> · {{ item.messageCount }} 轮</template>
                </span>
                <button type="button" class="board-enter-btn" @click="$emit('enter', item)">进入会话</button>
              </div>
            </article>
            <p v-if="!done.length" class="board-empty">还没有已完成的任务</p>
          </div>
          <p class="board-column-note">仅显示最近 50 个任务</p>
        </section>
      </template>
    </div>
  </el-dialog>
</template>

<script>
import {
  doneStatusLabel,
  formatRelativeActivity,
  turnElapsedMinutes,
  workspaceNameOf
} from '../utils/taskBoardGroup'

/**
 * 任务看板弹窗：纯展示组件（props in / event out）。
 *
 * 不直接请求接口、不直接调 openSession —— 所有副作用留在 ChatWorkbench，
 * 便于后续移植成独立路由页。分类与排序已在父组件用 groupSessions 完成。
 */
export default {
  name: 'TaskBoardDialog',
  props: {
    visible: { type: Boolean, default: false },
    running: { type: Array, default: () => [] },
    waiting: { type: Array, default: () => [] },
    done: { type: Array, default: () => [] },
    workspaces: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    agentName: { type: String, default: '' }
  },
  computed: {
    totalCount() {
      return this.running.length + this.waiting.length + this.done.length
    },
    allEmpty() {
      return this.totalCount === 0
    }
  },
  methods: {
    workspaceName(item) {
      return workspaceNameOf(item, this.workspaces)
    },
    relativeTime(item) {
      return formatRelativeActivity(item)
    },
    /**
     * 等待审批栏的时间提示。
     *
     * 语义：`lastMessageAt` 在审批态取到的是**本轮开始时间**（该轮 AI 消息还没有
     * finished_at），不是"进入审批的时间"，所以不能说成"已等待"。
     * 短时段说「本轮已进行 N 分钟」；超过 1 小时则说明这轮早已停在那儿，
     * 直接给「已搁置 N 小时/天」更贴近用户判断，也避免出现 2368 分钟这种无效数字。
     */
    waitingMeta(item) {
      const minutes = turnElapsedMinutes(item)
      if (minutes == null) return '等待你处理'
      if (minutes < 1) return '本轮刚进入等待'
      if (minutes < 60) return `本轮已进行 ${minutes} 分钟`
      const hours = Math.floor(minutes / 60)
      if (hours < 24) return `已搁置 ${hours} 小时`
      return `已搁置 ${Math.floor(hours / 24)} 天`
    },
    statusLabel(item) {
      return doneStatusLabel(item)
    },
    statusClass(item) {
      const status = Number(item && item.status)
      if (status === 2) return 'is-finished'
      if (status === 4) return 'is-cancelling'
      if (status === 3) return 'is-failed'
      return 'is-idle'
    }
  }
}
</script>
