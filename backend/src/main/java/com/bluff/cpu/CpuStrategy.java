package com.bluff.cpu;

import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.model.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class CpuStrategy {

    public sealed interface Decision permits Decision.Challenge, Decision.Bid {
        record Challenge() implements Decision {}

        record Bid(int quantity, int face) implements Decision {}
    }

    private static final int STAR = 6;
    private static final double OPENING_CONFIDENCE = 0.6;
    private static final double LOSS_TOLERANCE = 0.05;

    private final Random random;

    public CpuStrategy(Random random) {
        this.random = random;
    }

    public Decision decideAction(Game game, Player cpuPlayer) {
        if (!cpuPlayer.isCpu()) {
            throw new IllegalArgumentException("cpuPlayer は CPU である必要があります");
        }
        Bid current = game.getCurrentBid();
        if (current == null) {
            return openingDecision(game, cpuPlayer);
        }
        List<Bid> legal = collectLegalBids(game, cpuPlayer, current);
        if (legal.isEmpty()) {
            return new Decision.Challenge();
        }
        int ownDice = cpuPlayer.getDice().size();
        double bestBidLoss = Double.MAX_VALUE;
        double[] losses = new double[legal.size()];
        for (int i = 0; i < legal.size(); i++) {
            Bid b = legal.get(i);
            losses[i] = expectedLossAsBidder(countDistribution(game, cpuPlayer, b.getFace()), b.getQuantity(), ownDice);
            bestBidLoss = Math.min(bestBidLoss, losses[i]);
        }
        double challengeLoss =
                expectedLossAsChallenger(countDistribution(game, cpuPlayer, current.getFace()), current.getQuantity(), ownDice);
        if (challengeLoss < bestBidLoss) {
            return new Decision.Challenge();
        }
        List<Bid> candidates = new ArrayList<>();
        for (int i = 0; i < legal.size(); i++) {
            if (losses[i] <= bestBidLoss + LOSS_TOLERANCE) {
                candidates.add(legal.get(i));
            }
        }
        Bid pick = candidates.get(random.nextInt(candidates.size()));
        return new Decision.Bid(pick.getQuantity(), pick.getFace());
    }

    public void executeTurn(Game game) {
        Player current = game.getPlayers().get(game.getCurrentPlayerIndex());
        if (!current.isCpu()) {
            throw new IllegalStateException("現在のプレイヤーはCPUではありません");
        }
        Decision d = decideAction(game, current);
        switch (d) {
            case Decision.Challenge() -> game.challenge(current.getId());
            case Decision.Bid(int q, int f) -> game.bid(current.getId(), q, f);
        }
    }

    private Decision openingDecision(Game game, Player cpuPlayer) {
        int bestQ = 0;
        List<Integer> bestFaces = new ArrayList<>();
        for (int f = 1; f <= 6; f++) {
            double[] dist = countDistribution(game, cpuPlayer, f);
            int q = Math.max(1, largestQuantityWithConfidence(dist, OPENING_CONFIDENCE));
            if (q > bestQ) {
                bestQ = q;
                bestFaces.clear();
            }
            if (q == bestQ) {
                bestFaces.add(f);
            }
        }
        int face = bestFaces.get(random.nextInt(bestFaces.size()));
        return new Decision.Bid(bestQ, face);
    }

    private static double[] countDistribution(Game game, Player cpuPlayer, int face) {
        int known = 0;
        for (int d : cpuPlayer.getDice()) {
            if (d == face || (face <= 5 && d == STAR)) {
                known++;
            }
        }
        int unknown = totalSurvivorDiceCount(game) - cpuPlayer.getDice().size();
        double p = face == STAR ? 1.0 / 6 : 2.0 / 6;
        double[] binom = binomial(Math.max(unknown, 0), p);
        double[] dist = new double[known + binom.length];
        System.arraycopy(binom, 0, dist, known, binom.length);
        return dist;
    }

    private static double[] binomial(int n, double p) {
        double[] dist = new double[n + 1];
        dist[0] = 1.0;
        for (int i = 0; i < n; i++) {
            for (int k = i + 1; k >= 1; k--) {
                dist[k] = dist[k] * (1 - p) + dist[k - 1] * p;
            }
            dist[0] *= 1 - p;
        }
        return dist;
    }

    private static double expectedLossAsBidder(double[] dist, int q, int ownDice) {
        double loss = 0;
        for (int a = 0; a < dist.length && a < q; a++) {
            loss += dist[a] * Math.min(q - a, ownDice);
        }
        return loss;
    }

    private static double expectedLossAsChallenger(double[] dist, int q, int ownDice) {
        double loss = 0;
        for (int a = q; a < dist.length; a++) {
            loss += dist[a] * (a == q ? 1 : Math.min(a - q, ownDice));
        }
        return loss;
    }

    private static int largestQuantityWithConfidence(double[] dist, double confidence) {
        double atLeast = 0;
        for (int a = dist.length - 1; a >= 0; a--) {
            atLeast += dist[a];
            if (atLeast >= confidence) {
                return a;
            }
        }
        return 0;
    }

    private static List<Bid> collectLegalBids(Game game, Player cpuPlayer, Bid prev) {
        String pid = cpuPlayer.getId();
        List<Bid> out = new ArrayList<>();
        // 生存ダイス総数を超える quantity は実現不可能で、次の人間ターンで上乗せできず409を誘発するため上限とした
        int maxQ = totalSurvivorDiceCount(game);
        for (int q = 1; q <= maxQ; q++) {
            for (int f = 1; f <= 6; f++) {
                Bid next = new Bid(q, f, pid);
                if (Game.isValidBidAfter(prev, next)) {
                    out.add(next);
                }
            }
        }
        return out;
    }

    private static int totalSurvivorDiceCount(Game game) {
        int n = 0;
        for (Player p : game.getPlayers()) {
            if (!p.isEliminated()) {
                n += p.getDice().size();
            }
        }
        return Math.max(n, 0);
    }
}
