package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.IdeaArtifactEntity;
import cn.wayuwa.wao.domain.IdeaEntity;
import cn.wayuwa.wao.domain.IdeaResearchRunEntity;
import cn.wayuwa.wao.dto.AiAssistantClaimDto;
import cn.wayuwa.wao.dto.AiRunAccountDto;
import cn.wayuwa.wao.dto.CompleteAiAssistantRunRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.mapper.IdeaArtifactMapper;
import cn.wayuwa.wao.mapper.IdeaMapper;
import cn.wayuwa.wao.mapper.IdeaResearchRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 点子专用联网调研队列；与开发 AI Run、页面助手的权限和结果完全隔离。 */
@Service
public class IdeaResearchService {
    private final IdeaResearchRunMapper runs; private final IdeaMapper ideas; private final IdeaArtifactMapper artifacts;
    private final KnowledgeService knowledge;
    public IdeaResearchService(IdeaResearchRunMapper runs,IdeaMapper ideas,IdeaArtifactMapper artifacts,KnowledgeService knowledge){this.runs=runs;this.ideas=ideas;this.artifacts=artifacts;this.knowledge=knowledge;}

    /** 同一点子同一时刻只跑一份调研；不同点子和成员不设 WIP 限制。 */
    @Transactional
    public IdeaDto.ResearchRun enqueue(String ideaId,String memberId){
        IdeaEntity idea=requireIdea(ideaId);
        Long active=runs.selectCount(new LambdaQueryWrapper<IdeaResearchRunEntity>().eq(IdeaResearchRunEntity::getIdeaId,ideaId).in(IdeaResearchRunEntity::getStatus,List.of("QUEUED","RUNNING")));
        if(active!=null&&active>0)throw new BadRequestException("这个点子已有一份调研正在执行");
        IdeaResearchRunEntity run=new IdeaResearchRunEntity();run.setId(id("ir"));run.setIdeaId(ideaId);run.setMemberId(actor(memberId));run.setStatus("QUEUED");
        run.setPromptText(prompt(idea));LocalDateTime now=now();run.setCreatedAt(now);run.setUpdatedAt(now);runs.insert(run);return dto(run);
    }

    public List<IdeaDto.ResearchRun> list(String ideaId){requireIdea(ideaId);return runs.selectList(new LambdaQueryWrapper<IdeaResearchRunEntity>().eq(IdeaResearchRunEntity::getIdeaId,ideaId).orderByDesc(IdeaResearchRunEntity::getCreatedAt)).stream().map(this::dto).toList();}

    /** 普通页面问答优先领取；其空闲时 Runner 从同一安全协议领取调研。 */
    @Transactional
    public AiAssistantClaimDto claim(String runnerId,AiRunAccountDto account){
        for(IdeaResearchRunEntity candidate:runs.selectList(new LambdaQueryWrapper<IdeaResearchRunEntity>().eq(IdeaResearchRunEntity::getStatus,"QUEUED").orderByAsc(IdeaResearchRunEntity::getCreatedAt).last("LIMIT 8"))){
            String token=UUID.randomUUID().toString().replace("-","");
            if(runs.claim(candidate.getId(),runnerId,token)!=1)continue;
            IdeaResearchRunEntity claimed=runs.selectById(candidate.getId());
            return new AiAssistantClaimDto(claimed.getId(),token,claimed.getPromptText(),3600,account,"IDEA_RESEARCH");
        }
        return null;
    }

    /** 成功时先创建新版本原生文档，再用 CAS 固化结果；失败输出不进入长期资产。 */
    @Transactional
    public void complete(String runnerId,String runId,CompleteAiAssistantRunRequest req){
        IdeaResearchRunEntity run=runs.findForUpdate(runId);if(run==null)throw new NotFoundException("点子调研不存在："+runId);
        if(!runnerId.equals(run.getRunnerId()))throw new UnauthorizedException("该调研不属于当前执行节点");
        if(!"RUNNING".equals(run.getStatus())||!java.util.Objects.equals(req.claimToken(),run.getClaimToken()))
            throw new BadRequestException("调研结果已被其他请求处理");
        boolean ok=req.exitCode()==0&&StringUtils.hasText(req.response());
        if(!ok){String error=StringUtils.hasText(req.error())?req.error():"Codex 调研失败（退出码 "+req.exitCode()+"）";if(runs.completeFailure(runId,runnerId,req.claimToken(),limit(error,2000))!=1)throw new BadRequestException("调研结果已被其他请求处理");return;}
        IdeaEntity idea=requireIdea(run.getIdeaId());Long succeeded=runs.selectCount(new LambdaQueryWrapper<IdeaResearchRunEntity>().eq(IdeaResearchRunEntity::getIdeaId,idea.getId()).eq(IdeaResearchRunEntity::getStatus,"SUCCEEDED"));long version=(succeeded==null?0:succeeded)+1;
        String response=limit(req.response(),30000);DocDto doc=knowledge.createIdeaDocument(idea.getId(),idea.getTitle(),"调研报告 v"+version,response,run.getMemberId());
        if(runs.completeSuccess(runId,runnerId,req.claimToken(),response,doc.id())!=1)throw new BadRequestException("调研结果已被其他请求处理");
        IdeaArtifactEntity a=new IdeaArtifactEntity();a.setId(id("ia"));a.setIdeaId(idea.getId());a.setKind("RESEARCH_REPORT");a.setDocId(doc.id());a.setTitle(doc.title());a.setCreatedBy(run.getMemberId());a.setCreatedAt(now());artifacts.insert(a);
        idea.setUpdatedAt(now());ideas.updateById(idea);
    }

    /** Runner 异常消失或报告写入持续失败时，也会清空敏感提示并允许成员重新调研。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    @Transactional
    public void expireStale(){runs.expireStale();}

    public boolean exists(String runId){return runs.selectById(runId)!=null;}
    private IdeaEntity requireIdea(String id){IdeaEntity e=ideas.selectById(id);if(e==null)throw new NotFoundException("点子不存在："+id);return e;}
    private IdeaDto.ResearchRun dto(IdeaResearchRunEntity e){return new IdeaDto.ResearchRun(e.getId(),e.getStatus(),e.getErrorText(),e.getDocId(),e.getMemberId(),text(e.getCreatedAt()),text(e.getFinishedAt()));}
    private static String prompt(IdeaEntity i){return """
            你正在执行 WAO 点子库的“看明白”调研。只做联网研究，不修改本机文件、不运行命令、不访问仓库、不提交代码。
            请搜索并筛选 3 到 5 个与点子最相关、来源可核验的真实案例；如果可靠案例不足，明确写出证据不足，绝不虚构。

            点子标题：%s
            原始想法：%s
            真实问题：%s
            目标结果：%s

            输出中文 Markdown，必须包含：调研范围、案例对比（每项含直接来源 URL、用户问题、解决方式、商业模式、技术路径）、共同规律、机会与差异、技术可行性、主要风险、是否建议进入 MVP 及理由。不要输出任何 WAO_RUN_STATUS 标记。
            """.formatted(i.getTitle(),nz(i.getRawIdea()),nz(i.getProblemText()),nz(i.getTargetOutcome()));}
    private static String actor(String v){return StringUtils.hasText(v)?v:"unknown";} private static String nz(String v){return v==null?"":v;}
    private static String id(String p){return p+UUID.randomUUID().toString().replace("-","").substring(0,24);} private static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);} private static String text(LocalDateTime v){return v==null?null:v.toString();}
    private static String limit(String v,int max){if(v==null)return null;return v.length()<=max?v:v.substring(0,max);}
}
