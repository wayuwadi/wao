export interface ContextAction {
  id: string
  label: string
  run: () => void | Promise<void>
  disabled?: boolean
  danger?: boolean
  separatorBefore?: boolean
}
