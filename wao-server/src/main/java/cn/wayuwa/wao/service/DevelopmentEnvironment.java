package cn.wayuwa.wao.service;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** 专用开发工作区的依赖、验收资料与主干同步；不安装依赖或改变认证权限。 */
final class DevelopmentEnvironment {
    static final int MAX_CONTEXT_BYTES = 20_000;
    @FunctionalInterface interface Commands {
        String run(Path directory, List<String> arguments) throws Exception;
    }
    private final Path repository;
    private final Path state;
    private final Path dependenciesRepository;
    private final boolean isolatedDependencies;
    private final boolean windows;
    private final Commands commands;

    DevelopmentEnvironment(Path repository, Path state) {
        this(repository, state, null);
    }
    DevelopmentEnvironment(Path repository, Path state, Path dependenciesRepository) {
        this(repository, state, dependenciesRepository, System.getProperty("os.name", "").startsWith("Windows"), DevelopmentEnvironment::command);
    }
    DevelopmentEnvironment(Path repository, Path state, boolean windows, Commands commands) {
        this(repository, state, null, windows, commands);
    }
    DevelopmentEnvironment(Path repository, Path state, Path dependenciesRepository, boolean windows, Commands commands) {
        this.repository = repository.toAbsolutePath().normalize();
        this.state = state.toAbsolutePath().normalize();
        this.isolatedDependencies = dependenciesRepository != null;
        this.dependenciesRepository = dependenciesRepository == null ? this.repository : dependenciesRepository.toAbsolutePath().normalize();
        this.windows = windows;
        this.commands = commands;
    }

