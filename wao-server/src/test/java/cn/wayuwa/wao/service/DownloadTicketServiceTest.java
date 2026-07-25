package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;

class DownloadTicketServiceTest {

    private final DownloadTicketService service = new DownloadTicketService();

    /** 题面形如「7 + 5 = ?」/「9 − 2 = ?」，这里照着算一遍当作用户的回答。 */
    private String solve(String question) {
        String[] parts = question.replace(" = ?", "").split(" ");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[2]);
        return String.valueOf("+".equals(parts[1]) ? a + b : a - b);
    }

    @Test
    void correctAnswerYieldsTicketThatWorksExactlyOnce() {
        DownloadTicketService.ChallengeDto challenge = service.issueChallenge("file-1");

        String ticket = service.redeem("file-1", challenge.challengeId(), solve(challenge.question()));

        assertThat(service.consume("file-1", ticket)).isTrue();
        // 同一张票第二次就不认了 —— 这正是「每点一次就能下一次」的堵点
        assertThat(service.consume("file-1", ticket)).isFalse();
    }

    @Test
    void wrongAnswerIsRejectedAndBurnsTheChallenge() {
        DownloadTicketService.ChallengeDto challenge = service.issueChallenge("file-1");
        String wrong = String.valueOf(Integer.parseInt(solve(challenge.question())) + 1);

        assertThatThrownBy(() -> service.redeem("file-1", challenge.challengeId(), wrong))
                .isInstanceOf(BadRequestException.class);
        // 答错后原题作废，不能拿同一道题反复试
        assertThatThrownBy(() -> service.redeem("file-1", challenge.challengeId(), solve(challenge.question())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("验证已过期");
    }

    @Test
    void ticketIsBoundToTheFileItWasIssuedFor() {
        DownloadTicketService.ChallengeDto challenge = service.issueChallenge("file-1");
        String ticket = service.redeem("file-1", challenge.challengeId(), solve(challenge.question()));

        assertThat(service.consume("file-2", ticket)).isFalse();
    }

    @Test
    void challengeCannotBeRedeemedForAnotherFile() {
        DownloadTicketService.ChallengeDto challenge = service.issueChallenge("file-1");

        assertThatThrownBy(() -> service.redeem("file-2", challenge.challengeId(), solve(challenge.question())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void unknownOrEmptyTicketIsRejected() {
        assertThat(service.consume("file-1", null)).isFalse();
        assertThat(service.consume("file-1", "")).isFalse();
        assertThat(service.consume("file-1", "not-a-ticket")).isFalse();
    }
}
