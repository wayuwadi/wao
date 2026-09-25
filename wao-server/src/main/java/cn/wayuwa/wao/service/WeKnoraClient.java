package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** WeKnora v0.8 的受限知识 API 客户端；不跟随重定向，不输出响应正文或密钥。 */
@Component
public class WeKnoraClient {
    private final ObjectMapper json;
    private final HttpClient http;
    private final String base;
    public record Connection(String kbId, String key) { }
    public static class ApiFailure extends cn.wayuwa.wao.common.BadRequestException {
        final boolean uncertain;
        ApiFailure(String message, boolean uncertain) { super(message); this.uncertain=uncertain; }
    }
    public WeKnoraClient(ObjectMapper json,
            @Value("${wao.weknora.machine-url:https://wao.wayuwa.cn/api/weknora-machine}") String base) {
        this.json=json; this.base=base.replaceAll("/$", "");
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 验证目标是启用 Wiki 的独立知识库，允许同时启用向量索引。 */
    public Map<String,Object> check(Connection c) {
        JsonNode kb=request(c,"GET","/knowledge-bases/"+c.kbId,null).path("data");
        if (!c.kbId.equals(kb.path("id").asText())) throw new IllegalArgumentException("目标知识库不可访问");
        JsonNode indexing=kb.path("indexing_strategy");
        if (!indexing.path("wiki_enabled").asBoolean(false))
            throw new IllegalArgumentException("当前同步需要启用 Wiki 的独立知识库");
        String mode=indexing.path("vector_enabled").asBoolean(false)?"Wiki+Vector":"Wiki-only";
        return Map.of("name",kb.path("name").asText(),"id",c.kbId,"mode",mode);
    }
    /** 完整分页读取远端清单，异常或不完整分页必须失败关闭。 */
    public List<JsonNode> list(Connection c) {
        List<JsonNode> result=new ArrayList<>();
        for (int page=1;page<=10000;page++) {
            JsonNode response=request(c,"GET","/knowledge-bases/"+c.kbId+"/knowledge?page="+page+"&page_size=100",null);
            JsonNode data=response.path("data");
            if (!data.isArray() || !response.has("total")) throw new ApiFailure("WeKnora 清单格式不兼容",false);
            data.forEach(result::add);
            if (result.size()>=response.path("total").asLong()) return result;
            if (data.isEmpty()) throw new ApiFailure("WeKnora 清单不完整，已停止同步",false);
        }
        throw new ApiFailure("远端清单超过安全分页上限",false);
    }
    /** 创建或更新 Markdown 文档；不在 HTTP 层盲目重试 POST。 */
    public JsonNode write(Connection c,String remoteId,WeKnoraSourceService.Source s) {
        Map<String,Object> payload=Map.of("title",s.title(),"content",s.content(),"status","publish","channel","wao");
        return request(c,remoteId==null?"POST":"PUT",remoteId==null?
            "/knowledge-bases/"+c.kbId+"/knowledge/manual":"/knowledge/manual/"+remoteId,payload).path("data");
    }
    /** 仅由映射清单调用删除，404 视为副本已不存在。 */
    public void delete(Connection c,String id) { request(c,"DELETE","/knowledge/"+id,null); }
    private JsonNode request(Connection c,String method,String path,Object body) {
        try {
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(45))
                .header("X-API-Key",c.key).header("Accept","application/json");
            if (body!=null) b.header("Content-Type","application/json");
            var response=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            if (method.equals("DELETE") && response.statusCode()==404) return json.createObjectNode();
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new ApiFailure("WeKnora HTTP "+response.statusCode()+"，请检查连接、权限或服务状态", response.statusCode()>=500);
            JsonNode parsed=json.readTree(response.body());
            if (!parsed.path("success").asBoolean(false)) throw new ApiFailure("WeKnora 未确认操作成功",true);
            return parsed;
        } catch (ApiFailure ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ApiFailure("同步已中断，等待恢复核对",true); }
        catch (Exception ex) { throw new ApiFailure("WeKnora 请求未完成，等待核对远端结果",true); }
    }
}
