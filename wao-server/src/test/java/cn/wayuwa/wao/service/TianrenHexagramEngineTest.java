package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TianrenHexagramEngineTest {

    private final TianrenHexagramEngine engine = new TianrenHexagramEngine();

    @Test
    void mapsKnownTrigramPairsToKingWenSequence() {
        var qian = engine.describe(List.of(7, 7, 7, 7, 7, 7));
        assertThat(qian.primary().number()).isEqualTo(1);
        assertThat(qian.primary().name()).isEqualTo("乾");
        assertThat(qian.primary().symbol()).isEqualTo("䷀");
        assertThat(qian.relating()).isNull();

        var kun = engine.describe(List.of(8, 8, 8, 8, 8, 8));
        assertThat(kun.primary().number()).isEqualTo(2);
        assertThat(kun.primary().name()).isEqualTo("坤");

        var tai = engine.describe(List.of(7, 7, 7, 8, 8, 8));
        assertThat(tai.primary().number()).isEqualTo(11);
        assertThat(tai.primary().name()).isEqualTo("泰");
        assertThat(tai.primary().lower().name()).isEqualTo("乾");
        assertThat(tai.primary().upper().name()).isEqualTo("坤");
    }

    @Test
    void allTrigramPairsCoverEveryKingWenHexagramExactlyOnce() {
        List<List<Integer>> trigrams = List.of(
                List.of(7, 7, 7), List.of(7, 7, 8), List.of(7, 8, 7), List.of(7, 8, 8),
                List.of(8, 7, 7), List.of(8, 7, 8), List.of(8, 8, 7), List.of(8, 8, 8));
        var numbers = new HashSet<Integer>();
        for (List<Integer> lower : trigrams) {
            for (List<Integer> upper : trigrams) {
                numbers.add(engine.describe(List.of(
                        lower.get(0), lower.get(1), lower.get(2),
                        upper.get(0), upper.get(1), upper.get(2))).primary().number());
            }
        }

        assertThat(numbers).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 64).boxed().toList());
    }

    @Test
    void flipsOnlyOldYinAndOldYangIntoRelatingHexagram() {
        var reading = engine.describe(List.of(9, 7, 7, 8, 8, 8));

        assertThat(reading.primary().number()).isEqualTo(11);
        assertThat(reading.changingLines()).containsExactly(1);
        assertThat(reading.relating()).isNotNull();
        assertThat(reading.relating().number()).isEqualTo(46);
        assertThat(reading.relating().name()).isEqualTo("升");
    }

    @Test
    void threeCoinsProduceOnlySixThroughNineFromBottomToTop() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextBoolean()).thenReturn(
                false, false, false,
                true, false, false,
                true, true, false,
                true, true, true,
                false, true, false,
                true, false, true);
        TianrenHexagramEngine deterministic = new TianrenHexagramEngine(random);

        assertThat(deterministic.castLines()).containsExactly(6, 7, 8, 9, 7, 8);
    }

    @Test
    void rejectsMalformedStoredLinesInsteadOfInventingAHexagram() {
        assertThatThrownBy(() -> engine.describe(List.of(7, 8, 9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("六个");
        assertThatThrownBy(() -> engine.describe(List.of(5, 7, 8, 9, 7, 8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
