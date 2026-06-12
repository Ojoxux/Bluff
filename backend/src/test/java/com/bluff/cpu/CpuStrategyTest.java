package com.bluff.cpu;

import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.model.GameState;
import com.bluff.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpuStrategyTest {

    @Test
    void openingBid_usesMostFrequentFaceOnCpuDice() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(2, 2, 2, 4, 4));
        Game game = minimalPlayingGame(cpu, stubHuman());
        game.setCurrentBid(null);

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
        CpuStrategy.Decision.Bid bid = (CpuStrategy.Decision.Bid) d;
        assertThat(bid.quantity()).isEqualTo(3);
        assertThat(bid.face()).isEqualTo(2);
    }

    @Test
    void openingBid_emptyDice_fallsBackToOneOne() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of());
        Game game = minimalPlayingGame(cpu, stubHuman());
        game.setCurrentBid(null);

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isEqualTo(new CpuStrategy.Decision.Bid(1, 1));
    }

    @Test
    void challengesWhenCurrentQuantityExceedsTotalDiceOnTable() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(1, 1, 1, 1, 1));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        game.setCurrentBid(new Bid(11, 1, human.getId()));
        game.setLastBidPlayerId(human.getId());
        game.setCurrentPlayerIndex(0);

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Challenge.class);
    }

    @Test
    void followUpBid_isAlwaysValidAfterPrevious() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(3, 3, 3, 3, 3));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        Bid prev = new Bid(1, 1, human.getId());
        game.setCurrentBid(prev);
        game.setLastBidPlayerId(human.getId());
        game.setCurrentPlayerIndex(0);

        CpuStrategy strategy = new CpuStrategy(new Random(42));
        for (int i = 0; i < 30; i++) {
            CpuStrategy.Decision d = strategy.decideAction(game, cpu);
            assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
            CpuStrategy.Decision.Bid b = (CpuStrategy.Decision.Bid) d;
            assertThat(Game.isValidBidAfter(prev, new Bid(b.quantity(), b.face(), cpu.getId()))).isTrue();
        }
    }

    @Test
    void followUpBid_neverExceedsTotalSurvivorDiceCount() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(3, 3, 3, 3, 3));
        Player human = stubHuman();
        int totalDiceCount = cpu.getDice().size() + human.getDice().size();
        Game game = minimalPlayingGame(cpu, human);
        Bid prev = new Bid(totalDiceCount, 5, human.getId());
        game.setCurrentBid(prev);
        game.setLastBidPlayerId(human.getId());
        game.setCurrentPlayerIndex(0);

        Random pickLargestCandidate =
                new Random() {
                    @Override
                    public int nextInt(int bound) {
                        return bound - 1;
                    }
                };
        CpuStrategy strategy = new CpuStrategy(pickLargestCandidate);
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
        CpuStrategy.Decision.Bid bid = (CpuStrategy.Decision.Bid) d;
        assertThat(bid.quantity()).isLessThanOrEqualTo(totalDiceCount);
    }

    @Test
    void followUpBid_prefersSmallestLegalQuantityInsteadOfJumpingToMaximum() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(3, 3, 3, 3, 3));
        Player human = stubHuman();
        Player cpu2 = new Player("cpu2", "CPU 2", true);
        cpu2.setDice(List.of(4, 4, 4, 4, 4));
        Player cpu3 = new Player("cpu3", "CPU 3", true);
        cpu3.setDice(List.of(5, 5, 5, 5, 5));
        Player cpu4 = new Player("cpu4", "CPU 4", true);
        cpu4.setDice(List.of(6, 6, 6, 6, 6));
        Player cpu5 = new Player("cpu5", "CPU 5", true);
        cpu5.setDice(List.of(1, 1, 1, 1, 1));
        Game game = minimalPlayingGame(cpu, human, cpu2, cpu3, cpu4, cpu5);
        Bid prev = new Bid(7, 6, human.getId());
        game.setCurrentBid(prev);
        game.setLastBidPlayerId(human.getId());
        game.setCurrentPlayerIndex(0);

        Random pickLargestCandidate =
                new Random() {
                    @Override
                    public int nextInt(int bound) {
                        return bound - 1;
                    }
                };
        CpuStrategy strategy = new CpuStrategy(pickLargestCandidate);
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isEqualTo(new CpuStrategy.Decision.Bid(8, 6));
    }

    @Test
    void decideAction_rejectsNonCpuPlayer() {
        Player human = stubHuman();
        Game game = minimalPlayingGame(new Player("cpu", "CPU", true), human);
        game.setCurrentBid(null);
        CpuStrategy strategy = new CpuStrategy(new Random(1));
        assertThatThrownBy(() -> strategy.decideAction(game, human))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeTurn_runsBidFromDecision() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(5, 5, 5, 5, 5));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        game.setCurrentBid(null);
        game.setCurrentPlayerIndex(0);

        CpuStrategy strategy = new CpuStrategy(new Random(2));
        strategy.executeTurn(game);

        assertThat(game.getCurrentBid()).isNotNull();
        assertThat(game.getCurrentBid().getPlayerId()).isEqualTo(cpu.getId());
    }

    private static Player stubHuman() {
        Player human = new Player("human", "Human", false);
        human.setDice(List.of(1, 1, 1, 1, 1));
        return human;
    }

    private static Game minimalPlayingGame(Player firstSeat, Player secondSeat, Player... additionalSeats) {
        Game game = new Game("g1", "human");
        game.getPlayers().add(firstSeat);
        game.getPlayers().add(secondSeat);
        for (Player p : additionalSeats) {
            game.getPlayers().add(p);
        }
        game.setState(GameState.PLAYING);
        game.setCurrentPlayerIndex(0);
        return game;
    }
}
