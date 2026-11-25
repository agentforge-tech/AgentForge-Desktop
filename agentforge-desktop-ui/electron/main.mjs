import { app, BrowserWindow, ipcMain, shell, dialog, Menu, clipboard, net as electronNet } from 'electron'
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import { fileURLToPath } from 'node:url'
import {
  detectRg,
  ensureShellWrapper,
  installRg,
  listEnhancements,
  resolveInShell,
  resolveTarget,
  toolPathEntries
} from './enhancements.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DEV_URL = process.env.ELECTRON_RENDERER_URL || ''
const DEVELOPER_MODE = Boolean(DEV_URL)
const OPEN_DEVTOOLS = process.env.ELECTRON_OPEN_DEVTOOLS === '1'
const EXTERNAL_SERVER_URL = process.env.AGENTFORGE_DESKTOP_SERVER_URL || ''
const EXTERNAL_SERVER_TOKEN = process.env.AGENTFORGE_DESKTOP_SERVER_TOKEN || ''
const TOKEN_HEADER = 'X-AgentForge-Desktop-Token'
let backend = null
let quitting = false

// 主窗口引用与任务完成提醒状态：窗口由 createWindow 赋值，供提醒逻辑复用。
let mainWindow = null
let attentionBounceId = null        // macOS Dock 跳动请求 ID；null 表示当前没有待取消的请求
let attentionTimer = null           // 连续跳动的续跳定时器
let attentionPulsesLeft = 0         // 本轮剩余跳动次数
let lastReminderAt = 0              // 上一次提醒时间，用于短时间内合并多次提醒
const REMINDER_WINDOW_MS = 3000
// 任务完成提醒的跳动节奏：连续跳 REMINDER_BOUNCE_TIMES 次，每次间隔 REMINDER_BOUNCE_INTERVAL_MS。
// macOS 的 informational 单次只跳约 1 秒，靠间隔续跳形成“多次跳动”的观感。
const REMINDER_BOUNCE_TIMES = 3
const REMINDER_BOUNCE_INTERVAL_MS = 1600

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// 桌面数据根目录：与后端 --agentforge.desktop.root-dir 指向同一目录，保证配置与数据库同源。
function desktopRootDir() {
  return process.env.AGENTFORGE_DESKTOP_ROOT_DIR
    ? path.resolve(process.env.AGENTFORGE_DESKTOP_ROOT_DIR)
    : path.join(app.getPath('home'), '.agentforge-desktop')
}

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

// 内置能力工具目录：打包后位于 resources/runtime/tools，开发态位于 runtime/tools。
function bundledToolsDir() {
  if (app.isPackaged) return path.join(process.resourcesPath, 'runtime', 'tools')
  return path.join(process.cwd(), 'runtime', 'tools')
}

// 组装增强能力的运行上下文，避免各处重复拼参数。
// probeShell：检测时同时验证"Agent 工具的 shell 里能否解析到 rg"，
// 因为后端进程由谁启动会决定 PATH，文件存在不代表 Agent 能用。
function enhancementContext() {
  return {
    rootDir: desktopRootDir(),
    bundledToolsDir: bundledToolsDir(),
    probeShell: true
  }
}

