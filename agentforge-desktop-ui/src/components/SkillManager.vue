<template>
  <main class="skill-manager">
    <header class="skill-manager-header">
      <div>
        <h2>技能管理</h2>
        <p>管理所有工作空间均可使用的全局 Skills。</p>
      </div>
      <div class="skill-manager-actions">
        <el-button size="small" icon="el-icon-folder-opened" @click="openRoot">打开全局目录</el-button>
      </div>
    </header>

    <section class="skill-manager-content">
      <div
        class="skill-import-zone"
        :class="{ 'is-dragging': dragActive, 'is-importing': importing }"
        role="button"
        tabindex="0"
        title="选择或拖入 Skill 文件夹 / ZIP"
        @keydown.enter.prevent="chooseDirectoryAndImport"
        @keydown.space.prevent="chooseDirectoryAndImport"
        @dragenter.prevent="onDragEnter"
        @dragover.prevent="onDragOver"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="onDrop"
      >
        <span class="skill-import-icon" aria-hidden="true">
          <i class="el-icon-folder-add"></i>
          <i class="el-icon-plus skill-import-badge"></i>
        </span>
        <div class="skill-import-copy">
          <b>{{ importing ? '正在导入 Skill…' : '选择或拖入 Skill 文件夹 / ZIP' }}</b>
          <span>需包含 <code>SKILL.md</code>，ZIP 支持根目录或唯一顶层目录结构</span>
        </div>
        <div class="skill-import-actions">
          <el-button size="mini" icon="el-icon-folder-opened" :disabled="importing" @click.stop="chooseDirectoryAndImport">选择文件夹</el-button>
          <el-button size="mini" icon="el-icon-document" :loading="importing" @click.stop="chooseZipAndImport">选择 ZIP</el-button>
        </div>
      </div>

      <div class="skill-toolbar">
        <el-input v-model="keyword" clearable prefix-icon="el-icon-search" placeholder="搜索 Skill 名称或描述" />
        <el-button icon="el-icon-refresh" :loading="loading" title="刷新" @click="load">刷新</el-button>
      </div>

      <div v-loading="loading" class="skill-list">
        <article v-for="skill in filteredSkills" :key="skill.path" class="skill-card" tabindex="0" :title="`${skill.description || '未提供技能描述'}\n${skill.path}`" @contextmenu.prevent.stop="openContextMenu($event, skill)">
          <div class="skill-icon"><i class="el-icon-magic-stick"></i></div>
          <div class="skill-card-body">
            <div class="skill-name">{{ skill.name }}</div>
            <p>{{ skill.description || '未提供技能描述' }}</p>
          </div>
          <span class="skill-scope">全局</span>
        </article>
        <el-empty v-if="!loading && !filteredSkills.length" :description="keyword ? '没有匹配的 Skill' : '暂无全局 Skill，可从本地目录导入'" />
      </div>
      <div
        v-if="contextMenu.visible"
        ref="contextMenu"
        class="skill-context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        role="menu"
        @mousedown.stop
        @contextmenu.prevent
      >
        <button type="button" role="menuitem" @click="openSkillDirectory(contextMenu.skill)">
          <span class="skill-context-icon"><i class="el-icon-folder-opened"></i></span>
          <span class="skill-context-copy"><b>打开 Skill 文件夹</b><small>{{ contextMenu.skill && contextMenu.skill.name }}</small></span>
        </button>
      </div>
    </section>
  </main>
</template>

<script>
import { fetchGlobalSkills, fetchSkillRoot, importGlobalSkill } from '../api/skill'

