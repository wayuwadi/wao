package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.GameRunRecordEntity;
import cn.wayuwa.wao.domain.GameRunSummary;
import cn.wayuwa.wao.domain.GameSaveEntity;
import cn.wayuwa.wao.mapper.GameRunRecordMapper;
import cn.wayuwa.wao.mapper.GameSaveMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NumberMergeGameServiceTest {

    private final GameSaveMapper saves = mock(GameSaveMapper.class);
    private final GameRunRecordMapper runs = mock(GameRunRecordMapper.class);
    private final NumberMergeEngine engine = new NumberMergeEngine();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final NumberMergeGameService service = new NumberMergeGameService(saves, runs, engine, json);

    @BeforeEach
    void setUpSummaries() {
        GameRunSummary summary = new GameRunSummary();
        summary.setGamesPlayed(0L);
        summary.setBestScore(0L);
        summary.setHighestTile(0);
        summary.setTotalMoves(0L);
        summary.setWins(0L);
        when(runs.summarize("wg", "number-merge")).thenReturn(summary);
        when(runs.selectList(any())).thenReturn(List.of());
    }

    @Test
    void movePersistsScoreRevisionAndOneStepUndoSnapshot() throws Exception {
        GameSaveEntity save = save(List.of(
                2, 2, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0), 3);
        when(saves.selectById("wg:number-merge")).thenReturn(save);
        when(saves.updateSnapshot(any(), anyInt())).thenReturn(1);

        var result = service.move("wg", "LEFT", 3);

        assertThat(result.score()).isEqualTo(4);
        assertThat(result.moves()).isEqualTo(1);
        assertThat(result.revision()).isEqualTo(4);
        assertThat(result.canUndo()).isTrue();
        assertThat(result.board().stream().filter(value -> value != 0)).hasSize(2);
        assertThat(result.board()).contains(4);
    }

    @Test
    void rejectsStaleBrowserRevisionInsteadOfOverwritingNewerSave() throws Exception {
        when(saves.selectById("wg:number-merge")).thenReturn(save(List.of(
                2, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0), 5));

        assertThatThrownBy(() -> service.move("wg", "RIGHT", 4))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("另一个页面");
    }

    @Test
    void restartArchivesPlayedRunAndReturnsFreshBoard() throws Exception {
        GameSaveEntity save = save(List.of(
                4, 2, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0), 2);
        save.setScore(88L);
        save.setMoveCount(12);
        when(saves.selectById("wg:number-merge")).thenReturn(save);
        when(saves.updateSnapshot(any(), anyInt())).thenReturn(1);

        var result = service.restart("wg", 2);

        ArgumentCaptor<GameRunRecordEntity> record = ArgumentCaptor.forClass(GameRunRecordEntity.class);
        verify(runs).insert(record.capture());
        assertThat(record.getValue().getScore()).isEqualTo(88L);
        assertThat(record.getValue().getOutcome()).isEqualTo("RESTARTED");
        assertThat(result.score()).isZero();
        assertThat(result.moves()).isZero();
        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.board().stream().filter(value -> value != 0)).hasSize(2);
    }

    private GameSaveEntity save(List<Integer> board, int revision) throws Exception {
        GameSaveEntity save = new GameSaveEntity();
        save.setId("wg:number-merge");
        save.setMemberId("wg");
        save.setGameKey("number-merge");
        save.setStateJson(json.writeValueAsString(board));
        save.setScore(0L);
        save.setMoveCount(0);
        save.setWon(false);
        save.setRevision(revision);
        save.setStartedAt(LocalDateTime.now().minusMinutes(3));
        save.setUpdatedAt(LocalDateTime.now());
        return save;
    }
}
