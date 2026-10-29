import { TASK_QUADRANTS } from './taskQuadrants'
import type { TaskWorkspaceFilter } from './taskWorkspaceFilter'
export interface QuadrantRule extends TaskWorkspaceFilter { name:string; custom:boolean }
export function defaultQuadrantRules():QuadrantRule[] { return TASK_QUADRANTS.map(q=>({name:q.label,custom:false,projects:[],tags:[],tagMode:'any',date:'all',priorities:[q.priority]})) }
export function decodeQuadrantRules(raw:string|null):QuadrantRule[] {
 const defaults=defaultQuadrantRules()
 try { const value=JSON.parse(raw||'null'); if(!Array.isArray(value)||value.length!==4)return defaults
 return defaults.map((d,i)=>{const v=value[i]; if(!v||typeof v.name!=='string'||!['any','all','none'].includes(v.tagMode)||!['all','today','tomorrow','week','overdue','undated'].includes(v.date))return d
 const strings=(x:unknown):string[]=>Array.isArray(x)?x.filter((s):s is string=>typeof s==='string'):[]
 return {name:v.name.slice(0,40)||d.name,custom:v.custom===true,projects:strings(v.projects),tags:strings(v.tags),tagMode:v.tagMode,date:v.date,priorities:strings(v.priorities).filter(p=>['P0','P1','P2','P3'].includes(p))}})
 }catch{return defaults}
}