// 后端进程 PATH：前置增强工具目录 + 常见 GUI 缺失目录（方案 §7.1）。
// GUI 启动的 Electron 拿不到 Homebrew/cargo 路径，用户“终端里能用、Agent 里不能”多由此产生。
function backendPath() {
  const entries = [...toolPathEntries(enhancementContext())]
  if (process.platform === 'darwin') {
    entries.push('/opt/homebrew/bin', '/usr/local/bin')
    const home = app.getPath('home')
    if (home) entries.push(path.join(home, '.cargo', 'bin'))
  }
  const existing = process.env.PATH || ''
  return [...new Set(entries.filter(Boolean))].join(path.delimiter) + (existing ? path.delimiter + existing : '')
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
  const rootDir = desktopRootDir()
  fs.mkdirSync(rootDir, { recursive: true })

  const baseUrl = `http://127.0.0.1:${port}`
  // Agent 工具命令的 shell：优先用自带 PATH 的包装脚本，保证 rg 等工具一定能被解析到。
  const shellWrapper = process.platform === 'win32' ? '' : ensureShellWrapper(enhancementContext())
  const args = [
    '-Xms128m',
    `-Xmx${process.env.AGENTFORGE_DESKTOP_JAVA_XMX || '1536m'}`,
    '-Dfile.encoding=UTF-8',
    '-jar', jar,
    '--server.address=127.0.0.1',
    `--server.port=${port}`,
    `--agentforge.desktop.token=${token}`,
    `--agentforge.desktop.root-dir=${rootDir}`,
    ...(shellWrapper ? [`--agentforge.desktop.shell=${shellWrapper}`] : [])
  ]

  console.log(`[AgentForge] starting Java runtime: ${java} -jar ${jar}`)
  const child = spawn(java, args, {
    cwd: rootDir,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    env: { ...process.env, PATH: backendPath() }
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

// 重启本地后端：安装新工具后需要重建进程环境（PATH 在进程启动瞬间固化，方案 §7.3）。
async function restartBackend() {
  if (backend?.external) throw new Error('当前连接的是外部后端，无法在这里重启')
  quitting = true
  await stopBackend()
  quitting = false
  backend = await startBackend()
  console.log(`[AgentForge] backend restarted: ${backend.baseUrl}`)
  for (const win of BrowserWindow.getAllWindows()) {
    win.webContents.send('local-agent:server-info-changed', {
      baseUrl: backend.baseUrl,
      token: backend.token,
      workspace: backend.workspace
    })
  }
  return backend
}

// 4、创建桌面窗口：加载 preload 安全桥接和渲染页面，并管控外部链接跳转。
function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1080,
    minHeight: 700,
    title: 'AgentForge Desktop',
    ...(process.platform === 'darwin' ? { titleBarStyle: 'hiddenInset' } : {}),
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

  mainWindow = win
  bindAttentionToWindow(win)
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null
    cancelTaskAttention()
  })

  if (DEV_URL) void win.loadURL(DEV_URL)
  else void win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
}

// 5、注册主进程 IPC：向渲染进程提供后端信息、打开路径、资源选择与剪贴板读取能力。
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'])
const DOCUMENT_EXTENSIONS = new Set(['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'])

function describeResource(filePath) {
  const resolved = path.resolve(String(filePath))
  let stat = null
  try {
    stat = fs.statSync(resolved)
  } catch (_error) {
    return null
  }
  const directory = stat.isDirectory()
  const name = path.basename(resolved)
  const dot = directory ? -1 : name.lastIndexOf('.')
  const fileType = dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
  let type = 'file'
  if (directory) type = 'folder'
  else if (IMAGE_EXTENSIONS.has(fileType)) type = 'image'
  else if (fileType === 'pdf') type = 'pdf'
  else if (fileType === 'html' || fileType === 'htm') type = 'html'
  else if (DOCUMENT_EXTENSIONS.has(fileType)) type = 'document'
  return {
    path: resolved,
    name,
    fileType,
    size: directory ? 0 : stat.size,
    type
  }
}

function describeResources(filePaths) {
  return (filePaths || []).map(describeResource).filter(Boolean)
}

function systemSettingsPath() {
  return path.join(desktopRootDir(), 'conf', 'settings.json')
}

const DEFAULT_SETTINGS = {
  interactionModeSupport: false,
  taskCompletionReminder: true,
  // 能力增强状态快照：仅用于设置页秒开时避免闪烁，事实以每次检测结果为准。
  enhancements: {}
}

// 能力增强快照规范化：只保留已知字段，未知能力 / 未知字段一律丢弃。
function normalizeEnhancements(value) {
  const input = value && typeof value === 'object' ? value : {}
  const result = {}
  for (const [id, entry] of Object.entries(input)) {
    if (!entry || typeof entry !== 'object') continue
    result[id] = {
      status: typeof entry.status === 'string' ? entry.status : 'unknown',
      version: typeof entry.version === 'string' ? entry.version : '',
      source: typeof entry.source === 'string' ? entry.source : 'none',
      path: typeof entry.path === 'string' ? entry.path : '',
      checkedAt: Number.isFinite(entry.checkedAt) ? entry.checkedAt : 0
    }
  }
  return result
}

// 白名单规范化：未知字段丢弃，类型错误回退默认值。
function normalizeSettings(value) {
  const input = value && typeof value === 'object' ? value : {}
  return {
    interactionModeSupport: input.interactionModeSupport === true,
    // 任务完成提醒默认开启：只有用户显式关闭（写入 false）才不提醒。
    taskCompletionReminder: input.taskCompletionReminder !== false,
    enhancements: normalizeEnhancements(input.enhancements)
  }
}

