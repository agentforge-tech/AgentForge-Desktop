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
    <svg v-if="kind === 'image'" class="resource-chip-icon" viewBox="0 0 16 16" aria-hidden="true"><rect x="2.25" y="2.25" width="11.5" height="11.5" rx="2"/><circle cx="5.4" cy="5.5" r="1.15"/><path d="m3.6 12 3.05-3.1 1.8 1.75 1.55-1.5 2.4 2.35"/></svg>
    <svg v-else-if="kind === 'folder'" class="resource-chip-icon" viewBox="0 0 16 16" aria-hidden="true"><path d="M1.9 4.4c0-.8.65-1.45 1.45-1.45h3.1l1.4 1.5h4.35c.8 0 1.45.65 1.45 1.45v5.75c0 .8-.65 1.45-1.45 1.45H3.35c-.8 0-1.45-.65-1.45-1.45z"/><path d="M2 6.3h12"/></svg>
    <svg v-else class="resource-chip-icon" viewBox="0 0 16 16" aria-hidden="true"><path d="M4 1.75h5.2L12.75 5.3V14.25H4z"/><path d="M9 1.9v3.6h3.55M6 8h4.6M6 10.5h4.6"/></svg>
    <span>{{ displayName }}</span>
    <button v-if="removable" class="inline-resource-remove" type="button" title="移除资源" @click.stop="$emit('remove')">×</button>
  </span>
</template>

<script>
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
      if (this.type) return this.type
      return 'file'
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
