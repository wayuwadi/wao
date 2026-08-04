package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.GameRunRecordEntity;
import cn.wayuwa.wao.domain.GameRunSummary;
import cn.wayuwa.wao.domain.GameSaveEntity;
import cn.wayuwa.wao.dto.GameRunRecordDto;
import cn.wayuwa.wao.dto.NumberMergeGameDto;
import cn.wayuwa.wao.dto.NumberMergeStatsDto;
import cn.wayuwa.wao.mapper.GameRunRecordMapper;
import cn.wayuwa.wao.mapper.GameSaveMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数字熔炉存档服务：服务端权威执行移动，并按成员隔离存档和战绩。 */
@Service
public class NumberMergeGameService {

    private static final String GAME_KEY = "number-merge";
    private static final TypeReference<List<Integer>> BOARD_TYPE = new TypeReference<>() { };

    private final GameSaveMapper saves;
    private final GameRunRecordMapper runs;
    private final NumberMergeEngine engine;
    private final ObjectMapper json;

    public NumberMergeGameService(GameSaveMapper saves, GameRunRecordMapper runs,
            NumberMergeEngine engine, ObjectMapper json) {
        this.saves = saves;
        this.runs = runs;
        this.engine = engine;
        this.json = json;
    }

    /**
     * 读取当前成员的活动存档；第一次进入会自动创建一盘带两个数字的新游戏。
     *
     * @param memberId 当前登录成员
     * @return 可直接渲染的完整游戏快照
     */
    @Transactional
    public NumberMergeGameDto get(String memberId) {
        return view(loadOrCreate(memberId));
    }

    /**
     * 按方向执行一步，只有棋盘实际变化时才增加步数、分数、随机数字和存档版本。
     *
     * @param memberId 当前登录成员
     * @param direction UP / DOWN / LEFT / RIGHT
     * @param revision 浏览器当前看到的存档版本
     * @return 移动后的完整快照
     */
    @Transactional
    public NumberMergeGameDto move(String memberId, String direction, int revision) {
        GameSaveEntity save = loadOrCreate(memberId);
        requireRevision(save, revision);
        List<Integer> board = board(save.getStateJson());
        if (engine.isGameOver(board)) return view(save);

        NumberMergeEngine.MoveResult result = engine.move(board, direction);
        if (!result.moved()) return view(save);

        List<Integer> next = engine.addRandomTile(result.board(), ThreadLocalRandom.current());
        long nextScore = save.getScore() + result.gainedScore();
        int nextMoves = save.getMoveCount() + 1;
        boolean won = save.getWon() || engine.hasWon(next);
        LocalDateTime now = LocalDateTime.now();
        int nextRevision = revision + 1;

        save.setPreviousStateJson(save.getStateJson());
        save.setPreviousScore(save.getScore());
        save.setPreviousMoveCount(save.getMoveCount());
        save.setStateJson(write(next));
        save.setScore(nextScore);
        save.setMoveCount(nextMoves);
        save.setWon(won);
        save.setRevision(nextRevision);
        save.setUpdatedAt(now);
        persist(save, revision);
        return view(save);
    }

    /**
     * 撤销最近一次有效移动；每一步只保留一个前态，撤销后不能连续倒退。
     *
     * @param memberId 当前登录成员
     * @param revision 浏览器当前看到的存档版本
     * @return 撤销后的完整快照
     */
    @Transactional
    public NumberMergeGameDto undo(String memberId, int revision) {
        GameSaveEntity save = loadOrCreate(memberId);
        requireRevision(save, revision);
        if (save.getPreviousStateJson() == null) return view(save);

        List<Integer> previous = board(save.getPreviousStateJson());
        LocalDateTime now = LocalDateTime.now();
        int nextRevision = revision + 1;
        save.setStateJson(save.getPreviousStateJson());
        save.setScore(save.getPreviousScore());
        save.setMoveCount(save.getPreviousMoveCount());
        save.setWon(engine.hasWon(previous));
        save.setRevision(nextRevision);
        save.setPreviousStateJson(null);
        save.setPreviousScore(null);
        save.setPreviousMoveCount(null);
        save.setUpdatedAt(now);
        persist(save, revision);
        return view(save);
    }

    /**
     * 结束当前一局并重新开盘；走过至少一步的旧棋盘会进入历史战绩。
     *
     * @param memberId 当前登录成员
     * @param revision 浏览器当前看到的存档版本
     * @return 新一局的完整快照
     */
    @Transactional
    public NumberMergeGameDto restart(String memberId, int revision) {
        GameSaveEntity save = loadOrCreate(memberId);
        requireRevision(save, revision);
        List<Integer> current = board(save.getStateJson());
        List<Integer> next = engine.newBoard(ThreadLocalRandom.current());
        LocalDateTime now = LocalDateTime.now();
        int nextRevision = revision + 1;

        GameRunRecordEntity completed = save.getMoveCount() > 0 ? record(save, current, now) : null;

        save.setStateJson(write(next));
        save.setScore(0L);
        save.setMoveCount(0);
        save.setWon(false);
        save.setRevision(nextRevision);
        save.setPreviousStateJson(null);
        save.setPreviousScore(null);
        save.setPreviousMoveCount(null);
        save.setStartedAt(now);
        save.setUpdatedAt(now);
        persist(save, revision);
        if (completed != null) runs.insert(completed);
        return view(save);
    }

