<template>
  <div class="composer-input-shell" @click="focus">
    <span
      ref="editor"
      class="composer-input-editor"
      :class="{ 'is-disabled': disabled, 'is-empty': isEmpty, 'has-resources': hasResources }"
      :contenteditable="disabled ? 'false' : 'true'"
      :data-placeholder="placeholder"
      role="textbox"
      aria-multiline="true"
      @input="handleInput"
      @click="handleEditorClick"
      @keydown="handleKeydown"
      @paste="handlePaste"
      @dragover="handleDragOver"
    />
  </div>
</template>

<script>
import Vue from 'vue'
import ComposerResourceChip from './ComposerResourceChip.vue'
import ComposerSkillChip from './ComposerSkillChip.vue'
import {
  CARET_ANCHOR,
  UNIT,
  resolveCaretPoint,
  serializedTextLength,
  textOffsetToDomOffset
} from '../utils/caretOffset'
import {
  RESOURCE_MARKER_SOURCE,
  SKILL_MARKER_SOURCE,
  createResourceMarkerRegExp,
  createSkillMarkerRegExp,
  decodeResourcePath,
  inferResourceType
} from '../utils/resourceType'
import { chipIconMarkup } from '../utils/resourceChipIcon'

const ComposerResourceChipCtor = Vue.extend(ComposerResourceChip)
const ComposerSkillChipCtor = Vue.extend(ComposerSkillChip)
// 资源组在前（1、2），Skill 组在后（3、4）：顺序固定，便于一次扫描区分两类标记
const MARKER_PATTERN_SOURCE = `${RESOURCE_MARKER_SOURCE}|${SKILL_MARKER_SOURCE}`

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

