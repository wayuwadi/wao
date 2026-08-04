package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class NumberMergeEngineTest {

    private final NumberMergeEngine engine = new NumberMergeEngine();

    @Test
    void mergesEachTileOnlyOnceAndAddsCombinedValuesToScore() {
        var result = engine.move(List.of(
                2, 2, 4, 4,
                2, 2, 2, 2,
                0, 0, 0, 0,
                0, 0, 0, 0), "LEFT");

        assertThat(result.moved()).isTrue();
        assertThat(result.gainedScore()).isEqualTo(20);
        assertThat(result.board()).containsExactly(
                4, 8, 0, 0,
                4, 4, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
    }

    @Test
    void supportsVerticalMovesAndDetectsLockedBoard() {
        var result = engine.move(List.of(
                2, 0, 0, 0,
                2, 0, 0, 0,
                4, 0, 0, 0,
                4, 0, 0, 0), "UP");
        assertThat(result.board()).containsExactly(
                4, 0, 0, 0,
                8, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);

        assertThat(engine.isGameOver(List.of(
                2, 4, 2, 4,
                4, 2, 4, 2,
                2, 4, 2, 4,
                4, 2, 4, 8))).isTrue();
    }

    @Test
    void createsNewBoardWithExactlyTwoTiles() {
        var board = engine.newBoard(new Random(7));

        assertThat(board).hasSize(16);
        assertThat(board.stream().filter(value -> value != 0)).hasSize(2);
        assertThat(board).allMatch(value -> value == 0 || value == 2 || value == 4);
    }
}
