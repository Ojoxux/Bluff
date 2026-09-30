package com.bluff.dto;

import java.util.List;

public record RevealedHandResponse(String playerId, String playerName, List<Integer> dice) {}
