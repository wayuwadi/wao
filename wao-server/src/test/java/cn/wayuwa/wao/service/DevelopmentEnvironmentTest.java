package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentEnvironmentTest {
    @TempDir Path root;
    Path repository, state, work;
    final List<List<String>> executed = new ArrayList<>();
    String git(Path at, String... arguments) throws Exception {
        var args = new ArrayList<String>(); args.add("git"); args.addAll(List.of(arguments));
        return DevelopmentWorker.command(at, args).trim();
    }
    DevelopmentEnvironment fixture() throws Exception {
        repository = Files.createDirectories(root.resolve("fixed repository"));
        state = Files.createDirectories(root.resolve("private state"));
        Files.createDirectories(repository.resolve("wao-web/node_modules"));
        Files.writeString(repository.resolve("wao-web/node_modules/preserved.txt"), "existing dependency");
        Files.writeString(repository.resolve("wao-web/package.json"), "{\"name\":\"fixture\"}");
        Files.writeString(repository.resolve("wao-web/package-lock.json"), "{\"lockfileVersion\":3}");
        Files.writeString(repository.resolve(".gitignore"), "node_modules/\n");
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "Environment Fixture");
        git(repository, "config", "user.email", "fixture@example.invalid");
        git(repository, "add", "."); git(repository, "commit", "-m", "baseline");
        work = state.resolve("run/worktree");
        git(state, "clone", repository.toString(), work.toString());
        git(work, "config", "user.name", "Environment Fixture");
        git(work, "config", "user.email", "fixture@example.invalid");
        return new DevelopmentEnvironment(repository, state, true, (at, args) -> {
            executed.add(List.copyOf(args)); return DevelopmentEnvironment.command(at, args);
        });
    }
    @Test void eitherManifestMismatchRefusesDependencyReuseWithoutMutation() throws Exception {
        var environment = fixture();
        Files.writeString(work.resolve("wao-web/package-lock.json"), "different lock");
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("manifest");
        Files.copy(repository.resolve("wao-web/package-lock.json"), work.resolve("wao-web/package-lock.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(work.resolve("wao-web/package.json"), "different package");
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("manifest");
        assertThat(executed).isEmpty();
        assertThat(Files.exists(work.resolve("wao-web/node_modules"))).isFalse();
        assertThat(Files.readString(repository.resolve("wao-web/node_modules/preserved.txt"))).isEqualTo("existing dependency");
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void ignoredJunctionRetryIsIdempotentAndDoesNotTouchSourceDependencies() throws Exception {
        var environment = fixture();
        environment.prepareDependencies(work);
        environment.prepareDependencies(work);
        assertThat(work.resolve("wao-web/node_modules").toRealPath()).isEqualTo(repository.resolve("wao-web/node_modules").toRealPath());
        assertThat(executed.stream().filter(args -> args.get(0).equals("cmd"))).hasSize(1);
        assertThat(git(work, "status", "--porcelain", "--untracked-files=all")).isEmpty();
        assertThat(Files.readString(repository.resolve("wao-web/node_modules/preserved.txt"))).isEqualTo("existing dependency");
    }
    @Test void existingUnrelatedDependenciesArePreservedAndRefused() throws Exception {
        var environment = fixture();
        Path existing = Files.createDirectories(work.resolve("wao-web/node_modules"));
        Files.writeString(existing.resolve("keep.txt"), "do not replace");
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("已保留原目录");
        assertThat(Files.readString(existing.resolve("keep.txt"))).isEqualTo("do not replace");
        assertThat(executed.stream().noneMatch(args -> args.get(0).equals("cmd"))).isTrue();
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void gameDependenciesUseTheirOwnManifestsAndPreserveWebJunctionOnRetry() throws Exception {
        var environment = fixture();
        Files.createDirectories(repository.resolve("wao-game/node_modules"));
        Files.writeString(repository.resolve("wao-game/package.json"), "game package");
        Files.writeString(repository.resolve("wao-game/package-lock.json"), "game lock");
        Files.createDirectories(work.resolve("wao-game"));
        Files.writeString(work.resolve("wao-game/package.json"), "game package");
        Files.writeString(work.resolve("wao-game/package-lock.json"), "mismatched game lock");
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("wao-game/package-lock.json");
        assertThat(Files.isDirectory(work.resolve("wao-web/node_modules"))).isTrue();
        assertThat(Files.exists(work.resolve("wao-game/node_modules"))).isFalse();
        Files.writeString(work.resolve("wao-game/package-lock.json"), "game lock");
        environment.prepareDependencies(work);
        assertThat(work.resolve("wao-game/node_modules").toRealPath()).isEqualTo(repository.resolve("wao-game/node_modules").toRealPath());
        assertThat(executed.stream().filter(args -> args.get(0).equals("cmd"))).hasSize(2);
    }
    @Test void dependenciesMustBeIgnoredAndSourceMustExist() throws Exception {
        var environment = fixture();
        Files.writeString(work.resolve(".gitignore"), "");
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("失败");
        assertThat(executed.stream().noneMatch(args -> args.get(0).equals("cmd"))).isTrue();
        Files.move(repository.resolve("wao-web/node_modules"), repository.resolve("wao-web/unavailable"));
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("缺少");
    }
    @Test void contextEnforcesDirectoryByteLimitAndStrictUtf8() throws Exception {
        var environment = fixture();
        Path context = state.resolve("validation.txt");
        Files.writeString(context, "已有回环只读验收通道");
        assertThat(environment.validationContext("validation.txt")).isEqualTo("已有回环只读验收通道");
        Path outside = root.resolve("outside.txt"); Files.writeString(outside, "outside");
        assertThatThrownBy(() -> environment.validationContext(outside.toString())).hasMessageContaining("专用状态目录");
        assertThatThrownBy(() -> environment.validationContext("../outside.txt")).hasMessageContaining("专用状态目录");
        assertThatThrownBy(() -> environment.validationContext(state.toString())).hasMessageContaining("普通文件");
        Files.write(context, new byte[DevelopmentEnvironment.MAX_CONTEXT_BYTES + 1]);
        assertThatThrownBy(() -> environment.validationContext(context.toString())).hasMessageContaining("20KB");
        Files.writeString(context, "a".repeat(DevelopmentEnvironment.MAX_CONTEXT_BYTES));
        assertThat(environment.validationContext(context.toString())).hasSize(DevelopmentEnvironment.MAX_CONTEXT_BYTES);
        Files.write(context, new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> environment.validationContext(context.toString())).hasMessageContaining("UTF-8");
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void junctionCannotEscapeOutputOrContextBoundary() throws Exception {
        var environment = fixture();
        Path outside = Files.createDirectories(root.resolve("outside directory"));
        Files.writeString(outside.resolve("context.txt"), "outside");
        Path junction = state.resolve("escaped");
        DevelopmentEnvironment.command(state, List.of("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString()));
        assertThatThrownBy(() -> environment.validationContext(junction.resolve("context.txt").toString())).hasMessageContaining("专用状态目录");
        assertThatThrownBy(() -> environment.outputDirectory(junction.resolve("output"))).hasMessageContaining("超出");
        assertThatThrownBy(() -> environment.prepareDependencies(junction)).hasMessageContaining("超出");
        assertThat(Files.exists(outside.resolve("output"))).isFalse();
    }
    @Test void cmdMetaCharactersCannotTurnDependencyPathsIntoCommands() throws Exception {
        fixture();
        Path movedState = state.resolveSibling("private & state");
        Files.move(state, movedState);
        state = movedState; work = state.resolve("run/worktree");
        var environment = new DevelopmentEnvironment(repository, state, true, (at, args) -> {
            executed.add(List.copyOf(args)); return DevelopmentEnvironment.command(at, args);
        });
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("命令字符");
        assertThat(executed.stream().noneMatch(args -> args.get(0).equals("cmd"))).isTrue();
        assertThat(Files.exists(work.resolve("wao-web/node_modules"))).isFalse();
    }
    @Test void outputIsDedicatedAndCannotCreateOutsideState() throws Exception {
        var environment = fixture();
        assertThat(environment.outputDirectory(state.resolve("run/verification"))).isEqualTo(state.resolve("run/verification").toRealPath());
        assertThatThrownBy(() -> environment.outputDirectory(repository.resolve("output"))).hasMessageContaining("专用状态目录");
        assertThat(Files.exists(repository.resolve("output"))).isFalse();
    }
    Path isolatedSource() throws Exception {
        Path source = state.resolve("dependencies-repository");
        git(state, "clone", repository.toString(), source.toString());
        Files.createDirectories(source.resolve("wao-web/node_modules"));
        git(source, "remote", "set-url", "origin", "https://github.com/wayuwacn/wao.git");
        return source;
    }
    @Test void configuredDependencySourceRejectsOutsideCheckoutAndForeignRemote() throws Exception {
        fixture();
        var outside = new DevelopmentEnvironment(repository, state, repository, true, DevelopmentEnvironment::command);
        assertThatThrownBy(() -> outside.prepareDependencies(work)).hasMessageContaining("state");
        Path source = isolatedSource();
        git(source, "remote", "set-url", "origin", "https://example.invalid/other.git");
        var foreign = new DevelopmentEnvironment(repository, state, source, true, DevelopmentEnvironment::command);
        assertThatThrownBy(() -> foreign.prepareDependencies(work)).hasMessageContaining("WAO origin");
        assertThat(Files.exists(work.resolve("wao-web/node_modules"))).isFalse();
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void independentSourceLeavesDirtyPrimaryUntouchedAndUsesItsOwnMatchingDependencies() throws Exception {
        fixture(); Path source = isolatedSource();
        Files.writeString(repository.resolve("wao-web/package.json"), "private branch manifest");
        var environment = new DevelopmentEnvironment(repository, state, source, true, DevelopmentEnvironment::command);
        environment.prepareDependencies(work);
        assertThat(work.resolve("wao-web/node_modules").toRealPath()).isEqualTo(source.resolve("wao-web/node_modules").toRealPath());
        assertThat(Files.readString(repository.resolve("wao-web/package.json"))).isEqualTo("private branch manifest");
        assertThat(Files.readString(repository.resolve("wao-web/node_modules/preserved.txt"))).isEqualTo("existing dependency");
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void configuredSourceAliasCannotEscapeState() throws Exception {
        fixture();
        Path alias = state.resolve("dependencies-alias");
        DevelopmentEnvironment.command(state, List.of("cmd", "/c", "mklink", "/J", alias.toString(), repository.toString()));
        var environment = new DevelopmentEnvironment(repository, state, alias, true, DevelopmentEnvironment::command);
        assertThatThrownBy(() -> environment.prepareDependencies(work)).hasMessageContaining("普通 Git checkout");
        assertThat(Files.exists(work.resolve("wao-web/node_modules"))).isFalse();
    }
    @Test void dirtyOrUnfinishedRebaseBlocksBeforeFetchAndPreservesChanges() throws Exception {
        var environment = fixture();
        Files.writeString(work.resolve("wao-web/package.json"), "uncommitted");
        assertThatThrownBy(() -> environment.synchronizeForVerification(work)).hasMessageContaining("未汇聚改动");
        assertThat(Files.readString(work.resolve("wao-web/package.json"))).isEqualTo("uncommitted");
        assertThat(executed.stream().noneMatch(args -> args.contains("fetch"))).isTrue();
        git(work, "restore", "wao-web/package.json");
        Files.createDirectories(work.resolve(".git/rebase-merge"));
        assertThatThrownBy(() -> environment.synchronizeForVerification(work)).hasMessageContaining("未完成");
        assertThat(Files.isDirectory(work.resolve(".git/rebase-merge"))).isTrue();
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void verificationRebasesAggregatedCommitsBeforeFreezingTestedHead() throws Exception {
        var environment = fixture();
        Files.writeString(work.resolve("local.txt"), "agent change"); git(work, "add", "."); git(work, "commit", "-m", "agent");
        String previous = git(work, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("upstream.txt"), "upstream"); git(repository, "add", "."); git(repository, "commit", "-m", "upstream");
        String upstream = git(repository, "rev-parse", "HEAD");
        String tested = environment.synchronizeForVerification(work);
        assertThat(tested).isNotEqualTo(previous).isEqualTo(git(work, "rev-parse", "HEAD"));
        git(work, "merge-base", "--is-ancestor", upstream, tested);
        assertThat(Files.readString(work.resolve("local.txt"))).isEqualTo("agent change");
        assertThat(Files.readString(work.resolve("upstream.txt"))).isEqualTo("upstream");
    }
    @Test void conflictingRebaseRetainsWorkAndCannotFreezeUntestedCommit() throws Exception {
        var environment = fixture();
        Files.writeString(work.resolve("wao-web/package.json"), "local\n"); git(work, "add", "."); git(work, "commit", "-m", "local");
        Files.writeString(repository.resolve("wao-web/package.json"), "upstream\n"); git(repository, "add", "."); git(repository, "commit", "-m", "upstream");
        assertThatThrownBy(() -> environment.synchronizeForVerification(work)).hasMessageContaining("失败");
        assertThat(Files.isDirectory(work.resolve(".git/rebase-merge"))).isTrue();
        assertThat(Files.readString(work.resolve("wao-web/package.json"))).contains("<<<<<<<", "local", "upstream");
        assertThatThrownBy(() -> environment.synchronizeForVerification(work)).hasMessageContaining("未完成");
    }
}