    private GameSaveEntity loadOrCreate(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new BadRequestException("无法识别当前游戏玩家");
        String id = memberId + ":" + GAME_KEY;
        GameSaveEntity existing = saves.selectById(id);
        if (existing != null) return existing;

        LocalDateTime now = LocalDateTime.now();
        GameSaveEntity created = new GameSaveEntity();
        created.setId(id);
        created.setMemberId(memberId);
        created.setGameKey(GAME_KEY);
        created.setStateJson(write(engine.newBoard(ThreadLocalRandom.current())));
        created.setScore(0L);
        created.setMoveCount(0);
        created.setWon(false);
        created.setRevision(0);
        created.setStartedAt(now);
        created.setUpdatedAt(now);
        try {
            saves.insert(created);
            return created;
        } catch (DuplicateKeyException exception) {
            GameSaveEntity raced = saves.selectById(id);
            if (raced != null) return raced;
            throw exception;
        }
    }

    private GameRunRecordEntity record(GameSaveEntity save, List<Integer> board, LocalDateTime endedAt) {
        GameRunRecordEntity record = new GameRunRecordEntity();
        record.setId("gr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        record.setMemberId(save.getMemberId());
        record.setGameKey(GAME_KEY);
        record.setScore(save.getScore());
        record.setHighestTile(engine.highest(board));
        record.setMoveCount(save.getMoveCount());
        record.setOutcome(save.getWon() ? "WON" : engine.isGameOver(board) ? "GAME_OVER" : "RESTARTED");
        record.setStartedAt(save.getStartedAt());
        record.setEndedAt(endedAt);
        return record;
    }

    private void persist(GameSaveEntity save, int expectedRevision) {
        if (saves.updateSnapshot(save, expectedRevision) == 0) throw staleSave();
    }

    private NumberMergeGameDto view(GameSaveEntity save) {
        List<Integer> board = board(save.getStateJson());
        GameRunSummary summary = runs.summarize(save.getMemberId(), GAME_KEY);
        long archivedGames = summary == null || summary.getGamesPlayed() == null ? 0 : summary.getGamesPlayed();
        long archivedBest = summary == null || summary.getBestScore() == null ? 0 : summary.getBestScore();
        int archivedHighest = summary == null || summary.getHighestTile() == null ? 0 : summary.getHighestTile();
        long archivedMoves = summary == null || summary.getTotalMoves() == null ? 0 : summary.getTotalMoves();
        long archivedWins = summary == null || summary.getWins() == null ? 0 : summary.getWins();
        int currentHighest = engine.highest(board);
        NumberMergeStatsDto stats = new NumberMergeStatsDto(
                Math.max(archivedBest, save.getScore()),
                Math.max(archivedHighest, currentHighest),
                archivedGames + (save.getMoveCount() > 0 ? 1 : 0),
                archivedMoves + save.getMoveCount(),
                archivedWins + (save.getWon() ? 1 : 0));

        List<GameRunRecordDto> recent = runs.selectList(new LambdaQueryWrapper<GameRunRecordEntity>()
                        .eq(GameRunRecordEntity::getMemberId, save.getMemberId())
                        .eq(GameRunRecordEntity::getGameKey, GAME_KEY)
                        .orderByDesc(GameRunRecordEntity::getEndedAt)
                        .last("LIMIT 8"))
                .stream()
                .map(row -> new GameRunRecordDto(row.getId(), row.getScore(), row.getHighestTile(),
                        row.getMoveCount(), row.getOutcome(), row.getStartedAt(), row.getEndedAt()))
                .toList();
        return new NumberMergeGameDto(
                board, save.getScore(), save.getMoveCount(), save.getWon(), engine.isGameOver(board),
                save.getPreviousStateJson() != null, save.getRevision(), save.getStartedAt(), save.getUpdatedAt(),
                stats, recent);
    }

    private List<Integer> board(String raw) {
        try {
            List<Integer> board = json.readValue(raw, BOARD_TYPE);
            engine.validate(board);
            return List.copyOf(board);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("游戏存档无法读取", exception);
        }
    }

    private String write(List<Integer> board) {
        try {
            engine.validate(board);
            return json.writeValueAsString(board);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("游戏存档无法保存", exception);
        }
    }

    private static void requireRevision(GameSaveEntity save, int revision) {
        if (!save.getRevision().equals(revision)) throw staleSave();
    }

    private static ConflictException staleSave() {
        return new ConflictException("游戏已在另一个页面更新，请重新载入最新存档");
    }
}
