package com.bluff.model;

import java.util.List;

public class TurnLogEntry {
    public static final String TYPE_BID = "BID";
    public static final String TYPE_CHALLENGE = "CHALLENGE";
    public static final String TYPE_ROUND_START = "ROUND_START";

    private final int round;
    private final String playerId;
    private final String playerName;
    private final String type;
    private final Integer quantity;
    private final Integer face;
    private final Integer actualCount;
    private final String challengeResult;
    private final String penaltyDescription;
    private final List<RevealedHand> revealedHands;

    private TurnLogEntry(
            int round,
            String playerId,
            String playerName,
            String type,
            Integer quantity,
            Integer face,
            Integer actualCount,
            String challengeResult,
            String penaltyDescription,
            List<RevealedHand> revealedHands) {
        this.round = round;
        this.playerId = playerId;
        this.playerName = playerName;
        this.type = type;
        this.quantity = quantity;
        this.face = face;
        this.actualCount = actualCount;
        this.challengeResult = challengeResult;
        this.penaltyDescription = penaltyDescription;
        this.revealedHands = revealedHands;
    }

    public static TurnLogEntry bid(int round, String playerId, String playerName, int quantity, int face) {
        return new TurnLogEntry(round, playerId, playerName, TYPE_BID, quantity, face, null, null, null, null);
    }

    public static TurnLogEntry challenge(
            int round,
            String playerId,
            String playerName,
            int actualCount,
            String challengeResult,
            String penaltyDescription,
            List<RevealedHand> revealedHands) {
        return new TurnLogEntry(
                round,
                playerId,
                playerName,
                TYPE_CHALLENGE,
                null,
                null,
                actualCount,
                challengeResult,
                penaltyDescription,
                List.copyOf(revealedHands));
    }

    public static TurnLogEntry roundStart(int round, String playerId, String playerName) {
        return new TurnLogEntry(round, playerId, playerName, TYPE_ROUND_START, null, null, null, null, null, null);
    }

    public int getRound() {
        return round;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getType() {
        return type;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getFace() {
        return face;
    }

    public Integer getActualCount() {
        return actualCount;
    }

    public String getChallengeResult() {
        return challengeResult;
    }

    public String getPenaltyDescription() {
        return penaltyDescription;
    }

    public List<RevealedHand> getRevealedHands() {
        return revealedHands;
    }
}
