import { describe, expect, it } from 'vitest'
import { activityPose, companionActivities } from '@/components/home/companionActivities'

describe('四个伙伴的十五秒活动', () => {
  it('每种活动所有角色都参与，十五秒后姿态归零且不自动重播', () => {
    for (const { id } of companionActivities) for (let index = 0; index < 4; index++) {
      expect(activityPose(id, 2, index).y).toBeGreaterThan(0)
      for (const t of [15, 16, 60]) {
        const pose = activityPose(id, t, index)
        expect(pose.x).toBe(0)
        expect(pose.y).toBe(0)
        expect(pose.stretch).toBe(1)
        expect(Math.cos(pose.turn)).toBeCloseTo(1)
        expect(Math.cos(pose.tilt)).toBeCloseTo(1)
      }
    }
  })
  it('芭蕾包含完整转身，街舞包含地板旋转；其他活动有不同节奏', () => {
    expect(activityPose('ballet', 4, 0).turn).toBeGreaterThan(Math.PI)
    expect(activityPose('hiphop', 6, 0).tilt).toBeGreaterThan(Math.PI)
    expect(activityPose('relay', 2, 0).y).not.toBe(activityPose('relay', 2, 1).y)
    expect(activityPose('stretch', 2, 0).stretch).toBeGreaterThan(1.05)
    expect(activityPose('twirl', 4, 0).x).not.toBe(0)
  })
  it('所有活动最后两秒逐渐收势，位移与形变保持有界', () => {
    for (const { id } of companionActivities) for (let index = 0; index < 4; index++) {
      expect(activityPose(id, 14.9, index).strength).toBeLessThan(0.01)
      for (let t = 0; t < 15; t += 0.05) {
        const pose = activityPose(id, t, index)
        expect(Math.abs(pose.x)).toBeLessThanOrEqual(0.5)
        expect(pose.y).toBeLessThanOrEqual(1.5)
        expect(pose.stretch).toBeGreaterThan(0.8)
      }
    }
  })
})
