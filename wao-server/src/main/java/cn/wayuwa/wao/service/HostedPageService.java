package cn.wayuwa.wao.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.mapper.HostedPageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 单文件 HTML 页面的校验、持久化和安全渲染。 */
@Service
public class HostedPageService {
    public static final long MAX_HTML_BYTES = 10L * 1024 * 1024;
    public static final long MAX_ICON_BYTES = 1024 * 1024;
    private static final String RUN_REPORT_ID_PREFIX = "run-report-";
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?");
    private static final Pattern HEAD = Pattern.compile("(?i)<head(?:\\s[^>]*)?>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title(?:\\s[^>]*)?>.*?</title\\s*>");
    private static final Pattern ICON = Pattern.compile("(?is)<link\\s+[^>]*rel\\s*=\\s*(['\"]?)(?:shortcut\\s+)?icon\\1[^>]*>");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final HostedPageMapper mapper;
    public HostedPageService(HostedPageMapper mapper) { this.mapper = mapper; }

    /** 列出托管页元数据，避免读取 HTML 与图标正文。 */
    public List<HostedPageDto> list() { return mapper.listMeta().stream().map(this::dto).toList(); }

    /** 新建一个公开托管页。 */
    public HostedPageDto create(MultipartFile html, String slug, String title, MultipartFile icon, String memberId) {
        HostedPageEntity entity = new HostedPageEntity();
        entity.setId("page-" + UUID.randomUUID());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setCreatedBy(memberId == null ? "未知成员" : memberId);
        fill(entity, html, slug, title, icon, true);
        try { mapper.insert(entity); } catch (DuplicateKeyException ex) { throw new BadRequestException("链接后缀已被使用，请换一个"); }
        return dto(entity);
    }

    /**
     * 新建或更新一次 AI Run 的持久化报告。
     *
     * <p>页面 id 由运行 id 稳定推导，因此同一次运行继续优化时会更新同一个公开地址；如果用户在
     * 文件页改过标题或后缀，后续只替换 HTML 正文，不覆盖这些人工配置。
     */
    public HostedPageDto saveRunReport(String runId, MultipartFile html, String title, String memberId) {
        String pageId = runReportPageId(runId);
        HostedPageEntity entity = mapper.selectById(pageId);
        if (entity == null) {
            entity = new HostedPageEntity();
            entity.setId(pageId);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setCreatedBy(memberId == null ? "AI Run" : memberId);
            fill(entity, html, "ai-run-" + runId, title, null, true);
            try {
                mapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw new BadRequestException("AI 报告公开地址已被占用，请删除冲突页面后重试");
            }
        } else {
            fill(entity, html, entity.getSlug(), entity.getTitle(), null, true);
            mapper.updateById(entity);
        }
        return dto(entity);
    }

    /** 保存由服务端已校验业务结果生成的 UTF-8 HTML，不要求先伪装成浏览器上传文件。 */
    public HostedPageDto saveGeneratedReport(String runId, byte[] html, String title, String memberId) {
        if (html == null || html.length == 0) throw new BadRequestException("生成的 HTML 报告不能为空");
        if (html.length > MAX_HTML_BYTES) throw new BadRequestException("HTML 文件超过 10MB");
        decode(html);
        String pageId = runReportPageId(runId);
        HostedPageEntity entity = mapper.selectById(pageId);
        if (entity == null) {
            entity = new HostedPageEntity();
            entity.setId(pageId);
            entity.setSlug("ai-run-" + runId);
            entity.setTitle(title);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setCreatedBy(memberId == null ? "AI Run" : memberId);
            entity.setFileName("report.html");
            entity.setHtmlContent(html);
            entity.setUpdatedAt(LocalDateTime.now());
            try {
                mapper.insert(entity);
            } catch (DuplicateKeyException exception) {
                throw new BadRequestException("AI 报告公开地址已被占用，请删除冲突页面后重试");
            }
        } else {
            entity.setHtmlContent(html);
            entity.setFileName("report.html");
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(entity);
        }
        return dto(entity);
    }

    /** 批量返回 AI Run 与其仍然存在的公开报告地址；查询只取元数据，不加载 HTML 正文。 */
    public Map<String, String> runReportUrls(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        List<String> pageIds = runIds.stream().map(HostedPageService::runReportPageId).toList();
        return mapper.listMetaByIds(pageIds).stream().collect(Collectors.toMap(
                page -> page.getId().substring(RUN_REPORT_ID_PREFIX.length()),
                page -> dto(page).url(),
                (first, ignored) -> first));
    }

