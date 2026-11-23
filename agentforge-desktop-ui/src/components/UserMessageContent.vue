<template>
  <div class="user-message-content">
    <template v-for="(segment, index) in segments">
      <span v-if="segment.type === 'text'" :key="'text-' + index" class="user-message-text">{{ segment.value }}</span>
      <ComposerResourceChip
        v-else
        :key="'resource-' + index"
        :path="segment.path"
        :name="segment.name"
        :type="segment.typeName"
        :removable="false"
        @preview="$emit('preview', segment.path)"
      />
    </template>
  </div>
</template>

<script>
import ComposerResourceChip from './ComposerResourceChip.vue'

const RESOURCE_PATTERN = /\[\[agentforge-resource:([^:\]]+):([^\]]+)\]\]/g

export default {
  name: 'UserMessageContent',
  components: { ComposerResourceChip },
  props: {
    text: { type: String, default: '' },
    rawText: { type: String, default: '' },
    resources: { type: Array, default: () => [] }
  },
  computed: {
    resourceMap() {
      const map = {}
      ;(this.resources || []).forEach((item) => {
        if (item && item.path) map[item.path] = item
      })
      return map
    },
    segments() {
      if (this.rawText) {
        const result = []
        const seen = new Set()
        let cursor = 0
        for (const match of this.rawText.matchAll(RESOURCE_PATTERN)) {
          if (match.index > cursor) {
            result.push({ type: 'text', value: this.rawText.slice(cursor, match.index) })
          }
          const path = decodeURIComponent(match[2])
          seen.add(path)
          const resource = this.resourceMap[path] || {}
          result.push({
            type: 'resource',
            path,
            name: resource.name || '',
            typeName: resource.type || match[1]
          })
          cursor = Number(match.index) + match[0].length
        }
        if (cursor < this.rawText.length) {
          result.push({ type: 'text', value: this.rawText.slice(cursor) })
        }
        ;(this.resources || []).forEach((item) => {
          if (!item || !item.path || seen.has(item.path)) return
          result.push({
            type: 'resource',
            path: item.path,
            name: item.name || '',
            typeName: item.type || ''
          })
        })
        return result
      }
      const result = []
      if (this.text) result.push({ type: 'text', value: this.text })
      ;(this.resources || []).forEach((item) => {
        if (!item || !item.path) return
        result.push({
          type: 'resource',
          path: item.path,
          name: item.name || '',
          typeName: item.type || ''
        })
      })
      return result
    }
  }
}
</script>

<style scoped>
.user-message-content {
  display: inline;
  white-space: pre-wrap;
  word-break: break-word;
}
.user-message-text {
  white-space: pre-wrap;
}
.user-message-content ::v-deep .composer-resource-chip {
  margin: 1px 4px;
  vertical-align: -3px;
}
</style>