export default {
  name: 'SkillManager',
  data: () => ({
    skills: [], rootPath: '', keyword: '', loading: false, importing: false, dragActive: false,
    contextMenu: { visible: false, x: 0, y: 0, skill: null }
  }),
  computed: {
    filteredSkills() {
      const word = this.keyword.trim().toLowerCase()
      if (!word) return this.skills
      return this.skills.filter((item) => `${item.name || ''} ${item.description || ''}`.toLowerCase().includes(word))
    }
  },
  created() { this.load() },
  mounted() { document.addEventListener('mousedown', this.closeContextMenu) },
  beforeDestroy() { document.removeEventListener('mousedown', this.closeContextMenu) },
  methods: {
    async load() {
      this.loading = true
      try {
        const [skills, root] = await Promise.all([fetchGlobalSkills(), fetchSkillRoot()])
        this.skills = skills || []
        this.rootPath = (root && root.path) || ''
      } catch (error) {
        this.$message.error(error.message || 'Skill 加载失败')
      } finally {
        this.loading = false
      }
    },
    async chooseDirectoryAndImport() {
      if (this.importing) return
      if (!window.electronAPI || !window.electronAPI.chooseDirectory) {
        this.$message.info('浏览器模式不支持选择本地目录')
        return
      }
      const selected = await window.electronAPI.chooseDirectory('')
      if (!selected) return
      await this.importPath(selected)
    },
    async chooseZipAndImport() {
      if (this.importing) return
      if (!window.electronAPI || !window.electronAPI.chooseSkillZip) {
        this.$message.info('浏览器模式不支持选择本地 ZIP')
        return
      }
      const selected = await window.electronAPI.chooseSkillZip('')
      if (!selected) return
      await this.importPath(selected)
    },
    onDragEnter() {
      if (!this.importing) this.dragActive = true
    },
    onDragOver(event) {
      if (this.importing) return
      this.dragActive = true
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    },
    onDragLeave(event) {
      const current = event.currentTarget
      const related = event.relatedTarget
      if (!related || !current.contains(related)) this.dragActive = false
    },
    async onDrop(event) {
      this.dragActive = false
      if (this.importing) return
      const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]
      if (!file) return
      const getPath = window.electronAPI && window.electronAPI.getPathForFile
      const sourcePath = getPath ? getPath(file) : file.path
      if (!sourcePath) {
        this.$message.error('无法读取拖入文件夹的本地路径')
        return
      }
      await this.importPath(sourcePath)
    },
    async importPath(sourcePath) {
      this.importing = true
      try {
        const imported = await importGlobalSkill(sourcePath)
        await this.load()
        this.$message.success(`已导入 ${imported.name}`)
      } catch (error) {
        this.$message.error(error.message || 'Skill 导入失败')
      } finally {
        this.importing = false
      }
    },
    async openRoot() {
      if (window.electronAPI && window.electronAPI.openPath) {
        const error = await window.electronAPI.openPath(this.rootPath)
        if (error) this.$message.error(error)
      } else {
        this.$message.info(this.rootPath || '浏览器模式不支持打开目录')
      }
    },
    openContextMenu(event, skill) {
      this.contextMenu = { visible: true, x: event.clientX, y: event.clientY, skill }
      this.$nextTick(() => {
        const menu = this.$refs.contextMenu
        if (!menu) return
        const x = Math.min(event.clientX, window.innerWidth - menu.offsetWidth - 8)
        const y = Math.min(event.clientY, window.innerHeight - menu.offsetHeight - 8)
        this.contextMenu.x = Math.max(8, x)
        this.contextMenu.y = Math.max(8, y)
      })
    },
    closeContextMenu() {
      this.contextMenu.visible = false
    },
    async openSkillDirectory(skill) {
      this.closeContextMenu()
      if (!skill || !skill.path) return
      if (window.electronAPI && window.electronAPI.openPath) {
        const error = await window.electronAPI.openPath(skill.path)
        if (error) this.$message.error(error)
      } else {
        this.$message.info(skill.path)
      }
    }
  }
}
</script>