    /** 更新标题、后缀、HTML 正文和可选图标；没有上传图标时保留原图标。 */
    public HostedPageDto update(String id, MultipartFile html, String slug, String title, MultipartFile icon) {
        HostedPageEntity entity = required(id);
        fill(entity, html, slug, title, icon, false);
        try { mapper.updateById(entity); } catch (DuplicateKeyException ex) { throw new BadRequestException("链接后缀已被使用，请换一个"); }
        return dto(entity);
    }

    /** 按公开后缀读取页面。 */
    public HostedPageEntity publicPage(String slug) {
        HostedPageEntity entity = mapper.findBySlug(normalizeSlug(slug));
        if (entity == null) throw new NotFoundException("托管页面不存在");
        return entity;
    }

    /** 注入受控标题和 favicon 后返回 UTF-8 HTML。 */
    public byte[] render(HostedPageEntity entity) {
        String html = decode(entity.getHtmlContent());
        html = TITLE.matcher(html).replaceAll("");
        html = ICON.matcher(html).replaceAll("");
        String metadata = "<title>" + escape(entity.getTitle()) + "</title>"
                + (entity.getIconContent() == null ? "" : "<link rel=\"icon\" href=\"/api/pages/" + entity.getSlug() + "/icon\">");
        var match = HEAD.matcher(html);
        html = match.find() ? html.substring(0, match.end()) + metadata + html.substring(match.end()) : metadata + html;
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /** 删除一个托管页。 */
    public void delete(String id) { if (mapper.deleteById(id) == 0) throw new NotFoundException("托管页面不存在"); }

    private void fill(HostedPageEntity entity, MultipartFile html, String slug, String title, MultipartFile icon, boolean htmlRequired) {
        entity.setSlug(normalizeSlug(slug));
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 200) throw new BadRequestException("页面标题须为 1～200 个字符");
        entity.setTitle(cleanTitle);
        if (html != null && !html.isEmpty()) {
            if (html.getSize() > MAX_HTML_BYTES) throw new BadRequestException("HTML 文件超过 10MB");
            String name = safeName(html.getOriginalFilename());
            if (!name.toLowerCase(Locale.ROOT).endsWith(".html") && !name.toLowerCase(Locale.ROOT).endsWith(".htm"))
                throw new BadRequestException("只能上传 .html 或 .htm 文件");
            try { entity.setHtmlContent(html.getBytes()); } catch (IOException ex) { throw new BadRequestException("读取 HTML 文件失败"); }
            decode(entity.getHtmlContent());
            entity.setFileName(name);
        } else if (htmlRequired) throw new BadRequestException("请选择 HTML 文件");
        if (icon != null && !icon.isEmpty()) {
            String type = icon.getContentType();
            if (icon.getSize() > MAX_ICON_BYTES || type == null || !type.startsWith("image/"))
                throw new BadRequestException("icon 必须是 1MB 以内的图片");
            try { entity.setIconContent(icon.getBytes()); } catch (IOException ex) { throw new BadRequestException("读取 icon 失败"); }
            entity.setIconContentType(type);
        }
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private HostedPageEntity required(String id) {
        HostedPageEntity entity = mapper.selectById(id);
        if (entity == null) throw new NotFoundException("托管页面不存在");
        return entity;
    }
    private HostedPageDto dto(HostedPageEntity e) { return new HostedPageDto(e.getId(), e.getSlug(), e.getTitle(), e.getFileName(),
            e.getIconContentType() != null, e.getUpdatedAt() == null ? "" : e.getUpdatedAt().format(TIME), "/api/pages/" + e.getSlug()); }
    private static String normalizeSlug(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) throw new BadRequestException("链接后缀只能包含小写字母、数字和中划线，长度 1～80，首尾不能是中划线");
        return slug;
    }
    private static String runReportPageId(String runId) {
        String clean = runId == null ? "" : runId.trim();
        if (!SLUG.matcher(clean).matches() || RUN_REPORT_ID_PREFIX.length() + clean.length() > 64) {
            throw new BadRequestException("AI 运行 id 无法生成报告地址");
        }
        return RUN_REPORT_ID_PREFIX + clean;
    }
    private static String decode(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new BadRequestException("HTML 文件必须使用 UTF-8 编码"); }
    }
    private static String safeName(String original) {
        if (original == null || original.isBlank()) return "index.html";
        String value = original.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