    /** 仅复用固定仓库的匹配依赖；既有正确 junction 幂等保留，其他目录不删除、不覆盖。 */
    void prepareDependencies(Path worktree) throws Exception {
        Path work = requireStateDirectory(worktree);
        if (state.toRealPath().startsWith(repository.toRealPath())) throw new IllegalStateException("开发状态目录不能位于固定仓库内");
        Path primary = dependencySource();
        prepareModule(work, primary, "wao-web");
        if (Files.exists(primary.resolve("wao-game/package.json")) || Files.exists(work.resolve("wao-game/package.json")))
            prepareModule(work, primary, "wao-game");
    }
    private Path dependencySource() throws Exception {
        if (!isolatedDependencies) return repository.toRealPath();
        Path source = dependenciesRepository;
        if (!source.startsWith(state) || !Files.isDirectory(source) || !source.toRealPath().equals(source)
                || !source.toRealPath().startsWith(state.toRealPath())
                || !Files.isDirectory(source.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                || !source.resolve(".git").toRealPath().startsWith(source))
            throw new IllegalStateException("独立依赖源必须是 state 内无越界链接的普通 Git checkout");
        String top = commands.run(source, List.of("git", "rev-parse", "--show-toplevel")).trim();
        String remote = commands.run(source, List.of("git", "remote", "get-url", "origin")).trim();
        if (!Path.of(top).toRealPath().equals(source)
                || !java.util.Set.of("https://github.com/wayuwacn/wao.git", "git@github.com:wayuwacn/wao.git").contains(remote))
            throw new IllegalStateException("独立依赖源必须属于固定允许的 WAO origin");
        return source;
    }
    private void prepareModule(Path work, Path primary, String module) throws Exception {
        for (String name : List.of("package.json", "package-lock.json")) {
            Path source = primary.resolve(module).resolve(name);
            Path target = work.resolve(module).resolve(name);
            if (!Files.isRegularFile(source) || !Files.isRegularFile(target)
                    || !source.toRealPath().startsWith(primary) || !target.toRealPath().startsWith(work)
                    || Files.mismatch(source, target) != -1)
                throw new IllegalStateException("前端依赖 manifest 不一致或缺失，不能复用主仓库依赖：" + module + "/" + name);
        }
        Path source = primary.resolve(module).resolve("node_modules");
        if (!Files.isDirectory(source) || !source.toRealPath().startsWith(primary))
            throw new IllegalStateException("固定仓库内缺少可复用的前端 node_modules：" + module);
        if (!windows) throw new IllegalStateException("当前平台未配置隔离依赖准备方式；不自动安装或复用不匹配依赖");
        Path link = work.resolve(module).resolve("node_modules");
        commands.run(work, List.of("git", "check-ignore", "--quiet", module + "/node_modules/"));
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(link) || !link.toRealPath().equals(source.toRealPath()))
                throw new IllegalStateException("工作区已有 node_modules 不属于固定匹配依赖，已保留原目录");
            return;
        }
        // cmd 内建命令仍通过参数数组调用；拒绝 shell 元字符，避免路径变成另一条命令。
        requireCmdPath(link);
        requireCmdPath(source);
        commands.run(work, List.of("cmd", "/c", "mklink", "/J", link.toString(), source.toString()));
        if (!Files.isDirectory(link) || !link.toRealPath().equals(source.toRealPath()))
            throw new IllegalStateException("专用工作区依赖 junction 未正确建立，已有目录已保留");
    }

    /** UTF-8 运维验收资料只从 state 内的普通文件读取，限制实际读入字节数。 */
    String validationContext(String configured) throws Exception {
        if (configured == null || configured.isBlank()) return "";
        Path path = Path.of(configured);
        if (!path.isAbsolute()) path = state.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(state) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(state.toRealPath()))
            throw new IllegalStateException("验收上下文必须是专用状态目录内的普通文件");
        byte[] bytes;
        try (var input = Files.newInputStream(path.toRealPath(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_CONTEXT_BYTES + 1);
        }
        if (bytes.length > MAX_CONTEXT_BYTES) throw new IllegalStateException("验收上下文不得超过 20KB");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("验收上下文必须使用有效 UTF-8", invalid);
        }
    }

    /** 每次调用只准备自己的产物目录；缓存位于该目录，不经共享依赖写入主仓库。 */
    Path outputDirectory(Path output) throws Exception {
        Path normalized = output.toAbsolutePath().normalize();
        if (!normalized.startsWith(state) || normalized.equals(state))
            throw new IllegalStateException("开发产物目录必须属于本执行器专用状态目录");
        Path ancestor = normalized;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        requireStateDirectory(ancestor);
        Files.createDirectories(normalized);
        return requireStateDirectory(normalized);
    }

    /** 汇聚之后先同步主干再冻结验收提交；未完成操作、dirty、冲突全部保留并阻塞。 */
    String synchronizeForVerification(Path worktree) throws Exception {
        Path work = requireStateDirectory(worktree);
        requireClean(work);
        commands.run(work, List.of("git", "fetch", "origin", "main"));
        commands.run(work, List.of("git", "rebase", "origin/main"));
        requireClean(work);
        prepareDependencies(work);
        return commands.run(work, List.of("git", "rev-parse", "HEAD")).trim();
    }

    private void requireClean(Path work) throws Exception {
        for (String operation : List.of("rebase-merge", "rebase-apply", "MERGE_HEAD", "CHERRY_PICK_HEAD")) {
            Path marker = Path.of(commands.run(work, List.of("git", "rev-parse", "--git-path", operation)).trim());
            if (!marker.isAbsolute()) marker = work.resolve(marker);
            if (Files.exists(marker)) throw new IllegalStateException("工作区存在未完成的 Git 操作，已保留现场：" + operation);
        }
        if (!commands.run(work, List.of("git", "status", "--porcelain", "--untracked-files=all")).isBlank())
            throw new IllegalStateException("工作区存在未汇聚改动，已保留现场，不能冻结验收提交");
    }
    private Path requireStateDirectory(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(state) || !Files.isDirectory(normalized)
                || !normalized.toRealPath().startsWith(state.toRealPath()))
            throw new IllegalStateException("工作区或产物目录超出本执行器专用状态目录");
        return normalized.toRealPath();
    }
    private static void requireCmdPath(Path path) {
        if (path.toString().matches(".*[\"&|<>^%()\r\n!].*"))
            throw new IllegalStateException("依赖路径包含不允许的命令字符");
    }
    static String command(Path directory, List<String> arguments) throws Exception {
        if (!arguments.get(0).equals("cmd")) return DevelopmentWorker.command(directory, arguments);
        // mklink 成功提示使用系统代码页，不是 UTF-8；只核对退出码和实际链接，不解码或持久化输出。
        Process process = new ProcessBuilder(arguments).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("依赖 junction 创建超时");
            if (process.exitValue() != 0) throw new IllegalStateException("依赖 junction 创建失败，已有目录已保留");
            return "";
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            }
        }
    }
}
