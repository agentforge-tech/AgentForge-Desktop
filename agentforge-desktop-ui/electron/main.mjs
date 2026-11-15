import { app, BrowserWindow, ipcMain, shell, dialog, Menu } from 'electron'
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DEV_URL = process.env.ELECTRON_RENDERER_URL || ''
const DEVELOPER_MODE = Boolean(DEV_URL)
const OPEN_DEVTOOLS = process.env.ELECTRON_OPEN_DEVTOOLS === '1'
const EXTERNAL_SERVER_URL = process.env.AGENTFORGE_DESKTOP_SERVER_URL || ''
const EXTERNAL_SERVER_TOKEN = process.env.AGENTFORGE_DESKTOP_SERVER_TOKEN || ''
const TOKEN_HEADER = 'X-AgentForge-Desktop-Token'
let backend = null
let quitting = false

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// 1、准备后端运行环境：分配可用端口，并根据开发/打包环境定位 Java 与服务端 JAR。
async function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 43179
      server.close(() => resolve(port))
    })
  })
}

function javaExecutable() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'runtime', 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  }
  const local = path.join(process.cwd(), 'runtime', 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (fs.existsSync(local)) return local
  const home = process.env.AGENTFORGE_DESKTOP_JAVA_HOME || process.env.JAVA_HOME
  if (home) return path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  return process.platform === 'win32' ? 'java.exe' : 'java'
}

function serverJar() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'runtime', 'server', 'agentforge-desktop-server.jar')
  }
  return process.env.AGENTFORGE_DESKTOP_SERVER_JAR
    || path.join(process.cwd(), 'runtime', 'server', 'agentforge-desktop-server.jar')
}

async function waitForBackend(baseUrl, token, child, timeoutMs = 60_000) {
  const started = Date.now()
  let lastError = null
  while (Date.now() - started < timeoutMs) {
    if (child && child.exitCode != null) {
      throw new Error(`agentforge-desktop-server exited early with code ${child.exitCode}`)
    }
    try {
      const response = await fetch(`${baseUrl}/api/local/health`, {
        headers: token ? { [TOKEN_HEADER]: token } : {}
      })
      if (response.ok) {
        const body = await response.json()
        if (body?.code === 0 && body?.data?.ready) return body.data
      }
    } catch (error) {
      lastError = error
    }
    await sleep(180)
  }
  throw new Error(`Timed out waiting for agentforge-desktop-server${lastError ? `: ${lastError.message}` : ''}`)
}

