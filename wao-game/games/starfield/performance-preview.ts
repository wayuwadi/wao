// Dev-only visual QA panel; Vite removes the import from production builds.
export {};
const panel = document.createElement("aside");
panel.style.cssText =
  "padding:12px;background:#fff1a0;color:#493923;font:14px sans-serif";
const start = document.createElement("button");
start.textContent = "采样180帧（仅开发验收）";
const result = document.createElement("output");
result.setAttribute("aria-label", "帧调度验收结果");
panel.append(start, result);
document.body.append(panel);
let frame = 0;
let observer: PerformanceObserver | undefined;
start.addEventListener("click", () => {
  start.disabled = true;
  const samples: number[] = [],
    tasks: number[] = [];
  let previous = 0;
  const started = performance.now();
  if (PerformanceObserver.supportedEntryTypes.includes("longtask")) {
    observer = new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) tasks.push(entry.duration);
    });
    observer.observe({ type: "longtask" });
  }
  const sample = (time: number) => {
    if (previous) samples.push(time - previous);
    previous = time;
    if (samples.length < 180) {
      frame = requestAnimationFrame(sample);
      return;
    }
    observer?.disconnect();
    const sorted = [...samples].sort((a, b) => a - b);
    result.textContent = JSON.stringify({
      frames: samples.length,
      elapsedMs: +(performance.now() - started).toFixed(1),
      medianMs: +sorted[90]!.toFixed(2),
      p95Ms: +sorted[171]!.toFixed(2),
      maxMs: +sorted[179]!.toFixed(2),
      longTasks: tasks.length,
      maxLongTaskMs: Math.max(0, ...tasks),
      viewport: [innerWidth, innerHeight],
      note: "浏览器帧调度采样，不代表GPU耗时或实体手机性能",
    });
    start.disabled = false;
  };
  frame = requestAnimationFrame(sample);
});
window.addEventListener(
  "pagehide",
  () => {
    cancelAnimationFrame(frame);
    observer?.disconnect();
  },
  { once: true },
);
