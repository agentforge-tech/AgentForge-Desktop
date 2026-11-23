<template>
  <div class="workspace-picker-shell">
    <el-popover v-model="open" placement="top-start" width="248" trigger="click" popper-class="workspace-picker-popover" :disabled="locked">
      <div class="workspace-menu">
        <el-input v-model="keyword" size="small" prefix-icon="el-icon-search" placeholder="搜索工作空间" clearable />
        <div class="workspace-menu-list">
          <button v-for="item in filtered" :key="item.id" type="button" @click="select(item)">
            <i class="el-icon-folder"></i><span>{{ item.name }}</span>
            <i v-if="selectedId === item.id" class="el-icon-check"></i>
          </button>
          <div v-if="!filtered.length" class="workspace-menu-empty">暂无工作空间</div>
        </div>
        <div class="workspace-menu-actions">
          <button type="button" @click="showCreate"><i class="el-icon-plus"></i><span>新建工作空间</span></button>
          <button type="button" @click="chooseFolder"><i class="el-icon-folder-opened"></i><span>打开本地文件夹</span></button>
          <button type="button" @click="showImportPath"><i class="el-icon-edit-outline"></i><span>输入路径添加</span></button>
          <button v-if="selectedId" type="button" @click="clearSelection"><i class="el-icon-close"></i><span>取消选择</span></button>
        </div>
      </div>
      <button slot="reference" class="workspace-picker" type="button" :title="buttonTitle">
        <i class="el-icon-folder"></i>
        <span>{{ selected ? selected.name : '选择工作空间' }}</span>
        <i :class="open ? 'el-icon-arrow-up' : 'el-icon-arrow-down'"></i>
      </button>
    </el-popover>

    <el-dialog title="新建工作空间" :visible.sync="createVisible" width="480px" append-to-body custom-class="workspace-create-dialog">
      <p class="workspace-create-tip">为工作空间命名，本地将在首次使用时自动创建同名文件夹，创建后暂不支持重命名。</p>
      <el-input ref="nameInput" v-model="name" maxlength="60" placeholder="输入工作空间名称" @keyup.enter.native="confirmCreate" />
      <span slot="footer">
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!name.trim()" :loading="creating" @click="confirmCreate">确认</el-button>
      </span>
    </el-dialog>

    <el-dialog title="输入路径添加工作空间" :visible.sync="pathVisible" width="520px" append-to-body custom-class="workspace-create-dialog">
      <p class="workspace-create-tip">直接粘贴本地文件夹完整路径；macOS 可在访达中按 ⌥⌘C 复制路径，支持 <code>file://</code> 与 <code>~/</code> 路径。</p>
      <el-input
        ref="pathInput"
        v-model="folderPath"
        placeholder="/Users/you/Documents/project"
        clearable
        @keyup.enter.native="confirmImportPath"
      >
        <el-button slot="append" icon="el-icon-document-copy" @click="fillFromClipboard(true)">粘贴</el-button>
      </el-input>
      <span slot="footer">
        <el-button @click="pathVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!folderPath.trim()" :loading="importing" @click="confirmImportPath">确认</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { createWorkspace, importWorkspace } from '../api/workspace'

