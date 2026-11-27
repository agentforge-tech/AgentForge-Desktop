<template>
  <!--
    Skill 图标：与左侧栏「技能管理」按钮完全同一套路径与描边风格。

    宽高与颜色全部走内联属性/style：输入框里的 inline chip 是拿 `$el.outerHTML`
    序列化后再塞回 contenteditable 的，那种场景下 scoped class 样式不可靠。
    尺寸由调用方的 class 决定（chip 内与资源图标保持一致），这里只给出兜底值。
  -->
  <svg
    class="skill-glyph"
    :width="size"
    :height="size"
    :style="{ flex: `0 0 ${size}px`, color }"
    viewBox="0 0 20 20"
    fill="none"
    stroke="currentColor"
    stroke-width="1.45"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path d="m11.75 2.75.64 2.1a3.7 3.7 0 0 0 2.46 2.46l2.1.64-2.1.64a3.7 3.7 0 0 0-2.46 2.46l-.64 2.1-.64-2.1a3.7 3.7 0 0 0-2.46-2.46l-2.1-.64 2.1-.64a3.7 3.7 0 0 0 2.46-2.46l.64-2.1Z" />
    <path d="m5.15 11.75.36 1.19c.2.68.74 1.21 1.41 1.42l1.18.36-1.18.36c-.67.2-1.2.74-1.41 1.41l-.36 1.18-.36-1.18a2.12 2.12 0 0 0-1.41-1.41l-1.18-.36 1.18-.36c.67-.2 1.2-.74 1.41-1.42l.36-1.19Z" />
  </svg>
</template>

<script>
export default {
  name: 'SkillGlyph',
  props: {
    // 与资源 chip 的图标同尺寸，保证两类 chip 的内部度量完全一致
    size: { type: [Number, String], default: 14 },
    // 描边颜色；留空则继承父级 color
    color: { type: String, default: '' }
  }
}
</script>

<style scoped>
.skill-glyph {
  display: block;
  overflow: visible;
}
</style>