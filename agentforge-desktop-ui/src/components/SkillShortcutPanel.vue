<template>
  <div class="skill-shortcut-panel" @mousedown.prevent>
    <div class="skill-panel-header">
      <span>可用 Skills</span>
      <span class="skill-panel-hint">↑↓ 选择 · Enter 确认 · Esc 关闭</span>
    </div>
    <div v-if="loading" class="skill-panel-empty">加载中…</div>
    <div v-else-if="!filteredSkills.length" class="skill-panel-empty">
      {{ skills.length ? '没有匹配的 Skill' : '当前工作空间没有可用 Skill' }}
    </div>
    <div v-else class="skill-panel-body">
      <!-- 左：名称列表，只放名称，把空间留给右侧描述 -->
      <ul class="skill-panel-list">
        <li
          v-for="(skill, index) in filteredSkills"
          :key="skill.name"
          class="skill-panel-item"
          :class="{ 'is-active': index === currentIndex }"
          @mouseenter="currentIndex = index"
          @click="select(skill)"
        >
          <SkillGlyph class="skill-panel-mark" :size="13" />
          <span class="skill-panel-name" :title="skill.name">{{ skill.name }}</span>
        </li>
      </ul>
      <!-- 右：当前高亮项的完整描述 -->
      <div class="skill-panel-detail">
        <template v-if="activeSkill">
          <div class="skill-detail-head">
            <span class="skill-detail-name">{{ activeSkill.name }}</span>
            <span v-if="activeSkill.scope" class="skill-detail-scope">{{ scopeLabel }}</span>
          </div>
          <p class="skill-detail-desc">{{ activeSkill.description || '未提供技能描述' }}</p>
          <div v-if="activeSkill.path" class="skill-detail-path" :title="activeSkill.path">
            {{ activeSkill.path }}
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import SkillGlyph from './SkillGlyph.vue'

export default {
  name: 'SkillShortcutPanel',
  components: { SkillGlyph },
  props: {
    skills: { type: Array, default: () => [] },
    // 已经插入正文的 Skill 名，避免重复选择
    insertedNames: { type: Array, default: () => [] },
    query: { type: String, default: '' },
    loading: { type: Boolean, default: false }
  },
  data: () => ({ currentIndex: 0 }),
  computed: {
    filteredSkills() {
      const query = String(this.query || '').trim().toLowerCase()
      const inserted = new Set(this.insertedNames || [])
      const candidates = (this.skills || []).filter(
        (item) => item && item.name && !inserted.has(item.name)
      )
      if (!query) return candidates.slice(0, 8)
      // 前缀匹配优先，其次 name 包含，最后 description 包含；组内保持后端顺序
      const prefix = []
      const nameHit = []
      const descHit = []
      candidates.forEach((item) => {
        const name = item.name.toLowerCase()
        if (name.startsWith(query)) {
          prefix.push(item)
        } else if (name.includes(query)) {
          nameHit.push(item)
        } else if (String(item.description || '').toLowerCase().includes(query)) {
          descHit.push(item)
        }
      })
      return prefix.concat(nameHit, descHit).slice(0, 8)
    },
    // 右侧跟随键盘高亮，鼠标 hover 也会改 currentIndex，两者天然一致
    activeSkill() {
      return this.filteredSkills[this.currentIndex] || null
    },
    scopeLabel() {
      const scope = this.activeSkill && this.activeSkill.scope
      if (scope === 'workspace') return '工作空间'
      if (scope === 'global') return '全局'
      return scope || ''
    }
  },
  watch: {
    query() {
      this.currentIndex = 0
    },
    filteredSkills() {
      if (this.currentIndex >= this.filteredSkills.length) this.currentIndex = 0
    },
    // 切换高亮项时右栏回到顶部，避免沿用上一个 Skill 的滚动位置
    currentIndex() {
      this.$nextTick(() => {
        const detail = this.$el && this.$el.querySelector('.skill-panel-detail')
        if (detail) detail.scrollTop = 0
      })
    }
  },
  methods: {
    /** 面板打开时优先消费键盘事件；返回 true 表示已被面板处理，不再触发发送。 */
    handleKey(event) {
      if (event.key === 'Escape') {
        event.preventDefault()
        this.$emit('close')
        return true
      }
      const size = this.filteredSkills.length
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        if (!size) return false
        event.preventDefault()
        const step = event.key === 'ArrowDown' ? 1 : -1
        this.currentIndex = (this.currentIndex + step + size) % size
        return true
      }
      if (event.key === 'Enter' && !event.shiftKey) {
        // 无匹配时不拦截，让输入框按普通发送处理
        if (!size) return false
        event.preventDefault()
        this.select(this.filteredSkills[this.currentIndex])
        return true
      }
      return false
    },
    select(skill) {
      if (!skill) return
      this.$emit('select', skill)
    }
  }
}
</script>

<style scoped>
.skill-shortcut-panel{position:absolute;left:0;right:0;bottom:calc(100% + 8px);z-index:3050;background:#fff;border:1px solid #e6e8ee;border-radius:12px;box-shadow:0 16px 42px rgba(31,42,68,.18)}
.skill-panel-header{display:flex;align-items:center;justify-content:space-between;padding:6px 12px;color:#6b7280;font-size:12px;border-bottom:1px solid #f0f1f5}
.skill-panel-hint{color:#9aa0aa}
.skill-panel-empty{padding:16px 12px;color:#9aa0aa;font-size:12px;text-align:center}
/*
 * 高度完全固定：不随列表条数、也不随描述长短变化。
 * 一是避免面板以底部为基准时高度变化导致左侧列表上下位移、指针下的项来回切换；
 * 二是描述再长也不会把面板撑高，超出部分在右栏内部滚动。
 * 取 168px 是为了贴近「4-6 条列表 + 两三行描述」的常见体量：再高会压到输入框上方的正文，
 * 再矮右栏只剩一两行、必须频繁滚动。
 */
.skill-panel-body{position:relative;height:168px;padding-left:196px;overflow:hidden}
.skill-panel-list{position:absolute;top:0;left:0;bottom:0;width:196px;margin:0;padding:4px;overflow-y:auto;list-style:none;border-right:1px solid #f0f1f5;box-sizing:border-box}
.skill-panel-item{display:flex;align-items:center;gap:6px;padding:6px 8px;border-radius:7px;cursor:pointer;font-size:12px;line-height:16px}
.skill-panel-item.is-active{background:#f0f4ff}
.skill-panel-mark{color:#6680cf}
.skill-panel-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#2f3646;font-weight:600}
.skill-panel-item.is-active .skill-panel-name{color:#3f4a72}
.skill-panel-detail{height:100%;padding:8px 12px;overflow-y:auto;box-sizing:border-box;scrollbar-gutter:stable}
.skill-detail-head{display:flex;align-items:baseline;gap:8px;margin-bottom:4px}
.skill-detail-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#2f3646;font-size:12px;font-weight:600}
.skill-detail-scope{flex:0 0 auto;padding:1px 6px;color:#5b6b9c;background:#eef2ff;border-radius:4px;font-size:11px;line-height:16px}
.skill-detail-desc{margin:0;color:#5b6270;font-size:12px;line-height:1.55;white-space:pre-wrap;word-break:break-word}
.skill-detail-path{margin-top:6px;color:#9aa0aa;font-size:11px;line-height:1.5;word-break:break-all}
/* 窄窗口下左栏收窄，优先保证右侧描述的可读宽度 */
@media (max-width: 560px) {
  .skill-panel-body{padding-left:160px}
  .skill-panel-list{width:160px}
  .skill-panel-detail{padding:8px 10px}
}
</style>