async function probeBackend(baseUrl, token, timeoutMs = 800) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${baseUrl}/api/local/health`, {
      headers: token ? { [TOKEN_HEADER]: token } : {},
      signal: controller.signal
    })
    if (!response.ok) return null
    const body = await response.json()
    return body?.code === 0 && body?.data?.ready ? body.data : null
  } catch (_error) {
    return null
  } finally {
    clearTimeout(timer)
  }
}

// 2、启动后端服务：优先复用外部服务，否则启动本地 Java 子进程。
async function startBackend() {
  if (EXTERNAL_SERVER_URL) {
    let baseUrl = EXTERNAL_SERVER_URL.replace(/\/$/, '')
    let health = await probeBackend(baseUrl, EXTERNAL_SERVER_TOKEN)
    if (!health && /^http:\/\/(127\.0\.0\.1|localhost):43179$/i.test(baseUrl)) {
      const legacyUrl = 'http://127.0.0.1:43178'
      const legacyHealth = await probeBackend(legacyUrl, EXTERNAL_SERVER_TOKEN)
      if (legacyHealth) {
        console.warn(`[AgentForge] backend ${baseUrl} is unavailable; using legacy dev server ${legacyUrl}`)
        baseUrl = legacyUrl
        health = legacyHealth
      }
    }
    return {
      baseUrl,
      token: EXTERNAL_SERVER_TOKEN,
      workspace: health?.workspace || '',
      process: null,
      external: true
    }
  }

  const jar = serverJar()
  if (!fs.existsSync(jar)) {
    throw new Error(`agentforge-desktop-server.jar not found: ${jar}\nRun: npm run runtime:server`)
  }
  const java = javaExecutable()
  if ((java.includes(path.sep) || path.isAbsolute(java)) && !fs.existsSync(java)) {
    throw new Error(`Embedded Java runtime not found: ${java}`)
  }

  const port = Number(process.env.AGENTFORGE_DESKTOP_PORT) || await findFreePort()
  const token = randomBytes(32).toString('hex')
  const rootDir = process.env.AGENTFORGE_DESKTOP_ROOT_DIR
    ? path.resolve(process.env.AGENTFORGE_DESKTOP_ROOT_DIR)
    : path.join(app.getPath('home'), '.agentforge-desktop')
  fs.mkdirSync(rootDir, { recursive: true })

  const baseUrl = `http://127.0.0.1:${port}`
  const args = [
    '-Xms128m',
    `-Xmx${process.env.AGENTFORGE_DESKTOP_JAVA_XMX || '1536m'}`,
    '-Dfile.encoding=UTF-8',
    '-jar', jar,
    '--server.address=127.0.0.1',
    `--server.port=${port}`,
    `--agentforge.desktop.token=${token}`,
    `--agentforge.desktop.root-dir=${rootDir}`
  ]

  console.log(`[AgentForge] starting Java runtime: ${java} -jar ${jar}`)
  const child = spawn(java, args, {
    cwd: rootDir,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    env: { ...process.env }
  })
  child.stdout?.on('data', (data) => process.stdout.write(`[desktop-server] ${data}`))
  child.stderr?.on('data', (data) => process.stderr.write(`[desktop-server] ${data}`))
  child.once('exit', (code, signal) => {
    console.log(`[AgentForge] Java runtime exited: code=${code} signal=${signal || ''}`)
    if (!quitting && BrowserWindow.getAllWindows().length) {
      for (const win of BrowserWindow.getAllWindows()) {
        win.webContents.send('local-agent:backend-exit', { code, signal })
      }
    }
  })

  // 3、等待健康检查通过：只有后端 ready 后，才向下执行界面启动流程。
  try {
    const health = await waitForBackend(baseUrl, token, child)
    return { baseUrl, token, workspace: health.workspace || '', process: child, external: false }
  } catch (error) {
    if (!child.killed) child.kill('SIGTERM')
    throw error
  }
}

async function stopBackend(timeoutMs = 5000) {
  quitting = true
  const child = backend?.process
  if (!child || child.killed || child.exitCode != null) return
  await new Promise((resolve) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve()
    }
    const timer = setTimeout(() => {
      if (child.exitCode == null && !child.killed) child.kill('SIGKILL')
      finish()
    }, timeoutMs)
    child.once('exit', finish)
    child.kill('SIGTERM')
  })
}