function readSystemSettingsFile() {
  try {
    return normalizeSettings(JSON.parse(fs.readFileSync(systemSettingsPath(), 'utf8')))
  } catch (_error) {
    return normalizeSettings(DEFAULT_SETTINGS)
  }
}

// 回写能力增强快照：只更新 enhancements 字段，保留用户其它系统配置。
function saveEnhancementSnapshot(status) {
  try {
    const current = readSystemSettingsFile()
    const target = systemSettingsPath()
    const next = normalizeSettings({
      ...current,
      enhancements: { ...current.enhancements, [status.id]: status }
    })
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, `${JSON.stringify(next, null, 2)}\n`, 'utf8')
  } catch (error) {
    // 快照只是展示缓存，写失败不影响能力本身可用性。
    console.warn('[AgentForge] failed to persist enhancement snapshot:', error?.message || error)
  }
}

// 首次启动静默初始化：在后端启动之前把内置 rg 复制到用户级目录，保证 PATH 注入时已就绪。
// 任何失败都只记录日志，不阻断应用启动（能力缺失会由设置页检测暴露）。
async function initEnhancements() {
  const context = enhancementContext()
  try {
    if (!resolveTarget()) {
      const detected = detectRg(context)
      console.warn(`[AgentForge] ripgrep not available for ${process.platform}/${process.arch}`)
      saveEnhancementSnapshot(detected)
      return detected
    }

    const detected = detectRg(context)
    // 用户级已是本应用管理的二进制 → 直接采用，避免每次启动重复复制。
    if (detected.status === 'ready' && detected.source === 'user') {
      // 关键：二进制在但 Agent 的 shell 里找不到（典型是外部/IDEA 启动后端）→ 自动补写包装脚本。
      const wrapper = ensureShellWrapper(context)
      if (wrapper) {
        const shellCheck = resolveInShell({ rootDir: context.rootDir })
        console.log(
          `[AgentForge] enhancement rg ${detected.version} ready; shell=${shellCheck.ok ? 'ok' : 'missing'} wrapper=${wrapper}`
        )
      }
      saveEnhancementSnapshot(detected)
      return detected
    }

    // 内置缺失时不联网：启动阶段禁止隐式网络请求，交由设置页由用户显式触发。
    if (!toolPathEntries(context).length) {
      console.warn('[AgentForge] bundled ripgrep missing; enhancement will report current status')
      saveEnhancementSnapshot(detected)
      return detected
    }

    // 系统已装旧版 / 仅剩内置版本时，统一落一份到用户级目录，保证版本与行为一致。
    const installed = await installRg({ ...context, mode: 'bundled' })
    saveEnhancementSnapshot(installed.status)
    console.log(`[AgentForge] enhancement ready: rg ${installed.status.version} (${installed.status.path})`)
    return installed.status
  } catch (error) {
    console.warn('[AgentForge] enhancement init failed:', error?.message || error)
    return null
  }
}

// 停止续跳并取消尚未结束的跳动请求。
function stopAttentionLoop() {
  if (attentionTimer) {
    clearTimeout(attentionTimer)
    attentionTimer = null
  }
  attentionPulsesLeft = 0
  if (attentionBounceId !== null && app.dock) {
    app.dock.cancelBounce(attentionBounceId)
  }
  attentionBounceId = null
}

// 触发一次搏动：macOS 用 Dock 跳动，Windows 用任务栏闪烁。
// 返回 false 表示系统拒绝（例如用户其实就在应用里），此时应终止整轮续跳。
function pulseAttention(win) {
  if (!win || win.isDestroyed()) return false
  if (win.isFocused()) return false
  if (process.platform === 'darwin' && app.dock) {
    if (attentionBounceId !== null) {
      app.dock.cancelBounce(attentionBounceId)
      attentionBounceId = null
    }
    const id = app.dock.bounce('informational')
    // 实测返回 0 也是有效 ID，不能用真假值判断。
    // 返回 -1 表示应用仍处于前台活跃态（系统拒绝跳动，图标本来就在用户眼前）。
    if (!Number.isInteger(id) || id < 0) return false
    attentionBounceId = id
    return true
  }
  win.flashFrame(true)
  return true
}

