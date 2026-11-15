import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { javaExecutable } from './paths.mjs'

export function javaBinary(home) {
  if (!home) return ''
  const nested = path.join(home, 'jre', 'bin', javaExecutable)
  if (fs.existsSync(nested)) return nested
  const direct = path.join(home, 'bin', javaExecutable)
  return fs.existsSync(direct) ? direct : ''
}

export function javaVersion(home) {
  const java = javaBinary(home)
  if (!java) return ''
  const result = spawnSync(java, ['-version'], { encoding: 'utf8' })
  return `${result.stdout || ''}${result.stderr || ''}`.trim()
}

export function isJava8(home) {
  return /version "1\.8/.test(javaVersion(home))
}

export function macJavaHomes() {
  if (process.platform !== 'darwin') return []
  return [
    '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home',
    '/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home'
  ]
}

export function findJavaHome(candidates = []) {
  const list = [
    ...candidates,
    process.env.AGENTFORGE_DESKTOP_JAVA_HOME,
    process.env.JAVA_HOME,
    ...macJavaHomes()
  ].filter(Boolean)
  return list.find(isJava8) || ''
}