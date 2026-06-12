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

    private final Random random;

    public CpuStrategy(Random random) {
        this.random = random;
    }

    public Decision decideAction(Game game, Player cpuPlayer) {
        if (!cpuPlayer.isCpu()) {
            throw new IllegalArgumentException("cpuPlayer は CPU である必要があります");
        }
        Bid current = game.getCurrentBid();
        if (current != null && shouldChallenge(game, current)) {
            return new Decision.Challenge();
        }
        if (current == null) {
            return openingDecision(cpuPlayer);
        }
        List<Bid> legal = collectLegalBids(game, cpuPlayer, current);
        if (legal.isEmpty()) {
            return new Decision.Challenge();
        }
        List<Bid> conservative = smallestQuantityBids(legal);
        Bid pick = conservative.get(random.nextInt(conservative.size()));
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

    private boolean shouldChallenge(Game game, Bid currentBid) {
        return currentBid.getQuantity() > totalSurvivorDiceCount(game);
    }

    private Decision openingDecision(Player cpuPlayer) {
        int[] freq = new int[7];
        for (int d : cpuPlayer.getDice()) {
            if (d >= 1 && d <= 6) {
                freq[d]++;
            }
        }
        int max = 0;
        for (int f = 1; f <= 6; f++) {
            max = Math.max(max, freq[f]);
        }
        if (max == 0) {
            return new Decision.Bid(1, 1);
        }
        List<Integer> bestFaces = new ArrayList<>();
        for (int f = 1; f <= 6; f++) {
            if (freq[f] == max) {
                bestFaces.add(f);
            }
        }
        int face = bestFaces.get(random.nextInt(bestFaces.size()));
        return new Decision.Bid(max, face);
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

    private static List<Bid> smallestQuantityBids(List<Bid> legal) {
        int minQ = Integer.MAX_VALUE;
        for (Bid bid : legal) {
            minQ = Math.min(minQ, bid.getQuantity());
        }
        List<Bid> out = new ArrayList<>();
        for (Bid bid : legal) {
            if (bid.getQuantity() == minQ) {
                out.add(bid);
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
