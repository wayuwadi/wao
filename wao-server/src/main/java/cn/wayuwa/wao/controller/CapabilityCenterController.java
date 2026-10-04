package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.WorkspaceMcpTools;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 能力中心的真实工具目录与当前成员可选择的资源范围。 */
@RestController
public class CapabilityCenterController {
    private static final Map<String, String> CLI = Map.ofEntries(
            Map.entry("list_projects", "wao projects list"), Map.entry("get_project", "wao projects get ID"),
            Map.entry("create_project", "wao projects create"), Map.entry("update_project", "wao projects update ID"),
            Map.entry("list_open_tasks", "wao tasks open"), Map.entry("get_task", "wao tasks get ID"),
            Map.entry("create_task", "wao tasks create"), Map.entry("update_task", "wao tasks update ID"),
            Map.entry("update_subtask_status", "wao subtasks status ID"),
            Map.entry("list_knowledge_bases", "wao knowledge list"), Map.entry("get_knowledge_base", "wao knowledge get ID"),
            Map.entry("create_knowledge_base", "wao knowledge create"), Map.entry("update_knowledge_base", "wao knowledge update ID"),
            Map.entry("create_folder", "wao folders create"), Map.entry("rename_folder", "wao folders rename"),
            Map.entry("move_knowledge_node", "wao knowledge move"), Map.entry("list_documents", "wao documents list KB_ID"),
            Map.entry("get_document", "wao documents get ID"), Map.entry("create_document", "wao documents create"),
            Map.entry("update_document", "wao documents update ID"));
    private final WorkspaceMcpTools tools;
    private final CurrentMember members;
    private final ObjectMapper json;

    public CapabilityCenterController(WorkspaceMcpTools tools, CurrentMember members, ObjectMapper json) {
        this.tools = tools; this.members = members; this.json = json;
    }

    /** 公开工具元数据，不含账号、令牌或用户内容。 */
    @GetMapping("/api/public/capabilities")
    public ApiResponse<ObjectNode> catalog() {
        ArrayNode capabilities = json.createArrayNode();
        tools.list(true).path("tools").forEach(tool -> {
            String name = tool.path("name").asText();
            ObjectNode item = ((ObjectNode) tool).deepCopy();
            item.put("group", group(name)); item.put("cli", CLI.get(name));
            item.put("mcp", name); item.put("readOnly", tool.path("annotations").path("readOnlyHint").asBoolean());
            capabilities.add(item);
        });
        capabilities.add(json.createObjectNode().put("name", "publish_html").put("title", "发布 HTML 页面")
                .put("description", "新建公开 UTF-8 单文件 HTML，最大 10 MB；不开放已有页面的列表、修改或删除。")
                .put("group", "pages").put("cli", "wao pages publish").putNull("mcp").put("readOnly", false));
        return ApiResponse.ok(json.createObjectNode().set("capabilities", capabilities));
    }

    /** 返回当前成员实际可见项目及知识库；设备令牌无法调用浏览器管理接口。 */
    @GetMapping("/api/workspace-mcp/resources")
    public ResponseEntity<ApiResponse<ObjectNode>> resources(HttpServletRequest request) {
        String id = members.id(request);
        if (!StringUtils.hasText(id)) throw new UnauthorizedException("登录已过期，请重新登录");
        AuthenticatedToken member = new AuthenticatedToken("browser-resource-catalog", id, false);
        ObjectNode result = json.createObjectNode();
        result.set("projects", json.valueToTree(tools.call("list_projects", json.createObjectNode(), member)));
        result.set("knowledgeBases", json.valueToTree(tools.call("list_knowledge_bases", json.createObjectNode(), member)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    private static String group(String name) {
        if (name.contains("project")) return "projects";
        if (name.contains("task")) return "tasks";
        if (name.contains("document")) return "documents";
        return "knowledge";
    }
}
