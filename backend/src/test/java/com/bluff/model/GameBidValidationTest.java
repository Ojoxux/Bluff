package com.bluff.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void isValidBidAfter_followsReferenceExamplesForNumberFaces() {
        Bid prev = new Bid(9, 3, "h1");

        assertThat(Game.isValidBidAfter(prev, new Bid(9, 4, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(prev, new Bid(10, 2, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(prev, new Bid(10, 3, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(prev, new Bid(9, 3, "p2"))).isFalse();
        assertThat(Game.isValidBidAfter(prev, new Bid(9, 2, "p2"))).isFalse();
        assertThat(Game.isValidBidAfter(prev, new Bid(8, 5, "p2"))).isFalse();
    }

    @Test
    void isValidBidAfter_followsReferenceExamplesForStar() {
        assertThat(Game.isValidBidAfter(new Bid(9, 3, "h1"), new Bid(5, 6, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(new Bid(9, 3, "h1"), new Bid(4, 6, "p2"))).isFalse();
        assertThat(Game.isValidBidAfter(new Bid(2, 6, "h1"), new Bid(3, 6, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(new Bid(2, 6, "h1"), new Bid(4, 2, "p2"))).isTrue();
        assertThat(Game.isValidBidAfter(new Bid(2, 6, "h1"), new Bid(3, 5, "p2"))).isFalse();
    }

    @Test
    void bid_acceptsLowerFaceWithHigherQuantity() {
        Game game = newPlayingGame();
        game.bid("h1", 3, 5);

        game.bid("p2", 4, 1);

        assertThat(game.getCurrentBid().getFace()).isEqualTo(1);
        assertThat(game.getCurrentBid().getQuantity()).isEqualTo(4);
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
