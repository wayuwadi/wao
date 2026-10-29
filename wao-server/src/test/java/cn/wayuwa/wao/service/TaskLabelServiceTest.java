package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskLabelServiceTest {
 private final TaskService tasks=mock(TaskService.class);
 private final TaskDto task=mock(TaskDto.class);
 private List<TaskLabelService.Labels> stored=List.of();
 private int writes=0;
 private final JdbcTemplate jdbc=mock(JdbcTemplate.class, invocation -> {
  if(invocation.getMethod().getName().equals("query")) return stored;
  if(invocation.getMethod().getName().equals("update")) { writes++; return 1; }
  return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
 });
 private final TaskLabelService service=new TaskLabelService(jdbc,tasks,new ObjectMapper());
 private void visible() { when(tasks.lockWorkspaceTask("t1","member")).thenReturn(task); when(task.id()).thenReturn("t1"); }
 @Test void inaccessibleTaskIsRejectedBeforeAnyLabelReadOrWrite() {
  when(tasks.lockWorkspaceTask("hidden","member")).thenThrow(new NotFoundException("不存在"));
  assertThatThrownBy(()->service.save("hidden",new TaskLabelService.Change("",0,List.of("a")),"member")).isInstanceOf(NotFoundException.class);
  verifyNoInteractions(jdbc);
 }
 @Test void missingSubtaskCannotLeaveAnOrphanAssociation() {
  visible(); when(task.subs()).thenReturn(List.of(new Step("other",false,"s2",null,List.of())));
  assertThatThrownBy(()->service.save("t1",new TaskLabelService.Change("s1",0,List.of("a")),"member")).isInstanceOf(BadRequestException.class);
  verifyNoInteractions(jdbc);
 }
 @Test void newAssociationNormalizesDuplicatesAndAllowsExplicitClear() {
  visible();
  var created=service.save("t1",new TaskLabelService.Change(null,0,List.of(" a ","a","b")),"member");
  assertThat(created.tags()).containsExactly("a","b"); assertThat(created.subId()).isEmpty(); assertThat(created.revision()).isEqualTo(1);
  stored=List.of(created);
  var cleared=service.save("t1",new TaskLabelService.Change("",1,List.of()),"member");
  assertThat(cleared.tags()).isEmpty(); assertThat(cleared.revision()).isEqualTo(2); assertThat(writes).isEqualTo(2);
 }
 @Test void staleRevisionRejectsOverwriteButLostResponseRetryIsIdempotent() {
  visible(); stored=List.of(new TaskLabelService.Labels("t1","",7,List.of("new")));
  assertThatThrownBy(()->service.save("t1",new TaskLabelService.Change("",6,List.of("old")),"member")).isInstanceOf(ConflictException.class);
  assertThat(writes).isZero();
  var retried=service.save("t1",new TaskLabelService.Change("",6,List.of("new")),"member");
  assertThat(retried.revision()).isEqualTo(7); assertThat(writes).isZero();
 }
 @Test void visibleChildUsesItsOwnAssociation() {
  visible(); when(task.subs()).thenReturn(List.of(new Step("child",false,"s1",null,List.of())));
  var result=service.save("t1",new TaskLabelService.Change("s1",0,List.of("child label")),"member");
  assertThat(result.subId()).isEqualTo("s1"); assertThat(result.tags()).containsExactly("child label");
 }
 @Test void listNeverReturnsLabelsFromInvisibleTasks() {
  when(task.id()).thenReturn("t1"); when(tasks.list("member")).thenReturn(List.of(task));
  stored=List.of(new TaskLabelService.Labels("t1","",1,List.of("visible")),new TaskLabelService.Labels("hidden","",1,List.of("secret")));
  assertThat(service.list("member")).extracting(TaskLabelService.Labels::taskId).containsExactly("t1");
 }
 @Test void listExcludesDeletedChildrenButPreservesExistingChildLabels() {
  when(task.id()).thenReturn("t1"); when(tasks.list("member")).thenReturn(List.of(task));
  when(task.subs()).thenReturn(List.of(new Step("current",false,"s1",null,List.of())));
  stored=List.of(new TaskLabelService.Labels("t1","s1",1,List.of("current")),new TaskLabelService.Labels("t1","removed",1,List.of("old")));
  assertThat(service.list("member")).extracting(TaskLabelService.Labels::subId).containsExactly("s1");
 }
 @Test void emptyVisibilityDoesNotReadTheLabelTable() {
  when(tasks.list("member")).thenReturn(List.of()); assertThat(service.list("member")).isEmpty(); verifyNoInteractions(jdbc);
 }
 @Test void invalidNamesAndOversizeCollectionsAreRejected() {
  for(var labels:List.of(List.of(" "),List.of("a\nb"),List.of("x".repeat(41)),Collections.nCopies(21,"a")))
   assertThatThrownBy(()->TaskLabelService.normalize(labels)).isInstanceOf(BadRequestException.class);
  assertThatThrownBy(()->TaskLabelService.normalize(null)).isInstanceOf(BadRequestException.class);
 }
}
