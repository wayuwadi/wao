package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.GameSaveEntity;
import cn.wayuwa.wao.dto.MemoryMatchDto;
import cn.wayuwa.wao.mapper.GameSaveMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 记忆翻牌服务：服务端生成牌序、校验翻牌、计步并保存成员最佳记录。 */
@Service
public class MemoryMatchService {

    private static final String GAME_KEY = "memory-match";
    private static final int CARD_COUNT = 16;

    private final GameSaveMapper saves;
    private final ObjectMapper json;

    public MemoryMatchService(GameSaveMapper saves, ObjectMapper json) {
        this.saves = saves;
        this.json = json;
    }

    /**
     * 读取当前成员的活动牌局和长期最佳步数；第一次进入自动洗牌。
     *
     * @param memberId 当前登录成员
     * @return 可直接渲染的服务端牌局
     */
    @Transactional
    public MemoryMatchDto get(String memberId) {
        GameSaveEntity save = loadOrCreate(memberId);
        return view(save, state(save), null);
    }

    /**
     * 翻开一个位置。第一次翻牌只记选择，第二次由服务端判断配对并增加一步。
     *
     * @param memberId 当前登录成员
     * @param index 卡片位置 0..15
     * @param revision 浏览器当前看到的存档版本
     * @return 翻牌后的权威快照；配对失败时 revealed 暂含两张牌供前端展示
     */
    @Transactional
    public MemoryMatchDto flip(String memberId, int index, int revision) {
        GameSaveEntity save = loadOrCreate(memberId);
        requireRevision(save, revision);
        State current = state(save);
        if (current.finished()) throw new BadRequestException("本局已经完成，请先重新洗牌");
        if (current.matched().contains(index)) throw new BadRequestException("这张卡片已经配对");
        if (current.firstIndex() != null && current.firstIndex() == index) {
            throw new BadRequestException("请选择另一张卡片");
        }

        State next;
        List<Integer> temporaryReveal = null;
        if (current.firstIndex() == null) {
            next = new State(current.cards(), current.matched(), index, current.steps(), current.bestSteps(), false);
        } else {
            int first = current.firstIndex();
            int nextSteps = current.steps() + 1;
            Set<Integer> matched = new HashSet<>(current.matched());
            boolean pair = current.cards().get(first).equals(current.cards().get(index));
            if (pair) {
                matched.add(first);
                matched.add(index);
            } else {
                temporaryReveal = List.of(first, index);
            }
            boolean finished = matched.size() == CARD_COUNT;
            Integer best = current.bestSteps();
            if (finished && (best == null || nextSteps < best)) best = nextSteps;
            next = new State(current.cards(), sorted(matched), null, nextSteps, best, finished);
        }
        persist(save, next, revision);
        return view(save, next, temporaryReveal);
    }

    /**
     * 保留长期最佳记录并开始一局新的服务端牌序。
     *
     * @param memberId 当前登录成员
     * @param revision 浏览器当前看到的存档版本
     * @return 新牌局快照
     */
    @Transactional
    public MemoryMatchDto restart(String memberId, int revision) {
        GameSaveEntity save = loadOrCreate(memberId);
        requireRevision(save, revision);
        State current = state(save);
        State next = fresh(current.bestSteps());
        save.setStartedAt(LocalDateTime.now());
        persist(save, next, revision);
        return view(save, next, null);
    }