// 任务完成提醒：只在用户离开应用时，让 Dock / 任务栏图标连续给出多次注意力提示。
function requestTaskAttention() {
  const win = mainWindow
  if (!win || win.isDestroyed()) return { ok: false, reason: 'no-window' }
  if (!readSystemSettingsFile().taskCompletionReminder) return { ok: false, reason: 'disabled' }
  // 先自行判断应用是否在前台，不依赖 bounce 返回 -1（该返回值在部分环境下不可靠）
  if (win.isFocused()) return { ok: false, reason: 'focused' }

  const now = Date.now()
  if (now - lastReminderAt < REMINDER_WINDOW_MS) return { ok: false, reason: 'throttled' }

  // 新提醒接管旧提醒：先清掉上一轮未跳完的节奏，避免两轮叠加
  stopAttentionLoop()

  if (!pulseAttention(win)) {
    // 被系统拒绝（应用仍活跃）：不占用节流窗口，用户真正切走后仍能提醒
    lastReminderAt = 0
    return { ok: false, reason: 'app-active' }
  }

  lastReminderAt = now
  attentionPulsesLeft = REMINDER_BOUNCE_TIMES - 1
  scheduleNextPulse()
  return { ok: true, times: REMINDER_BOUNCE_TIMES }
}

// 安排下一次搏动；用户回到窗口或次数用尽时自然结束。
function scheduleNextPulse() {
  if (attentionPulsesLeft <= 0) return
  attentionTimer = setTimeout(() => {
    attentionTimer = null
    const win = mainWindow
    // 用户已回来 / 窗口没了 / 提醒被取消 / 中途关掉了开关 → 终止续跳
    if (!win || win.isDestroyed() || win.isFocused() || !readSystemSettingsFile().taskCompletionReminder) {
      stopAttentionLoop()
      return
    }
    if (!pulseAttention(win)) {
      stopAttentionLoop()
      return
    }
    attentionPulsesLeft -= 1
    scheduleNextPulse()
  }, REMINDER_BOUNCE_INTERVAL_MS)
  // 定时器不应阻止进程退出
  if (attentionTimer.unref) attentionTimer.unref()
}

function cancelTaskAttention() {
  stopAttentionLoop()
  const win = mainWindow
  if (win && !win.isDestroyed() && process.platform !== 'darwin') win.flashFrame(false)
  lastReminderAt = 0
}

// 用户回到窗口即停止闪烁，避免 Windows 上出现“闪了停不下来”。
function bindAttentionToWindow(win) {
  win.on('focus', () => cancelTaskAttention())
}

