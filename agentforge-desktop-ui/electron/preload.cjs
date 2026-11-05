const { contextBridge, ipcRenderer } = require('electron')

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
  onBackendExit(callback) {
    const listener = (_event, payload) => callback(payload)
    ipcRenderer.on('local-agent:backend-exit', listener)
    return () => ipcRenderer.removeListener('local-agent:backend-exit', listener)
  }
})
