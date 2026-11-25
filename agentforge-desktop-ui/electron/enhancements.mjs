import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'

/**
 * 能力增强（Enhancement）核心逻辑：只做检测与安装的纯文件/进程操作，不依赖 Electron API，
 * 便于在主进程复用、也便于脱离界面单独验证。
 *
 * 设计约束（对应方案文档 §3.2）：
 * 1、检测绝不依赖 shell：只做 existsSync + spawnSync(绝对路径)，避免 GUI PATH 缺失导致假阴性；
 * 2、安装幂等：已就绪直接返回，不做任何写操作；
 * 3、不做隐式联网：只有 mode=online 才会下载。
 */

// 硬门槛：低于该版本判 outdated 并阻断；推荐版本仅用于“可升级”提示，不阻断（方案 §5.3.1）。
export const RG_MIN_VERSION = '13.0.0'
export const RG_RECOMMENDED_VERSION = '16.0.0'

// 官方 release 资产与校验值（钉版本，禁止跟随 latest，方案 §6.2）。
const RG_VERSION = RG_RECOMMENDED_VERSION
const RG_ASSETS = {
  'mac-arm64': {
    asset: `ripgrep-${RG_VERSION}-aarch64-apple-darwin.tar.gz`,
    sha256: '5a145656d5067144001bfce6648767fd3254599aa736f1ffdb7fb907760b8ca1'
  },
  'mac-x64': {
    asset: `ripgrep-${RG_VERSION}-x86_64-apple-darwin.tar.gz`,
    sha256: 'aa9183b0e0bfac03c5818895ad18b75001c402d230063898abfe5a58cd413caa'
  },
  'win-x64': {
    asset: `ripgrep-${RG_VERSION}-x86_64-pc-windows-msvc.zip`,
    sha256: 'f418ae83e63046a94a7b5f3a44bf3a169f66e689164856ecee02547131c73931'
  }
}

const RG_RELEASE_BASE = `https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}`

const IS_WINDOWS = process.platform === 'win32'
const RG_BIN_NAME = IS_WINDOWS ? 'rg.exe' : 'rg'

/** 平台 → 目标目录名（与 prepare-jre 的命名习惯保持一致）。不支持的组合返回 null。 */
export function resolveTarget(platform = process.platform, arch = process.arch) {
  if (platform === 'darwin' && arch === 'arm64') return { name: 'mac-arm64', ...RG_ASSETS['mac-arm64'] }
  if (platform === 'darwin' && arch === 'x64') return { name: 'mac-x64', ...RG_ASSETS['mac-x64'] }
  if (platform === 'win32' && arch === 'x64') return { name: 'win-x64', ...RG_ASSETS['win-x64'] }
  return null
}

/** semver 比较：a > b 返回 1，相等 0，a < b 返回 -1。仅处理数字段，足够覆盖 rg 版本。 */
export function compareVersion(a, b) {
  const pa = String(a || '0').split('.').map((v) => parseInt(v, 10) || 0)
  const pb = String(b || '0').split('.').map((v) => parseInt(v, 10) || 0)
  const len = Math.max(pa.length, pb.length)
  for (let i = 0; i < len; i += 1) {
    const x = pa[i] || 0
    const y = pb[i] || 0
    if (x > y) return 1
    if (x < y) return -1
  }
  return 0
}

function sha256File(file) {
  try {
    return createHash('sha256').update(fs.readFileSync(file)).digest('hex')
  } catch (_error) {
    return ''
  }
}

/**
 * 探测某个 rg 二进制是否可用。
 * 返回 { ok:true, version } / { ok:false, reason }，永不抛异常。
 *
 * macOS 首次执行新落盘的 ad-hoc 签名二进制时，系统可能正在做安全扫描，
 * 导致首次 spawn 偶发超时 / 返回非 0；因此失败后做一次短重试，避免"刚装好却报损坏"。
 */
export function probeRg(bin, { retries = 2, delayMs = 120 } = {}) {
  if (!bin || !fs.existsSync(bin)) return { ok: false, reason: '文件不存在' }
  let last = { ok: false, reason: '执行失败' }
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    last = probeOnce(bin)
    if (last.ok) return last
    if (attempt < retries) sleepSync(delayMs)
  }
  return last
}

