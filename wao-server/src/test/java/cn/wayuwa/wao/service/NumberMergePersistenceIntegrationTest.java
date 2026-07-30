package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.dto.NumberMergeGameDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Sql("/db/migration/V47__game_hall_persistence.sql")
class NumberMergePersistenceIntegrationTest {

    @Autowired
    private NumberMergeGameService game;

    @Test
    void persistsAuthoritativeMoveUndoAndArchivedRunThroughRealMappers() {
        NumberMergeGameDto initial = game.get("game-test-member");
        assertThat(initial.board()).hasSize(16);
        assertThat(initial.board().stream().filter(value -> value != 0)).hasSize(2);

        NumberMergeGameDto moved = makeOneMove(initial);
        assertThat(moved.revision()).isGreaterThan(initial.revision());
        assertThat(moved.moves()).isEqualTo(1);
        assertThat(moved.canUndo()).isTrue();

        NumberMergeGameDto undone = game.undo("game-test-member", moved.revision());
        assertThat(undone.moves()).isZero();
        assertThat(undone.canUndo()).isFalse();

        NumberMergeGameDto playedAgain = makeOneMove(undone);
        NumberMergeGameDto restarted = game.restart("game-test-member", playedAgain.revision());
        assertThat(restarted.moves()).isZero();
        assertThat(restarted.recentRuns()).singleElement().satisfies(run -> {
            assertThat(run.moves()).isEqualTo(1);
            assertThat(run.outcome()).isIn("RESTARTED", "WON", "GAME_OVER");
        });
    }

    private NumberMergeGameDto makeOneMove(NumberMergeGameDto snapshot) {
        NumberMergeGameDto current = snapshot;
        for (String direction : new String[] { "LEFT", "UP", "RIGHT", "DOWN" }) {
            NumberMergeGameDto next = game.move("game-test-member", direction, current.revision());
            if (next.revision() > current.revision()) return next;
            current = next;
        }
        throw new AssertionError("初始棋盘至少应有一个有效移动方向");
    }
}
