package com.bluff.cpu;

import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.model.GameState;
import com.bluff.model.Player;
import com.bluff.model.TurnLogEntry;
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
        assertThat(bid.quantity()).isEqualTo(4);
        assertThat(bid.face()).isEqualTo(2);
    }

    @Test
    void openingBid_emptyDice_neverBidsStarBelowConfidence() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of());
        Game game = minimalPlayingGame(cpu, stubHuman());
        game.setCurrentBid(null);

        for (int seed = 0; seed < 50; seed++) {
            CpuStrategy.Decision d = new CpuStrategy(new Random(seed)).decideAction(game, cpu);

            assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
            CpuStrategy.Decision.Bid bid = (CpuStrategy.Decision.Bid) d;
            assertThat(bid.quantity()).isEqualTo(1);
            assertThat(bid.face()).isBetween(1, 5);
        }
    }

    @Test
    void openingBid_endgame_bidsOnlyFaceBackedByOwnDice() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(3));
        Player human = new Player("human", "Human", false);
        human.setDice(List.of(1));
        Game game = minimalPlayingGame(cpu, human);
        game.setCurrentBid(null);

        for (int seed = 0; seed < 50; seed++) {
            CpuStrategy.Decision d = new CpuStrategy(new Random(seed)).decideAction(game, cpu);

            assertThat(d).isEqualTo(new CpuStrategy.Decision.Bid(1, 3));
        }
    }

    @Test
    void challengesWhenCurrentQuantityExceedsTotalDiceOnTable() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(1, 1, 1, 1, 1));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(11, 1, human.getId()));
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
        placeBid(game, prev);
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
        Random pickLargestCandidate =
                new Random() {
                    @Override
                    public int nextInt(int bound) {
                        return bound - 1;
                    }
                };
        CpuStrategy strategy = new CpuStrategy(pickLargestCandidate);
        Random setup = new Random(123);
        int bids = 0;
        for (int i = 0; i < 300; i++) {
            Player cpu = new Player("cpu", "CPU 1", true);
            cpu.setDice(rollDice(setup, 1 + setup.nextInt(5)));
            Player human = new Player("human", "Human", false);
            human.setDice(rollDice(setup, 1 + setup.nextInt(5)));
            int totalDiceCount = cpu.getDice().size() + human.getDice().size();
            Game game = minimalPlayingGame(cpu, human);
            Bid prev = new Bid(1 + setup.nextInt(totalDiceCount), 1 + setup.nextInt(6), human.getId());
            placeBid(game, prev);

            if (strategy.decideAction(game, cpu) instanceof CpuStrategy.Decision.Bid(int q, int f)) {
                bids++;
                assertThat(q).isLessThanOrEqualTo(totalDiceCount);
                assertThat(Game.isValidBidAfter(prev, new Bid(q, f, cpu.getId()))).isTrue();
            }
        }
        assertThat(bids).isGreaterThan(50);
    }

    @Test
    void challengesImplausibleBidJudgedOnlyFromOwnDice() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(1, 1, 2, 2, 3));
        Player human = new Player("human", "Human", false);
        human.setDice(List.of(5, 5, 5, 5, 5));
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(4, 5, human.getId()));

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Challenge.class);
    }

    @Test
    void doesNotChallengeBidAlreadyCoveredByOwnDice() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(2, 2, 2, 4, 5));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(3, 2, human.getId()));

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
    }

    @Test
    void followUpBid_prefersFaceBackedByOwnDice() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(4, 4, 4, 4, 6));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(2, 1, human.getId()));

        CpuStrategy strategy = new CpuStrategy(new Random(3));
        for (int i = 0; i < 20; i++) {
            CpuStrategy.Decision d = strategy.decideAction(game, cpu);
            assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
            assertThat(((CpuStrategy.Decision.Bid) d).face()).isEqualTo(4);
        }
    }

    @Test
    void challengesStarBidThatIsUnlikelyOnLargeTable() {
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
        placeBid(game, prev);
        game.setCurrentPlayerIndex(0);

        CpuStrategy strategy = new CpuStrategy(new Random(1));
        CpuStrategy.Decision d = strategy.decideAction(game, cpu);

        assertThat(d).isInstanceOf(CpuStrategy.Decision.Challenge.class);
    }

    @Test
    void challengesWhenBidderLikelyLosesEvenIfSafeRaiseExists() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(1, 1, 1, 1, 1));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(4, 5, human.getId()));

        for (int seed = 0; seed < 20; seed++) {
            CpuStrategy.Decision d = new CpuStrategy(new Random(seed)).decideAction(game, cpu);

            assertThat(d).isInstanceOf(CpuStrategy.Decision.Challenge.class);
        }
    }

    @Test
    void followUpBid_withStrongHand_prefersBidThatLooksTooHighToOthers() {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(4, 4, 4, 4, 4));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, human);
        placeBid(game, new Bid(1, 1, human.getId()));

        for (int seed = 0; seed < 20; seed++) {
            CpuStrategy.Decision d = new CpuStrategy(new Random(seed)).decideAction(game, cpu);

            assertThat(d).isInstanceOf(CpuStrategy.Decision.Bid.class);
            CpuStrategy.Decision.Bid bid = (CpuStrategy.Decision.Bid) d;
            assertThat(bid.face()).isEqualTo(4);
            assertThat(bid.quantity()).isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void trustsBidMoreWhenAnotherPlayerClaimedSameFaceThisRound() {
        CpuStrategy strategy = new CpuStrategy(new Random(1));

        assertThat(decideAfterEarlierClaim(strategy, 5)).isInstanceOf(CpuStrategy.Decision.Bid.class);
        assertThat(decideAfterEarlierClaim(strategy, 1)).isInstanceOf(CpuStrategy.Decision.Challenge.class);
    }

    private static CpuStrategy.Decision decideAfterEarlierClaim(CpuStrategy strategy, int earlierFace) {
        Player cpu = new Player("cpu", "CPU 1", true);
        cpu.setDice(List.of(1, 1, 2, 3, 5));
        Player cpu2 = new Player("cpu2", "CPU 2", true);
        cpu2.setDice(List.of(1, 1, 1, 1, 1));
        Player human = stubHuman();
        Game game = minimalPlayingGame(cpu, cpu2, human);
        placeBid(game, new Bid(4, earlierFace, cpu2.getId()));
        placeBid(game, new Bid(5, 5, human.getId()));
        return strategy.decideAction(game, cpu);
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

    private static void placeBid(Game game, Bid bid) {
        game.setCurrentBid(bid);
        game.setLastBidPlayerId(bid.getPlayerId());
        Player bidder = game.getPlayers().stream()
                .filter(p -> p.getId().equals(bid.getPlayerId()))
                .findFirst()
                .orElseThrow();
        game.getActionLog().add(TurnLogEntry.bid(
                game.getCurrentRound(), bidder.getId(), bidder.getName(), bid.getQuantity(), bid.getFace()));
    }

    private static List<Integer> rollDice(Random random, int count) {
        List<Integer> dice = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            dice.add(1 + random.nextInt(6));
        }
        return dice;
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
