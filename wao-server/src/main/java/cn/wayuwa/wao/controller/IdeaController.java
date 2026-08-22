package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.service.IdeaResearchService;
import cn.wayuwa.wao.service.IdeaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 团队共享点子库接口；第一版不提供公开分享端点。 */
@RestController
@RequestMapping("/api/ideas")
public class IdeaController {
    private final IdeaService ideas; private final IdeaResearchService research; private final CurrentMember current;
    public IdeaController(IdeaService ideas, IdeaResearchService research, CurrentMember current){this.ideas=ideas;this.research=research;this.current=current;}
    @GetMapping public ApiResponse<List<IdeaDto>> list(){return ApiResponse.ok(ideas.list());}
    @PostMapping public ApiResponse<IdeaDto> create(@Valid @RequestBody IdeaDto.CreateRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.create(req,current.idOr(h,"wg")));}
    @GetMapping("/{id}") public ApiResponse<IdeaDto> get(@PathVariable String id){return ApiResponse.ok(ideas.get(id));}
    @PatchMapping("/{id}") public ApiResponse<IdeaDto> patch(@PathVariable String id,@RequestBody IdeaDto.PatchRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.patch(id,req,current.idOr(h,"wg")));}
    @PostMapping("/{id}/transition") public ApiResponse<IdeaDto> transition(@PathVariable String id,@Valid @RequestBody IdeaDto.TransitionRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.transition(id,req,current.idOr(h,"wg")));}
    @PostMapping("/{id}/research-acceptance") public ApiResponse<IdeaDto> accept(@PathVariable String id,HttpServletRequest h){return ApiResponse.ok(ideas.acceptResearch(id,current.idOr(h,"wg")));}
    @GetMapping("/{id}/research-runs") public ApiResponse<List<IdeaDto.ResearchRun>> researchRuns(@PathVariable String id){return ApiResponse.ok(research.list(id));}
    @PostMapping("/{id}/research-runs") public ApiResponse<IdeaDto.ResearchRun> research(@PathVariable String id,HttpServletRequest h){return ApiResponse.ok(research.enqueue(id,current.idOr(h,"wg")));}
    @GetMapping("/{id}/artifacts") public ApiResponse<List<IdeaDto.Artifact>> artifacts(@PathVariable String id){return ApiResponse.ok(ideas.listArtifacts(id));}
    @PostMapping("/{id}/artifacts") public ApiResponse<IdeaDto.Artifact> artifact(@PathVariable String id,@Valid @RequestBody IdeaDto.ArtifactRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.addArtifact(id,req,current.idOr(h,"wg")));}
    @PostMapping("/{id}/tasks") public ApiResponse<IdeaDto.TaskLink> task(@PathVariable String id,@RequestBody IdeaDto.TaskRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.addTask(id,req,current.idOr(h,"wg")));}
    @PutMapping("/{id}/tasks/{taskId}") public ApiResponse<IdeaDto.TaskLink> link(@PathVariable String id,@PathVariable String taskId,HttpServletRequest h){return ApiResponse.ok(ideas.addTask(id,new IdeaDto.TaskRequest(taskId,null,null,null,null,true),current.idOr(h,"wg")));}
    @DeleteMapping("/{id}/tasks/{taskId}") public ApiResponse<Void> unlink(@PathVariable String id,@PathVariable String taskId){ideas.unlinkTask(id,taskId);return ApiResponse.ok(null);}
    @GetMapping("/{id}/iterations") public ApiResponse<List<IdeaDto.Iteration>> iterations(@PathVariable String id){return ApiResponse.ok(ideas.get(id).iterations());}
    @PostMapping("/{id}/iterations") public ApiResponse<IdeaDto.Iteration> iteration(@PathVariable String id,@Valid @RequestBody IdeaDto.IterationRequest req,HttpServletRequest h){return ApiResponse.ok(ideas.createIteration(id,req,current.idOr(h,"wg")));}
    @PatchMapping("/{id}/iterations/{iterationId}") public ApiResponse<IdeaDto.Iteration> patchIteration(@PathVariable String id,@PathVariable String iterationId,@RequestBody IdeaDto.IterationPatchRequest req){return ApiResponse.ok(ideas.patchIteration(id,iterationId,req));}
}
