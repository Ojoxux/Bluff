package com.bluff.model;

import java.util.List;

public record RevealedHand(String playerId, String playerName, List<Integer> dice) {}
