import fs from "node:fs";

const inputPath = process.argv[2] ?? "upload-benchmark-results.csv";
const lines = fs.readFileSync(inputPath, "utf8").trim().split(/\r?\n/);
const expectedHeader =
  "variant,size_mib,iteration,elapsed_ms,peak_heap_delta_bytes,temp_disk_bytes,body_bytes,sha256";

if (lines.shift() !== expectedHeader) {
  throw new Error(`Unexpected CSV header in ${inputPath}`);
}

const rows = lines.map((line) => {
  const [variant, sizeMiB, iteration, elapsedMs, heapBytes, tempBytes, bodyBytes, sha256] =
    line.split(",");
  return {
    variant,
    sizeMiB: Number(sizeMiB),
    iteration: Number(iteration),
    elapsedMs: Number(elapsedMs),
    heapBytes: Number(heapBytes),
    tempBytes: Number(tempBytes),
    bodyBytes: Number(bodyBytes),
    sha256,
  };
});

function percentile(values, fraction) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(fraction * sorted.length) - 1];
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = sorted.length / 2;
  return sorted.length % 2
    ? sorted[Math.floor(middle)]
    : (sorted[middle - 1] + sorted[middle]) / 2;
}

function summarize(sizeMiB, variant) {
  const samples = rows.filter(
    (row) => row.sizeMiB === sizeMiB && row.variant === variant,
  );
  if (samples.length === 0) {
    throw new Error(`No ${variant} samples for ${sizeMiB} MiB`);
  }
  return {
    samples: samples.length,
    elapsedMedian: median(samples.map((row) => row.elapsedMs)),
    elapsedP95: percentile(samples.map((row) => row.elapsedMs), 0.95),
    heapMedian: median(samples.map((row) => row.heapBytes)),
    heapP95: percentile(samples.map((row) => row.heapBytes), 0.95),
    tempMedian: median(samples.map((row) => row.tempBytes)),
  };
}

const sizes = [...new Set(rows.map((row) => row.sizeMiB))].sort((left, right) => left - right);

for (const sizeMiB of sizes) {
  const sizeRows = rows.filter((row) => row.sizeMiB === sizeMiB);
  const hashes = new Set(sizeRows.map((row) => row.sha256));
  const byteCounts = new Set(sizeRows.map((row) => row.bodyBytes));
  if (hashes.size !== 1 || byteCounts.size !== 1) {
    throw new Error(`Content mismatch for ${sizeMiB} MiB`);
  }
}

console.log("| 文件 | 每组样本 | 旧版耗时 中位数 / P95 | 流式耗时 中位数 / P95 | 中位数变化 |");
console.log("|---:|---:|---:|---:|---:|");
for (const sizeMiB of sizes) {
  const legacy = summarize(sizeMiB, "legacy");
  const streaming = summarize(sizeMiB, "streaming");
  const reduction = (1 - streaming.elapsedMedian / legacy.elapsedMedian) * 100;
  console.log(
    `| ${sizeMiB} MiB | ${legacy.samples} | ` +
      `${legacy.elapsedMedian.toFixed(3)} / ${legacy.elapsedP95.toFixed(3)} ms | ` +
      `${streaming.elapsedMedian.toFixed(3)} / ${streaming.elapsedP95.toFixed(3)} ms | ` +
      `-${reduction.toFixed(1)}% |`,
  );
}

console.log();
console.log("| 文件 | 旧版堆增量 中位数 / P95 | 流式堆增量 中位数 / P95 | 中位数变化 | 临时磁盘 |");
console.log("|---:|---:|---:|---:|---:|");
for (const sizeMiB of sizes) {
  const legacy = summarize(sizeMiB, "legacy");
  const streaming = summarize(sizeMiB, "streaming");
  const reduction = (1 - streaming.heapMedian / legacy.heapMedian) * 100;
  console.log(
    `| ${sizeMiB} MiB | ` +
      `${(legacy.heapMedian / 1048576).toFixed(3)} / ${(legacy.heapP95 / 1048576).toFixed(3)} MiB | ` +
      `${(streaming.heapMedian / 1024).toFixed(1)} / ${(streaming.heapP95 / 1024).toFixed(1)} KiB | ` +
      `-${reduction.toFixed(1)}% | ` +
      `${(legacy.tempMedian / 1048576).toFixed(0)} MiB → ` +
      `${(streaming.tempMedian / 1048576).toFixed(0)} MiB |`,
  );
}

console.log();
console.log(`Validated ${rows.length} measured rows; body length and SHA-256 agree for every size.`);
