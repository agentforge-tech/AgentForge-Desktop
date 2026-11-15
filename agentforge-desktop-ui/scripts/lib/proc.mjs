export function shutdown(children) {
  for (const child of children) {
    if (child && !child.killed) child.kill('SIGTERM')
  }
}