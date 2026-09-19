/** 用户可见的执行边界；与现有无人值守 Runner 语义保持一致。 */
export const AI_EXECUTION_MODE_DESCRIPTIONS: Record<string, string> = {
  direct: '发送后立即开始处理目标；如果目标包含修改或执行操作，AI 会按工作流规则执行。',
  plan: '先制定计划，再连续执行。常规选择会自动采用推荐项；不会停在计划阶段等待你确认。',
  goal: '围绕目标持续执行多个回合，直到完成、阻塞或受额度等限制；可能持续消耗模型额度。',
}
