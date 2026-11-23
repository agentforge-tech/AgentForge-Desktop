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
    />
  </div>
</template>

<script>
import Vue from 'vue'
import ComposerResourceChip from './ComposerResourceChip.vue'

const CARET_ANCHOR = '\u200B'
const RESOURCE_PATTERN = /\[\[agentforge-resource:([^:\]]+):([^\]]+)\]\]/g
const ComposerResourceChipCtor = Vue.extend(ComposerResourceChip)

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
    resources: { type: Array, default: () => [] }
  },
  data: () => ({ savedSelection: null }),
  computed: {
    isEmpty() {
      return !this.value
    },
    hasResources() {
      return /\[\[agentforge-resource:/.test(this.value || '')
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
    renderValue() {
      const editor = this.$refs.editor
      if (!editor) return
      if (!this.value) {
        editor.innerText = CARET_ANCHOR
        return
      }
      const resourceMap = this.resourceMap()
      let cursor = 0
      const fragments = []
      for (const match of this.value.matchAll(RESOURCE_PATTERN)) {
        fragments.push(this.renderText(this.value.slice(cursor, match.index)))
        const resourcePath = decodeURIComponent(match[2])
        const resource = resourceMap[resourcePath] || {}
        fragments.push(`${this.renderResource(resourcePath, match[0], resource)}${CARET_ANCHOR}`)
        cursor = Number(match.index) + match[0].length
      }
      fragments.push(this.renderText(this.value.slice(cursor)))
      editor.innerHTML = fragments.join('')
    },
    renderText(value) {
      return escapeHtml(value).replace(/\n/g, '<br>')
    },
    renderResource(path, marker, resource) {
      const component = new ComposerResourceChipCtor({
        propsData: {
          path,
          marker,
          inline: true,
          removable: true,
          name: resource.name || '',
          type: resource.type || ''
        }
      })
      component.$mount()
      const html = component.$el.outerHTML
      component.$destroy()
      return html
    },
    serializeValue(value) {
      return String(value || '').replace(RESOURCE_PATTERN, '').replace(new RegExp(CARET_ANCHOR, 'g'), '').trim()
    },
    serializeEditor() {
      const walk = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          return String(node.textContent || '').split(CARET_ANCHOR).join('')
        }
        const element = node
        if (element.dataset && element.dataset.resourceMarker) return element.dataset.resourceMarker
        if (element.tagName === 'BR') return '\n'
        return Array.from(node.childNodes).map(walk).join('') + (element.tagName === 'DIV' ? '\n' : '')
      }
      return Array.from((this.$refs.editor || { childNodes: [] }).childNodes)
        .map(walk)
        .join('')
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
      const token = event.target && event.target.closest && event.target.closest('.composer-inline-resource')
      if (!token) return
      if (event.target.classList && event.target.classList.contains('inline-resource-remove')) {
        this.$emit('remove-resource', token.dataset.resourcePath || '')
      } else {
        this.$emit('preview-resource', token.dataset.resourcePath || '')
      }
    },
    handleKeydown(event) {
      if (event.isComposing || event.keyCode === 229) return
      if (event.key !== 'Enter') return
      if (event.shiftKey) return
      event.preventDefault()
      this.$emit('enter', event)
    },
    saveSelection() {
      const selection = window.getSelection()
      const editor = this.$refs.editor
      if (!selection || !selection.rangeCount || !editor) {
        this.savedSelection = null
        return
      }
      const range = selection.getRangeAt(0)
      this.savedSelection = editor.contains(range.commonAncestorContainer)
        ? range.cloneRange()
        : null
    },
    insertResourceMarkers(markers) {
      this.focus()
      const editor = this.$refs.editor
      const selection = window.getSelection()
      if (this.savedSelection && selection && editor
        && editor.contains(this.savedSelection.commonAncestorContainer)) {
        selection.removeAllRanges()
        selection.addRange(this.savedSelection)
      } else if (editor && selection
        && (!selection.rangeCount || !editor.contains(selection.anchorNode))) {
        const range = document.createRange()
        range.selectNodeContents(editor)
        range.collapse(false)
        selection.removeAllRanges()
        selection.addRange(range)
      }
      document.execCommand('insertText', false, markers)
      this.savedSelection = null
      this.handleInput()
      const matches = [...String(markers || '').matchAll(RESOURCE_PATTERN)]
      const lastPath = matches.length ? decodeURIComponent(matches[matches.length - 1][2]) : ''
      this.$nextTick(() => {
        this.renderValue()
        this.focusAfterResource(lastPath)
      })
    },
    focusAfterResource(resourcePath) {
      const editor = this.$refs.editor
      if (!editor || !resourcePath) return
      const token = Array.from(editor.querySelectorAll('.composer-inline-resource'))
        .find((item) => item.dataset.resourcePath === resourcePath)
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
    handlePaste(event) {
      const clipboard = event.clipboardData
      const types = Array.from((clipboard && clipboard.types) || [])
      const items = Array.from((clipboard && clipboard.items) || [])
      const hasFiles = types.includes('Files') || items.some((item) => item.kind === 'file')
      if (hasFiles) {
        event.preventDefault()
        this.$emit('paste-files', event)
        return
      }
      event.preventDefault()
      const text = (clipboard && clipboard.getData('text/plain')) || ''
      document.execCommand('insertText', false, text)
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
