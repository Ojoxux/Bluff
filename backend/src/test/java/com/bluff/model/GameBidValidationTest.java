package com.bluff.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameBidValidationTest {

    @Test
    void bid_rejectsQuantityAboveTotalSurvivorDiceCount() {
        Game game = newPlayingGame();

        assertThatThrownBy(() -> game.bid("h1", 11, 6)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void followUpBid_rejectsQuantityAboveTotalSurvivorDiceCount() {
        Game game = newPlayingGame();
        game.bid("h1", 2, 6);

        assertThatThrownBy(() -> game.bid("p2", 11, 6)).isInstanceOf(IllegalStateException.class);
    }

    private static Game newPlayingGame() {
        Game game = new Game("g1", "h1");
        Player host = new Player("h1", "Host", false);
        host.setDice(List.of(1, 1, 1, 1, 1));
        Player second = new Player("p2", "Bob", false);
        second.setDice(List.of(2, 2, 2, 2, 2));
        game.getPlayers().add(host);
        game.getPlayers().add(second);
        game.setState(GameState.PLAYING);
        game.setCurrentPlayerIndex(0);
        return game;
    }
}
