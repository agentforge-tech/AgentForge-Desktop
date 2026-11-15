import net from 'node:net'

export async function findFreePort(start, host = '127.0.0.1') {
  for (let port = start; port < start + 100; port += 1) {
    const free = await new Promise((resolve, reject) => {
      const server = net.createServer()
      server.unref()
      server.once('error', (error) => {
        if (error.code === 'EADDRINUSE') resolve(false)
        else reject(error)
      })
      server.listen({ port, host }, () => server.close(() => resolve(true)))
    })
    if (free) return port
  }
  throw new Error(`No free port found from ${start}`)
}

export async function waitForHttp(url, timeoutMs = 30000) {
  const started = Date.now()
  let lastError
  while (Date.now() - started < timeoutMs) {
    try {
      const response = await fetch(url)
      if (response.ok || response.status < 500) return
    } catch (error) {
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, 180))
  }
  throw new Error(`Timed out waiting for ${url}${lastError ? `: ${lastError.message}` : ''}`)
}