// 4、创建桌面窗口：加载 preload 安全桥接和渲染页面，并管控外部链接跳转。
function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1080,
    minHeight: 700,
    title: 'AgentForge Desktop',
    backgroundColor: '#ffffff',
    show: true,
    center: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      spellcheck: false
    }
  })
  win.once('ready-to-show', () => {
    win.show()
    win.focus()
    app.focus({ steal: true })
  })
  win.webContents.on('did-finish-load', async () => {
    if (!win.isVisible()) win.show()
    win.focus()
    console.log(`[AgentForge] renderer loaded: ${await win.webContents.getTitle()}`)
    if (process.env.AGENTFORGE_DESKTOP_SMOKE === '1') {
      try {
        await new Promise((resolve) => setTimeout(resolve, 2500))
        const text = await win.webContents.executeJavaScript('document.body.innerText.slice(0, 800)')
        console.log('[AgentForge] renderer text:', JSON.stringify(text))
        const apiType = await win.webContents.executeJavaScript('typeof window.electronAPI')
        console.log('[AgentForge] electronAPI:', apiType)
        const info = await win.webContents.executeJavaScript(
          'window.electronAPI && window.electronAPI.getLocalServerInfo ? window.electronAPI.getLocalServerInfo() : null'
        )
        console.log('[AgentForge] serverInfo:', JSON.stringify(info))
      } catch (error) {
        console.error('[AgentForge] renderer text failed:', error)
      }
    }
  })
  win.webContents.on('preload-error', (_event, preloadPath, error) => {
    console.error('[AgentForge] preload error:', preloadPath, error)
  })
  if (DEVELOPER_MODE) {
    win.webContents.on('console-message', (_event, level, message, line, sourceId) => {
      console.log(`[renderer:${level}] ${message} (${sourceId}:${line})`)
    })
  }
  if (DEVELOPER_MODE) {
    if (OPEN_DEVTOOLS) win.webContents.openDevTools({ mode: 'detach' })
    win.webContents.on('before-input-event', (event, input) => {
      const macShortcut = process.platform === 'darwin' && input.meta && input.alt
      const desktopShortcut = process.platform !== 'darwin' && input.control && input.shift
      if (input.type === 'keyDown' && input.key.toLowerCase() === 'i' && (macShortcut || desktopShortcut)) {
        win.webContents.toggleDevTools()
        event.preventDefault()
      }
    })
    win.webContents.on('context-menu', (_event, params) => {
      Menu.buildFromTemplate([
        { label: '检查元素', click: () => win.webContents.inspectElement(params.x, params.y) }
      ]).popup({ window: win })
    })
  }

  const openInSystemBrowser = (url) => {
    if (/^https?:/i.test(url)) void shell.openExternal(url)
  }
  const isRendererUrl = (url) => {
    if (!DEV_URL) return /^file:/i.test(url)
    try {
      return new URL(url).origin === new URL(DEV_URL).origin
    } catch {
      return false
    }
  }
  win.webContents.setWindowOpenHandler(({ url }) => {
    openInSystemBrowser(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (event, url) => {
    if (/^https?:/i.test(url) && !isRendererUrl(url)) {
      event.preventDefault()
      openInSystemBrowser(url)
    }
  })

  if (DEV_URL) void win.loadURL(DEV_URL)
  else void win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
}

// 5、注册主进程 IPC：向渲染进程提供后端信息、打开路径和选择目录能力。
function registerIpc() {
  ipcMain.handle('local-agent:server-info', () => ({
    baseUrl: backend.baseUrl,
    token: backend.token,
    workspace: backend.workspace,
    platform: process.platform,
    arch: process.arch,
    runtime: backend.external ? 'external-java' : 'embedded-java'
  }))
  ipcMain.handle('local-agent:open-path', async (_event, target) => {
    return shell.openPath(String(target || backend.workspace || app.getPath('home')))
  })
  ipcMain.handle('local-agent:choose-directory', async (_event, currentPath) => {
    const result = await dialog.showOpenDialog({
      title: '选择目录',
      defaultPath: String(currentPath || backend.workspace || app.getPath('home')),
      properties: ['openDirectory', 'createDirectory']
    })
    return result.canceled || !result.filePaths.length ? null : result.filePaths[0]
  })
}

// 6、Electron 就绪后串联核心启动流程：启动后端 -> 注册 IPC -> 创建主窗口。
app.whenReady().then(async () => {
  try {
    backend = await startBackend()
    console.log(`[AgentForge] backend ready: ${backend.baseUrl} (${backend.external ? 'external' : 'embedded'})`)
    registerIpc()
    createWindow()
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow()
    })
  } catch (error) {
    console.error('[AgentForge] desktop startup failed:', error)
    dialog.showErrorBox('AgentForge Desktop 启动失败', error?.message || String(error))
    app.quit()
  }
})

// 7、统一处理应用退出：关闭窗口时退出应用，并终止由主进程启动的 Java 子进程。
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  quitting = true
  const child = backend?.process
  if (child && !child.killed && child.exitCode == null) child.kill('SIGTERM')
})

process.on('SIGINT', () => {
  void stopBackend().then(() => process.exit(0))
})
