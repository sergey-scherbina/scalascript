#!/usr/bin/env node
const fs = require('fs')
const http = require('http')

const readyFile = process.argv[2]
if (!readyFile) {
  process.stderr.write('usage: rdf4j-server-tools.js READY_FILE\n')
  process.exit(2)
}

let titles = 2
const server = http.createServer((request, response) => {
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('end', () => {
    const body = Buffer.concat(chunks).toString('utf8')
    if (request.method !== 'POST' || request.url !== '/repositories/kg') {
      response.writeHead(404).end('not found')
      return
    }
    if ((request.headers['content-type'] || '').startsWith('application/sparql-update')) {
      if (body.includes('Frankenstein')) titles = 3
      response.writeHead(204).end()
      return
    }
    if (!body.includes('SELECT')) {
      response.writeHead(400).end('expected SPARQL SELECT')
      return
    }
    const bindings = body.includes('COUNT(*)')
      ? [{ n: { type: 'literal', value: String(titles) } }]
      : [
          {
            id: { type: 'uri', value: 'book:crime' },
            title: { type: 'literal', value: 'Crime and Punishment' },
            author: { type: 'literal', value: 'Fyodor Dostoevsky' }
          },
          {
            id: { type: 'uri', value: 'book:moby-dick' },
            title: { type: 'literal', value: 'Moby Dick' },
            author: { type: 'literal', value: 'Herman Melville' }
          }
        ]
    const payload = JSON.stringify({ head: { vars: [] }, results: { bindings } })
    response.writeHead(200, { 'Content-Type': 'application/sparql-results+json' })
    response.end(payload)
  })
})

server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(readyFile,
    `http://127.0.0.1:${server.address().port}/repositories/kg\n`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
