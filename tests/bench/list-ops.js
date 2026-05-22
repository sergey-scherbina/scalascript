const n  = 100000;
const t0 = Date.now();

const xs = [];
for (let i = 0; i < n; i++) xs.push(i);
const result = xs.map(x => x * 2).filter(x => x % 3 === 0).reduce((a, x) => a + x, 0);

const t1 = Date.now();

console.log(`BENCH_MS: ${t1 - t0}`);
console.log(`result=${result}`);
