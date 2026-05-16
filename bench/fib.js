function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

const n      = 28;
const t0     = Date.now();
const result = fib(n);
const t1     = Date.now();

console.log(`BENCH_MS: ${t1 - t0}`);
console.log(`result=${result}`);
