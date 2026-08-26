package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.SkillRequests;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:skillmarket;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql(
        scripts = "/skill-market-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS,
        config = @SqlConfig(encoding = "UTF-8")
)
class SkillMarketServiceIntegrationTest {
    @Autowired SkillMarketService market;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clearBusinessData() {
        jdbc.update("DELETE FROM skill_favorite"); jdbc.update("DELETE FROM skill_version");
        jdbc.update("DELETE FROM skill"); jdbc.update("DELETE FROM skill_publisher");
    }

    @Test void completesRejectResubmitPublishFavoriteDownloadAndKeepsOldVersionPublic() throws Exception {
        market.savePublisher("author", new SkillRequests.PublisherProfile("wao-author", "WAO 作者"));
        var first = market.createZipDraft("author", draft(null, "1.0.0"), file("1.0.0"));
        var firstVersion = first.versions().get(0);
        market.submit("author", first.id(), firstVersion.id());
        assertThat(market.reviews("admin")).hasSize(1);
        market.reject("admin", firstVersion.id(), "请补充安装说明");

        var resubmitted = market.createZipDraft("author", draft(first.id(), "1.0.1"), file("1.0.1"));
        var approvedVersion = resubmitted.versions().stream().filter(v -> v.version().equals("1.0.1")).findFirst().orElseThrow();
        market.submit("author", first.id(), approvedVersion.id()); market.approve("admin", approvedVersion.id());
        assertThat(market.market("meeting", "", "codex", "newest", 1, 20).total()).isEqualTo(1);
        var detail = market.detail("wao-author", "meeting-helper", null);
        assertThat(detail.publishedVersion().version()).isEqualTo("1.0.1");
        market.favorite("author", first.id(), true); market.favorite("author", first.id(), true);
        assertThat(market.detail("wao-author", "meeting-helper", "author").skill().favorites()).isEqualTo(1);
        var oldDownload = market.download("wao-author", "meeting-helper", "1.0.1", "test-ip");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldDownload.content()))).isEqualTo(oldDownload.sha256());

        var next = market.createZipDraft("author", draft(first.id(), "2.0.0"), file("2.0.0"));
        var pending = next.versions().stream().filter(v -> v.version().equals("2.0.0")).findFirst().orElseThrow();
        market.submit("author", first.id(), pending.id());
        assertThat(market.detail("wao-author", "meeting-helper", null).publishedVersion().version()).isEqualTo("1.0.1");
        assertThat(market.download("wao-author", "meeting-helper", null, "another-ip").sha256()).isEqualTo(oldDownload.sha256());
        market.approve("admin", pending.id());
        assertThat(market.detail("wao-author", "meeting-helper", null).publishedVersion().version()).isEqualTo("2.0.0");
        assertThat(market.download("wao-author", "meeting-helper", "1.0.1", "history-ip").sha256()).isEqualTo(oldDownload.sha256());
    }

    @Test void onlyAdministratorsCanReview() throws Exception {
        assertThatThrownBy(() -> market.reviews("author")).isInstanceOf(UnauthorizedException.class);
    }

    @Test void aiCandidateBecomesUnconfirmedDraftAndIteratesPatchVersion() throws Exception {
        market.savePublisher("author", new SkillRequests.PublisherProfile("wao-author", "WAO 作者"));

        var first = market.createAiDraft("author", file("verified method"), "ar-1");
        var second = market.createAiDraft("author", file("improved method"), "ar-2");

        assertThat(first.slug()).isEqualTo("meeting-helper");
        assertThat(first.versions()).extracting("version").containsExactly("0.1.0");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.versions()).extracting("version").contains("0.1.1", "0.1.0");
        assertThat(second.versions().get(0).state()).isEqualTo("DRAFT");
        assertThat(second.versions().get(0).license()).isEqualTo("ALL-RIGHTS-RESERVED");
    }

    private static SkillRequests.Draft draft(String skillId, String version) {
        return new SkillRequests.Draft(skillId, "meeting-helper", "Meeting Helper", "Summarize meetings and decisions safely",
                "office-efficiency", List.of("meeting", "notes"), version, "Release " + version, "MIT", List.of("codex", "claude-code"), true);
    }
    private static MockMultipartFile file(String version) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(new ZipArchiveEntry("meeting/SKILL.md"));
            zip.write(("---\nname: Meeting Helper\ndescription: Release " + version + "\n---\n# Install").getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        return new MockMultipartFile("archive", "meeting.zip", "application/zip", out.toByteArray());
    }
}
