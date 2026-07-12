#!/usr/bin/env node
'use strict';

const readline = require('node:readline');

const tools = [
  { name: 'echo', description: 'Return the input string unchanged', inputSchema: { type: 'object' } },
  { name: 'add', description: 'Add two numbers', inputSchema: { type: 'object' } },
  { name: 'get_weather', description: 'Get current weather for a city (stub)', inputSchema: { type: 'object' } },
];

function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function handle(message) {
  if (message.id === undefined) return;
  switch (message.method) {
    case 'initialize':
      reply(message.id, {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {}, resources: {}, prompts: {} },
        serverInfo: { name: 'ssc-example-tools', version: '1.0.0' },
      });
      break;
    case 'tools/list': reply(message.id, { tools }); break;
    case 'resources/list': reply(message.id, { resources: [] }); break;
    case 'prompts/list': reply(message.id, { prompts: [] }); break;
    case 'tools/call': {
      const name = message.params && message.params.name;
      const args = (message.params && message.params.arguments) || {};
      let text;
      if (name === 'echo') text = `Echo: ${args.message || ''}`;
      else if (name === 'add') text = String(Number(args.a || 0) + Number(args.b || 0));
      else if (name === 'get_weather') text = `Weather in ${args.city || ''}: sunny, 22°C (stub data)`;
      else return reply(message.id, { content: [{ type: 'text', text: `unknown tool: ${name}` }], isError: true });
      reply(message.id, { content: [{ type: 'text', text }], isError: false });
      break;
    }
    default:
      process.stdout.write(JSON.stringify({
        jsonrpc: '2.0', id: message.id,
        error: { code: -32601, message: `method not found: ${message.method}` },
      }) + '\n');
  }
}

const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', line => {
  try { handle(JSON.parse(line)); }
  catch (error) {
    process.stderr.write(`mcp-server-tools: ${error.message}\n`);
  }
});
