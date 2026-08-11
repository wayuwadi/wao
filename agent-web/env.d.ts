/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_WAO_ORIGIN?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}
