<template>
  <div class="user-message-content">
    <template v-for="(segment, index) in segments">
      <span v-if="segment.type === 'text'" :key="'text-' + index" class="user-message-text">{{ segment.value }}</span>
      <ComposerSkillChip
        v-else-if="segment.type === 'skill'"
        :key="'skill-' + index"
        :name="segment.name"
        :path="segment.path"
        :removable="false"
        @open="$emit('open-skill', { name: segment.name, path: segment.path })"
      />
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
import ComposerSkillChip from './ComposerSkillChip.vue'
import {
  createResourceMarkerRegExp,
  createSkillMarkerRegExp,
  decodeResourcePath,
  inferResourceType,
  RESOURCE_MARKER_SOURCE,
  SKILL_MARKER_SOURCE
} from '../utils/resourceType'

// 资源组在前（1、2），Skill 组在后（3、4），顺序固定便于一次扫描区分
const MARKER_PATTERN_SOURCE = `${RESOURCE_MARKER_SOURCE}|${SKILL_MARKER_SOURCE}`

export default {
  name: 'UserMessageContent',
  components: { ComposerResourceChip, ComposerSkillChip },
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
        // 一次扫描覆盖资源与 Skill，保证两者都按原文位置渲染
        for (const match of this.rawText.matchAll(new RegExp(MARKER_PATTERN_SOURCE, 'g'))) {
          if (match.index > cursor) {
            result.push({ type: 'text', value: this.rawText.slice(cursor, match.index) })
          }
          if (match[1] !== undefined) {
            const path = decodeResourcePath(match[2])
            seen.add(path)
            // 标记里的类型是历史快照，可能已经过期（例如旧版把文件夹写成 file），
            // 能用真实资源类型时一律以资源为准
            const resource = this.resourceMap[path] || {}
            result.push({
              type: 'resource',
              path,
              name: resource.name || '',
              typeName: inferResourceType({ ...resource, path, type: resource.type || match[1] })
            })
          } else {
            result.push({
              type: 'skill',
              name: decodeResourcePath(match[3]),
              path: decodeResourcePath(match[4])
            })
          }
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
            typeName: inferResourceType(item)
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
          typeName: inferResourceType(item)
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
.user-message-content ::v-deep .composer-skill-chip {
  margin: 1px 4px;
  vertical-align: -3px;
}
</style>
