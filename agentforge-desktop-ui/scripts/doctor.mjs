import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { agentforgeHome, devDataDir, javaExecutable, runtimeDir, uiDir } from './lib/paths.mjs'
import { findMaven, localRepository, coreArtifact } from './lib/maven.mjs'
import { findJavaHome, javaBinary } from './lib/java.mjs'

const results = []

function report(level, label, detail) {
  results.push({ level, label, detail })
  const tag = { ok: 'ok  ', warn: 'warn', fail: 'fail' }[level]
  console.log(`[${tag}] ${label}${detail ? `: ${detail}` : ''}`)
}

function commandVersion(command, args = ['--version'], env = process.env) {
  const result = spawnSync(command, args, { encoding: 'utf8', env })
  if (result.status !== 0) return ''
  return `${result.stdout || ''}${result.stderr || ''}`.trim().split(/\r?\n/)[0]
}

function javaEnv(javaHome) {
  return javaHome
    ? { ...process.env, JAVA_HOME: javaHome, PATH: `${path.join(javaHome, 'bin')}${path.delimiter}${process.env.PATH || ''}` }
    : process.env
}

function majorOf(text) {
  const match = String(text).match(/(\d+)(?:\.\d+)*/)
  return match ? Number(match[1]) : 0
}

function checkNode() {
  const version = process.versions.node
  const major = majorOf(version)
  if (major >= 22) report('ok', 'Node.js', `v${version}`)
  else report('warn', 'Node.js', `v${version} (README 要求 22+)`)
}

function checkNpm() {
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const version = commandVersion(npm)
  if (!version) {
    report('fail', 'npm', '未找到，请安装 Node.js 22+ 并重新打开终端')
    return
  }
  if (majorOf(version) >= 10) report('ok', 'npm', version)
  else report('warn', 'npm', `${version} (README 要求 10+)`)
}

function checkJava() {
  const home = findJavaHome()
  if (home) {
    report('ok', 'JDK 8', home)
    return home
  }
  const pathJava = spawnSync(javaExecutable, ['-version'], { encoding: 'utf8' })
  const current = `${pathJava.stdout || ''}${pathJava.stderr || ''}`.trim().split(/\r?\n/)[0]
  report('fail', 'JDK 8', current ? `当前 java: ${current}；请设置 AGENTFORGE_DESKTOP_JAVA_HOME` : '未找到 Java 8')
  return ''
}

function checkMaven(javaHome) {
  const mvn = findMaven()
  if (!mvn) {
    report('fail', 'Maven', '未找到，请安装 Maven 3.9+ 或设置 AGENTFORGE_MAVEN')
    return ''
  }
  const version = commandVersion(mvn, ['-version'], javaEnv(javaHome))
  report('ok', 'Maven', version || mvn)
  return mvn
}

function checkCoreArtifacts(mvn, javaHome) {
  const source = agentforgeHome()
  if (!mvn) {
    report('warn', 'AgentForge 核心构件', 'Maven 不可用，跳过本地仓库检查')
    return
  }
  const repo = localRepository(mvn, javaEnv(javaHome))
  const jar = coreArtifact(repo)
  if (fs.existsSync(jar)) {
    report('ok', 'AgentForge 核心构件', jar)
    return
  }
  if (process.env.AGENTFORGE_REBUILD_CORE === '1') {
    report('warn', 'AgentForge 核心构件', '缺失，且 AGENTFORGE_REBUILD_CORE=1，将重新安装')
    return
  }
  if (fs.existsSync(source)) {
    report('warn', 'AgentForge 核心构件', `本地仓库缺失，构建时会从源码安装: ${source}`)
    return
  }
  report('fail', 'AgentForge 核心构件', `本地仓库与源码目录均缺失，请设置 AGENTFORGE_HOME（当前: ${source}）`)
}

function checkNodeModules() {
  const vite = path.join(uiDir, 'node_modules', 'vite', 'bin', 'vite.js')
  const electron = path.join(uiDir, 'node_modules', 'electron', 'package.json')
  if (fs.existsSync(vite) && fs.existsSync(electron)) {
    report('ok', '前端依赖', path.join(uiDir, 'node_modules'))
  } else {
    report('fail', '前端依赖', `缺少 vite/electron，请先执行 npm install（${uiDir}）`)
  }
}

function checkRuntime() {
  const java = javaBinary(path.join(runtimeDir, 'jre'))
  if (java) report('ok', '开发期 JRE', java)
  else report('warn', '开发期 JRE', 'runtime/jre 缺失，将回退到 AGENTFORGE_DESKTOP_JAVA_HOME / JAVA_HOME / PATH')

  const jar = path.join(runtimeDir, 'server', 'agentforge-desktop-server.jar')
  if (fs.existsSync(jar)) report('ok', '内置后端 JAR', jar)
  else report('warn', '内置后端 JAR', 'runtime/server 缺失，desktop:dev:embedded 会先执行 build:server + runtime:server')
}

function checkDisk() {
  try {
    const stat = fs.statfsSync(uiDir)
    const free = stat.bsize * stat.bavail
    const gib = free / 1024 / 1024 / 1024
    const text = `${gib.toFixed(1)} GiB 可用`
    if (free < 256 * 1024 * 1024) report('fail', '磁盘空间', text)
    else if (free < 1024 * 1024 * 1024) report('warn', '磁盘空间', `${text}，首次 Maven 构建建议至少 1 GiB`)
    else report('ok', '磁盘空间', text)
  } catch (error) {
    report('warn', '磁盘空间', `无法读取: ${error.message}`)
  }
}

function checkWritable() {
  try {
    fs.accessSync(uiDir, fs.constants.W_OK)
    report('ok', '工作目录权限', uiDir)
  } catch {
    report('fail', '工作目录权限', `${uiDir} 不可写`)
  }
  if (fs.existsSync(devDataDir)) {
    try {
      fs.accessSync(devDataDir, fs.constants.W_OK)
      report('ok', '调试数据目录', devDataDir)
    } catch {
      report('fail', '调试数据目录', `${devDataDir} 不可写`)
    }
  }
}

console.log('[AgentForge] 环境体检 doctor\n')
checkNode()
checkNpm()
const javaHome = checkJava()
const mvn = checkMaven(javaHome)
checkCoreArtifacts(mvn, javaHome)
checkNodeModules()
checkRuntime()
checkDisk()
checkWritable()

const failures = results.filter((item) => item.level === 'fail')
const warnings = results.filter((item) => item.level === 'warn')
console.log(`\n[AgentForge] 完成：${results.length - failures.length - warnings.length} 通过，${warnings.length} 警告，${failures.length} 失败`)
if (failures.length) process.exit(1)