    private GameSaveEntity loadOrCreate(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new BadRequestException("无法识别当前游戏玩家");
        String id = memberId + ":" + GAME_KEY;
        GameSaveEntity existing = saves.selectById(id);
        if (existing != null) return existing;

        LocalDateTime now = LocalDateTime.now();
        State initial = fresh(null);
        GameSaveEntity created = new GameSaveEntity();
        created.setId(id);
        created.setMemberId(memberId);
        created.setGameKey(GAME_KEY);
        created.setStateJson(write(initial));
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

    private void persist(GameSaveEntity save, State state, int expectedRevision) {
        save.setStateJson(write(state));
        save.setScore((long) state.steps());
        save.setMoveCount(state.steps());
        save.setWon(state.finished());
        save.setRevision(expectedRevision + 1);
        save.setPreviousStateJson(null);
        save.setPreviousScore(null);
        save.setPreviousMoveCount(null);
        save.setUpdatedAt(LocalDateTime.now());
        if (saves.updateSnapshot(save, expectedRevision) == 0) throw staleSave();
    }

    private MemoryMatchDto view(GameSaveEntity save, State state, List<Integer> temporaryReveal) {
        List<Integer> revealed = new ArrayList<>(state.matched());
        if (state.firstIndex() != null) revealed.add(state.firstIndex());
        if (temporaryReveal != null) revealed.addAll(temporaryReveal);
        List<Integer> visible = sorted(new HashSet<>(revealed));
        List<Integer> maskedCards = new ArrayList<>(Collections.nCopies(CARD_COUNT, null));
        visible.forEach(index -> maskedCards.set(index, state.cards().get(index)));
        return new MemoryMatchDto(maskedCards, state.matched(), visible,
                state.steps(), state.bestSteps(), state.finished(), save.getRevision(), save.getUpdatedAt());
    }

    private State state(GameSaveEntity save) {
        try {
            State state = json.readValue(save.getStateJson(), State.class);
            validate(state);
            return state;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("记忆翻牌存档无法读取", exception);
        }
    }

    private String write(State state) {
        validate(state);
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆翻牌存档无法保存", exception);
        }
    }

    private static State fresh(Integer bestSteps) {
        List<Integer> cards = new ArrayList<>(CARD_COUNT);
        for (int icon = 0; icon < CARD_COUNT / 2; icon++) {
            cards.add(icon);
            cards.add(icon);
        }
        Collections.shuffle(cards, ThreadLocalRandom.current());
        return new State(List.copyOf(cards), List.of(), null, 0, bestSteps, false);
    }

    private static void validate(State state) {
        if (state.cards() == null || state.cards().size() != CARD_COUNT
                || state.cards().stream().anyMatch(card -> card == null || card < 0 || card >= CARD_COUNT / 2)) {
            throw new IllegalArgumentException("牌序不合法");
        }
        for (int icon = 0; icon < CARD_COUNT / 2; icon++) {
            final int expected = icon;
            if (state.cards().stream().filter(card -> card == expected).count() != 2) {
                throw new IllegalArgumentException("每种图标必须恰好两张");
            }
        }
        if (state.matched() == null || state.matched().stream().anyMatch(index -> index < 0 || index >= CARD_COUNT)
                || new HashSet<>(state.matched()).size() != state.matched().size()
                || state.matched().size() % 2 != 0) {
            throw new IllegalArgumentException("配对状态不合法");
        }
        if (state.firstIndex() != null && (state.firstIndex() < 0 || state.firstIndex() >= CARD_COUNT
                || state.matched().contains(state.firstIndex()))) {
            throw new IllegalArgumentException("翻牌位置不合法");
        }
        if (state.steps() < 0 || state.bestSteps() != null && state.bestSteps() < CARD_COUNT / 2
                || state.finished() != (state.matched().size() == CARD_COUNT)) {
            throw new IllegalArgumentException("成绩状态不合法");
        }
    }

    private static List<Integer> sorted(Set<Integer> values) {
        return values.stream().sorted().toList();
    }

    private static void requireRevision(GameSaveEntity save, int revision) {
        if (!save.getRevision().equals(revision)) throw staleSave();
    }

    private static ConflictException staleSave() {
        return new ConflictException("牌局已在另一个页面更新，请重新载入最新记录");
    }

    private record State(List<Integer> cards, List<Integer> matched, Integer firstIndex,
                         int steps, Integer bestSteps, boolean finished) {
    }
}
