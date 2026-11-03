package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.*;
import java.util.zip.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 菜单操作和回执同事务提交；正文由服务器快照复制，不接收客户端复制内容。 */
@Service
public class TaskMenuService {
    public record Request(String action, String subId, Map<String,String> versions) {}
    public record Result(String selectedId, List<TaskDto> tasks) {}
    private final TaskService tasks;
    private final TaskMapper mapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TaskMenuService(TaskService tasks, TaskMapper mapper, JdbcTemplate jdbc, ObjectMapper json) {
        this.tasks=tasks; this.mapper=mapper; this.jdbc=jdbc; this.json=json;
    }

    @Transactional
    public Result apply(String account, String member, String id, String operation, Request request) {
        if(account==null || member==null) throw new UnauthorizedException("请先登录");
        if(operation==null || !operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            || request==null || request.action()==null || !Set.of("abandon","restore","duplicate","note","task").contains(request.action()))
            throw new BadRequestException("无效的任务操作");
        TaskDto initial=tasks.get(id,member);
        String hash=hash(request);
        jdbc.update("INSERT INTO wao_task_write_operation(account_id,operation_id,operation_type,task_id,request_hash) VALUES(?,?,'TASK_MENU',?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id",account,operation,id,hash);
        var receipt=jdbc.queryForMap("SELECT operation_type,task_id,request_hash,result_gzip FROM wao_task_write_operation WHERE account_id=? AND operation_id=? FOR UPDATE",account,operation);
        if(!"TASK_MENU".equals(receipt.get("operation_type")) || !id.equals(receipt.get("task_id")) || !hash.equals(receipt.get("request_hash"))) throw new ConflictException("请先确认原操作结果，不能更换重试内容");
        if(receipt.get("result_gzip") instanceof byte[] bytes) {
            Result saved=decode(bytes);
            for(TaskDto t:saved.tasks()) tasks.get(t.id(),member);
            return saved;
        }
        // 与层级调整使用相同锁顺序，复制期间不能插入或移走后代。
        mapper.lockProjectTasks(initial.project());
        TaskDto source=tasks.get(id,member);
        if(!Objects.equals(initial.project(),source.project())) throw new ConflictException("任务清单已变化，请刷新后重试");
        boolean embedded=request.subId()!=null && !request.subId().isBlank();
        List<TaskDto> affected=new ArrayList<>(); affected.add(source);
        if(!embedded) {
            var visible=tasks.list(member); Set<String> ids=new HashSet<>(Set.of(id));
            boolean changed;
            do { changed=false; for(var t:visible) if(t.parentTaskId()!=null && ids.contains(t.parentTaskId()) && ids.add(t.id())) {affected.add(tasks.get(t.id(),member));changed=true;} } while(changed);
        }
        for(var t:affected) if(request.versions()==null || !Objects.equals(t.editVersion(),request.versions().get(t.id()))) throw new ConflictException("任务或子任务已更新，请刷新并核对后重试");
        List<TaskDto> saved=new ArrayList<>(); String selected=id;
        if(embedded) {
            ObjectNode node=json.valueToTree(source); ArrayNode subs=(ArrayNode)node.get("subs");
            Map<String,ObjectNode> byId=steps(subs,id);
            ObjectNode item=byId.get(request.subId());
            if(item==null) throw new NotFoundException("子任务不存在");
            Set<String> branch=branch(byId,request.subId());
            if(request.action().equals("duplicate")) {
                Map<String,String> mapping=new HashMap<>();branch.forEach(key->mapping.put(key,newId(operation,key)));
                for(String key:branch) {
                    ObjectNode copy=byId.get(key).deepCopy(); String newSub=mapping.get(key);
                    resetStep(copy,newSub,member);
                    if(!key.equals(request.subId())) copy.put("parentId",mapping.get(copy.path("parentId").asText()));
                    subs.add(copy); copyLabels(id,key,id,newSub);
                }
                selected="sub:"+id+":"+mapping.get(request.subId());
            } else {
                if(Set.of("note","task").contains(request.action())) {
                    if(branch.size()>1) throw new BadRequestException("带有子任务的任务不能转换为笔记，请先调整子任务");
                    convert(item,request.action(),true);
                } else for(String key:branch) state(byId.get(key),request.action(),true);
            }
            saved.add(tasks.replace(id,json.convertValue(node,TaskDto.class),member));
        } else if(request.action().equals("duplicate")) {
            Map<String,String> mapping=new HashMap<>();affected.forEach(t->mapping.put(t.id(),newId(operation,t.id())));
            for(var t:affected) {
                ObjectNode copy=json.valueToTree(t);String newTask=mapping.get(t.id());
                copy.put("id",newTask);copy.remove(List.of("number","editVersion","subtaskVersion","createdAt","completedAt","parentTaskId"));
                copy.put("createdBy",member);copy.put("status","待办");copy.set("comments",json.createArrayNode());copy.set("activity",json.createArrayNode());copy.set("pdcaCycles",json.createArrayNode());
                var subs=(ArrayNode)copy.get("subs");var byId=steps(subs,t.id()); Map<String,String> subIds=new HashMap<>();byId.keySet().forEach(key->subIds.put(key,newId(operation,t.id()+":"+key)));
                byId.forEach((key,sub)->{String parent=sub.path("parentId").asText();resetStep(sub,subIds.get(key),member);if(subIds.containsKey(parent))sub.put("parentId",subIds.get(parent));});
                tasks.replace(newTask,json.convertValue(copy,TaskDto.class),member);
                subIds.forEach((key,newSub)->copyLabels(t.id(),key,newTask,newSub));
                mapper.updateParent(newTask,t.id().equals(id)?source.parentTaskId():mapping.get(t.parentTaskId()));
                copyLabels(t.id(),"",newTask,"");saved.add(tasks.get(newTask,member));
            }
            selected=mapping.get(id);
        } else {
            if(Set.of("note","task").contains(request.action()) && (affected.size()>1 || !source.subs().isEmpty())) throw new BadRequestException("带有子任务的任务不能转换为笔记，请先调整子任务");
            for(var t:affected) {
                ObjectNode node=json.valueToTree(t);
                if(Set.of("note","task").contains(request.action())) convert(node,request.action(),false);
                else {state(node,request.action(),false);for(var sub:node.withArray("subs"))state((ObjectNode)sub,request.action(),true);}
                saved.add(tasks.replace(t.id(),json.convertValue(node,TaskDto.class),member));
            }
        }
        Result result=new Result(selected,saved);
        jdbc.update("UPDATE wao_task_write_operation SET result_gzip=? WHERE account_id=? AND operation_id=?",encode(result),account,operation);
        return result;
    }
    static void state(ObjectNode node,String action,boolean sub) {
        if("abandon".equals(action)) {if(node.path("done").asBoolean() || "已完成".equals(node.path("status").asText()) || "note".equals(node.path("workItemType").asText()))return;if(sub)node.put("done",false);node.put("status","已放弃");}
        else if("已放弃".equals(node.path("status").asText())) {if(sub)node.putNull("status");else node.put("status","待办");}
    }
    static void convert(ObjectNode node,String action,boolean sub) {
        if("已放弃".equals(node.path("status").asText())) throw new BadRequestException("请先重新开始，再转换为笔记");
        node.put("workItemType",action.equals("note")?"note":"task");
        if(sub){node.put("done",false);node.putNull("status");}else node.put("status","待办");
        if("note".equals(action)){node.put("due","");node.put("start","");node.putNull("startAt");node.putNull("endAt");node.putNull("schedulePeriod");}
    }
    private void resetStep(ObjectNode node,String id,String member) {
        node.put("id",id);node.remove("number");node.put("done",false);node.putNull("status");node.put("createdBy",member);node.put("createdAt",java.time.OffsetDateTime.now().toString());node.set("comments",json.createArrayNode());
    }
    static Map<String,ObjectNode> steps(ArrayNode nodes,String task) {
        Map<String,ObjectNode> result=new LinkedHashMap<>();for(int i=0;i<nodes.size();i++){var n=(ObjectNode)nodes.get(i);String key=n.path("id").asText();if(key.isBlank())key=task+"#"+i;result.put(key,n);}return result;
    }
    static Set<String> branch(Map<String,ObjectNode> nodes,String root) {
        Set<String> ids=new LinkedHashSet<>(Set.of(root));boolean changed;do{changed=false;for(var e:nodes.entrySet())if(ids.contains(e.getValue().path("parentId").asText()) && ids.add(e.getKey()))changed=true;}while(changed);return ids;
    }
    private void copyLabels(String from,String sub,String to,String targetSub) {jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) SELECT ?,?,1,labels_json FROM task_labels WHERE task_id=? AND sub_id=?",to,targetSub,from,sub);}
    private static String newId(String operation,String id){return UUID.nameUUIDFromBytes((operation+":"+id).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");}
    private String hash(Request request){try{var normalized=new Request(request.action(),request.subId(),request.versions()==null?null:new TreeMap<>(request.versions()));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(normalized)));}catch(Exception e){throw new IllegalStateException(e);}}
    private byte[] encode(Result result){try{byte[] raw=json.writeValueAsBytes(result);if(raw.length>4*1024*1024)throw new BadRequestException("任务副本过大，请按子任务分批创建");var out=new ByteArrayOutputStream();try(var zip=new GZIPOutputStream(out)){zip.write(raw);}return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    private Result decode(byte[] bytes){try(var zip=new GZIPInputStream(new ByteArrayInputStream(bytes))){return json.readValue(zip.readNBytes(4*1024*1024+1),Result.class);}catch(IOException e){throw new IllegalStateException(e);}}
}
