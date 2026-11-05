import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const clean = process.argv.includes('--clean')
const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const uiDir = path.resolve(scriptDir, '..')
const desktopDir = path.resolve(uiDir, '..')
const serverDir = path.join(desktopDir, 'agentforge-desktop-server')
const agentforgeHome = process.env.AGENTFORGE_HOME || path.resolve(desktopDir, '..', 'AgentForge')
const executable = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'

function existsCommand(value) {
  if (!value) return false
  if (value.includes(path.sep) || path.isAbsolute(value)) return fs.existsSync(value)
  return spawnSync(process.platform === 'win32' ? 'where' : 'which', [value], { stdio: 'ignore' }).status === 0
}

function findMaven() {
  const candidates = [
    process.env.AGENTFORGE_MAVEN,
    path.join(desktopDir, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'),
    ...(process.platform === 'win32'
      ? ['mvn.cmd', 'mvn']
      : ['mvn', '/opt/homebrew/bin/mvn', '/usr/local/bin/mvn',
         '/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn',
         '/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn'])
  ]
  return candidates.find(existsCommand)
}

function findJavaHome() {
  const candidates = [
    process.env.AGENTFORGE_DESKTOP_JAVA_HOME,
    process.env.JAVA_HOME,
    '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home',
    '/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home',
    '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home'
  ].filter(Boolean)
  for (const home of candidates) {
    const java = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    if (!fs.existsSync(java)) continue
    const result = spawnSync(java, ['-version'], { encoding: 'utf8' })
    const text = `${result.stdout || ''}${result.stderr || ''}`
    if (/version "1\.8/.test(text)) return home
  }
  return process.env.JAVA_HOME || ''
}

const mvn = findMaven()
if (!mvn) {
  throw new Error('Maven not found. Set AGENTFORGE_MAVEN or install Maven 3.9+.')
}

const javaHome = findJavaHome()
if (!javaHome) {
  throw new Error('JDK 8 not found. Set AGENTFORGE_DESKTOP_JAVA_HOME to a JDK 8 home.')
}
console.log(`[AgentForge] JAVA_HOME=${javaHome}`)
console.log(`[AgentForge] Maven=${mvn}`)

const env = {
  ...process.env,
  JAVA_HOME: javaHome,
  PATH: `${path.join(javaHome, 'bin')}${path.delimiter}${process.env.PATH || ''}`
}

function run(command, args, options = {}) {
  console.log(`\n> ${command} ${args.join(' ')}`)
  const result = spawnSync(command, args, { stdio: 'inherit', env, ...options })
  if (result.status !== 0) process.exit(result.status ?? 1)
}

function localRepository() {
  const result = spawnSync(
    mvn,
    ['help:evaluate', '-Dexpression=settings.localRepository', '-q', '-DforceStdout'],
    { encoding: 'utf8', env }
  )
  const lines = String(result.stdout || '').trim().split(/\r?\n/).filter(Boolean)
  return lines.length ? lines[lines.length - 1].trim() : path.join(os.homedir(), '.m2', 'repository')
}

const repo = localRepository()
const coreJar = path.join(
  repo,
  'cloud/changlu/agentforge/agentforge-agent-core/1.0.0-SNAPSHOT/agentforge-agent-core-1.0.0-SNAPSHOT.jar'
)
const coreMissing = !fs.existsSync(coreJar)
if (coreMissing || process.env.AGENTFORGE_REBUILD_CORE === '1') {
  if (!fs.existsSync(agentforgeHome)) {
    throw new Error(`AgentForge source not found: ${agentforgeHome}. Set AGENTFORGE_HOME or run AgentForge core install manually.`)
  }
  console.log(`[AgentForge] installing core artifacts from ${agentforgeHome}`)
  run(mvn, [
    '-f', path.join(agentforgeHome, 'pom.xml'),
    '-pl', ':agentforge-ai-bom,:agentforge-model-api,:agentforge-model-core,:agentforge-model-openai,:agentforge-model-anthropic,:agentforge-model-registry,:agentforge-agent-core',
    '-am', 'install', '-DskipTests', '-Dspotless.check.skip=true', '-Dspotless.apply.skip=true'
  ])
} else {
  console.log(`[AgentForge] core artifacts found: ${coreJar}`)
}

run(mvn, [
  '-f', path.join(serverDir, 'pom.xml'),
  ...(clean ? ['clean'] : ['clean']),
  'package', '-DskipTests'
])
console.log('[AgentForge] server jar build completed')
