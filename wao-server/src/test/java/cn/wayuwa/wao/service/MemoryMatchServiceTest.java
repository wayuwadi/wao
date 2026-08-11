package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.GameSaveEntity;
import cn.wayuwa.wao.mapper.GameSaveMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryMatchServiceTest {

    private final GameSaveMapper saves = mock(GameSaveMapper.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MemoryMatchService service = new MemoryMatchService(saves, json);

    @Test
    void serverOwnsDeckAndCountsOnlyCompletedPairAttempts() throws Exception {
        GameSaveEntity save = save(3, List.of(), null, 0, null, false);
        when(saves.selectById("wg:memory-match")).thenReturn(save);
        when(saves.updateSnapshot(any(), anyInt())).thenReturn(1);

        var first = service.flip("wg", 0, 3);
        assertThat(first.steps()).isZero();
        assertThat(first.revealed()).containsExactly(0);
        assertThat(first.cards()).containsOnlyOnce(0);
        assertThat(first.cards()).filteredOn(java.util.Objects::isNull).hasSize(15);

        var second = service.flip("wg", 2, 4);
        assertThat(second.steps()).isEqualTo(1);
        assertThat(second.matched()).containsExactlyInAnyOrder(0, 2);
        assertThat(second.cards()).filteredOn(java.util.Objects::nonNull).hasSize(2);
        assertThat(second.revision()).isEqualTo(5);
    }

    @Test
    void completingLastPairUpdatesBestWithoutTrustingBrowserScore() throws Exception {
        List<Integer> matched = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15);
        GameSaveEntity save = save(8, matched, 12, 11, 14, false);
        when(saves.selectById("wg:memory-match")).thenReturn(save);
        when(saves.updateSnapshot(any(), anyInt())).thenReturn(1);

        var completed = service.flip("wg", 14, 8);

        assertThat(completed.finished()).isTrue();
        assertThat(completed.steps()).isEqualTo(12);
        assertThat(completed.bestSteps()).isEqualTo(12);
    }

    @Test
    void stalePageCannotOverwriteLatestGame() throws Exception {
        when(saves.selectById("wg:memory-match")).thenReturn(save(5, List.of(), null, 0, null, false));

        assertThatThrownBy(() -> service.flip("wg", 0, 4))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("另一个页面");
    }

    private GameSaveEntity save(int revision, List<Integer> matched, Integer firstIndex,
                                int steps, Integer bestSteps, boolean finished) throws Exception {
        // 0/2、1/3……分别成对，让测试不依赖随机数。
        List<Integer> cards = List.of(0, 1, 0, 1, 2, 3, 2, 3, 4, 5, 4, 5, 6, 7, 6, 7);
        String state = json.writeValueAsString(new TestState(cards, matched, firstIndex, steps, bestSteps, finished));
        GameSaveEntity save = new GameSaveEntity();
        save.setId("wg:memory-match");
        save.setMemberId("wg");
        save.setGameKey("memory-match");
        save.setStateJson(state);
        save.setScore((long) steps);
        save.setMoveCount(steps);
        save.setWon(finished);
        save.setRevision(revision);
        save.setStartedAt(LocalDateTime.now().minusMinutes(3));
        save.setUpdatedAt(LocalDateTime.now());
        return save;
    }

    private record TestState(List<Integer> cards, List<Integer> matched, Integer firstIndex,
                             int steps, Integer bestSteps, boolean finished) {
    }
}
