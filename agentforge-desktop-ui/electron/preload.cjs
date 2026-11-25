const { contextBridge, ipcRenderer, webUtils } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  getLocalServerInfo() {
    return ipcRenderer.invoke('local-agent:server-info')
  },
  readSystemSettings() {
    return ipcRenderer.invoke('local-agent:read-system-settings')
  },
  saveSystemSettings(settings) {
    return ipcRenderer.invoke('local-agent:save-system-settings', settings)
  },
  openPath(target) {
    return ipcRenderer.invoke('local-agent:open-path', target)
  },
  chooseDirectory(currentPath) {
    return ipcRenderer.invoke('local-agent:choose-directory', currentPath)
  },
  chooseSkillZip(currentPath) {
    return ipcRenderer.invoke('local-agent:choose-skill-zip', currentPath)
  },
  chooseResources(currentPath) {
    return ipcRenderer.invoke('local-agent:choose-resources', currentPath)
  },
  chooseResourceFiles(currentPath) {
    return ipcRenderer.invoke('local-agent:choose-resource-files', currentPath)
  },
  chooseResourceFolder(currentPath) {
    return ipcRenderer.invoke('local-agent:choose-resource-folder', currentPath)
  },
  readClipboardText() {
    return ipcRenderer.invoke('local-agent:read-clipboard-text')
  },
  readClipboardResources() {
    return ipcRenderer.invoke('local-agent:read-clipboard-resources')
  },
  getPathForFile(file) {
    return webUtils.getPathForFile(file)
  },
  notifyTaskDone() {
    return ipcRenderer.invoke('local-agent:notify-task-done')
  },
  cancelTaskAttention() {
    return ipcRenderer.invoke('local-agent:cancel-task-attention')
  },
  onBackendExit(callback) {
    const listener = (_event, payload) => callback(payload)
    ipcRenderer.on('local-agent:backend-exit', listener)
    return () => ipcRenderer.removeListener('local-agent:backend-exit', listener)
  }
})
