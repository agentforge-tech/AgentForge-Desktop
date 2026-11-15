import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { desktopDir } from './paths.mjs'
import { findCommand } from './exec.mjs'

export function findMaven() {
  const candidates = [
    process.env.AGENTFORGE_MAVEN,
    path.join(desktopDir, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'),
    ...(process.platform === 'win32'
      ? ['mvn.cmd', 'mvn']
      : ['mvn', '/opt/homebrew/bin/mvn', '/usr/local/bin/mvn',
         '/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn',
         '/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn'])
  ]
  return findCommand(candidates) || ''
}

export function localRepository(mvn, env = process.env) {
  const result = spawnSync(
    mvn,
    ['help:evaluate', '-Dexpression=settings.localRepository', '-q', '-DforceStdout'],
    { encoding: 'utf8', env }
  )
  const lines = String(result.stdout || '').trim().split(/\r?\n/).filter(Boolean)
  return lines.length ? lines[lines.length - 1].trim() : path.join(os.homedir(), '.m2', 'repository')
}

export function coreArtifact(repo) {
  return path.join(
    repo,
    'cloud',
    'changlu',
    'agentforge',
    'agentforge-agent-core',
    '1.0.0-SNAPSHOT',
    'agentforge-agent-core-1.0.0-SNAPSHOT.jar'
  )
}