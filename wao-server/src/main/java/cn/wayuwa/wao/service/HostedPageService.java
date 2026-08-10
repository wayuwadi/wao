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
import java.util.UUID;
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