function registerIpc() {
  ipcMain.handle('local-agent:server-info', () => ({
    baseUrl: backend.baseUrl,
    token: backend.token,
    workspace: backend.workspace,
    platform: process.platform,
    arch: process.arch,
    runtime: backend.external ? 'external-java' : 'embedded-java'
  }))
  ipcMain.handle('local-agent:read-system-settings', () => readSystemSettingsFile())
  ipcMain.handle('local-agent:save-system-settings', async (_event, settings) => {
    const target = systemSettingsPath()
    const normalized = normalizeSettings(settings)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, `${JSON.stringify(normalized, null, 2)}\n`, 'utf8')
    return normalized
  })
  ipcMain.handle('local-agent:notify-task-done', () => requestTaskAttention())
  ipcMain.handle('local-agent:cancel-task-attention', () => cancelTaskAttention())
  ipcMain.handle('local-agent:enhancements:list', () => listEnhancements(enhancementContext()))
  ipcMain.handle('local-agent:enhancements:detect', (_event, payload) => {
    const id = String(payload?.id || 'rg')
    if (id !== 'rg') throw new Error(`未知的增强能力：${id}`)
    const status = detectRg(enhancementContext())
    saveEnhancementSnapshot(status)
    return status
  })
  ipcMain.handle('local-agent:enhancements:install', async (_event, payload) => {
    const id = String(payload?.id || 'rg')
    if (id !== 'rg') throw new Error(`未知的增强能力：${id}`)
    const mode = ['auto', 'bundled', 'online', 'upgrade'].includes(payload?.mode) ? payload.mode : 'auto'
    const result = await installRg({
      ...enhancementContext(),
      mode,
      log: (line) => console.log(`[AgentForge] ${line}`),
      // Electron 主进程的全局 fetch 不走系统代理，改用 net.fetch（遵循 PAC / 系统代理）。
      fetchImpl: electronNet.fetch
    })
    saveEnhancementSnapshot(result.status)
    return result
  })
  ipcMain.handle('local-agent:enhancements:restart-backend', async () => {
    await restartBackend()
    return { ok: true, baseUrl: backend?.baseUrl || '' }
  })
  // 修复 PATH：重写包装脚本，并返回 Agent 工具的 shell 当前能否解析到 rg。
  ipcMain.handle('local-agent:enhancements:repair-path', () => {
    const context = enhancementContext()
    const wrapper = ensureShellWrapper(context)
    const shellCheck = resolveInShell({ rootDir: context.rootDir })
    const status = detectRg(context)
    saveEnhancementSnapshot(status)
    return {
      wrapper,
      shellOk: shellCheck.ok,
      shellPath: shellCheck.path,
      shellVersion: shellCheck.version,
      shellReason: shellCheck.reason || '',
      status
    }
  })
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
  ipcMain.handle('local-agent:choose-skill-zip', async (_event, currentPath) => {
    const result = await dialog.showOpenDialog({
      title: '选择 Skill ZIP',
      defaultPath: String(currentPath || backend.workspace || app.getPath('home')),
      properties: ['openFile'],
      filters: [{ name: 'Skill ZIP', extensions: ['zip'] }]
    })
    return result.canceled || !result.filePaths.length ? null : result.filePaths[0]
  })
  ipcMain.handle('local-agent:choose-resources', async (_event, currentPath) => {
    const result = await dialog.showOpenDialog({
      title: '添加图片、文件或文件夹',
      defaultPath: String(currentPath || backend.workspace || app.getPath('home')),
      properties: ['openFile', 'openDirectory', 'multiSelections']
    })
    return result.canceled ? [] : describeResources(result.filePaths)
  })
  ipcMain.handle('local-agent:choose-resource-files', async (_event, currentPath) => {
    const result = await dialog.showOpenDialog({
      title: '添加文件',
      defaultPath: String(currentPath || backend.workspace || app.getPath('home')),
      properties: ['openFile', 'multiSelections']
    })
    return result.canceled ? [] : describeResources(result.filePaths)
  })
  ipcMain.handle('local-agent:choose-resource-folder', async (_event, currentPath) => {
    const result = await dialog.showOpenDialog({
      title: '添加文件夹',
      defaultPath: String(currentPath || backend.workspace || app.getPath('home')),
      properties: ['openDirectory']
    })
    return result.canceled ? [] : describeResources(result.filePaths)
  })
  ipcMain.handle('local-agent:read-clipboard-text', () => {
    const text = clipboard.readText()
    if (text && text.trim()) return text
    const raw = clipboard.read('public.file-url') || clipboard.read('text/uri-list') || ''
    const first = String(raw).split(/\r?\n/).map((line) => line.trim()).find(Boolean) || ''
    if (!first) return ''
    try {
      return first.startsWith('file://') ? fileURLToPath(first) : first
    } catch (_error) {
      return first
    }
  })

  ipcMain.handle('local-agent:read-clipboard-resources', async () => {
    const resources = []
    try {
      const raw = clipboard.read('public.file-url') || clipboard.read('text/uri-list') || ''
      String(raw).split(/\r?\n/).forEach((line) => {
        const value = line.trim()
        if (!value) return
        try {
          const filePath = value.startsWith('file://') ? fileURLToPath(value) : value
          if (fs.existsSync(filePath)) {
            const item = describeResource(filePath)
            if (item) resources.push(item)
          }
        } catch (_error) {
          // ignore malformed clipboard entry
        }
      })
    } catch (_error) {
      // unsupported clipboard format
    }
    if (!resources.length) {
      try {
        const image = clipboard.readImage()
        if (image && !image.isEmpty()) {
          const dir = path.join(app.getPath('temp'), 'agentforge-clipboard')
          fs.mkdirSync(dir, { recursive: true })
          const target = path.join(dir, `clipboard-${Date.now()}-${randomBytes(4).toString('hex')}.png`)
          fs.writeFileSync(target, image.toPNG())
          const item = describeResource(target)
          if (item) resources.push(item)
        }
      } catch (_error) {
        // clipboard image unavailable
      }
    }
    return resources
  })
}

// 6、Electron 就绪后串联核心启动流程：初始化能力工具 -> 启动后端 -> 注册 IPC -> 创建主窗口。
// 能力初始化必须早于 startBackend，否则 PATH 注入时工具目录还不存在（方案 §7.3）。
app.whenReady().then(async () => {
  try {
    await initEnhancements()
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