export default {
  name: 'ComposerInput',
  props: {
    value: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
    resources: { type: Array, default: () => [] },
    // 已插入正文的 Skill（用于「只选 Skill 不写正文」时的空态判定）
    skills: { type: Array, default: () => [] },
    // 面板键盘托管：返回 true 表示事件已被消费，输入框不再触发发送
    shortcutHandler: { type: Function, default: null }
  },
  data: () => ({
    // 选区用「序列化文本偏移」保存，而不是 Range：
    // Chip 重绘会整体替换 innerHTML，Range 会失效并让选区塌陷到开头。
    savedCaretOffset: null,
    // dragover 期间记下的光标：drop 到达时选区可能已被清空
    dragCaretOffset: null
  }),
  computed: {
    isEmpty() {
      return !this.value && !(this.skills && this.skills.length)
    },
    hasResources() {
      return /\[\[agentforge-(resource|skill):/.test(this.value || '')
    },
    /**
     * 资源类型签名。
     *
     * Chip 图标由 resources 的类型决定，而类型可能在挂载后才被纠正
     * （会话恢复 → 回主进程核验真实类型 → 文件夹从 file 变成 folder）。
     * 此时标记文本可能没变，value 不会触发重绘，需要靠这个签名兜底。
     */
    resourceSignature() {
      return (this.resources || [])
        .map((item) => `${(item && item.path) || ''}\u0001${(item && item.type) || ''}`)
        .join('\u0002')
    }
  },
  mounted() {
    this.renderValue()
    this.$nextTick(this.resizeShell)
  },
  watch: {
    value() {
      const editor = this.$refs.editor
      const currentValue = editor ? this.serializeEditor().replace(/\r/g, '') : ''
      if (currentValue !== this.value) {
        this.renderValue()
      } else if (!this.value) {
        this.ensureCaretAnchor()
      }
      this.$nextTick(this.resizeShell)
    },
    // 只改类型、不改标记时也要更新图标，否则输入框里仍是旧图标。
    // 注意：这里绝不能重建 innerHTML —— 那会让选区塌陷到编辑器开头，
    // 后续插入的资源就会错误地跑到最前面。只原地替换每个 Chip 的图标与类名。
    resourceSignature() {
      this.refreshChipKinds()
    },
    disabled() {
      this.$nextTick(this.resizeShell)
    }
  },
  methods: {
    resourceMap() {
      const map = {}
      ;(this.resources || []).forEach((item) => {
        if (item && item.path) map[item.path] = item
      })
      return map
    },
    /**
     * 重建编辑器内容。
     *
     * 会先记录光标偏移、重建后立即恢复：Chip 重绘（资源类型纠正、增删资源）
     * 都会走到这里，若不恢复，Chrome 会把选区塌陷到编辑器开头，
     * 后续插入就变成"资源跑到最前面"。
     *
     * @param {boolean} keepCaret 是否恢复光标（默认恢复）
     */
    renderValue(keepCaret = true) {
      this.renderFrom(this.value, keepCaret)
    },
    /** 按给定文本重建编辑器；keepCaret 控制是否恢复光标。 */
    renderFrom(text, keepCaret = true) {
      const editor = this.$refs.editor
      if (!editor) return
      // 仅在光标确实位于编辑器内时才记录，避免抢走外部焦点
      const offset = keepCaret ? this.currentCaretOffset() : null
      const value = String(text || '')
      if (!value) {
        editor.innerText = CARET_ANCHOR
        if (offset != null) this.restoreCaretOffset(0)
        return
      }
      const resourceMap = this.resourceMap()
      // 两类标记共用一个扫描：谁在前谁先渲染，保证正文顺序与用户书写一致
      const pattern = new RegExp(MARKER_PATTERN_SOURCE, 'g')
      let cursor = 0
      const fragments = []
      for (const match of value.matchAll(pattern)) {
        fragments.push(this.renderText(value.slice(cursor, match.index)))
        if (match[1] !== undefined) {
          const resourcePath = decodeResourcePath(match[2])
          const resource = resourceMap[resourcePath] || {}
          fragments.push(`${this.renderResource(resourcePath, match[0], resource, match[1])}${CARET_ANCHOR}`)
        } else {
          fragments.push(`${this.renderSkill(match[3], match[4], match[0])}${CARET_ANCHOR}`)
        }
        cursor = Number(match.index) + match[0].length
      }
      fragments.push(this.renderText(value.slice(cursor)))
      editor.innerHTML = fragments.join('')
      if (offset != null) this.restoreCaretOffset(offset)
    },
    /**
     * 就地刷新所有 Chip 的类型样式与图标。
     *
     * 不触碰文本节点、不重建 innerHTML，因此选区完全不受影响。
     * 这是「资源类型被纠正」时唯一安全的更新方式。
     */
    refreshChipKinds() {
      const editor = this.$refs.editor
      if (!editor) return
      const resourceMap = this.resourceMap()
      Array.from(editor.querySelectorAll('.composer-inline-resource')).forEach((token) => {
        const path = token.dataset.resourcePath
        if (!path) return
        const resource = resourceMap[path] || {}
        const markerType = this.markerTypeOf(token.dataset.resourceMarker || '')
        const kind = inferResourceType({ ...resource, path, type: resource.type || markerType || '' })
        if (token.dataset.chipKind === kind) return
        token.dataset.chipKind = kind
        // 类名用于配色（is-folder / is-image）
        Array.from(token.classList)
          .filter((name) => name.startsWith('is-'))
          .forEach((name) => token.classList.remove(name))
        token.classList.add(`is-${kind}`)
        const icon = token.querySelector('.resource-chip-icon')
        if (icon) icon.outerHTML = chipIconMarkup(kind)
      })
    },
    /** 从标记原文中取类型，兜底用。 */
    markerTypeOf(marker) {
      const match = String(marker || '').match(/^\[\[agentforge-resource:([^:\]]+):/)
      return match ? match[1] : ''
    },
    renderText(value) {
      return escapeHtml(value).replace(/\n/g, '<br>')
    },
    renderResource(path, marker, resource, markerType) {
      // 类型以资源的真实类型为准：资源里没有可用类型时才回退到标记里的历史类型，
      // 否则会出现"输入框是文件图标、发出后是文件夹图标"的不一致。
      const type = inferResourceType({
        ...resource,
        path,
        type: resource.type || markerType || ''
      })
      const component = new ComposerResourceChipCtor({
        propsData: {
          path,
          marker,
          inline: true,
          removable: true,
          name: resource.name || '',
          type
        }
      })
      component.$mount()
      let html = component.$el.outerHTML
      component.$destroy()
      // 记下当前图标类型：刷新时只处理真正变化的 Chip，避免无谓的 DOM 操作
      html = html.replace(
        'class="composer-resource-chip',
        `data-chip-kind="${type}" class="composer-resource-chip`
      )
      return html
    },
    renderSkill(encodedName, encodedPath, marker) {
      const name = decodeResourcePath(encodedName)
      const path = decodeResourcePath(encodedPath)
      const component = new ComposerSkillChipCtor({
        propsData: { name, path, marker, inline: true, removable: true }
      })
      component.$mount()
      const html = component.$el.outerHTML
      component.$destroy()
      return html
    },
    serializeValue(value) {
      return String(value || '')
        .replace(createResourceMarkerRegExp(), '')
        .replace(createSkillMarkerRegExp(), '')
        .replace(new RegExp(CARET_ANCHOR, 'g'), '')
        .trim()
    },
    serializeEditor() {
      return this.serializeNodes((this.$refs.editor || { childNodes: [] }).childNodes)
        .replace(/\n$/, '')
    },
    handleInput(event) {
      const editor = (event && event.currentTarget) || this.$refs.editor
      const text = editor ? this.serializeEditor().replace(/\r/g, '') : ''
      this.$emit('input', text)
      if (!this.serializeValue(text)) this.$nextTick(this.ensureCaretAnchor)
      this.$nextTick(this.resizeShell)
    },
    handleEditorClick(event) {
      const target = event.target
      if (!target || !target.closest) return
      const skill = target.closest('.composer-inline-skill')
      if (skill) {
        if (target.classList && target.classList.contains('inline-skill-remove')) {
          this.$emit('remove-skill', skill.dataset.skillName || '')
        } else {
          this.$emit('open-skill', {
            name: skill.dataset.skillName || '',
            path: skill.dataset.skillPath || ''
          })
        }
        return
      }
      const token = target.closest('.composer-inline-resource')
      if (!token) return
      if (target.classList && target.classList.contains('inline-resource-remove')) {
        this.$emit('remove-resource', token.dataset.resourcePath || '')
      } else {
        this.$emit('preview-resource', token.dataset.resourcePath || '')
      }
    },
    handleKeydown(event) {
      if (event.isComposing || event.keyCode === 229) return
      // 面板优先消费键盘；返回 true 时不再触发发送
      if (this.shortcutHandler && this.shortcutHandler(event)) return
      // 输入 "/" 的瞬间 input 事件尚未触发，先上报光标变化让父组件及时开面板
      if (event.key === '/' || event.key === 'ArrowLeft' || event.key === 'ArrowRight'
        || event.key === 'Home' || event.key === 'End') {
        this.$emit('caret-change')
      }
      if (event.key !== 'Enter') return
      if (event.shiftKey) return
      event.preventDefault()
      this.$emit('enter', event)
    },
    /**
     * 保存当前光标位置。
     *
     * 只记录「编辑器起点 → 光标」的序列化文本长度，不保存 Range：
     * 后续 Chip 重绘会替换 innerHTML，Range 会随之失效。
     */
    saveSelection() {
      // 拖拽过程中浏览器会清掉选区，dragover 阶段记下的偏移更可信
      const offset = this.dragCaretOffset != null ? this.dragCaretOffset : this.currentCaretOffset()
      this.savedCaretOffset = offset
      this.dragCaretOffset = null
    },
    /**
     * 确保插入点已记录。
     *
     * 优先级是「真实选区 > 已有偏移」：
     * - 选区有效时以它为准。否则「打开添加资源对话框 → 取消 → 移动光标 → 再次添加」
     *   会沿用取消那一刻留下的旧偏移，资源被插到旧位置。
     * - 选区为空时（拖拽 drop、粘贴的异步等待期间浏览器会清掉选区）
     *   必须保留事件当刻记下的偏移，不能被覆盖成 null。
     */
    ensureSelectionSaved() {
      const live = this.currentCaretOffset()
      if (live != null) {
        this.savedCaretOffset = live
        this.dragCaretOffset = null
        return
      }
      if (this.savedCaretOffset != null) return
      this.saveSelection()
    },
    /** 当前光标对应的序列化文本偏移；光标不在编辑器内时返回 null。 */
    currentCaretOffset() {
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (!editor || !selection || !selection.rangeCount) return null
      const range = selection.getRangeAt(0)
      if (!editor.contains(range.startContainer)) return null
      const pre = document.createRange()
      pre.selectNodeContents(editor)
      pre.setEnd(range.startContainer, range.startOffset)
      return serializedTextLength(this.serializeNodes(pre.cloneContents().childNodes))
    },
    /**
     * 按序列化偏移把光标放回编辑器。
     *
     * 用于 innerHTML 重建后恢复位置（Chrome 会把选区塌陷到编辑器开头）。
     */
    restoreCaretOffset(offset) {
      const editor = this.$refs.editor
      if (!editor || offset == null) return false
      const units = this.collectCaretUnits(editor)
      const point = resolveCaretPoint(units, offset)
      const range = document.createRange()
      if (point.type === 'text') {
        const unit = units[point.index]
        range.setStart(unit.node, textOffsetToDomOffset(unit.node.textContent, point.offset))
      } else if (point.type === 'after') {
        range.setStartAfter(units[point.index].node)
      } else if (point.type === 'before') {
        range.setStartBefore(units[point.index].node)
      } else {
        range.selectNodeContents(editor)
        range.collapse(false)
      }
      range.collapse(true)
      editor.focus()
      const selection = window.getSelection()
      if (!selection) return false
      selection.removeAllRanges()
      selection.addRange(range)
      return true
    },
    /**
     * 按文档顺序收集可定位单元，长度规则与 serializeNodes 完全一致。
     *
     * 一致性是光标能落准的前提：这里少算或多算一个字符，光标就会偏一位。
     */
    collectCaretUnits(root) {
      const units = []
      const walk = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          units.push({ kind: UNIT.TEXT, node, length: serializedTextLength(node.textContent) })
          return
        }
        const element = node
        if (element.dataset && element.dataset.resourceMarker) {
          // Chip 内容不计入文本，按标记原文长度占位
          units.push({ kind: UNIT.CHIP, node: element, length: String(element.dataset.resourceMarker).length })
          return
        }
        if (element.tagName === 'BR') {
          units.push({ kind: UNIT.BR, node: element, length: 1 })
          return
        }
        Array.from(node.childNodes).forEach(walk)
        if (element.tagName === 'DIV') units.push({ kind: UNIT.NEWLINE, node: element, length: 1 })
      }
      Array.from(root.childNodes).forEach(walk)
      return units
    },
    insertResourceMarkers(markers) {
      const editor = this.$refs.editor
      if (!editor) return
      // 先确定插入点：优先用调用方保存的偏移（跨 innerHTML 重建有效），
      // 其次用当前真实选区，最后才退化到末尾——绝不默认从头插入。
      const saved = this.savedCaretOffset
      const live = this.currentCaretOffset()
      const insertAt = saved != null ? saved : live
      if (insertAt != null) {
        this.restoreCaretOffset(insertAt)
      } else {
        this.focus()
      }
      this.savedCaretOffset = null

      const committed = this.insertMarkersAtCaret(markers)
      if (!committed) return
      // 先把插入结果序列化下来：handleInput 会通知父组件，
      // 父组件的 value 要下一帧才更新，直接用 this.value 重绘会丢掉刚插入的内容。
      const inserted = this.serializeEditor().replace(/\r/g, '')
      this.handleInput()
      // 取最后一个标记原文定位 chip：资源与 Skill 都在这里插入，不能假设只有资源
      const lastMarker = this.lastMarkerIn(markers)
      this.$nextTick(() => {
        // 重绘后 DOM 已重建，必须按偏移重新落位，否则光标会跑到开头
        this.renderFrom(inserted)
        this.restoreCaretOffset(committed)
        this.focusAfterMarker(lastMarker)
      })
    },
    /**
     * 在光标处插入标记原文。
     *
     * 返回插入后光标应处的序列化偏移；插入失败返回 null。
     * 用 Range 手工写入而不是 execCommand：后者在自定义 Chip 附近
     * 行为不稳定（会把标记插进 Chip 内部或吞掉相邻锚点）。
     */
    insertMarkersAtCaret(markers) {
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (!editor || !selection || !selection.rangeCount) return null
      const range = selection.getRangeAt(0)
      if (!editor.contains(range.commonAncestorContainer)) return null
      if (!String(markers)) return null
      range.deleteContents()
      const textNode = document.createTextNode(String(markers))
      range.insertNode(textNode)
      // 光标落到插入文本之后，便于继续输入
      const after = document.createRange()
      after.setStartAfter(textNode)
      after.collapse(true)
      selection.removeAllRanges()
      selection.addRange(after)
      return this.currentCaretOffset()
    },
    lastMarkerIn(text) {
      const pattern = new RegExp(MARKER_PATTERN_SOURCE, 'g')
      let last = ''
      for (const match of String(text || '').matchAll(pattern)) last = match[0]
      return last
    },
    /**
     * 按资源路径把光标放到对应 chip 之后。
     *
     * <p>对外保留该方法（拖拽、粘贴链路仍按路径调用），内部统一走 marker 定位，
     * 与 Skill chip 共用同一套逻辑。
     */
    focusAfterResource(resourcePath) {
      const editor = this.$refs.editor
      if (!editor || !resourcePath) return
      const token = Array.from(editor.querySelectorAll('.composer-inline-resource'))
        .find((item) => item.dataset.resourcePath === resourcePath)
      if (!token || !token.dataset.resourceMarker) return
      this.focusAfterMarker(token.dataset.resourceMarker)
    },
    /** 把光标放到指定标记对应 chip 之后；资源与 Skill 共用。 */
    focusAfterMarker(marker) {
      const editor = this.$refs.editor
      if (!editor || !marker) return
      // 用 dataset 直接比对：路径可能含引号，拼进选择器会匹配失败
      const token = Array.from(editor.querySelectorAll('[data-resource-marker]'))
        .find((item) => item.dataset.resourceMarker === marker)
      if (!token) return
      editor.focus()
      const range = document.createRange()
      range.setStartAfter(token)
      range.collapse(true)
      const selection = window.getSelection()
      if (selection) {
        selection.removeAllRanges()
        selection.addRange(range)
      }
    },
    insertNewline() {
      this.focus()
      document.execCommand('insertLineBreak')
      this.handleInput()
    },
    /**
     * 返回光标前的纯文本（chip 记为其标记原文），最多 limit 个字符。
     *
     * <p>不能用 {@code range.toString()}：contenteditable 中 {@code <br>} 不产生换行，
     * 会把第二行行首的 "/" 误判成行内。这里序列化「编辑器起点 → 光标」，规则与
     * {@code serializeEditor} 一致。
     */
    getCaretTextBefore(limit = 40) {
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (!editor || !selection || !selection.rangeCount) return ''
      const range = selection.getRangeAt(0)
      if (!editor.contains(range.commonAncestorContainer)) return ''
      const pre = range.cloneRange()
      pre.selectNodeContents(editor)
      pre.setEnd(range.endContainer, range.endOffset)
      const text = this.serializeNodes(pre.cloneContents().childNodes)
      return text.slice(-limit)
    },
    /** 删除光标前的 count 个字符，用于抹掉 "/query" 触发片段。 */
    deleteBeforeCaret(count) {
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (!editor || !selection || !selection.rangeCount || count <= 0) return
      const caret = selection.getRangeAt(0)
      if (!editor.contains(caret.commonAncestorContainer)) return

      const pre = document.createRange()
      pre.selectNodeContents(editor)
      pre.setEnd(caret.endContainer, caret.endOffset)

      // 只统计「编辑器起点 → 光标」之间的文本节点，从后往前扣减字符数
      const targets = []
      const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT, null)
      let node = walker.nextNode()
      while (node) {
        if (pre.intersectsNode(node)) {
          const end = node === caret.endContainer ? caret.endOffset : node.length
          if (end > 0) targets.push({ node, end })
        }
        node = walker.nextNode()
      }

      let remaining = count
      let startNode = null
      let startOffset = 0
      for (let i = targets.length - 1; i >= 0 && remaining > 0; i--) {
        const target = targets[i]
        if (remaining <= target.end) {
          startNode = target.node
          startOffset = target.end - remaining
          remaining = 0
        } else {
          remaining -= target.end
        }
      }
      // 字符数不足说明前面还有 chip 或零宽锚点，放弃删除以免误删正文
      if (!startNode) return

      const range = document.createRange()
      range.setStart(startNode, startOffset)
      range.setEnd(caret.endContainer, caret.endOffset)
      range.deleteContents()
      // 删除后面两个 range 都已塌陷到删除点；normalize 后再显式收敛一次，
      // 避免合并文本节点后选区失效，导致随后的插入跑到行尾
      range.collapse(true)
      editor.normalize()
      const rest = window.getSelection()
      if (rest) {
        rest.removeAllRanges()
        rest.addRange(range)
      }
      this.handleInput()
      this.$nextTick(() => {
        this.ensureCaretAnchor()
        this.resizeShell()
      })
    },
    /** 与 serializeEditor 共用同一套遍历规则，供片段序列化复用。 */
    serializeNodes(nodes) {
      const walk = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          return String(node.textContent || '').split(CARET_ANCHOR).join('')
        }
        const element = node
        if (element.dataset && element.dataset.resourceMarker) return element.dataset.resourceMarker
        if (element.tagName === 'BR') return '\n'
        return Array.from(node.childNodes).map(walk).join('') + (element.tagName === 'DIV' ? '\n' : '')
      }
      return Array.from(nodes).map(walk).join('')
    },
    handlePaste(event) {
      const clipboard = event.clipboardData
      const types = Array.from((clipboard && clipboard.types) || [])
      const items = Array.from((clipboard && clipboard.items) || [])
      const hasFiles = types.includes('Files') || items.some((item) => item.kind === 'file')
      if (hasFiles) {
        event.preventDefault()
        // 父组件要等 IPC 返回才插入资源；此刻必须先记下光标，
        // 否则异步期间选区漂移会让资源插到错误位置。
        this.saveSelection()
        this.$emit('paste-files', event)
        return
      }
      event.preventDefault()
      const text = (clipboard && clipboard.getData('text/plain')) || ''
      if (!text) return
      // execCommand 能保住浏览器原生撤销栈，是首选路径；但它在部分 contenteditable
      // 组合下会返回 false 且什么都不插入。此时若不兜底，文本就彻底丢了。
      if (document.execCommand && document.execCommand('insertText', false, text)) return
      this.insertPlainTextAtCaret(text)
    },
    /** 光标处插入纯文本（换行转 <br>）；execCommand 不可用时的兜底。 */
    insertPlainTextAtCaret(text) {
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (!editor || !selection || !selection.rangeCount) return
      const range = selection.getRangeAt(0)
      if (!editor.contains(range.commonAncestorContainer)) return
      range.deleteContents()

      // 收集本次插入的节点，用来把光标停在最后一个节点之后
      const nodes = []
      const fragment = document.createDocumentFragment()
      String(text).replace(/\r\n?/g, '\n').split('\n').forEach((line, index) => {
        if (index > 0) {
          const br = document.createElement('br')
          fragment.appendChild(br)
          nodes.push(br)
        }
        if (line) {
          const textNode = document.createTextNode(line)
          fragment.appendChild(textNode)
          nodes.push(textNode)
        }
      })
      if (!nodes.length) return

      range.insertNode(fragment)
      const last = nodes[nodes.length - 1]
      const after = document.createRange()
      after.setStartAfter(last)
      after.collapse(true)
      selection.removeAllRanges()
      selection.addRange(after)
      // 通知父组件同步草稿，并把保证空态锚点
      this.handleInput()
      if (!this.serializeValue(this.serializeEditor())) this.$nextTick(this.ensureCaretAnchor)
    },
    /**
     * 拖拽经过编辑器时记录光标。
     *
     * drop 事件到达时浏览器可能已经清掉选区，此时再读 selection 会拿不到位置，
     * 资源就会插到开头。dragover 阶段选区还在，先记下来最稳妥。
     */
    handleDragOver() {
      this.dragCaretOffset = this.currentCaretOffset()
    },
    focus() {
      if (this.disabled) return
      const editor = this.$refs.editor
      if (!editor) return
      this.ensureCaretAnchor()
      editor.focus()
      if (!this.value) {
        const range = document.createRange()
        range.selectNodeContents(editor)
        range.collapse(false)
        const selection = window.getSelection()
        if (selection) {
          selection.removeAllRanges()
          selection.addRange(range)
        }
      }
    },
    ensureCaretAnchor() {
      const editor = this.$refs.editor
      if (editor && !editor.innerText) editor.innerText = CARET_ANCHOR
    },
    resizeShell() {
      const shell = this.$el
      if (!shell) return
      shell.style.height = 'auto'
      const styles = window.getComputedStyle(shell)
      const minHeight = parseFloat(styles.minHeight) || 74
      const maxHeight = parseFloat(styles.maxHeight) || 140
      const nextHeight = Math.max(minHeight, Math.min(shell.scrollHeight, maxHeight))
      shell.style.height = `${nextHeight}px`
      shell.style.overflowY = shell.scrollHeight > maxHeight ? 'auto' : 'hidden'
    }
  }
}
</script>

<style scoped>
.composer-input-shell{height:auto;min-height:74px;max-height:140px;box-sizing:border-box;overflow-y:hidden;padding:0;color:#111827;cursor:text;white-space:normal;word-break:break-word;font-size:14px;line-height:1.6}
.composer-input-editor{display:inline;min-width:2px;color:#111827;background:transparent;border:0;outline:0;font-family:inherit;font-size:14px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}
.composer-input-editor.is-empty::before{content:attr(data-placeholder);color:#9aa0aa;pointer-events:none}
.composer-input-editor.is-empty:focus::before{content:none}
.composer-input-editor.is-disabled{color:#a0a6af;cursor:not-allowed}
.composer-input-editor.has-resources{line-height:30px}
</style>
