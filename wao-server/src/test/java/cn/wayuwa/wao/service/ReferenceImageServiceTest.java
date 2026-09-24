package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

class ReferenceImageServiceTest {
    private ReferenceImageService images;

    @BeforeEach
    void setUp() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE reference_image (id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(32) NOT NULL, "
                + "name VARCHAR(200) NOT NULL, content_type VARCHAR(32) NOT NULL, byte_size BIGINT NOT NULL, "
                + "content_hash CHAR(64) NOT NULL, content BLOB NOT NULL, created_at TIMESTAMP NOT NULL, "
                + "UNIQUE(member_id, content_hash))");
        images = new ReferenceImageService(jdbc);
    }

    @Test
    void uploadRequiresNoFormFieldsAndRetryReturnsSamePrivateImage() {
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2};
        var file = new MockMultipartFile("file", "截图.png", "application/octet-stream", png);
        var first = images.upload("member-a", file);
        var retried = images.upload("member-a", file);

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(images.list("member-a")).hasSize(1);
        assertThat(images.list("member-b")).isEmpty();
        assertThat(images.raw("member-a", first.id()).bytes()).isEqualTo(png);
        assertThatThrownBy(() -> images.raw("member-b", first.id()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsPretendImageAndForeignReferenceSelection() {
        var fake = new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> images.upload("member-a", fake))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("只支持");
        assertThatThrownBy(() -> images.requireOwned("member-b", java.util.List.of("a".repeat(32))))
                .isInstanceOf(NotFoundException.class);
    }
}
