package cn.wayuwa.wao.service;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** Separate revisioned storage protects labels against legacy full-task saves. */
@Service
public class TaskLabelService {
 public record Labels(String taskId, String subId, long revision, List<String> tags) {}
 public record Change(String subId, long revision, List<String> tags) {}
 private final JdbcTemplate jdbc; private final TaskService tasks; private final ObjectMapper json;
 public TaskLabelService(JdbcTemplate jdbc, TaskService tasks, ObjectMapper json) { this.jdbc=jdbc; this.tasks=tasks; this.json=json; }
 public List<Labels> list(String member) {
  var visibleTasks=tasks.list(member);
  var visible=visibleTasks.stream().map(t->t.id()).collect(java.util.stream.Collectors.toSet());
  var valid=new HashSet<String>();
  for(var t:visibleTasks){valid.add(t.id()+"/");if(t.subs()!=null) for(int i=0;i<t.subs().size();i++){var sub=t.subs().get(i);valid.add(t.id()+"/"+(sub.id()==null?t.id()+"#"+i:sub.id()));}}
  if(visible.isEmpty()) return List.of();
  return jdbc.query("SELECT task_id,sub_id,revision,labels_json FROM task_labels", (rs,n)->new Labels(rs.getString(1),rs.getString(2),rs.getLong(3),read(rs.getString(4))))
   .stream().filter(row->valid.contains(row.taskId()+"/"+row.subId())).toList();
 }
 @Transactional public Labels save(String taskId, Change change, String member) {
  var task=tasks.lockWorkspaceTask(taskId,member);
  String sub=change.subId()==null?"":change.subId();
  if(!sub.isEmpty() && (task.subs()==null || java.util.stream.IntStream.range(0,task.subs().size()).noneMatch(i->sub.equals(task.subs().get(i).id()==null?taskId+"#"+i:task.subs().get(i).id())))) throw new BadRequestException("子任务不存在，请刷新任务后重试");
  List<String> labels=normalize(change.tags());
  var previous=jdbc.query("SELECT revision,labels_json FROM task_labels WHERE task_id=? AND sub_id=?",(rs,n)->new Labels(taskId,sub,rs.getLong(1),read(rs.getString(2))),taskId,sub);
  long revision=previous.isEmpty()?0:previous.get(0).revision();
  if(revision!=change.revision()) {
   if(!previous.isEmpty() && previous.get(0).tags().equals(labels)) return previous.get(0);
   throw new ConflictException("标签已被其他窗口修改，请保留草稿并重新读取");
  }
  String encoded;
  try { encoded=json.writeValueAsString(labels); } catch(Exception e) {throw new IllegalStateException(e);}
  jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),labels_json=VALUES(labels_json)",taskId,sub,revision+1,encoded);
  return new Labels(taskId,sub,revision+1,labels);
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public void move(String sourceId,String sourceSub,String targetId,String targetSub) {
  if(sourceId.equals(targetId) && sourceSub.equals(targetSub))return;
  jdbc.update("DELETE FROM task_labels WHERE task_id=? AND sub_id=?",targetId,targetSub);
  jdbc.update("UPDATE task_labels SET task_id=?,sub_id=?,revision=revision+1 WHERE task_id=? AND sub_id=?",targetId,targetSub,sourceId,sourceSub);
 }
 static List<String> normalize(List<String> input) {
  if(input==null || input.size()>20) throw new BadRequestException("每个任务最多设置 20 个标签");
  LinkedHashSet<String> result=new LinkedHashSet<>();
  for(String value:input) {
   if(value==null || value.strip().isEmpty() || value.strip().length()>40 || value.chars().anyMatch(Character::isISOControl)) throw new BadRequestException("标签名称需为 1–40 个字符");
   result.add(value.strip());
  }
  return List.copyOf(result);
 }
 private List<String> read(String raw) {
  try{return json.readValue(raw,new TypeReference<List<String>>(){});} catch(Exception e){throw new IllegalStateException("标签数据无法读取",e);}
 }
}
