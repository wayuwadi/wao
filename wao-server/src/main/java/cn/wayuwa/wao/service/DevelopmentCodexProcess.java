package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 开发专用 CLI 进程；业务只读执行器不借此获得 shell 或代码写入权限。 */
final class DevelopmentCodexProcess {
    record Result(JsonNode answer, String sessionId, int successfulCommands) {}
    private static final Set<String> ENV=Set.of("SYSTEMROOT","WINDIR","SYSTEMDRIVE","COMSPEC","USERPROFILE","HOME",
            "APPDATA","LOCALAPPDATA","TEMP","TMP","PATH","PATHEXT","JAVA_HOME","PYTHONUTF8","PYTHONIOENCODING","CODEX_HOME","HTTP_PROXY","HTTPS_PROXY","NO_PROXY");
    static List<String> command(String executable, Path worktree, Path schema, Path output, boolean analysis) {
        var args=new ArrayList<>(List.of(executable,"exec","--ignore-user-config","--sandbox",analysis?"read-only":"workspace-write",
                "--disable","multi_agent","-c","approval_policy=\"never\"","-c","forced_login_method=\"chatgpt\"",
                "-c","model_reasoning_effort=\"high\"","-c","sandbox_workspace_write.network_access=true",
                "-c","windows.sandbox=\"elevated\"",
                "--model","gpt-6.1-sol","--cd",worktree.toString(),"--json","--color","never",
                "--output-schema",schema.toString(),"--output-last-message",output.toString(),"-"));
        return args;
    }
    static Result run(String executable, Path worktree, Path directory, Path schema, String prompt,
            boolean analysis, Duration timeout, Consumer<String> sessions) throws Exception {
        return run(executable,worktree,directory,schema,prompt,analysis,timeout,sessions,List.of());
    }
    static Result run(String executable, Path worktree, Path directory, Path schema, String prompt,
            boolean analysis, Duration timeout, Consumer<String> sessions,List<Path> writableDirectories) throws Exception {
        Files.createDirectories(directory);
        Path output=directory.resolve("result.json");
        Files.deleteIfExists(output);
        var args=command(LocalCodexExecutable.resolve(executable),worktree,schema,output,analysis);
        if(analysis&&!writableDirectories.isEmpty())throw new IllegalStateException("只读审视不能增加可写目录");
        for(Path path:writableDirectories){args.add(args.size()-1,"--add-dir");args.add(args.size()-1,path.toString());}
        var builder=new ProcessBuilder(args);
        builder.environment().keySet().removeIf(k->!ENV.contains(k.toUpperCase(java.util.Locale.ROOT)));
        builder.redirectErrorStream(true);
        Process process=builder.start();
        AtomicReference<String> session=new AtomicReference<>();
        var commands=new java.util.concurrent.atomic.AtomicInteger();
        var reader=new Thread(()-> {
            try(var lines=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                ObjectMapper json=new ObjectMapper();
                for(String line; (line=lines.readLine())!=null;) {
                    // 原始命令、正文和 stderr 不进入数据库或日志；只持久化会话关联。
                    if(!line.startsWith("{"))continue;
                    try {
                        var event=json.readTree(line);
                        JsonNode item=event.path("item");
                        if("item.completed".equals(event.path("type").asText())&&"command_execution".equals(item.path("type").asText())
                                &&item.path("exit_code").asInt(-1)==0)commands.incrementAndGet();
                        if("thread.started".equals(event.path("type").asText())) {
                            String id=event.path("thread_id").asText();
                            if(id.matches("[0-9a-f-]{36}")){session.set(id);sessions.accept(id);}
                        }
                    }catch(Exception ignored){ /* 结构化输出末尾单独校验 */ }
                }
            }catch(Exception ignored){ /* 退出状态与结构化结果负责失败收口 */ }
        },"development-events");reader.setDaemon(true);reader.start();
        try {
            try(var input=process.getOutputStream()){input.write(prompt.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))throw new IllegalStateException("开发 Agent 超时；工作区和已有产物已保留");
            reader.join(5000);
            if(process.exitValue()!=0)throw new IllegalStateException("开发 Agent 执行失败，请核对本机登录、网络、额度与沙箱支持");
            if(!Files.isRegularFile(output)||Files.size(output)>120_000)throw new IllegalStateException("开发 Agent 没有返回有效结构化产物");
            JsonNode answer=new ObjectMapper().readTree(Files.readString(output));
            if(!answer.isObject())throw new IllegalStateException("开发产物格式无效");
            if(analysis&&commands.get()==0)throw new IllegalStateException("只读审视未完成任何实际命令，不能采用模型推测；请核对沙箱与执行条件");
            return new Result(answer,session.get(),commands.get());
        } finally {
            if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private DevelopmentCodexProcess(){}
}
