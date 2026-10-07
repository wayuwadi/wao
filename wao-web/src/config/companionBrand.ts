import { COMPANIONS } from '@/utils/companionIdentity'

/** Shared identity; individual scenes may soften these colors through lighting. */
export const companionBrand = [
  { name: COMPANIONS.painter.name, color: '#00b5f7', trait: 'beret' },
  { name: COMPANIONS.bookworm.name, color: '#ffca0d', trait: 'glasses' },
  { name: COMPANIONS.happy.name, color: '#f42cdb', trait: 'sunglasses' },
  { name: COMPANIONS.frog.name, color: '#b3ec22', trait: 'bow' },
] as const
