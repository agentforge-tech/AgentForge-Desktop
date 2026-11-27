<template>
  <span
    class="composer-resource-chip"
    :class="[`is-${kind}`, { 'composer-inline-resource': inline }]"
    :title="`${path}\n点击打开`"
    :contenteditable="inline ? 'false' : undefined"
    :data-resource-path="inline ? path : undefined"
    :data-resource-marker="inline ? marker : undefined"
    @click="$emit('preview', path)"
  >
    <svg
      v-if="kind === 'image'"
      class="resource-chip-icon"
      viewBox="0 0 16 16"
      aria-hidden="true"
      v-html="iconPaths"
    ></svg>
    <svg
      v-else
      class="resource-chip-icon"
      viewBox="0 0 16 16"
      aria-hidden="true"
      v-html="iconPaths"
    ></svg>
    <span>{{ displayName }}</span>
    <button v-if="removable" class="inline-resource-remove" type="button" title="移除资源" @click.stop="$emit('remove')">×</button>
  </span>
</template>

<script>
import { chipIconPaths, normalizeChipKind } from '../utils/resourceChipIcon'

export default {
  name: 'ComposerResourceChip',
  props: {
    path: { type: String, required: true },
    name: { type: String, default: '' },
    type: { type: String, default: '' },
    removable: { type: Boolean, default: true },
    inline: { type: Boolean, default: false },
    marker: { type: String, default: '' }
  },
  computed: {
    displayName() {
      const base = this.name
        || String(this.path || '').split(/[\\/]/).filter(Boolean).pop()
        || this.path
      return this.kind === 'folder' && !String(base).endsWith('/') ? `${base}/` : base
    },
    kind() {
      return normalizeChipKind(this.type)
    },
    /** 图标路径片段与原地替换共用同一份定义，避免两处样式走样。 */
    iconPaths() {
      return chipIconPaths(this.kind)
    }
  }
}
</script>

<style scoped>
.composer-resource-chip{display:inline-flex;max-width:250px;height:24px;box-sizing:border-box;align-items:center;gap:5px;padding:0 6px 0 7px;color:#424851;background:#f4f5f7;border:1px solid #e4e6ea;border-radius:7px;box-shadow:0 1px 1px rgba(31,35,41,.02);cursor:pointer;font-size:12px;font-weight:500;line-height:1;vertical-align:middle}
.composer-resource-chip.composer-inline-resource{margin:0 6px 0 4px;vertical-align:-2px;user-select:none}
.resource-chip-icon{width:14px;height:14px;flex:0 0 14px;overflow:visible;fill:none;stroke:#6680cf;stroke-width:1.35;stroke-linecap:round;stroke-linejoin:round}
.resource-chip-icon circle{fill:#6680cf;stroke:none}
.composer-resource-chip>span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.composer-resource-chip>button{display:inline-flex;width:14px;height:14px;align-items:center;justify-content:center;padding:0;color:#9299a3;background:transparent;border:0;border-radius:50%;cursor:pointer;font-size:13px;line-height:1}
.composer-resource-chip>button:hover{color:#d7525b;background:#ffecee}
.composer-resource-chip.is-folder .resource-chip-icon{stroke:#c9913b}
.composer-resource-chip.is-image .resource-chip-icon{stroke:#4f8dd8}
</style>
