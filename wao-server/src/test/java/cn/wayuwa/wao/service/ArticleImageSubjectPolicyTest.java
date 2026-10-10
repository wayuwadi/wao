package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ArticleImageSubjectPolicyTest {
    @Test void plannerAndNoPersonGenerationExcludeIdentityButPreserveEntireArticleAndPreferences() {
        String body="<visual_preferences>横版</visual_preferences>\n<article_text>本周完成三件事\n- 下周第一件\n- 下周第二件\n保留结尾</article_text>";
        String request=body+"\n人物必须出现<identity_snapshot>私有名字和照片</identity_snapshot>";
        assertThat(ArticleImageSubjectPolicy.planningPrompt(request)).contains(body,"NO_PERSON","USE_SELF").doesNotContain("私有名字和照片","人物必须出现");
        assertThat(ArticleImageSubjectPolicy.noPersonPrompt(request)).startsWith(body).contains("不要加入作者").doesNotContain("私有名字和照片","人物必须出现");
    }
    @Test void onlyExactAffirmativeDecisionEnablesDefaultIdentity() {
        assertThat(ArticleImageSubjectPolicy.needsSelf(" USE_SELF\n")).isTrue();
        for(String value:new String[]{null,"", "NO_PERSON","不确定","```USE_SELF```","USE_SELF\nNO_PERSON"})
            assertThat(ArticleImageSubjectPolicy.needsSelf(value)).isFalse();
    }
}
