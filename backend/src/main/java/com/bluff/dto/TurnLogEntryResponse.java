package com.bluff.dto;

import java.util.List;

public record TurnLogEntryResponse(
        int round,
        String playerId,
        String playerName,
        String type,
        Integer quantity,
        Integer face,
        Integer actualCount,
        String challengeResult,
        String penaltyDescription,
        List<RevealedHandResponse> revealedHands) {}
