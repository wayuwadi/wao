/** 活动时间线的文案组装：主语按「看的人是谁」现算。 */

import type { Activity } from '@/types'

/**
 * 把一条活动渲染成完整句子。
 *
 * <p>活动正文存的是不带主语的动作（「创建了任务」），主语由这里补：自己干的写「你」，
 * 别人干的写对方的名字。以前正文里就写死了「你创建了任务」，结果娃哥打开娃弟建的任务，
 * 时间线上也是「你创建了任务」——每条记录都在冒充当前这个人。
 *
 * <p>历史数据没有 `by`，正文里已经带着「你」，这时原样返回，不去猜是谁干的。
 *
 * @param activity 活动条目
 * @param meId     当前登录成员 id
 * @param nameOf   成员 id → 名字
 * @return 用于展示的整句
 */
export function activitySentence(
  activity: Pick<Activity, 'text' | 'by'>,
  meId: string,
  nameOf: (id: string) => string,
): string {
  const by = activity.by
  if (!by) return activity.text
  return `${by === meId ? '你' : nameOf(by)}${activity.text}`
}
