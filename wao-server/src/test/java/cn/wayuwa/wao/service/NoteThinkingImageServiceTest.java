package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.dto.ImageItemDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class NoteThinkingImageServiceTest {
    static final String SOURCE="今天我离开办公室走了一会儿。走回来后感觉更清醒，但也可能是休息的时间起了作用。";
    static final String PLAN="""
        {"kind":"因果解释","title":"什么帮助我恢复清醒？",
        "observations":[{"quote":"走回来后感觉更清醒","meaning":"状态变化发生在散步之后"}],
        "inferences":[{"text":"环境切换可能帮助恢复状态，但还不能确定因果关系","basis":[1]}],
        "alternatives":["也可能是休息的时间起了作用"],"questions":["坐着休息后是否也有类似变化？"],
        "experiment":"下次自然休息时，分别记下散步前后的清醒感，并与坐着休息的记录对照。"}
        """;
    final ObjectMapper json=new ObjectMapper();
    final ImageService images=mock(ImageService.class);
    final NoteAssetPrivacyService privacy=mock(NoteAssetPrivacyService.class);
    final NoteThinkingImageService service=new NoteThinkingImageService(json,images,privacy);

    @Test void quotesMustBeContiguousOriginalTextAndRelationMustReferToRealObservation() {
        assertThat(service.validate(PLAN,SOURCE).path("title").asText()).contains("清醒");
        assertThatThrownBy(()->service.validate(PLAN.replace("走回来后感觉更清醒","散步解决了一切疲惫"),SOURCE)).hasMessageContaining("原文依据");
        assertThatThrownBy(()->service.validate(PLAN.replace("\"basis\":[1]","\"basis\":[2]"),SOURCE)).hasMessageContaining("结构");
        assertThatThrownBy(()->service.validate(PLAN.replace("\"basis\":[1]","\"basis\":[1.2]"),SOURCE)).hasMessageContaining("结构");
    }
    @Test void rejectsMissingQuestionsUnboundedOutputAndInventedGraphKind() {
        assertThatThrownBy(()->service.validate(PLAN.replace("[\"坐着休息后是否也有类似变化？\"]","[]"),SOURCE)).hasMessageContaining("结构");
        assertThatThrownBy(()->service.validate(PLAN.replace("因果解释","随意发挥"),SOURCE)).hasMessageContaining("结构");
        assertThatThrownBy(()->service.validate("x".repeat(9000),SOURCE)).hasMessageContaining("结构");
    }
    @Test void rendersRealReadableFourByThreePngAndCopyableOutlineUsesSamePlan() throws Exception {
        var plan=service.validate(PLAN,SOURCE);
        var png=service.render(plan,7);var image=ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(2048);assertThat(image.getHeight()).isEqualTo(1536);
        assertThat(png.length).isGreaterThan(10000);
        assertThat(service.outline(plan)).contains("原文观察","AI 推测","待验证","下一步","走回来后感觉更清醒");
        assertThat(service.outline(plan)).doesNotContain("```","来源不明");
    }
    @Test void generationMarksAssetPrivateAndKeepsQuestionsSourceAndStructuredPlan() {
        when(images.store(any(byte[].class),eq("image/png"),anyString(),anyString())).thenReturn(
            new ImageItemDto("thought","思考图","10KB","今天","小记","/api/images/thought/raw","",""));
        var result=service.generate(PLAN,SOURCE,9,"attempt");
        verify(privacy).privateUpload("images","thought");
        assertThat(result.questions()).containsExactly("坐着休息后是否也有类似变化？");
        assertThat(result.sourceRev()).isEqualTo(9);assertThat(result.current()).isTrue();
        assertThat(result.outline()).isEqualTo(service.outline(result.plan()));
    }
}
