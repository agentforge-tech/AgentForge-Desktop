import path from 'node:path'
import { fileURLToPath } from 'node:url'

const libDir = path.dirname(fileURLToPath(import.meta.url))

export const scriptsDir = path.resolve(libDir, '..')
export const uiDir = path.resolve(scriptsDir, '..')
export const desktopDir = path.resolve(uiDir, '..')
export const serverDir = path.join(desktopDir, 'agentforge-desktop-server')
export const runtimeDir = path.join(uiDir, 'runtime')
export const devDataDir = path.join(uiDir, '.agentforge-dev')
export const isWindows = process.platform === 'win32'
export const javaExecutable = isWindows ? 'java.exe' : 'java'
export const mvnExecutable = isWindows ? 'mvn.cmd' : 'mvn'

export function agentforgeHome() {
  return process.env.AGENTFORGE_HOME || path.resolve(desktopDir, '..', 'AgentForge')
}

export function fromUi(...segments) {
  return path.join(uiDir, ...segments)
}

export function resolveFromUi(...segments) {
  return path.resolve(uiDir, ...segments)
}