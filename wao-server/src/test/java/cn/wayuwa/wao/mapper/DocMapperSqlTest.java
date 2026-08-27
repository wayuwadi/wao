package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.DocEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 验证完整文档树顺序会收敛为一条只更新目标列的动态 SQL。 */
class DocMapperSqlTest {

    @Test
    void treeOrderBatchBuildsOneScopedUpdateForEveryDocument() {
        Configuration mybatis = new Configuration();
        mybatis.addMapper(DocMapper.class);

        BoundSql bound = mybatis.getMappedStatement(DocMapper.class.getName() + ".updateTreeOrders")
                .getBoundSql(Map.of("kbId", "k1", "docs", List.of(
                        doc("d1", "f1", 1), doc("d2", "f2", 2))));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql).startsWith("UPDATE doc SET folder_id = CASE id")
                .contains("WHEN ? THEN ? WHEN ? THEN ? END, ord = CASE id")
                .contains("WHERE kb_id = ? AND deleted_at IS NULL AND id IN ( ? , ? )")
                .doesNotContain("blocks_json", "links_json", "auto_sync_version");
        assertThat(bound.getParameterMappings()).hasSize(11);
    }

    private static DocEntity doc(String id, String folderId, int ord) {
        DocEntity doc = new DocEntity();
        doc.setId(id);
        doc.setFolderId(folderId);
        doc.setOrd(ord);
        return doc;
    }
}
