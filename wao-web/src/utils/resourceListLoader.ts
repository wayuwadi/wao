interface ResourceListOptions<T> {
  read: () => Promise<T[]>
  current: () => T[]
  replace: (items: T[]) => void
  key: (item: T) => string
  loading: () => void
  loaded: () => void
  failed: (cause: unknown) => void
}

/** Share concurrent reads and preserve writes confirmed while a snapshot was in flight. */
export function createResourceListLoader<T>(options: ResourceListOptions<T>) {
  let generation = 0
  let active: { changes: Map<string, T | null>; promise: Promise<boolean> } | undefined
  const mutations = new Map<string, Promise<unknown>>()

  function reset() {
    generation++
    active = undefined
    mutations.clear()
  }

  function upsert(item: T) {
    const key = options.key(item)
    active?.changes.set(key, item)
    const items = options.current()
    const index = items.findIndex(value => options.key(value) === key)
    if (index < 0) options.replace([item, ...items])
    else options.replace(items.map((value, i) => i === index ? item : value))
  }

  function remove(key: string) {
    active?.changes.set(key, null)
    options.replace(options.current().filter(item => options.key(item) !== key))
  }

  function refresh(): Promise<boolean> {
    if (active) return active.promise
    const requestedGeneration = generation
    const changes = new Map<string, T | null>()
    options.loading()
    // Install the shared operation before the transport can resolve or throw.
    const operation = { changes, promise: Promise.resolve(false) }
    active = operation
    const read = async () => options.read()
    operation.promise = read().then(items => {
      if (requestedGeneration !== generation) return false
      const loadedKeys = new Set(items.map(options.key))
      const result = items.flatMap(item => {
        const key = options.key(item)
        if (!changes.has(key)) return [item]
        const confirmed = changes.get(key)
        return confirmed == null ? [] : [confirmed]
      })
      const additions = options.current().filter(item => {
        const key = options.key(item)
        return changes.has(key) && changes.get(key) !== null && !loadedKeys.has(key)
      })
      options.replace([...additions, ...result])
      options.loaded()
      return true
    }).catch((cause: unknown) => {
      if (requestedGeneration !== generation) return false
      options.failed(cause)
      return false
    }).finally(() => {
      if (active === operation) active = undefined
    })
    return operation.promise
  }

  /** A ticket also distinguishes A → B → A from the original A session. */
  const ticket = () => {
    const captured = generation
    return () => captured === generation
  }

  /** Serialize writes to one asset, so delayed full DTOs cannot revive a later deletion. */
  function mutate<R>(key: string, write: () => Promise<R>): Promise<R> {
    const isCurrent = ticket()
    const execute = async () => {
      if (!isCurrent()) throw new Error('账号已切换，请在原账号中重新操作')
      return write()
    }
    const previous = mutations.get(key)
    const pending = previous ? previous.catch(() => {}).then(execute) : execute()
    const operation = pending.finally(() => {
      if (mutations.get(key) === operation) mutations.delete(key)
    })
    mutations.set(key, operation)
    return operation
  }

  return { refresh, upsert, remove, reset, ticket, mutate }
}
