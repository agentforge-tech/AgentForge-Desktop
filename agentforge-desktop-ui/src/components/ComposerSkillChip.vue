<template>
  <span
    class="composer-skill-chip composer-inline-skill"
    :class="{ 'is-inline': inline }"
    :title="`${path}\n点击打开 Skill 目录`"
    :contenteditable="inline ? 'false' : undefined"
    :data-skill-name="inline ? name : undefined"
    :data-skill-path="inline ? path : undefined"
    :data-resource-marker="inline ? marker : undefined"
    @click="$emit('open')"
  >
    <SkillGlyph class="skill-chip-icon" :size="14" color="#6680cf" />
    <span class="skill-chip-name">{{ name }}</span>
    <button
      v-if="removable"
      class="inline-skill-remove"
      type="button"
      title="移除 Skill"
    >×</button>
  </span>
</template>

<script>
import SkillGlyph from './SkillGlyph.vue'

export default {
  name: 'ComposerSkillChip',
  components: { SkillGlyph },
  props: {
    name: { type: String, required: true },
    path: { type: String, default: '' },
    // inline=true 时作为 contenteditable 内的不可编辑节点，靠 marker 参与序列化
    inline: { type: Boolean, default: false },
    marker: { type: String, default: '' },
    removable: { type: Boolean, default: true }
  }
}
</script>

<style scoped>
/*
 * 度量与 ComposerResourceChip 逐项一致：inline-flex + 24px 高 + 12px/1 行高 +
 * 图标 14px + 5px 间距 + 相同内边距。两种 chip 只有图标和配色不同，
 * 这样它们在正文里的基线位置才完全相同。
 */
.composer-skill-chip{display:inline-flex;max-width:250px;height:24px;box-sizing:border-box;align-items:center;gap:5px;padding:0 6px 0 7px;color:#3f4a72;background:#f0f4ff;border:1px solid #b9c7ee;border-radius:7px;box-shadow:0 1px 1px rgba(31,35,41,.02);cursor:pointer;font-size:12px;font-weight:500;line-height:1;vertical-align:middle}
.composer-skill-chip.is-inline{margin:0 6px 0 4px;vertical-align:-2px;user-select:none}
.skill-chip-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.composer-skill-chip>button{display:inline-flex;width:14px;height:14px;align-items:center;justify-content:center;padding:0;color:#838db0;background:transparent;border:0;border-radius:50%;cursor:pointer;font-size:13px;line-height:1}
.composer-skill-chip>button:hover{color:#d7525b;background:#ffecee}
</style>