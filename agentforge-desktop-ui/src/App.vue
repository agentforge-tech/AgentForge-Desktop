<template>
  <div
    class="app-shell"
    :class="{ 'is-mac': isMac, 'has-mac-titlebar': isMac && !isWorkbench }"
  >
    <div
      v-if="isMac && !isWorkbench"
      class="mac-window-titlebar"
      aria-hidden="true"
    ></div>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script>
export default {
  name: 'App',
  computed: {
    isMac() {
      return Boolean(window.electronAPI && window.electronAPI.platform === 'darwin')
    },
    isWorkbench() {
      return Boolean(this.$route && this.$route.name === 'workbench')
    }
  }
}
</script>