export default {
  name: 'WorkspacePicker',
  props: {
    workspaces: { type: Array, default: () => [] },
    selectedId: { type: Number, default: null },
    locked: { type: Boolean, default: false }
  },
  data() {
    return {
      open: false,
      keyword: '',
      createVisible: false,
      name: '',
      creating: false,
      pathVisible: false,
      folderPath: '',
      importing: false
    }
  },
  computed: {
    selected() { return this.workspaces.find((item) => item.id === this.selectedId) || null },
    filtered() {
      const key = this.keyword.trim().toLowerCase()
      return key ? this.workspaces.filter((item) => String(item.name || '').toLowerCase().includes(key)) : this.workspaces
    },
    buttonTitle() {
      if (this.locked) return this.selected ? this.selected.folderPath : '当前会话使用独立任务目录'
      return this.selected ? this.selected.folderPath : '选择工作空间'
    }
  },
  methods: {
    select(item) { this.$emit('select', item); this.open = false },
    clearSelection() { this.$emit('clear'); this.open = false },
    showCreate() {
      this.open = false
      this.name = ''
      this.createVisible = true
      this.$nextTick(() => this.$refs.nameInput && this.$refs.nameInput.focus())
    },
    async confirmCreate() {
      const name = this.name.trim()
      if (!name || this.creating) return
      this.creating = true
      try {
        const item = await createWorkspace(name)
        this.$emit('created', item)
        this.createVisible = false
        this.$message.success('工作空间已创建')
      } catch (error) {
        this.$message.error(error.message || '创建工作空间失败')
      } finally {
        this.creating = false
      }
    },
    async chooseFolder() {
      this.open = false
      if (!window.electronAPI || !window.electronAPI.chooseDirectory) {
        this.$message.info('浏览器模式不支持选择本地目录')
        return
      }
      const selected = await window.electronAPI.chooseDirectory('')
      if (!selected) return
      await this.importFolderPath(selected, '本地文件夹已添加')
    },
    showImportPath() {
      this.open = false
      this.folderPath = ''
      this.pathVisible = true
      this.$nextTick(async () => {
        if (this.$refs.pathInput) this.$refs.pathInput.focus()
        await this.fillFromClipboard(false)
      })
    },
    async confirmImportPath() {
      const path = this.normalizeFolderPath(this.folderPath)
      if (!path || this.importing) return
      await this.importFolderPath(path, '工作空间已就绪')
    },
    async importFolderPath(folderPath, successMessage) {
      this.importing = true
      try {
        const item = await importWorkspace(folderPath)
        this.$emit('created', item)
        this.pathVisible = false
        this.$message.success(successMessage)
      } catch (error) {
        this.$message.error(error.message || '添加本地文件夹失败')
      } finally {
        this.importing = false
      }
    },
    async fillFromClipboard(notify) {
      if (!window.electronAPI || !window.electronAPI.readClipboardText) {
        if (notify) this.$message.info('当前环境不支持读取剪贴板')
        return
      }
      try {
        const candidate = this.normalizeFolderPath(await window.electronAPI.readClipboardText())
        if (candidate && this.looksLikePath(candidate)) {
          this.folderPath = candidate
          return
        }
        if (notify) this.$message.warning('剪贴板中没有可用的文件夹路径')
      } catch (error) {
        if (notify) this.$message.warning('读取剪贴板失败')
      }
    },
    normalizeFolderPath(raw) {
      let value = String(raw || '').trim()
      if (!value) return ''
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1).trim()
      }
      if (/^file:\/\//i.test(value)) {
        try {
          value = decodeURIComponent(new URL(value).pathname)
        } catch (error) {
          return ''
        }
      }
      return value
    },
    looksLikePath(value) {
      const path = String(value || '').trim()
      return /^\//.test(path) || /^~\//.test(path) || /^[A-Za-z]:[\\/]/.test(path) || path === '~'
    }
  }
}
</script>

<style scoped>
.workspace-picker-shell {
  display: inline-flex;
  width: 190px;
  min-width: 160px;
  max-width: 70%;
}
.workspace-picker-shell .workspace-picker {
  width: 100%;
  max-width: none;
  box-sizing: border-box;
}
.workspace-picker-shell .workspace-picker > span {
  flex: 1;
  min-width: 0;
  text-align: left;
}
.workspace-menu { display: flex; flex-direction: column; gap: 8px; }
.workspace-menu-list { max-height: 190px; overflow-y: auto; }
.workspace-menu-list button,
.workspace-menu-actions button {
  width: 100%; display: flex; align-items: center; gap: 9px; border: 0; border-radius: 7px;
  padding: 8px 9px; background: transparent; color: #303846; font-size: 13px; cursor: pointer; text-align: left;
}
.workspace-menu-list button:hover, .workspace-menu-actions button:hover { background: #f4f6f8; }
.workspace-menu-list button span, .workspace-menu-actions button span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-menu-empty { padding: 14px 8px; color: #a0a7b2; font-size: 12px; text-align: center; }
.workspace-menu-actions { border-top: 1px solid #edf0f3; padding-top: 6px; }
.workspace-create-tip { margin: -4px 0 18px; color: #667085; font-size: 14px; line-height: 1.7; }
</style>