// 同步小睡：本模块的探测调用都是同步语义，且只在失败重试时使用，开销可忽略。
function sleepSync(ms) {
  const shared = new SharedArrayBuffer(4)
  Atomics.wait(new Int32Array(shared), 0, 0, ms)
}

// 给网络请求套超时：GitHub 在受限网络下会长时间挂起，必须主动中断，否则界面卡在"安装中"。
async function withTimeout(promise, ms) {
  let timer = null
  const timeout = new Promise((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error(`TIMEOUT: ${ms}ms`)), ms)
  })
  try {
    return await Promise.race([promise, timeout])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function probeOnce(bin) {
  let result
  try {
    result = spawnSync(bin, ['--version'], { encoding: 'utf8', timeout: 5000 })
  } catch (error) {
    return { ok: false, reason: error?.message || '执行失败' }
  }
  if (result.error) return { ok: false, reason: result.error.message || '执行失败' }
  if (result.status !== 0) {
    const stderr = `${result.stderr || ''}`.trim().split(/\r?\n/).filter(Boolean).pop() || ''
    return { ok: false, reason: stderr || `exitCode=${result.status}` }
  }
  // 实测输出两行：'ripgrep 16.0.0 (rev 669cb4c755)' 与 'features:+pcre2'，只解析版本。
  const text = `${result.stdout || ''}${result.stderr || ''}`.trim()
  const matched = text.match(/ripgrep\s+(\d+)\.(\d+)\.(\d+)/)
  if (!matched) return { ok: false, reason: `无法识别版本输出：${text.split('\n')[0] || '(空)'}` }
  return { ok: true, version: `${matched[1]}.${matched[2]}.${matched[3]}` }
}

/**
 * 复刻 Agent 工具的真实执行环境，判断 rg 在 shell 里能否被解析到。
 *
 * 为什么需要：后端的 CommandExecutor 用 `ProcessBuilder(shell, "-lc", cmd)` 执行工具命令，
 * 且不设置 environment → 完全继承"谁启动了 JVM"的 PATH。
 * 因此"文件存在"不等于"Agent 能用"：IDEA 启动后端时就属于这种情况。
 *
 * 关键：必须用**后端实际使用的 shell** 来判定。桌面端注入的是包装脚本，
 * 若这里仍用 /bin/sh 检测，会把"已修复"误报成"未修复"（口径不一致）。
 */
export function resolveInShell({ shell, rootDir, timeout = 5000 } = {}) {
  if (IS_WINDOWS) return { ok: false, path: '', version: '', supported: false }
  const effectiveShell = shell || (rootDir ? defaultShellFor(rootDir) : '/bin/sh')
  let result
  try {
    result = spawnSync(effectiveShell, ['-lc', 'command -v rg'], { encoding: 'utf8', timeout })
  } catch (error) {
    return { ok: false, path: '', version: '', supported: false, reason: error?.message }
  }
  if (result.error) return { ok: false, path: '', version: '', supported: false, reason: result.error.message }
  const resolved = `${result.stdout || ''}`.trim().split(/\r?\n/).filter(Boolean).pop() || ''
  if (result.status !== 0 || !resolved) {
    return {
      ok: false,
      path: '',
      version: '',
      supported: true,
      shell: effectiveShell,
      reason: 'shell PATH 中未找到 rg'
    }
  }
  const probed = probeRg(resolved)
  return {
    ok: probed.ok,
    path: resolved,
    version: probed.ok ? probed.version : '',
    supported: true,
    shell: effectiveShell,
    reason: probed.ok ? '' : probed.reason
  }
}

/** 该 rootDir 下后端应使用的 shell：有包装脚本就用它，否则 /bin/sh。 */
export function defaultShellFor(rootDir) {
  if (IS_WINDOWS) return ''
  const wrapper = path.join(rootDir, 'tools', 'bin', 'agentforge-sh')
  return fs.existsSync(wrapper) ? wrapper : '/bin/sh'
}

/** 系统已装 rg 的候选绝对路径（绝不用 which，方案 §5.1）。 */
export function systemRgCandidates(home = process.env.HOME || process.env.USERPROFILE || '') {
  if (IS_WINDOWS) {
    const localAppData = process.env.LOCALAPPDATA || ''
    const list = []
    if (localAppData) list.push(path.join(localAppData, 'Programs', 'ripgrep', RG_BIN_NAME))
    list.push('C:\\Program Files\\ripgrep\\' + RG_BIN_NAME)
    if (home) list.push(path.join(home, '.cargo', 'bin', RG_BIN_NAME))
    return list
  }
  const list = ['/opt/homebrew/bin/rg', '/usr/local/bin/rg', '/usr/bin/rg', '/opt/local/bin/rg']
  if (home) list.push(path.join(home, '.cargo', 'bin', 'rg'))
  return list
}

/** 用户级目录下所有已安装版本，按版本从高到低排序。 */
export function userRgCandidates(rootDir) {
  const base = path.join(rootDir, 'tools', 'rg')
  if (!fs.existsSync(base)) return []
  let entries = []
  try {
    entries = fs.readdirSync(base, { withFileTypes: true }).filter((e) => e.isDirectory())
  } catch (_error) {
    return []
  }
  return entries
    .filter((e) => fs.existsSync(path.join(base, e.name, RG_BIN_NAME)))
    .map((e) => ({ version: e.name, bin: path.join(base, e.name, RG_BIN_NAME) }))
    .sort((a, b) => compareVersion(b.version, a.version))
}

/** 内置（安装包内）二进制路径。 */
export function bundledRgPath(bundledToolsDir, target) {
  if (!bundledToolsDir || !target) return ''
  return path.join(bundledToolsDir, 'rg', target.name, RG_BIN_NAME)
}

function readManifest(dir) {
  try {
    return JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'))
  } catch (_error) {
    return null
  }
}

/**
 * 检测 rg 能力状态。优先级：用户级 > 内置 > 系统（方案 §5.1）。
 * 纯读操作，不写盘、不联网。
 */
export function detectRg({
  rootDir,
  bundledToolsDir,
  target = resolveTarget(),
  systemCandidates = systemRgCandidates(),
  // 传入 true 时额外探测"Agent 工具的 shell 里能否解析到 rg"，用于对齐真实可用性。
  probeShell = false,
  shell
}) {
  const base = {
    id: 'rg',
    name: 'ripgrep',
    command: 'rg',
    kind: 'cli',
    capability: '高速文件内容检索（rg 工具）',
    minVersion: RG_MIN_VERSION,
    recommendedVersion: RG_RECOMMENDED_VERSION,
    installMode: target ? 'bundled' : 'none',
    canAutoInstall: Boolean(target),
    needsRestart: false,
    checkedAt: Date.now()
  }
  if (!target) {
    return {
      ...base,
      status: 'missing',
      version: '',
      source: 'none',
      path: '',
      message: `当前平台（${process.platform}/${process.arch}）暂无内置可用的 ripgrep 版本`
    }
  }

  const broken = []
  const tryCandidate = (bin, source) => {
    const probed = probeRg(bin)
    if (probed.ok) return { bin, source, version: probed.version }
    broken.push({ bin, source, reason: probed.reason })
    return null
  }

  let hit = null
  for (const candidate of userRgCandidates(rootDir)) {
    hit = tryCandidate(candidate.bin, 'user')
    if (hit) break
  }

  if (!hit) {
    const bundledBin = bundledRgPath(bundledToolsDir, target)
    if (bundledBin && fs.existsSync(bundledBin)) hit = tryCandidate(bundledBin, 'bundled')
  }

  if (!hit) {
    for (const bin of systemCandidates) {
      if (!fs.existsSync(bin)) continue
      hit = tryCandidate(bin, 'system')
      if (hit) break
    }
  }

  if (!hit) {
    if (broken.length) {
      const first = broken[0]
      return {
        ...base,
        status: 'broken',
        version: '',
        source: first.source,
        path: first.bin,
        message: `ripgrep 不可用：${first.reason}`
      }
    }
    return {
      ...base,
      status: 'missing',
      version: '',
      source: 'none',
      path: '',
      message: '未检测到 ripgrep，安装后 Agent 可使用 rg 工具检索代码'
    }
  }

  const belowMin = compareVersion(hit.version, RG_MIN_VERSION) < 0
  const belowRecommended = compareVersion(hit.version, RG_RECOMMENDED_VERSION) < 0
  const sourceLabel = { user: '已安装', bundled: '内置', system: '系统已装' }[hit.source] || hit.source

  if (belowMin) {
    return {
      ...base,
      status: 'outdated',
      version: hit.version,
      source: hit.source,
      path: hit.bin,
      message: `版本过低（${hit.version}），需 ≥ ${RG_MIN_VERSION}`
    }
  }

  const shellUsable = probeShell ? resolveInShell({ shell, rootDir }) : null

  return {
    ...base,
    status: 'ready',
    version: hit.version,
    source: hit.source,
    path: hit.bin,
    upgradeAvailable: belowRecommended,
    // effective 表示"Agent 工具能否真的调用到"：文件存在 ≠ 后端 shell 的 PATH 里有它。
    ...(shellUsable
      ? {
          effective: shellUsable.ok,
          effectivePath: shellUsable.path,
          effectiveVersion: shellUsable.version,
          effectiveReason: shellUsable.ok ? '' : shellUsable.reason
        }
      : {}),
    message: shellUsable
      ? shellUsable.ok
        ? belowRecommended
          ? `已就绪（${hit.version}，${sourceLabel}），可升级至 ${RG_RECOMMENDED_VERSION}`
          : `已就绪（${hit.version}，${sourceLabel}）`
        : `已安装（${hit.version}），但当前服务进程的 PATH 未包含它，Agent 调用 rg 会失败；重启本地服务或点击「修复 PATH」`
      : belowRecommended
        ? `已就绪（${hit.version}，${sourceLabel}），可升级至 ${RG_RECOMMENDED_VERSION}`
        : `已就绪（${hit.version}，${sourceLabel}）`
  }
}

function ensureExecutable(bin) {
  if (IS_WINDOWS) return
  try {
    fs.chmodSync(bin, 0o755)
  } catch (_error) {
    // 权限位设置失败不致命，后续探测会给出 Permission denied 的真实原因。
  }
}

/** 原子落盘：先写临时文件再 rename，避免半写坏文件（方案 §10 第 7 项）。 */
function atomicCopy(source, destination) {
  const dir = path.dirname(destination)
  fs.mkdirSync(dir, { recursive: true })
  const temp = `${destination}.tmp-${process.pid}`
  fs.copyFileSync(source, temp)
  fs.renameSync(temp, destination)
  ensureExecutable(destination)
}

/** 从内置目录安装：无网络、秒级完成。 */
function installFromBundled({ rootDir, bundledToolsDir, target }) {
  const source = bundledRgPath(bundledToolsDir, target)
  if (!source || !fs.existsSync(source)) {
    throw new Error(`内置 ripgrep 缺失：${source || '(未配置 tools 目录)'}`)
  }

  // 内置校验：优先信任 manifest 记录的二进制 sha256；无 manifest 时仅做可执行探测。
  const manifest = readManifest(path.dirname(source))
  if (manifest?.binSha256) {
    const actual = sha256File(source)
    if (actual !== manifest.binSha256) {
      throw new Error(`内置 ripgrep 校验失败：期望 ${manifest.binSha256}，实际 ${actual}`)
    }
  }

  const probed = probeRg(source)
  if (!probed.ok) {
    throw new Error(`内置 ripgrep 不可执行：${probed.reason}`)
  }

  const destination = path.join(rootDir, 'tools', 'rg', probed.version, RG_BIN_NAME)
  atomicCopy(source, destination)
  return { version: probed.version, path: destination, source: 'user' }
}

/** 在线下载安装：钉版本 + sha256 校验 + 清理 macOS quarantine（方案 §6.2）。 */
async function installFromOnline({ rootDir, target, log, fetchImpl }) {
  if (!target) throw new Error('当前平台暂无官方 ripgrep 版本')
  // 允许注入网络实现：Electron 主进程默认 fetch 不走系统代理，需改用 net.fetch。
  const doFetch = fetchImpl || globalThis.fetch
  const base = process.env.AGENTFORGE_RG_DOWNLOAD_BASE || RG_RELEASE_BASE
  const url = `${base}/${target.asset}`
  const downloadDir = path.join(rootDir, 'tools', '.download')
  fs.mkdirSync(downloadDir, { recursive: true })
  const archive = path.join(downloadDir, target.asset)

  log?.(`[rg] 下载 ${url}`)
  let response
  try {
    response = await withTimeout(doFetch(url, { redirect: 'follow' }), 60_000)
  } catch (error) {
    const wrapped = new Error(`NETWORK_UNREACHABLE: ${error?.cause?.code || error?.message || '网络不可达'}`)
    wrapped.code = 'NETWORK_UNREACHABLE'
    throw wrapped
  }
  if (!response.ok) {
    const wrapped = new Error(`NETWORK_UNREACHABLE: HTTP ${response.status}`)
    wrapped.code = 'NETWORK_UNREACHABLE'
    throw wrapped
  }
  fs.writeFileSync(archive, Buffer.from(await response.arrayBuffer()))

  const actual = sha256File(archive)
  if (actual !== target.sha256) {
    fs.rmSync(archive, { force: true })
    const wrapped = new Error(`CHECKSUM_MISMATCH: 期望 ${target.sha256}，实际 ${actual}`)
    wrapped.code = 'CHECKSUM_MISMATCH'
    throw wrapped
  }

  // 解压：两类资产都靠系统 tar 处理（Windows 10+ 自带 bsdtar，支持 zip）。
  const extracted = path.join(downloadDir, `extract-${process.pid}`)
  fs.rmSync(extracted, { recursive: true, force: true })
  fs.mkdirSync(extracted, { recursive: true })
  const untar = spawnSync('tar', ['-xf', archive, '-C', extracted], { encoding: 'utf8' })
  if (untar.status !== 0) {
    throw new Error(`解压失败：${(untar.stderr || '').trim() || `exitCode=${untar.status}`}`)
  }

  const found = findFileRecursive(extracted, RG_BIN_NAME)
  if (!found) throw new Error(`压缩包内未找到 ${RG_BIN_NAME}`)

  const probed = probeRg(found)
  if (!probed.ok) throw new Error(`下载的 ripgrep 不可执行：${probed.reason}`)

  const destination = path.join(rootDir, 'tools', 'rg', probed.version, RG_BIN_NAME)
  atomicCopy(found, destination)
  fs.rmSync(extracted, { recursive: true, force: true })
  fs.rmSync(archive, { force: true })

  // macOS：网络下载可能带 quarantine 属性，主动清理，避免 Gatekeeper 拦截。
  if (process.platform === 'darwin') {
    spawnSync('xattr', ['-d', 'com.apple.quarantine', destination], { encoding: 'utf8' })
  }

  const binSha256 = sha256File(destination)
  fs.writeFileSync(
    path.join(path.dirname(destination), 'manifest.json'),
    `${JSON.stringify({ version: probed.version, asset: target.asset, binSha256, preparedAt: new Date().toISOString() }, null, 2)}\n`,
    'utf8'
  )
  return { version: probed.version, path: destination, source: 'user' }
}

function findFileRecursive(root, name) {
  let entries = []
  try {
    entries = fs.readdirSync(root, { withFileTypes: true })
  } catch (_error) {
    return ''
  }
  for (const entry of entries) {
    const full = path.join(root, entry.name)
    if (entry.isFile() && entry.name === name) return full
    if (entry.isDirectory()) {
      const hit = findFileRecursive(full, name)
      if (hit) return hit
    }
  }
  return ''
}

/**
 * 安装 rg。mode：
 * - auto（默认）：有内置走内置（无网络），否则在线下载；
 * - bundled：只走内置；
 * - online：强制在线下载；
 * - upgrade：同 auto，但明确允许替换更高版本。
 * 幂等：已 ready 且非 upgrade 时直接返回当前状态。
 */
export async function installRg({
  rootDir,
  bundledToolsDir,
  mode = 'auto',
  target = resolveTarget(),
  log,
  fetchImpl,
  systemCandidates
}) {
  const current = detectRg({ rootDir, bundledToolsDir, target, systemCandidates })

  if (mode !== 'upgrade' && current.status === 'ready' && current.source === 'user') {
    return { status: current, installed: false, needsRestart: false, message: '当前已安装，无需重复安装' }
  }

  let result
  const hasBundled = Boolean(bundledRgPath(bundledToolsDir, target) && fs.existsSync(bundledRgPath(bundledToolsDir, target)))
  const preferBundled = mode === 'bundled' || (mode !== 'online' && hasBundled)

  if (preferBundled) {
    result = installFromBundled({ rootDir, bundledToolsDir, target })
  } else if (mode === 'bundled') {
    throw new Error(`内置 ripgrep 缺失：${bundledRgPath(bundledToolsDir, target) || '(未配置 tools 目录)'}`)
  } else {
    result = await installFromOnline({ rootDir, target, log, fetchImpl })
  }

  const status = detectRg({ rootDir, bundledToolsDir, target, systemCandidates })
  // 安装/升级后立即写 PATH 包装脚本：保证"装完 Agent 就能直接用 rg"，无需额外配置。
  const wrapper = ensureShellWrapper({ rootDir, bundledToolsDir, target })
  return {
    status: { ...status, needsRestart: true },
    installed: true,
    needsRestart: true,
    shellWrapper: wrapper,
    message: `已安装 ripgrep ${result.version}，需重启本地服务后生效`
  }
}

/** 需要注入后端进程 PATH 的目录列表（用户级优先，其次内置，最后系统已装目录）。 */
export function toolPathEntries({ rootDir, bundledToolsDir, target = resolveTarget() }) {
  const entries = []
  const detected = detectRg({ rootDir, bundledToolsDir, target })
  if (detected.status === 'ready' && detected.path) entries.push(path.dirname(detected.path))
  if (target && bundledToolsDir) {
    const bundledBin = bundledRgPath(bundledToolsDir, target)
    if (fs.existsSync(bundledBin)) entries.push(path.dirname(bundledBin))
  }
  return [...new Set(entries)]
}

/**
 * 生成"自带 PATH 的 shell 包装脚本"（方案 §7.4）。
 *
 * 为什么必须有它：Agent 的工具命令由后端 JVM 里的
 * `new ProcessBuilder(options.shell(), "-lc", command)` 执行，且**没有设置 environment**，
 * 因此完全继承"谁启动了 JVM"的 PATH。若后端由 IDEA / 外部进程启动，
 * 那里没有我们的工具目录，就会出现"设置页显示已就绪、Agent 里 rg 却 command not found"。
 *
 * 做法：安装/升级成功后写一个 shell 脚本，它先把工具目录前置进 PATH 再 exec 真正的 shell。
 * 这样无论后端由谁启动，只要用它当 shell，rg 就一定能被解析到。
 */
export function ensureShellWrapper({ rootDir, bundledToolsDir, target = resolveTarget() }) {
  if (IS_WINDOWS) return ''
  const binDir = path.join(rootDir, 'tools', 'bin')
  const wrapper = path.join(binDir, 'agentforge-sh')

  // 目标目录顺序：用户级（本应用管理）优先，其次内置，最后系统级常见路径。
  const dirs = []
  for (const candidate of userRgCandidates(rootDir)) dirs.push(path.dirname(candidate.bin))
  if (target && bundledToolsDir) {
    const bundledBin = bundledRgPath(bundledToolsDir, target)
    if (fs.existsSync(bundledBin)) dirs.push(path.dirname(bundledBin))
  }
  dirs.push('/opt/homebrew/bin', '/usr/local/bin')
  const home = process.env.HOME || ''
  if (home) dirs.push(path.join(home, '.cargo', 'bin'))

  const existing = [...new Set(dirs)].filter((dir) => dir && fs.existsSync(dir))

  // 累加后在最后一次性前置，保证第一个目录最终位于 PATH 最前（与检测链优先级对齐）。
  const lines = [
    '#!/bin/sh',
    '# AgentForge Desktop 自动生成：为 Agent 工具注入能力目录，请勿手工编辑。',
    'AGENTFORGE_EXTRA_PATH=""',
    ...existing.map((dir) => `[ -d ${shellQuote(dir)} ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"${shellQuote(`${dir}:`)}`),
    '[ -n "$AGENTFORGE_EXTRA_PATH" ] && PATH="$AGENTFORGE_EXTRA_PATH$PATH"',
    'export PATH',
    'exec /bin/sh "$@"',
    ''
  ]

  try {
    fs.mkdirSync(binDir, { recursive: true })
    const content = lines.join('\n')
    if (!fs.existsSync(wrapper) || fs.readFileSync(wrapper, 'utf8') !== content) {
      fs.writeFileSync(wrapper, content, 'utf8')
    }
    fs.chmodSync(wrapper, 0o755)
    return wrapper
  } catch (_error) {
    return ''
  }
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`
}

/** 组装一个供 Agent 使用的增强状态列表（后续扩展 git/node 等只需追加）。 */
export function listEnhancements(options) {
  return [detectRg(options)]
}