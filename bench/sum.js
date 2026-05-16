function sum(n, acc) {
  while (n > 0) { acc += n; n -= 1; }
  return acc;
}

const n      = 1000000;
const t0     = Date.now();
const result = sum(n, 0);
const t1     = Date.now();

console.log(`BENCH_MS: ${t1 - t0}`);
console.log(`result=${result}`);
