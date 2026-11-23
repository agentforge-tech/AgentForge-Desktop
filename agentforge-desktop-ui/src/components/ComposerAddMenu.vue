<template>
  <div class="composer-add-menu">
    <button
      type="button"
      class="composer-add-btn"
      title="添加本地资源"
      :disabled="disabled"
      @mousedown.prevent="captureSelection"
      @click.stop="toggle"
    >
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 4v12M4 10h12" /></svg>
    </button>

    <transition name="composer-add-fade">
      <div v-if="open" class="composer-add-panel" @click.stop>
        <div
          class="composer-add-item has-submenu"
          :class="{ active: submenuOpen }"
          @mouseenter="submenuOpen = true"
          @click="submenuOpen = !submenuOpen"
        >
          <i class="el-icon-paperclip composer-add-item-icon"></i>
          <span class="composer-add-item-label">上传文件</span>
          <i class="el-icon-arrow-right composer-add-item-arrow"></i>

          <transition name="composer-add-fade">
            <div v-if="submenuOpen" class="composer-add-submenu" @click.stop>
              <button type="button" class="composer-add-subitem" @click="pick('files')">
                <i class="el-icon-document"></i>
                <span>本地文件</span>
              </button>
              <button type="button" class="composer-add-subitem" @click="pick('folder')">
                <i class="el-icon-folder"></i>
                <span>本地文件夹</span>
              </button>
            </div>
          </transition>
        </div>
      </div>
    </transition>
  </div>
</template>

<script>
export default {
  name: 'ComposerAddMenu',
  props: {
    disabled: { type: Boolean, default: false }
  },
  data: () => ({ open: false, submenuOpen: false }),
  watch: {
    disabled(value) {
      if (value) this.close()
    }
  },
  mounted() {
    document.addEventListener('mousedown', this.handleOutside)
    document.addEventListener('keydown', this.handleKeydown)
  },
  beforeDestroy() {
    document.removeEventListener('mousedown', this.handleOutside)
    document.removeEventListener('keydown', this.handleKeydown)
  },
  methods: {
    captureSelection() {
      if (!this.disabled) this.$emit('capture-selection')
    },
    toggle() {
      if (this.disabled) return
      this.open = !this.open
      this.submenuOpen = this.open
    },
    close() {
      this.open = false
      this.submenuOpen = false
    },
    pick(kind) {
      this.close()
      this.$emit(kind === 'folder' ? 'pick-folder' : 'pick-files')
    },
    handleOutside(event) {
      if (!this.open) return
      if (this.$el && !this.$el.contains(event.target)) this.close()
    },
    handleKeydown(event) {
      if (event.key === 'Escape') this.close()
    }
  }
}
</script>

<style scoped>
.composer-add-menu {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
}

.composer-add-panel {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  z-index: 3100;
  min-width: 168px;
  padding: 5px;
  background: #fff;
  border: 1px solid #e5e8ee;
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.13);
}

.composer-add-item {
  position: relative;
  display: flex;
  height: 34px;
  align-items: center;
  gap: 9px;
  padding: 0 9px;
  color: #303846;
  border-radius: 8px;
  cursor: pointer;
  white-space: nowrap;
  font-size: 13px;
}

.composer-add-item:hover,
.composer-add-item.active {
  background: #f4f5f8;
}

.composer-add-item-icon {
  color: #6b7280;
  font-size: 14px;
}

.composer-add-item-label {
  flex: 1;
}

.composer-add-item-arrow {
  color: #9aa1ac;
  font-size: 12px;
}

.composer-add-submenu {
  position: absolute;
  top: -6px;
  left: calc(100% + 6px);
  min-width: 168px;
  padding: 5px;
  background: #fff;
  border: 1px solid #e5e8ee;
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.13);
}

.composer-add-subitem {
  display: flex;
  width: 100%;
  height: 34px;
  align-items: center;
  gap: 9px;
  padding: 0 9px;
  color: #303846;
  background: transparent;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
}

.composer-add-subitem:hover {
  background: #f4f5f8;
}

.composer-add-subitem i {
  color: #6b7280;
  font-size: 14px;
}

.composer-add-fade-enter-active,
.composer-add-fade-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.composer-add-fade-enter,
.composer-add-fade-leave-to {
  opacity: 0;
  transform: translateY(3px);
}
</style>
