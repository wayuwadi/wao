/** Merge one editor transaction's text/JSON updates without adding a timed loss window. */
export function coalescedDraftWriter(write: () => void) {
  let pending = false
  function flush() {
    if (!pending) return
    pending = false
    write()
  }
  return {
    schedule() { if (!pending) { pending = true; queueMicrotask(flush) } },
    flush,
    cancel() { pending = false },
  }
}
