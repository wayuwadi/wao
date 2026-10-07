package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** 核对 Agent 的实际文件范围和依赖图，模型输出不能替代执行边界。 */
final class DevelopmentPlan {
    static void validate(JsonNode plan) {
        if(!plan.path("blockedReason").asText().isBlank())throw new IllegalStateException("审视未完成："+DevelopmentWorker.safeMessage(plan.path("blockedReason").asText()));
        if(plan.path("noChange").asBoolean()) {
            if(plan.path("validation").isEmpty()||plan.path("reason").asText().isBlank())throw new IllegalStateException("无改动结论也必须有实际审视证据");
            return;
        }
        if(plan.path("goal").asText().isBlank()||plan.path("options").size()!=3||plan.path("recommendedIndex").asInt(-1)!=0)
            throw new IllegalStateException("方案必须保存改动目标、三个选择和首项推荐理由");
        for(var option:plan.path("options"))if(option.path("title").asText().isBlank()||option.path("description").asText().isBlank())
            throw new IllegalStateException("三个方案必须有完整标题与说明");
        JsonNode agents=plan.path("agents");
        if(!agents.isArray()||agents.isEmpty()||agents.size()>3)throw new IllegalStateException("Agent 分工必须为 1–3 项明确工作");
        Set<String> ids=new HashSet<>();
        for(var agent:agents) {
            String id=agent.path("id").asText();
            if(!id.matches("[a-z][a-z0-9-]{0,30}")||!ids.add(id)||agent.path("goal").asText().isBlank())throw new IllegalStateException("Agent 身份或目标无效");
            if(!agent.path("paths").isArray()||agent.path("paths").isEmpty())throw new IllegalStateException("Agent 必须有明确可写路径");
            for(var path:agent.path("paths"))if(!safePath(path.asText()))throw new IllegalStateException("计划包含未允许或不明确的文件范围");
        }
        Set<String> done=new HashSet<>();
        while(done.size()<ids.size()) {
            boolean advanced=false;
            for(var agent:agents) {
                String id=agent.path("id").asText();if(done.contains(id))continue;
                boolean ready=true;
                for(var dependency:agent.path("dependsOn")){if(!ids.contains(dependency.asText()))throw new IllegalStateException("Agent 依赖不存在");ready &= done.contains(dependency.asText());}
                if(ready){done.add(id);advanced=true;}
            }
            if(!advanced)throw new IllegalStateException("Agent 依赖存在循环");
        }
    }
    static boolean safePath(String path) {
        if(path==null||path.isBlank()||path.startsWith("/")||path.contains("..")||path.contains("\\")||path.contains(":")||path.contains("*")||path.chars().anyMatch(Character::isISOControl))return false;
        return path.startsWith("wao-web/src/")||path.startsWith("wao-web/scripts/acceptance/")
                ||path.startsWith("wao-server/src/main/java/cn/wayuwa/wao/")||path.startsWith("wao-server/src/test/")||path.startsWith("docs/");
    }
    static boolean matches(String file, JsonNode paths) {
        for(var node:paths){String path=node.asText();if(file.equals(path)||path.endsWith("/")&&file.startsWith(path))return true;}
        return false;
    }
    static boolean important(String file) {
        // 目录前缀可能包含权限代码，不能用宽泛目录规避确认。
        if(file.endsWith("/")) return true;
        String lower=file.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/auth/")||lower.contains("/common/")||lower.contains("workspacemcp")||lower.contains("settingservice")
                ||lower.contains("account")||lower.contains("vault")||lower.contains("development")||lower.startsWith("docs/adr/")
                ||lower.contains("security")||lower.contains("permission")||lower.contains("feishu")||lower.contains("deploy");
    }
    private DevelopmentPlan(){}
}
