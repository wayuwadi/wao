package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:drawinggroups;DB_CLOSE_DELAY=-1;MODE=MySQL")
class DrawingGroupMapperIntegrationTest {
    @Autowired DrawingGroupMapper groups;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void tables() {
        jdbc.execute("DROP TABLE IF EXISTS drawing_group_assignment");
        jdbc.execute("DROP TABLE IF EXISTS drawing_group");
        jdbc.execute("CREATE TABLE drawing_group(id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(32), name VARCHAR(128), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE drawing_group_assignment(member_id VARCHAR(32), file_id VARCHAR(32), group_id VARCHAR(32), PRIMARY KEY(member_id,file_id))");
    }
    @Test void groupAndAssignmentsAreMemberScopedAndCanMove() {
        groups.create("g1", "m1", "工作"); groups.create("g2", "m2", "私人");
        assertThat(groups.groups("m1")).containsExactly(new DrawingGroupMapper.Group("g1", "工作"));
        assertThat(groups.owned("g2", "m1")).isZero();
        groups.assign("m1", "dr1", "g1");
        assertThat(groups.assignments("m1")).containsExactly(new DrawingGroupMapper.Assignment("dr1", "g1"));
        assertThat(groups.assignments("m2")).isEmpty();
        groups.assign("m1", "dr1", "g1");
        groups.unassign("m1", "dr1");
        assertThat(groups.assignments("m1")).isEmpty();
    }
}
