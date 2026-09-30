package com.bluff.cpu;

import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.model.Player;
import com.bluff.model.TurnLogEntry;

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
    private static final double VALUE_TOLERANCE = 0.05;

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
        int opponents = survivorCount(game) - 1;
        double opponentWeight = 1.0 / opponents;
        int nextDice = nextSurvivor(game, cpuPlayer).getDice().size();
        int bidderDice = bidderDiceCount(game, current);
        int totalDice = totalSurvivorDiceCount(game);
        double[][] dists = countDistributionsByFace(game, cpuPlayer);
        double bestBidValue = -Double.MAX_VALUE;
        double[] values = new double[legal.size()];
        for (int i = 0; i < legal.size(); i++) {
            Bid b = legal.get(i);
            double challenged = challengeProbability(totalDice, b.getQuantity(), b.getFace());
            values[i] = challenged
                    * expectedValueAsBidder(dists[b.getFace()], b.getQuantity(), ownDice, nextDice, opponentWeight);
            bestBidValue = Math.max(bestBidValue, values[i]);
        }
        double challengeValue = expectedValueAsChallenger(
                dists[current.getFace()], current.getQuantity(), ownDice, bidderDice, opponents, opponentWeight);
        if (challengeValue > bestBidValue) {
            return new Decision.Challenge();
        }
        List<Bid> candidates = new ArrayList<>();
        for (int i = 0; i < legal.size(); i++) {
            if (values[i] >= bestBidValue - VALUE_TOLERANCE) {
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
        double[][] dists = countDistributionsByFace(game, cpuPlayer);
        int bestQ = 0;
        List<Integer> bestFaces = new ArrayList<>();
        for (int f = 1; f <= 6; f++) {
            int q = largestQuantityWithConfidence(dists[f], OPENING_CONFIDENCE);
            if (q > bestQ) {
                bestQ = q;
                bestFaces.clear();
            }
            if (q == bestQ) {
                bestFaces.add(f);
            }
        }
        int face = bestFaces.get(random.nextInt(bestFaces.size()));
        return new Decision.Bid(Math.max(1, bestQ), face);
    }

    private static double[][] countDistributionsByFace(Game game, Player cpuPlayer) {
        double[][] dists = new double[7][];
        for (int f = 1; f <= 6; f++) {
            dists[f] = countDistribution(game, cpuPlayer, f);
        }
        return dists;
    }

    private static double[] countDistribution(Game game, Player cpuPlayer, int face) {
        int known = 0;
        for (int d : cpuPlayer.getDice()) {
            if (d == face || (face <= 5 && d == STAR)) {
                known++;
            }
        }
        double p = face == STAR ? 1.0 / 6 : 2.0 / 6;
        int totalDice = totalSurvivorDiceCount(game);
        double[] dist = new double[known + 1];
        dist[known] = 1.0;
        for (Player other : game.getPlayers()) {
            if (other == cpuPlayer || other.isEliminated()) {
                continue;
            }
            int n = other.getDice().size();
            Integer claimed = latestClaimThisRound(game, other, face);
            double[] own = claimed == null
                    ? binomial(n, p)
                    : handDistributionGivenClaim(n, totalDice - n, p, claimed);
            dist = convolve(dist, own);
        }
        return dist;
    }

    private static double[] handDistributionGivenClaim(int handDice, int otherDice, double p, int claimed) {
        double[] prior = binomial(handDice, p);
        double[] rest = binomial(otherDice, p);
        double[] posterior = new double[handDice + 1];
        double sum = 0;
        for (int k = 0; k <= handDice; k++) {
            double plausible = 0;
            for (int r = Math.max(0, claimed - k); r < rest.length; r++) {
                plausible += rest[r];
            }
            posterior[k] = prior[k] * plausible;
            sum += posterior[k];
        }
        if (sum == 0) {
            return prior;
        }
        for (int k = 0; k <= handDice; k++) {
            posterior[k] /= sum;
        }
        return posterior;
    }

    private static Integer latestClaimThisRound(Game game, Player player, int face) {
        Integer claimed = null;
        for (TurnLogEntry e : game.getActionLog()) {
            if (e.getRound() == game.getCurrentRound()
                    && TurnLogEntry.TYPE_BID.equals(e.getType())
                    && player.getId().equals(e.getPlayerId())
                    && e.getFace() == face) {
                claimed = e.getQuantity();
            }
        }
        return claimed;
    }

    private static double[] convolve(double[] x, double[] y) {
        double[] out = new double[x.length + y.length - 1];
        for (int i = 0; i < x.length; i++) {
            for (int j = 0; j < y.length; j++) {
                out[i + j] += x[i] * y[j];
            }
        }
        return out;
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

    private static double expectedValueAsBidder(
            double[] dist, int q, int ownDice, int nextDice, double opponentWeight) {
        double value = 0;
        for (int a = 0; a < dist.length; a++) {
            if (a < q) {
                value -= dist[a] * Math.min(q - a, ownDice);
            } else if (a == q) {
                value += dist[a];
            } else {
                value += dist[a] * opponentWeight * Math.min(a - q, nextDice);
            }
        }
        return value;
    }

    private static double expectedValueAsChallenger(
            double[] dist, int q, int ownDice, int bidderDice, int opponents, double opponentWeight) {
        double value = 0;
        for (int a = 0; a < dist.length; a++) {
            if (a < q) {
                value += dist[a] * opponentWeight * Math.min(q - a, bidderDice);
            } else if (a == q) {
                value += dist[a] * (opponentWeight * (opponents - 1) - 1);
            } else {
                value -= dist[a] * Math.min(a - q, ownDice);
            }
        }
        return value;
    }

    private static double challengeProbability(int totalDice, int q, int face) {
        double[] dist = binomial(totalDice, face == STAR ? 1.0 / 6 : 2.0 / 6);
        double below = 0;
        for (int a = 0; a < q && a < dist.length; a++) {
            below += dist[a];
        }
        return below;
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

    private static int survivorCount(Game game) {
        int n = 0;
        for (Player p : game.getPlayers()) {
            if (!p.isEliminated()) {
                n++;
            }
        }
        return n;
    }

    private static Player nextSurvivor(Game game, Player cpuPlayer) {
        List<Player> players = game.getPlayers();
        int self = players.indexOf(cpuPlayer);
        for (int step = 1; step < players.size(); step++) {
            Player p = players.get((self + step) % players.size());
            if (!p.isEliminated()) {
                return p;
            }
        }
        return cpuPlayer;
    }

    private static int bidderDiceCount(Game game, Bid current) {
        for (Player p : game.getPlayers()) {
            if (p.getId().equals(current.getPlayerId())) {
                return p.getDice().size();
            }
        }
        return 0;
    }
}
