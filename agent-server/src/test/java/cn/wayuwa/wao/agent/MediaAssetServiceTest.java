package cn.wayuwa.wao.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.agent.dto.MediaAssetDto;
import cn.wayuwa.wao.agent.service.MediaAssetService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class MediaAssetServiceTest {
    @Autowired private MediaAssetService mediaAssets;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void listReturnsOnlyCurrentWorkspaceAssetsWithCreationTime() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String foreignMemberId = "media-" + suffix;
        String foreignAccountId = "acc-media-" + suffix;
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO member (id, name, initial, color, role) VALUES (?, ?, ?, ?, ?)",
                foreignMemberId, "其他创作者", "他", "#347FC5", "member");
        jdbc.update("INSERT INTO account (id, member_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                foreignAccountId, foreignMemberId, "ACTIVE", Timestamp.valueOf(now), Timestamp.valueOf(now));

        MediaAssetDto own = mediaAssets.store("acc-demo", image("own-" + suffix + ".png"));
        MediaAssetDto foreign = mediaAssets.store(foreignAccountId, image("foreign-" + suffix + ".png"));

        List<MediaAssetDto> listed = mediaAssets.list("acc-demo");

        assertThat(listed).extracting(MediaAssetDto::id).contains(own.id()).doesNotContain(foreign.id());
        assertThat(listed.stream().filter(item -> item.id().equals(own.id())).findFirst().orElseThrow().createdAt())
                .isNotNull();
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[] {1, 2, 3});
    }
}
