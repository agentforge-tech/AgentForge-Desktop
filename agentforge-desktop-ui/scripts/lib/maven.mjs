import fs from 'node:fs'
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

export function isolatedSettings(repo) {
  const file = path.join(os.tmpdir(), 'agentforge-desktop-maven-settings.xml')
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
    <localRepository>${repo}</localRepository>
    <mirrors>
        <mirror>
            <id>central</id>
            <mirrorOf>*</mirrorOf>
            <url>https://repo.maven.apache.org/maven2</url>
        </mirror>
    </mirrors>
</settings>
`
  fs.writeFileSync(file, xml, 'utf8')
  return file
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
    '1.0.2-SNAPSHOT',
    'agentforge-agent-core-1.0.2-SNAPSHOT.jar'
  )
}

export function requiredArtifacts(repo) {
  const version = '1.0.2-SNAPSHOT'
  const artifacts = [
    ['agentforge-ai-bom', 'pom'],
    ['agentforge-model-api', 'jar'],
    ['agentforge-model-core', 'jar'],
    ['agentforge-model-openai', 'jar'],
    ['agentforge-model-anthropic', 'jar'],
    ['agentforge-model-registry', 'jar'],
    ['agentforge-agent-core', 'jar'],
    ['agentforge-harness-agent', 'jar'],
    ['agentforge-service-protocal', 'jar']
  ]
  return artifacts.map(([artifact, extension]) =>
    path.join(
      repo,
      'cloud',
      'changlu',
      'agentforge',
      artifact,
      version,
      `${artifact}-${version}.${extension}`
    )
  )
}