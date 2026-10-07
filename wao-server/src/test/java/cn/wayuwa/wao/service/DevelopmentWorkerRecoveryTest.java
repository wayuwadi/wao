package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DevelopmentWorkerRecoveryTest {
    @TempDir Path root;
    private String git(Path at,String... args) throws Exception {
        var command=new java.util.ArrayList<String>();command.add("git");command.addAll(List.of(args));
        return DevelopmentWorker.command(at,command).trim();
    }
    private Path init() throws Exception {
        Path parent=Files.createDirectory(root.resolve("parent"));git(parent,"init");
        git(parent,"config","user.name","Recovery Fixture");git(parent,"config","user.email","fixture@example.invalid");
        Files.createDirectories(parent.resolve("wao-web/src"));Files.writeString(parent.resolve("wao-web/src/example.ts"),"baseline\n");
        git(parent,"add",".");git(parent,"commit","-m","baseline");return parent;
    }
    private Path child(Path parent) throws Exception {
        Path child=root.resolve("child");git(parent,"worktree","add","-b","child",child.toString());return child;
    }
    private List<String> recover(Path parent,Path child,Path base,String path) throws Exception {
        var method=DevelopmentWorker.class.getDeclaredMethod("preservedChild",Path.class,Path.class,Path.class,String.class,JsonNode.class);
        method.setAccessible(true);Object result=method.invoke(null,parent,child,base,"agent",new ObjectMapper().valueToTree(List.of(path)));
        var commits=result.getClass().getDeclaredMethod("commits");commits.setAccessible(true);return ((List<?>)commits.invoke(result)).stream().map(Object::toString).toList();
    }
    @Test void interruptedAggregationReturnsOnlyCommitsNotAlreadyApplied() throws Exception {
        Path parent=init(),child=child(parent),base=root.resolve("base");Files.writeString(base,git(parent,"rev-parse","HEAD"));
        Files.writeString(child.resolve("wao-web/src/example.ts"),"first\n");git(child,"add",".");git(child,"commit","-m","first");String first=git(child,"rev-parse","HEAD");
        Files.writeString(child.resolve("wao-web/src/example.ts"),"second\n");git(child,"add",".");git(child,"commit","-m","second");String second=git(child,"rev-parse","HEAD");
        git(parent,"cherry-pick",first);
        assertThat(recover(parent,child,base,"wao-web/src/example.ts")).containsExactly(second);
        git(parent,"cherry-pick",second);assertThat(recover(parent,child,base,"wao-web/src/example.ts")).isEmpty();
    }
    @Test void preservedCommitCannotBypassFrozenPaths() throws Exception {
        Path parent=init(),child=child(parent),base=root.resolve("base");Files.writeString(base,git(parent,"rev-parse","HEAD"));
        Files.writeString(child.resolve("wao-web/src/outside.ts"),"outside\n");git(child,"add",".");git(child,"commit","-m","outside");
        assertThatThrownBy(()->recover(parent,child,base,"wao-web/src/example.ts")).hasCauseInstanceOf(IllegalStateException.class);
    }
}
