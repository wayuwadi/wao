package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** 数字熔炉的纯规则引擎：合并、计分、随机出牌与终局判断都在服务端执行。 */
@Component
public class NumberMergeEngine {

    public static final int SIZE = 4;
    public static final int CELLS = SIZE * SIZE;
    public static final int TARGET = 2048;

    /** 创建带两枚初始数字的新棋盘。 */
    public List<Integer> newBoard(RandomGenerator random) {
        List<Integer> board = new ArrayList<>(Collections.nCopies(CELLS, 0));
        board = new ArrayList<>(addRandomTile(board, random));
        return addRandomTile(board, random);
    }

    /** 沿给定方向执行一次完整滑动与合并，不生成新数字。 */
    public MoveResult move(List<Integer> source, String rawDirection) {
        validate(source);
        Direction direction;
        try {
            direction = Direction.valueOf(rawDirection);
        } catch (RuntimeException exception) {
            throw new BadRequestException("移动方向不正确");
        }

        List<Integer> next = new ArrayList<>(source);
        long gained = 0;
        for (int line = 0; line < SIZE; line++) {
            List<Integer> compact = new ArrayList<>();
            for (int step = 0; step < SIZE; step++) {
                int value = source.get(position(line, step, direction));
                if (value != 0) compact.add(value);
            }
            List<Integer> merged = new ArrayList<>();
            for (int index = 0; index < compact.size();) {
                int value = compact.get(index);
                if (index + 1 < compact.size() && value == compact.get(index + 1)) {
                    int combined = value * 2;
                    merged.add(combined);
                    gained += combined;
                    index += 2;
                } else {
                    merged.add(value);
                    index++;
                }
            }
            while (merged.size() < SIZE) merged.add(0);
            for (int step = 0; step < SIZE; step++) {
                next.set(position(line, step, direction), merged.get(step));
            }
        }
        return new MoveResult(List.copyOf(next), gained, !next.equals(source));
    }

    /** 在一个随机空格放入 2（90%）或 4（10%）。 */
    public List<Integer> addRandomTile(List<Integer> source, RandomGenerator random) {
        validate(source);
        List<Integer> empty = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index) == 0) empty.add(index);
        }
        if (empty.isEmpty()) return List.copyOf(source);
        List<Integer> next = new ArrayList<>(source);
        int target = empty.get(random.nextInt(empty.size()));
        next.set(target, random.nextInt(10) == 0 ? 4 : 2);
        return List.copyOf(next);
    }

    /** 没有空格且横纵相邻数字都不同即为终局。 */
    public boolean isGameOver(List<Integer> board) {
        validate(board);
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                int value = board.get(row * SIZE + column);
                if (value == 0) return false;
                if (column + 1 < SIZE && value == board.get(row * SIZE + column + 1)) return false;
                if (row + 1 < SIZE && value == board.get((row + 1) * SIZE + column)) return false;
            }
        }
        return true;
    }

    /** 棋盘是否已经达到基础目标 2048。 */
    public boolean hasWon(List<Integer> board) {
        validate(board);
        return highest(board) >= TARGET;
    }

    /** 返回当前棋盘上的最大数字。 */
    public int highest(List<Integer> board) {
        validate(board);
        return board.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** 防止损坏存档进入规则引擎。 */
    public void validate(List<Integer> board) {
        if (board == null || board.size() != CELLS) throw new BadRequestException("游戏存档棋盘不完整");
        for (Integer value : board) {
            if (value == null || value < 0 || (value != 0 && (value & (value - 1)) != 0)) {
                throw new BadRequestException("游戏存档包含无效数字");
            }
        }
    }

    private static int position(int line, int step, Direction direction) {
        return switch (direction) {
            case LEFT -> line * SIZE + step;
            case RIGHT -> line * SIZE + (SIZE - 1 - step);
            case UP -> step * SIZE + line;
            case DOWN -> (SIZE - 1 - step) * SIZE + line;
        };
    }

    private enum Direction { UP, DOWN, LEFT, RIGHT }

    public record MoveResult(List<Integer> board, long gainedScore, boolean moved) {
    }
}
