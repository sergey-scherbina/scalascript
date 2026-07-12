#!/usr/bin/env node
const fs = require('fs')
const http = require('http')

const readyFile = process.argv[2]
if (!readyFile) {
  process.stderr.write('usage: swift-server-tools.js READY_FILE\n')
  process.exit(2)
}

const transfers = new Map()
const server = http.createServer((request, response) => {
  if (request.headers.authorization !== 'Bearer test-swift-key') {
    response.writeHead(401).end('{"error":"unauthorized"}')
    return
  }
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('end', () => {
    if (request.method === 'POST' && request.url === '/transfers') {
      const input = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      const pacs = input.rail === 'SWIFT_PACS008'
      const id = pacs ? 'swift-pacs-001' : 'swift-mt-001'
      const uetr = pacs
        ? '11111111-1111-4111-8111-111111111111'
        : '22222222-2222-4222-8222-222222222222'
      transfers.set(id, { id, uetr })
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ transfer_id: id, uetr, status: 'pending' }))
      return
    }
    const match = request.url.match(/^\/transfers\/([^/]+)$/)
    if (request.method === 'GET' && match && transfers.has(match[1])) {
      const transfer = transfers.get(match[1])
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({
        id: transfer.id,
        uetr: transfer.uetr,
        status: 'settled',
        gpi_trail: [{
          agent_bic: 'DEUTDEFF',
          status: 'ACCC',
          updated_at: '2026-07-12T10:00:00Z'
        }]
      }))
      return
    }
    response.writeHead(404).end('{"error":"not found"}')
  })
})

server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(readyFile, `http://127.0.0.1:${server.address().port}\n`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
