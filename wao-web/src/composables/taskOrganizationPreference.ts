import { computed, onScopeDispose, ref, watch } from 'vue'
import { fetchPreference } from '@/api/favorites'
import { saveTaskOrganizationPreference, type TaskOrganizationKey } from '@/api/taskOrganization'
import { useSessionStore } from '@/stores/session'

/** CAS-backed preferences; a conflict never advances the draft's original base implicitly. */
export function useTaskOrganizationPreference<T>(key: TaskOrganizationKey, decode: (raw: string | null) => T) {
  const session = useSessionStore()
  const owner = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
  const value = ref<T>(decode(null)), remote = ref<T>(decode(null))
  const state = ref<'idle'|'loading'|'saving'|'saved'|'error'>('idle'), error = ref(''), loaded = ref(false), conflict = ref(false), remoteReady = ref(false)
  let expected = '', remoteRaw: string | null = null, pending = false, epoch = 0, sequence = 0
  onScopeDispose(() => { epoch++; sequence++ })
  const cacheKey = (identity = owner.value) => `wao-task-organization:${identity}:${key}`
  function protect() {
    try { localStorage.setItem(cacheKey(), JSON.stringify({ raw: JSON.stringify(value.value), expected, pending })); return true }
    catch { return false }
  }
  function saved(raw: string) {
    expected = raw; remoteRaw = raw; remoteReady.value=true; pending = false; conflict.value = false; loaded.value = true
    remote.value = decode(raw) as typeof remote.value; state.value = 'saved'; error.value = ''; protect()
  }
  async function load() {
    if (!session.userId || state.value === 'saving') return
    const generation=epoch, request=++sequence
    state.value='loading';error.value=''
    try {
      const raw=await fetchPreference(key)
      if(generation!==epoch || request!==sequence)return
      loaded.value=true;remoteRaw=raw;remoteReady.value=true;remote.value=decode(raw) as typeof remote.value
      if(pending) {
        if(JSON.stringify(decode(raw))===JSON.stringify(value.value)) saved(raw)
        else if(raw!==expected) { conflict.value=true;state.value='error';error.value='其他窗口已修改账号配置，本机草稿已保留。请选择要保留的版本。' }
        else { conflict.value=false;state.value='error';error.value='本机修改尚未同步，可重试保存。' }
      } else { value.value=decode(raw) as typeof value.value;saved(raw) }
    } catch { if(generation===epoch && request===sequence) { state.value='error';error.value='无法读取账号配置，已保留本机内容，请重试读取。' } }
  }
  async function save() {
    if(state.value==='saving' || conflict.value || !session.userId)return
    const raw=JSON.stringify(value.value)
    if(raw.length>60_000) {state.value='error';error.value='配置内容超过保存上限，请减少筛选条件或文件夹。';return}
    const generation=epoch, request=++sequence, base=expected
    pending=true;const protectedLocally=protect();state.value='saving';error.value=''
    try {
      await saveTaskOrganizationPreference(key,raw,base)
      if(generation===epoch && request===sequence)saved(raw)
    } catch (e: unknown) {
      if(generation!==epoch || request!==sequence)return
      state.value='error'
      const failure=e && typeof e==='object' ? e as {status?:number;response?:{status?:number}} : undefined
      if((failure?.status??failure?.response?.status)===409) { remoteRaw=null;remoteReady.value=false;conflict.value=true;error.value='其他窗口已修改账号配置，本机草稿已保留。';await load() }
      else error.value=protectedLocally?'尚未同步到账号，本机修改已保留，请重试。':'保存失败，且无法保护本机修改，请保持页面并重试。'
    }
  }
  async function set(next:T) {
    if(!loaded.value || ['saving','loading'].includes(state.value) || conflict.value || error.value)return
    value.value=decode(JSON.stringify(next)) as typeof value.value
    await save()
  }
  function retry() { return pending && !conflict.value ? save() : load() }
  function useRemote() {
    if(remoteRaw===null || state.value==='loading' || state.value==='saving')return
    value.value=decode(remoteRaw) as typeof value.value;saved(remoteRaw)
  }
  async function keepLocal() {
    if(remoteRaw===null || state.value==='loading' || state.value==='saving')return
    expected=remoteRaw;conflict.value=false;await save()
  }
  watch(owner,()=>{
    epoch++;sequence++;expected='';remoteRaw=null;remoteReady.value=false;pending=false
    value.value=decode(null) as typeof value.value;remote.value=decode(null) as typeof remote.value
    loaded.value=false;conflict.value=false;state.value='idle';error.value=''
    try { const cached=JSON.parse(localStorage.getItem(cacheKey())||'null');if(cached && typeof cached.raw==='string') { value.value=decode(cached.raw) as typeof value.value;expected=typeof cached.expected==='string'?cached.expected:'';pending=cached.pending===true } }
    catch { error.value='本机配置无法读取。' }
    void load()
  },{immediate:true,flush:'sync'})
  return {value,remote,remoteReady,state,error,loaded,conflict,set,retry,load,useRemote,keepLocal}
}
