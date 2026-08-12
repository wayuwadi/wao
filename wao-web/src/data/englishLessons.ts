export interface EnglishPhrase {
  term: string
  meaning: string
  example: string
}

export interface EnglishLesson {
  id: string
  stage: string
  title: string
  objective: string
  listenText: string
  transcript: string
  phrases: EnglishPhrase[]
  reading: string
  readingQuestion: string
  speakingPrompts: string[]
  writingPrompt: string
}

/**
 * 面向程序员跨国协作的原创基础课程。课程本身是版本化产品内容，不冒充动态翻译或 AI 评分。
 * 每轮 14 课从“敢开口”推进到“能协作”，完成一轮后带着复盘重新练习，重点是表达更自然。
 */
export const ENGLISH_LESSONS: EnglishLesson[] = [
  {
    id: 'workplace-01', stage: '起步 · 敢开口', title: '用三句话介绍自己',
    objective: '不追求复杂语法，先让同事知道你是谁、做什么、正在关注什么。',
    listenText: "Hi, I'm Wei. I'm a backend engineer based in China. I mainly work with Java, and this week I'm improving our deployment process.",
    transcript: "Hi, I'm Wei. / I'm a backend engineer based in China. / I mainly work with Java, and this week I'm improving our deployment process.",
    phrases: [
      { term: 'I mainly work with…', meaning: '我主要使用……工作', example: 'I mainly work with Java and Spring Boot.' },
      { term: "I'm currently focusing on…", meaning: '我目前专注于……', example: "I'm currently focusing on deployment stability." },
      { term: 'Nice to meet you', meaning: '很高兴认识你', example: 'Nice to meet you. I look forward to working together.' },
    ],
    reading: 'A useful introduction is short and concrete. Your name, role, main skill, and current focus are enough. A simple sentence that you can say confidently is more helpful than a perfect sentence that stays in your head.',
    readingQuestion: '哪四类信息足以组成一段实用的自我介绍？',
    speakingPrompts: ['Say your name and role.', 'Name one technology you use often.', 'Describe your focus this week.'],
    writingPrompt: '写 3—4 句英文自我介绍：姓名或称呼、岗位、常用技术、本周重点。',
  },
  {
    id: 'workplace-02', stage: '起步 · 敢开口', title: '开始一段轻松寒暄',
    objective: '用安全、自然的问题开始会议前的一分钟交流。',
    listenText: "Hey, how's your day going? Mine has been busy, but I finally fixed a tricky issue. Do you have any plans for the weekend?",
    transcript: "Hey, how's your day going? / Mine has been busy, but I finally fixed a tricky issue. / Do you have any plans for the weekend?",
    phrases: [
      { term: "How's your day going?", meaning: '今天过得怎么样？', example: "Hi Sam, how's your day going?" },
      { term: "It's been a busy day", meaning: '今天挺忙的', example: "It's been a busy day, but things are going well." },
      { term: 'Do you have any plans for…?', meaning: '你……有什么安排吗？', example: 'Do you have any plans for the weekend?' },
    ],
    reading: 'Small talk is not a test of humor. Its purpose is to create a comfortable start. Ask one open question, listen to the answer, and share one related detail. Avoid private topics until you know the other person well.',
    readingQuestion: '寒暄的目的是什么？一次自然回应可以由哪两步组成？',
    speakingPrompts: ['Ask how the other person is doing.', 'Share one small detail about your day.', 'Ask a safe follow-up question.'],
    writingPrompt: '写一段 4 句的会议前寒暄，对方说今天很忙，你先回应，再问一个轻松的问题。',
  },
  {
    id: 'workplace-03', stage: '起步 · 敢开口', title: '完成每日站会更新',
    objective: '用“昨天—今天—阻塞”结构说清进度。',
    listenText: "Yesterday I finished the login API. Today I'll add integration tests and update the documentation. I'm blocked by a missing test account, so I need help from the platform team.",
    transcript: "Yesterday I finished the login API. / Today I'll add integration tests and update the documentation. / I'm blocked by a missing test account, so I need help from the platform team.",
    phrases: [
      { term: 'Yesterday I finished…', meaning: '昨天我完成了……', example: 'Yesterday I finished the login API.' },
      { term: "Today I'll…", meaning: '今天我会……', example: "Today I'll add integration tests." },
      { term: "I'm blocked by…", meaning: '我被……阻塞了', example: "I'm blocked by a missing test account." },
    ],
    reading: 'A stand-up update should help the team coordinate. Mention outcomes instead of every action. If you are blocked, name the exact dependency and the person or decision you need. Clear updates make it easier for others to help.',
    readingQuestion: '描述阻塞时，除了问题本身还应说明什么？',
    speakingPrompts: ['Say one thing you completed yesterday.', 'Say today’s concrete outcome.', 'Name a blocker or say there is no blocker.'],
    writingPrompt: '按 Yesterday / Today / Blocker 写一份你明天可以直接说的站会稿。',
  },
  {
    id: 'workplace-04', stage: '协作 · 说清楚', title: '澄清一个模糊需求',
    objective: '不假装听懂，用复述和具体问题确认范围。',
    listenText: "Let me make sure I understand. Do you want the notification to be sent immediately, or only after deployment succeeds? Also, should this apply to every project?",
    transcript: "Let me make sure I understand. / Do you want the notification to be sent immediately, or only after deployment succeeds? / Also, should this apply to every project?",
    phrases: [
      { term: 'Let me make sure I understand', meaning: '让我确认一下是否理解正确', example: 'Let me make sure I understand the expected behavior.' },
      { term: 'Do you mean…?', meaning: '你的意思是……吗？', example: 'Do you mean all users or only admins?' },
      { term: 'Should this apply to…?', meaning: '这是否应该适用于……？', example: 'Should this apply to existing projects?' },
    ],
    reading: 'Clarifying is a professional skill, not a sign of weak English. First paraphrase what you heard. Then ask one question about scope, timing, or expected behavior. End by confirming the next action.',
    readingQuestion: '澄清需求的三步结构是什么？',
    speakingPrompts: ['Paraphrase a feature request.', 'Ask whether it applies to all users.', 'Confirm the next action.'],
    writingPrompt: '假设有人说“Please improve notifications”，写 3 个英文问题澄清触发时机、接收人和失败行为。',
  },
  {
    id: 'workplace-05', stage: '协作 · 说清楚', title: '自然地请求帮助',
    objective: '说明已经尝试过什么、卡在哪里、需要对方做什么。',
    listenText: "Could you help me with the staging database? I've checked the connection settings and the network rules, but I still get a timeout. Could you confirm whether my IP is allowed?",
    transcript: "Could you help me with the staging database? / I've checked the connection settings and the network rules, but I still get a timeout. / Could you confirm whether my IP is allowed?",
    phrases: [
      { term: 'Could you help me with…?', meaning: '你能帮我处理……吗？', example: 'Could you help me with this test environment?' },
      { term: "I've already tried…", meaning: '我已经尝试了……', example: "I've already tried restarting the service." },
      { term: 'Could you confirm whether…?', meaning: '你能确认是否……吗？', example: 'Could you confirm whether the account is active?' },
    ],
    reading: 'A good request for help saves time for both people. Include the goal, the symptom, what you already tried, and the exact help you need. This shows ownership and gives the other person a useful starting point.',
    readingQuestion: '高质量求助应包含哪四项信息？',
    speakingPrompts: ['State the problem in one sentence.', 'Name two things you tried.', 'Ask for one specific check or action.'],
    writingPrompt: '用 4—6 句英文写一条求助消息：测试环境超时，你已检查配置和日志，希望同事确认网络规则。',
  },
  {
    id: 'workplace-06', stage: '协作 · 说清楚', title: '解释一个 Bug',
    objective: '用预期、实际、复现条件描述问题，不把猜测说成事实。',
    listenText: "The expected behavior is to keep the draft after a failed save. Instead, the text disappears when the request times out. I can reproduce it on mobile when the network is slow.",
    transcript: "The expected behavior is to keep the draft after a failed save. / Instead, the text disappears when the request times out. / I can reproduce it on mobile when the network is slow.",
    phrases: [
      { term: 'The expected behavior is…', meaning: '预期行为是……', example: 'The expected behavior is to keep the draft.' },
      { term: 'Instead,…', meaning: '但实际却……', example: 'Instead, the page shows an empty state.' },
      { term: 'I can reproduce it when…', meaning: '在……时我可以复现', example: 'I can reproduce it when the network is slow.' },
    ],
    reading: 'Separate evidence from hypotheses when reporting a bug. Screens, logs, and repeatable steps are evidence. A possible root cause is a hypothesis until it is verified. This wording keeps the discussion accurate.',
    readingQuestion: '为什么要把证据与根因猜测分开表达？',
    speakingPrompts: ['Describe the expected result.', 'Describe the actual result.', 'Give one reproducible condition.'],
    writingPrompt: '选一个你最近见过的 Bug，用 Expected / Actual / Steps / Evidence 写一份简短英文报告。',
  },
  {
    id: 'workplace-07', stage: '复盘 · 形成习惯', title: '第一周复盘',
    objective: '回看真实练习，保留有效方法，只调整一个重点。',
    listenText: "This week I spoke English three times. Short prepared sentences worked well, but I paused too long when answering questions. Next week I'll practice one follow-up question every day.",
    transcript: "This week I spoke English three times. / Short prepared sentences worked well, but I paused too long when answering questions. / Next week I'll practice one follow-up question every day.",
    phrases: [
      { term: 'What worked well was…', meaning: '有效的是……', example: 'What worked well was preparing short sentences.' },
      { term: 'I struggled with…', meaning: '我在……方面有困难', example: 'I struggled with follow-up questions.' },
      { term: "Next week I'll focus on…", meaning: '下周我会重点练……', example: "Next week I'll focus on speaking speed." },
    ],
    reading: 'Progress becomes visible when you review evidence. Count real attempts, notice one thing that became easier, and choose one small adjustment. Changing everything at once makes it hard to know what helped.',
    readingQuestion: '一次有效复盘为什么只选择一个小调整？',
    speakingPrompts: ['Name one real attempt from this week.', 'Say what became easier.', 'Choose one focus for next week.'],
    writingPrompt: '用 What worked / What was hard / Next focus 三段各写 1—2 句英文周复盘。',
  },
  {
    id: 'workplace-08', stage: '推进 · 参与讨论', title: '给出开发时间预估',
    objective: '表达范围、未知项和信心水平，而不是只报一个日期。',
    listenText: "The API change should take about two days if the contract stays the same. The main uncertainty is the data migration. I can give you a firmer estimate after a short investigation.",
    transcript: "The API change should take about two days if the contract stays the same. / The main uncertainty is the data migration. / I can give you a firmer estimate after a short investigation.",
    phrases: [
      { term: 'It should take about…', meaning: '大约需要……', example: 'It should take about two days.' },
      { term: 'The main uncertainty is…', meaning: '主要不确定项是……', example: 'The main uncertainty is the migration.' },
      { term: 'a firmer estimate', meaning: '更可靠的估算', example: 'I can give a firmer estimate tomorrow.' },
    ],
    reading: 'An estimate is a forecast based on assumptions. State the likely range, important dependencies, and what could change it. If the work is unclear, time-box an investigation before committing to a date.',
    readingQuestion: '工作范围不清楚时，应该如何给出更负责任的预估？',
    speakingPrompts: ['Estimate a small change.', 'Name one assumption.', 'Explain when you can update the estimate.'],
    writingPrompt: '为一个接口改动写英文预估：正常两天，依赖契约不变，migration 是未知项，明天更新估算。',
  },
  {
    id: 'workplace-09', stage: '推进 · 参与讨论', title: '礼貌地表达不同意见',
    objective: '先承认对方关注点，再给证据和替代方案。',
    listenText: "I see why a quick patch is attractive. My concern is that it would hide the data issue. Could we fix the validation first and then add a smaller UI safeguard?",
    transcript: "I see why a quick patch is attractive. / My concern is that it would hide the data issue. / Could we fix the validation first and then add a smaller UI safeguard?",
    phrases: [
      { term: 'I see why…', meaning: '我理解为什么……', example: 'I see why this option looks simpler.' },
      { term: 'My concern is that…', meaning: '我担心的是……', example: 'My concern is that it hides the root cause.' },
      { term: 'Could we… instead?', meaning: '我们是否可以改为……？', example: 'Could we validate the input instead?' },
    ],
    reading: 'Disagreement is easier to hear when it focuses on outcomes rather than people. Acknowledge the benefit of the proposal, explain a concrete risk, and offer an alternative. Invite the team to test the assumptions.',
    readingQuestion: '让不同意见更容易被接受的表达顺序是什么？',
    speakingPrompts: ['Acknowledge one benefit.', 'Explain one concrete concern.', 'Offer an alternative as a question.'],
    writingPrompt: '同事建议跳过测试快速上线。写 4 句英文：认可速度、说明风险、提出替代方案、邀请确认。',
  },
  {
    id: 'workplace-10', stage: '推进 · 参与讨论', title: '写清异步进度更新',
    objective: '让不在会议里的人也能快速理解状态和下一步。',
    listenText: "Quick update: the new login flow is now on staging. Automated tests are passing, and I'm doing a mobile check today. If no issue appears, it will be ready for release tomorrow.",
    transcript: "Quick update: the new login flow is now on staging. / Automated tests are passing, and I'm doing a mobile check today. / If no issue appears, it will be ready for release tomorrow.",
    phrases: [
      { term: 'Quick update:', meaning: '快速同步一下：', example: 'Quick update: the fix is now on staging.' },
      { term: 'is now on staging', meaning: '现已部署到测试环境', example: 'The new flow is now on staging.' },
      { term: 'If no issue appears,…', meaning: '如果没有发现问题，……', example: 'If no issue appears, we can release tomorrow.' },
    ],
    reading: 'An async update should answer four questions: what changed, what evidence exists, what happens next, and whether anyone needs to act. Put the outcome first so readers can scan it quickly.',
    readingQuestion: '一条完整的异步进度更新要回答哪四个问题？',
    speakingPrompts: ['Lead with the outcome.', 'Give one piece of evidence.', 'State the next step and timing.'],
    writingPrompt: '用 5 句以内英文写今天真实工作的异步更新，包含结果、证据、下一步、时间和是否需要协助。',
  },
  {
    id: 'workplace-11', stage: '表达 · 建立信心', title: '做一个两分钟功能演示',
    objective: '按问题、操作、结果、价值组织演示，而不是逐个念界面。',
    listenText: "Previously, a failed save could lose the user's draft. Now the text stays on the device and the page offers a retry. This makes mobile editing safer when the network is unstable.",
    transcript: "Previously, a failed save could lose the user's draft. / Now the text stays on the device and the page offers a retry. / This makes mobile editing safer when the network is unstable.",
    phrases: [
      { term: 'Previously,… Now…', meaning: '以前……现在……', example: 'Previously it failed silently. Now it shows a retry.' },
      { term: 'Let me show you…', meaning: '让我演示……', example: 'Let me show you the new save flow.' },
      { term: 'This makes…', meaning: '这让……变得……', example: 'This makes editing safer.' },
    ],
    reading: 'A demo tells a small story. Start with the user problem, show the shortest path through the change, and end with visible evidence and value. Prepare transition sentences so silence does not feel stressful.',
    readingQuestion: '功能演示为什么要从用户问题而不是界面开始？',
    speakingPrompts: ['State the old user problem.', 'Say “Let me show you” and explain one action.', 'End with the user value.'],
    writingPrompt: '选 WAO 的一个真实功能，写一份 90 秒英文演示提纲：Problem / Demo / Evidence / Value。',
  },
  {
    id: 'workplace-12', stage: '表达 · 建立信心', title: '在故障中保持清晰',
    objective: '只报告已知事实、影响、正在做的事和下次更新时间。',
    listenText: "We are seeing elevated errors on the login API. New sessions are affected, but existing sessions still work. We are checking the latest deployment and will share another update in fifteen minutes.",
    transcript: "We are seeing elevated errors on the login API. / New sessions are affected, but existing sessions still work. / We are checking the latest deployment and will share another update in fifteen minutes.",
    phrases: [
      { term: 'We are seeing…', meaning: '我们观察到……', example: 'We are seeing elevated error rates.' },
      { term: 'is affected', meaning: '受到影响', example: 'New sessions are affected.' },
      { term: 'We will share another update in…', meaning: '我们会在……后再次更新', example: 'We will share another update in fifteen minutes.' },
    ],
    reading: 'Incident communication should reduce uncertainty. Say what is known, who or what is affected, what the team is doing, and when the next update will arrive. Do not present an unverified cause as a fact.',
    readingQuestion: '故障通报如何降低他人的不确定感？',
    speakingPrompts: ['State one observed symptom.', 'Describe the confirmed impact.', 'Promise a realistic next update time.'],
    writingPrompt: '写一条英文故障更新：登录错误升高、新会话受影响、正在检查最近发布、15 分钟后更新。',
  },
  {
    id: 'workplace-13', stage: '表达 · 建立信心', title: '把午餐闲聊聊下去',
    objective: '用追问和关联分享维持自然对话，不急着寻找“正确答案”。',
    listenText: "You mentioned that you started hiking recently. What do you enjoy most about it? I haven't tried a long hike yet, but I like walking around the city on weekends.",
    transcript: "You mentioned that you started hiking recently. / What do you enjoy most about it? / I haven't tried a long hike yet, but I like walking around the city on weekends.",
    phrases: [
      { term: 'You mentioned that…', meaning: '你刚才提到……', example: 'You mentioned that you enjoy hiking.' },
      { term: 'What do you enjoy most about…?', meaning: '你最喜欢……的什么？', example: 'What do you enjoy most about the city?' },
      { term: "I haven't tried… yet", meaning: '我还没有尝试过……', example: "I haven't tried a long hike yet." },
    ],
    reading: 'Good conversation is built on attention. Pick one detail from the other person’s answer, ask about it, and then share a related experience. You do not need a large vocabulary to show genuine interest.',
    readingQuestion: '如何用对方回答中的一个细节继续对话？',
    speakingPrompts: ['Refer to something the other person mentioned.', 'Ask one open follow-up question.', 'Share a related experience.'],
    writingPrompt: '写一段 6 轮英文午餐对话，话题是周末爱好；至少使用两次基于细节的追问。',
  },
  {
    id: 'workplace-14', stage: '复盘 · 进入下一轮', title: '完成两周复盘并重启',
    objective: '用真实证据看见进步，选择下一轮要提高的一个维度。',
    listenText: "I completed ten practice sessions and spoke up in two real meetings. I still need time to organize longer answers. In the next cycle, I'll reuse these lessons but answer without reading my notes.",
    transcript: "I completed ten practice sessions and spoke up in two real meetings. / I still need time to organize longer answers. / In the next cycle, I'll reuse these lessons but answer without reading my notes.",
    phrases: [
      { term: 'I have evidence that…', meaning: '我有证据表明……', example: 'I have evidence that I speak more often now.' },
      { term: 'I still need to improve…', meaning: '我仍需提高……', example: 'I still need to improve longer answers.' },
      { term: 'In the next cycle,…', meaning: '在下一轮中，……', example: 'In the next cycle, I will speak without notes.' },
    ],
    reading: 'Confidence grows from repeated evidence, not from waiting to feel ready. Compare what you can do now with your first session. Keep useful materials, increase one difficulty, and begin the next cycle without resetting your history.',
    readingQuestion: '为什么下一轮应该增加一个难度，而不是再次从零开始？',
    speakingPrompts: ['Name your completed sessions or real conversations.', 'Describe one improvement with evidence.', 'Choose one harder rule for the next cycle.'],
    writingPrompt: '写一份英文两周复盘：Evidence / Improvement / Remaining difficulty / One rule for the next cycle。',
  },
]

export const lessonById = (id: string) => ENGLISH_LESSONS.find((lesson) => lesson.id === id)
