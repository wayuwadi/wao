import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { exportTaskSelection, printTaskMarkdown } from '@/utils/taskSelectionExport'

beforeEach(() => { setActivePinia(createPinia()); seedDataStore() })
const context = { origin:'https://example.com', projectName:()=>'测试清单', memberName:(id:string)=>id }
describe('选中任务的导出范围', () => {
  it('主任务导出其独立后代和内嵌评论，不混入其他分支', () => {
    const data=useDataStore(), base=data.tasks[0]!
    data.tasks=[
      {...base,id:'root',title:'父任务',parentTaskId:null,subs:[{id:'sub',text:'内嵌项',done:false,comments:[{who:'me',time:'今天',text:'内嵌评论'}]}],comments:[]},
      {...base,id:'child',title:'独立后代',parentTaskId:'root',subs:[],comments:[]},
      {...base,id:'other',title:'无关任务',parentTaskId:null,subs:[],comments:[]},
    ]
    const output=exportTaskSelection(data.tasks,'root',undefined,context).markdown
    expect(output).toContain('父任务'); expect(output).toContain('独立后代'); expect(output).toContain('内嵌评论')
    expect(output).not.toContain('无关任务')
  })
  it('子任务导出只含自身与后代，链接定位到真实子任务', () => {
    const data=useDataStore(), base=data.tasks[0]!
    data.tasks=[{...base,id:'root',title:'父任务秘密',desc:'父正文秘密',comments:[{who:'me',time:'今天',text:'父评论秘密'}],subs:[
      {id:'sub',text:'选中子项',done:false,comments:[{who:'me',time:'今天',text:'子项评论'}]},
      {id:'nested',parentId:'sub',text:'下级子项',done:false},
      {id:'sibling',text:'同级秘密',done:false},
    ]}]
    const output=exportTaskSelection(data.tasks,'root','sub',context).markdown
    expect(output).toContain('选中子项'); expect(output).toContain('下级子项'); expect(output).toContain('子项评论')
    expect(output).toContain('/api/share/tasks/root/subs/sub.md')
    expect(output).not.toContain('父任务秘密'); expect(output).not.toContain('父正文秘密'); expect(output).not.toContain('父评论秘密'); expect(output).not.toContain('同级秘密')
  })
  it('打印按纯文本处理任务内容，不执行其中的 HTML', () => {
    vi.useFakeTimers()
    let frame: HTMLIFrameElement | undefined
    const append = document.body.appendChild.bind(document.body)
    const spy = vi.spyOn(document.body, 'appendChild').mockImplementation(node => {
      const result = append(node)
      if (node instanceof HTMLIFrameElement) {
        frame = node
        vi.spyOn(node.contentWindow!, 'focus').mockImplementation(() => {})
        vi.spyOn(node.contentWindow!, 'print').mockImplementation(() => {})
      }
      return result
    })
    try {
      printTaskMarkdown('测试', '<img src=x onerror=alert(1)>')
      expect(frame!.contentDocument!.querySelector('img')).toBeNull()
      expect(frame!.contentDocument!.querySelector('pre')!.textContent).toBe('<img src=x onerror=alert(1)>')
      expect(frame!.contentWindow!.print).toHaveBeenCalledOnce()
      frame!.contentWindow!.dispatchEvent(new Event('afterprint'))
      expect(frame!.isConnected).toBe(false)
    } finally { spy.mockRestore(); frame?.remove(); vi.useRealTimers() }
  })
})
