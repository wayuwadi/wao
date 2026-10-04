package cn.wayuwa.wao.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BookExcerptProtectionTest {
    private final String prefix = "## 书摘\n\n> 保留  两个空格、\\*原文\\*\n> \n> 第二段\n\n来源：实际提供的书名\n\n## 我的感想\n\n";
    @Test void onlyThoughtsGoToModelAndQuoteIsReassembledVerbatim() {
        assertThat(BookExcerptProtection.thoughts(prefix + "我的想法")).isEqualTo("我的想法");
        assertThat(BookExcerptProtection.reassemble(prefix + "旧感想", "新感想")).isEqualTo(prefix + "新感想");
        assertThat(BookExcerptProtection.thoughts(prefix)).isEmpty();
    }
    @Test void rejectsMissingOrModifiedExcerptAndAcceptsCoverAdded() {
        assertThatThrownBy(() -> BookExcerptProtection.assertPreserved(prefix+"旧",prefix.replace("第二段","改写")+"新"))
                .hasMessageContaining("改变了书摘");
        assertThatThrownBy(() -> BookExcerptProtection.assertPreserved(prefix+"旧","只有感想")).isInstanceOf(RuntimeException.class);
        assertThatCode(() -> BookExcerptProtection.assertPreserved(prefix+"旧","![文章总览图](/api/images/a/raw)\n\n"+prefix+"新"))
                .doesNotThrowAnyException();
    }
    @Test void ordinaryNotesAndQuotedHeadingsAreNotMisparsed() {
        String normal="普通引用\n\n> 书摘\n\n感想";
        assertThat(BookExcerptProtection.thoughts(normal)).isEqualTo(normal);
        assertThat(BookExcerptProtection.thoughts(prefix.replace("第二段", "## 我的感想")+"真实感想")).isEqualTo("真实感想");
    }
}
