package cn.wayuwa.wao.service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import javax.imageio.ImageIO;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.dto.ArticleImageJobDto;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 把登录成员的文章交给本地 Codex ImageGen，并将生成的 PNG 收进既有图床。 */
@Service
public class ArticleImageService {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private final AiAssistantService assistant;
    private final AiAssistantRunMapper runs;
    private final AiRunService aiRuns;
    private final ImageService images;

    public ArticleImageService(AiAssistantService assistant, AiAssistantRunMapper runs,
            AiRunService aiRuns, ImageService images) {
        this.assistant = assistant;
        this.runs = runs;
        this.aiRuns = aiRuns;
        this.images = images;
    }

    /** 提交生成任务，不覆盖正在编辑的正文。 */
    public ArticleImageJobDto start(String memberId, ArticleImageRequest request) {
        String id = assistant.enqueueArticleImageJob(memberId, request.articleText(), request.visualPrompt(),
                request.model(), request.requestId());
        return get(memberId, id);
    }

    /** 仅供服务端持久小记工作流入队；普通浏览器图片请求仍受单任务限制。 */
    ArticleImageJobDto startNoteWorkflow(String memberId, ArticleImageRequest request) {
        String id = assistant.enqueueNoteWorkflowArticleImageJob(memberId, request.articleText(), request.visualPrompt(),
                request.model(), request.requestId());
        return get(memberId, id);
    }

    /** 只向任务发起人返回状态和已入图床的图片地址。 */
    public ArticleImageJobDto get(String memberId, String runId) {
        AiAssistantRunEntity run = runs.selectById(runId);
        if (run == null || !Objects.equals(run.getMemberId(), memberId)
                || !AiAssistantService.ARTICLE_IMAGE_PURPOSE.equals(run.getUsagePurpose())) {
            throw new NotFoundException("总览图任务不存在");
        }
        return switch (run.getStatus()) {
            case "succeeded" -> new ArticleImageJobDto(runId, "SUCCEEDED", "图片已生成",
                    run.getResponseText());
            case "failed" -> new ArticleImageJobDto(runId, "FAILED",
                    StringUtils.hasText(run.getErrorText()) ? run.getErrorText() : "生成图片失败，请重试", null);
            case "running", "secure_running", "provider_running" -> new ArticleImageJobDto(runId, "RUNNING",
                    "正在生成文章总览图…", null);
            default -> new ArticleImageJobDto(runId, "QUEUED", "等待本地 Codex 生成图片", null);
        };
    }

    /** 仅持有当前领取令牌的 Runner 可上传一张真实 PNG；旧领取与重复上传无效。 */
    public String upload(String runnerToken, String runId, String claimToken, MultipartFile file) {
        String runnerId = aiRuns.authenticateRunner(runnerToken).getId();
        AiAssistantRunEntity run = runs.selectById(runId);
        if (run == null || !AiAssistantService.ARTICLE_IMAGE_PURPOSE.equals(run.getUsagePurpose())) {
            throw new NotFoundException("总览图任务不存在");
        }
        if (!Objects.equals(run.getRunnerId(), runnerId)
                || !Objects.equals(run.getClaimToken(), claimToken)
                || !"secure_running".equals(run.getStatus())) {
            throw new UnauthorizedException("当前节点无权上传这张总览图");
        }
        if (run.getResponseText() != null) return run.getResponseText();
        if (file == null || file.isEmpty() || file.getSize() > ImageService.MAX_BYTES) {
            throw new BadRequestException("生成图片为空或超过 10MB");
        }
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException exception) { throw new BadRequestException("读取生成图片失败，请重试"); }
        validatePng(bytes);
        ImageItemDto image = images.store(bytes, "image/png", "文章总览图.png", "文章总览图 · " + runId);
        if (runs.attachArticleImage(runId, runnerId, claimToken, image.url()) != 1) {
            images.delete(image.id());
            throw new BadRequestException("图片任务已经由其他节点处理，请重试");
        }
        return image.url();
    }
    /** 本机执行与历史上传共用 PNG 校验，不接受伪图片或解码尺寸膨胀。 */
    static void validatePng(byte[] bytes) {
        if (bytes == null || bytes.length > ImageService.MAX_BYTES) throw new BadRequestException("生成图片为空或超过 10MB");
        if (bytes.length < 24) throw new BadRequestException("生成结果不是有效的 PNG 图片");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) throw new BadRequestException("生成结果不是有效的 PNG 图片");
        }
        int width = ByteBuffer.wrap(bytes, 16, 4).getInt();
        int height = ByteBuffer.wrap(bytes, 20, 4).getInt();
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new BadRequestException("生成图片尺寸超出范围");
        }
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                throw new BadRequestException("生成结果不是有效的 PNG 图片");
            }
        } catch (IOException exception) {
            throw new BadRequestException("生成结果不是有效的 PNG 图片");
        }
    }

}
