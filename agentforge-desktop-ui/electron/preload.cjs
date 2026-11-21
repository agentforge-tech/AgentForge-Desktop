const { contextBridge, ipcRenderer, webUtils } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  getLocalServerInfo() {
    return ipcRenderer.invoke('local-agent:server-info')
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
  getPathForFile(file) {
    return webUtils.getPathForFile(file)
  },
  onBackendExit(callback) {
    const listener = (_event, payload) => callback(payload)
    ipcRenderer.on('local-agent:backend-exit', listener)
    return () => ipcRenderer.removeListener('local-agent:backend-exit', listener)
  }
})