<style scoped>
.skill-manager { flex: 1; min-width: 0; background: #f7f8fa; color: #252b36; overflow: auto; }
.skill-manager-header { height: 108px; box-sizing: border-box; padding: 24px 32px; background: #fff; border-bottom: 1px solid #e7e9ee; display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.skill-manager-header h2 { margin: 0 0 7px; font-size: 20px; }
.skill-manager-header p { margin: 0; color: #8a93a3; font-size: 13px; }
.skill-manager-actions { display: flex; gap: 8px; flex-shrink: 0; }
.skill-manager-content { width: min(920px, calc(100% - 64px)); margin: 28px auto; }
.skill-import-zone { display: flex; min-height: 72px; box-sizing: border-box; align-items: center; gap: 13px; margin-bottom: 18px; padding: 12px 14px; border: 1px dashed #c9d4ec; border-radius: 11px; background: #f8faff; color: #66758d; cursor: pointer; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.skill-import-zone:hover, .skill-import-zone:focus { border-color: #7290dc; background: #f3f6ff; outline: none; box-shadow: 0 0 0 2px rgba(88, 121, 217, .1); }
.skill-import-zone.is-dragging { border-color: #5879d9; background: #eaf0ff; box-shadow: 0 0 0 3px rgba(88, 121, 217, .14); }
.skill-import-zone.is-importing { cursor: wait; }
.skill-import-icon { position: relative; display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 10px; background: #e8edff; color: #5879d9; font-size: 20px; }
.skill-import-badge { position: absolute; right: 2px; bottom: 2px; display: grid; width: 13px; height: 13px; place-items: center; border: 2px solid #f8faff; border-radius: 50%; background: #5879d9; color: #fff; font-size: 8px; }
.is-dragging .skill-import-icon { background: #dce6ff; color: #4167c7; transform: translateY(-1px); }
.skill-import-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 5px; }
.skill-import-copy b { color: #3e4d68; font-size: 13px; }
.skill-import-copy span { color: #8a95a7; font-size: 11px; }
.skill-import-copy code { padding: 1px 4px; border-radius: 3px; background: #e9eefb; color: #526da9; }
.skill-import-actions { display: flex; flex: 0 0 auto; }
.skill-toolbar { display: flex; gap: 10px; margin-bottom: 16px; }
.skill-toolbar .el-input { max-width: 420px; }
.skill-list { display: grid; min-height: 260px; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 11px; align-content: start; }
.skill-card { position: relative; display: flex; min-width: 0; min-height: 108px; box-sizing: border-box; align-items: flex-start; gap: 11px; padding: 14px; background: #fff; border: 1px solid #e3e7ed; border-radius: 11px; outline: none; transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.skill-card:hover, .skill-card:focus { border-color: #aebbe2; box-shadow: 0 7px 18px rgba(44, 57, 92, .09); transform: translateY(-1px); }
.skill-icon { width: 34px; height: 34px; border-radius: 9px; display: grid; place-items: center; background: #eef2ff; color: #5879d9; font-size: 17px; flex-shrink: 0; }
.skill-card-body { flex: 1; min-width: 0; }
.skill-name { overflow: hidden; color: #303640; font-weight: 600; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.skill-card p { display: -webkit-box; overflow: hidden; margin: 7px 0 0; color: #7e8793; font-size: 10px; line-height: 16px; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.skill-scope { flex: 0 0 auto; padding: 2px 6px; color: #7385bb; background: #eef2ff; border-radius: 5px; font-size: 10px; }
.skill-list .el-empty { grid-column: 1 / -1; }
.skill-context-menu { position: fixed; z-index: 5000; width: 220px; box-sizing: border-box; padding: 5px; background: #fff; border: 1px solid #dfe4ec; border-radius: 10px; box-shadow: 0 12px 30px rgba(31, 42, 68, .18), 0 2px 7px rgba(31, 42, 68, .08); }
.skill-context-menu button { display: flex; width: 100%; min-height: 48px; align-items: center; gap: 9px; padding: 6px 7px; color: #4f5866; background: transparent; border: 0; border-radius: 7px; text-align: left; cursor: pointer; }
.skill-context-menu button:hover, .skill-context-menu button:focus { color: #334983; background: #f1f4ff; outline: none; }
.skill-context-icon { display: inline-flex; width: 28px; height: 28px; flex: 0 0 28px; align-items: center; justify-content: center; color: #6079d8; background: #edf1ff; border-radius: 7px; font-size: 14px; }
.skill-context-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }
.skill-context-copy b { color: inherit; font-size: 11px; font-weight: 600; line-height: 15px; }
.skill-context-copy small { overflow: hidden; color: #969eaa; font-size: 9px; line-height: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 980px) { .skill-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .skill-manager-header { height: auto; align-items: flex-start; flex-direction: column; } .skill-manager-content { width: calc(100% - 32px); } .skill-list { grid-template-columns: 1fr; } .skill-import-actions { display: none; } }
</style>
