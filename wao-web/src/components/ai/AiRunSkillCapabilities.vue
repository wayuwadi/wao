<script setup lang="ts">
import type { AiSkillAudit, AiSkillCapability } from '@/types'

const props = defineProps<{ skills?: AiSkillCapability[]; audits?: AiSkillAudit[] }>()
const audit = (versionId: string) => props.audits?.find((item) => item.versionId === versionId)
const label = (status?: AiSkillAudit['status']) => ({ loaded: '已加载', failed: '加载失败', incompatible: '不兼容', not_reported: '未回传' }[status || 'not_reported'])
</script>

<template>
  <section v-if="skills?.length" class="run-skills">
    <div class="skill-title">本次固定 Skill</div>
    <div class="skill-list">
      <div v-for="skill in skills" :key="skill.versionId" class="skill-row">
        <span><b>{{ skill.displayName }}</b><small>v{{ skill.version }} · {{ skill.sha256.slice(0, 12) }}</small></span>
        <em :class="audit(skill.versionId)?.status || 'pending'" :title="audit(skill.versionId)?.message || '等待 Runner 装配'">
          {{ audit(skill.versionId) ? label(audit(skill.versionId)?.status) : '等待加载' }}
        </em>
      </div>
    </div>
  </section>
</template>

<style scoped>
.run-skills{margin-top:16px}.skill-title{font-size:12px;font-weight:750;color:var(--wao-text-2);margin-bottom:8px}.skill-list{display:grid;gap:7px}.skill-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:9px 11px;border:1px solid var(--wao-line);border-radius:9px;background:var(--wao-bg-sub)}.skill-row>span{display:grid;min-width:0}.skill-row b{font-size:12px}.skill-row small{font-size:10px;color:var(--wao-text-weak);font-family:ui-monospace,monospace}.skill-row em{font-style:normal;font-size:10px;padding:4px 7px;border-radius:999px;background:var(--wao-bg-hover-2);color:var(--wao-text-weak);white-space:nowrap}.skill-row em.loaded{background:var(--wao-success-bg);color:var(--wao-success)}.skill-row em.failed,.skill-row em.incompatible{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}
</style>
