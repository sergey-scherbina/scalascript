#!/usr/bin/env node
const fs = require('fs')
const http = require('http')

const readyFile = process.argv[2]
if (!readyFile) {
  process.stderr.write('usage: x402-server-tools.js READY_FILE\n')
  process.exit(2)
}

const requirement = {
  error: 'Payment Required',
  requirements: {
    x402Version: 1,
    scheme: { type: 'exact', amount: '1000000' },
    network: 'Base',
    chainId: 8453,
    asset: {
      address: '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913',
      symbol: 'USDC',
      decimals: 6
    },
    payTo: '0x1111111111111111111111111111111111111111',
    resource: '/api/premium',
    description: 'Deterministic premium access',
    maxTimeoutSeconds: 300
  }
}

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok')
    return
  }
  if (request.method === 'GET' && request.url === '/api/premium') {
    if (request.headers['x-payment']) {
      response.writeHead(200, { 'Content-Type': 'text/plain' }).end('premium data')
    } else {
      response.writeHead(402, { 'Content-Type': 'application/json' })
        .end(JSON.stringify(requirement))
    }
    return
  }
  response.writeHead(404).end('not found')
})

server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(readyFile, `http://127.0.0.1:${server.address().port}\n`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
