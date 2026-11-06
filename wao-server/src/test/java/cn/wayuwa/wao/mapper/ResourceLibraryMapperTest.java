package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.HostedPageEntity;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercise the actual scoped SQL, including empty visible-project sets and same-time ordering. */
class ResourceLibraryMapperTest {
    private SqlSession session;

    @BeforeEach
    void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        // Body columns intentionally do not exist: list queries must only request metadata.
        jdbc.execute("CREATE TABLE file_blob(id VARCHAR(64), name VARCHAR(64), display_name VARCHAR(64), content_type VARCHAR(64),"
                + "byte_size BIGINT, created_by VARCHAR(64), project_id VARCHAR(64), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE image_blob(id VARCHAR(64), name VARCHAR(64), content_type VARCHAR(64), byte_size BIGINT,"
                + "source VARCHAR(64), project_id VARCHAR(64), content_hash VARCHAR(64), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE hosted_page(id VARCHAR(64), slug VARCHAR(64), title VARCHAR(64), file_name VARCHAR(64),"
                + "source_type VARCHAR(32), project_id VARCHAR(64), icon_content_type VARCHAR(64), created_by VARCHAR(64),"
                + "created_at TIMESTAMP, updated_at TIMESTAMP, revision BIGINT NOT NULL DEFAULT 0)");
        for (String id : new String[]{"global", "visible-a", "visible-b", "hidden"}) {
            String project = id.equals("global") ? null : id.startsWith("visible") ? "allowed" : "private";
            jdbc.update("INSERT INTO file_blob(id,name,byte_size,project_id,created_at) VALUES (?,?,7,?,TIMESTAMP '2026-11-06 12:00:00')", id, id, project);
            jdbc.update("INSERT INTO image_blob(id,name,byte_size,project_id,created_at) VALUES (?,?,9,?,TIMESTAMP '2026-11-06 12:00:00')", id, id, project);
            jdbc.update("INSERT INTO hosted_page(id,title,source_type,project_id,updated_at) VALUES (?,?,?,?,TIMESTAMP '2026-11-06 12:00:00')",
                    id, id, id.equals("visible-b") ? "AI_RUN_REPORT" : "MANUAL_UPLOAD", project);
        }
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FileBlobMapper.class);
        configuration.addMapper(ImageBlobMapper.class);
        configuration.addMapper(HostedPageMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
    }

    @AfterEach void close() { if (session != null) session.close(); }

    @Test
    void globalAndVisibleProjectMetadataAreOrderedWithoutLoadingBodies() {
        var files = session.getMapper(FileBlobMapper.class).listVisibleMeta(Set.of("allowed"), null);
        var images = session.getMapper(ImageBlobMapper.class).listVisibleMeta(Set.of("allowed"), null);
        var pages = session.getMapper(HostedPageMapper.class).listVisibleMeta("ALL", Set.of("allowed"), null);
        assertThat(files).extracting(FileBlobEntity::getId).containsExactly("visible-b", "visible-a", "global");
        assertThat(images).extracting(ImageBlobEntity::getId).containsExactly("visible-b", "visible-a", "global");
        assertThat(pages).extracting(HostedPageEntity::getId).containsExactly("visible-b", "visible-a", "global");
        assertThat(files).allSatisfy(file -> { assertThat(file.getContent()).isNull(); assertThat(file.getByteSize()).isEqualTo(7L); });
        assertThat(images).allSatisfy(image -> assertThat(image.getContent()).isNull());
        assertThat(pages).allSatisfy(page -> assertThat(page.getHtmlContent()).isNull());
    }

    @Test
    void emptyVisibleSetReturnsOnlyGlobalItemsAndNeverGeneratesAnEmptyInClause() {
        assertThat(session.getMapper(FileBlobMapper.class).listVisibleMeta(Set.of(), null))
                .extracting(FileBlobEntity::getId).containsExactly("global");
        assertThat(session.getMapper(ImageBlobMapper.class).listVisibleMeta(Set.of(), null))
                .extracting(ImageBlobEntity::getId).containsExactly("global");
        assertThat(session.getMapper(HostedPageMapper.class).listVisibleMeta("ALL", Set.of(), null))
                .extracting(HostedPageEntity::getId).containsExactly("global");
    }

    @Test
    void explicitProjectAndPageSourceFiltersComposeWithoutLeakingOtherProjects() {
        assertThat(session.getMapper(FileBlobMapper.class).listVisibleMeta(Set.of("allowed"), "allowed"))
                .extracting(FileBlobEntity::getId).containsExactly("visible-b", "visible-a");
        assertThat(session.getMapper(ImageBlobMapper.class).listVisibleMeta(Set.of("allowed"), "allowed"))
                .extracting(ImageBlobEntity::getId).containsExactly("visible-b", "visible-a");
        assertThat(session.getMapper(HostedPageMapper.class).listVisibleMeta("MANUAL_UPLOAD", Set.of("allowed"), null))
                .extracting(HostedPageEntity::getId).containsExactly("visible-a", "global");
        assertThat(session.getMapper(HostedPageMapper.class).listVisibleMeta("AI_RUN_REPORT", Set.of("allowed"), "allowed"))
                .extracting(HostedPageEntity::getId).containsExactly("visible-b");
    }

    @Test
    void renameCompareAndSetIsAtomicAndDistinguishesNullCaseAndTrailingSpaces() {
        var files = session.getMapper(FileBlobMapper.class);
        assertThat(files.compareAndSetDisplayName("global", "第一份名称", null)).isEqualTo(1);
        assertThat(files.compareAndSetDisplayName("global", "迟到的名称", null)).isZero();
        assertThat(files.compareAndSetDisplayName("global", "Foo ", "第一份名称")).isEqualTo(1);
        assertThat(files.compareAndSetDisplayName("global", "错误覆盖", "Foo")).isZero();
        assertThat(files.compareAndSetDisplayName("global", "错误覆盖", "foo ")).isZero();
        assertThat(files.compareAndSetDisplayName("global", "错误覆盖", "Faa ")).isZero();
        assertThat(files.compareAndSetDisplayName("global", "最终名称", "Foo ")).isEqualTo(1);
        assertThat(files.listVisibleMeta(Set.of(), null).get(0).getDisplayName()).isEqualTo("最终名称");
        assertThat(files.compareAndSetDisplayName("missing", "不会创建", null)).isZero();
    }
}
