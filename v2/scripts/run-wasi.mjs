// Minimal WASI preview1 host on Node's built-in `node:wasi`.
// Usage: node run-wasi.mjs <module.wasm> [args...]
import { readFile } from 'node:fs/promises';
import { WASI } from 'node:wasi';

const [, , wasmPath, ...args] = process.argv;
if (!wasmPath) { console.error('usage: node run-wasi.mjs <module.wasm> [args...]'); process.exit(2); }

const wasi = new WASI({ version: 'preview1', args: [wasmPath, ...args], env: {} });
const wasm = await WebAssembly.compile(await readFile(wasmPath));
const instance = await WebAssembly.instantiate(wasm, wasi.getImportObject());
process.exitCode = wasi.start(instance);
