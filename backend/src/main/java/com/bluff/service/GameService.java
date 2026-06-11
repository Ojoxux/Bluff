package com.bluff.service;

import com.bluff.cpu.CpuStrategy;
import com.bluff.model.Action;
import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.model.GameState;
import com.bluff.model.Player;
import com.bluff.model.TurnLogEntry;
import com.bluff.repository.GameRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {

    private final GameRepository repository;
    private final CpuStrategy cpuStrategy;
    private final ConcurrentHashMap<String, Object> gameLocks = new ConcurrentHashMap<>();

    @Autowired
    public GameService(GameRepository repository) {
        this(repository, new Random());
    }

    public GameService(GameRepository repository, Random random) {
        this.repository = repository;
        this.cpuStrategy = new CpuStrategy(random);
    }

    public String joinGame(String gameId, String name) {
        Object lock = gameLocks.computeIfAbsent(gameId, k -> new Object());
        synchronized (lock) {
            Game game = requireGame(gameId);
            String playerId = UUID.randomUUID().toString();
            String displayName = (name == null || name.isBlank()) ? "player" : name.trim();
            game.join(playerId, displayName);
            repository.save(game);
            return playerId;
        }
    }

    public CreateGameResult createGame(String name, int cpuCount) {
        if (cpuCount < 1 || cpuCount > 5) {
            throw new IllegalArgumentException("cpuCount は 1 以上 5 以下の整数である必要があります");
        }
        String displayName = (name == null || name.isBlank()) ? "player" : name.trim();
        String gameId = UUID.randomUUID().toString();
        String humanPlayerId = UUID.randomUUID().toString();
        Game game = new Game(gameId, humanPlayerId);
        for (int i = 1; i <= cpuCount; i++) {
            game.getPlayers().add(new Player(UUID.randomUUID().toString(), "CPU " + i, true));
        }
        game.getPlayers().add(new Player(humanPlayerId, displayName, false));
        repository.save(game);
        return new CreateGameResult(gameId, humanPlayerId);
    }

    public void startGame(String gameId, String playerId) {
        Object lock = gameLocks.computeIfAbsent(gameId, k -> new Object());
        synchronized (lock) {
            Game game = requireGame(gameId);
            game.start(playerId);
            repository.save(game);
            runCpuUntilHumanOrFinished(game);
        }
    }

    public void performAction(String gameId, Action action) {
        Object lock = gameLocks.computeIfAbsent(gameId, k -> new Object());
        synchronized (lock) {
            Game game = requireGame(gameId);
            switch (action.getType()) {
                case JOIN, START -> throw new IllegalArgumentException("JOIN と START はこのエンドポイントでは受け付けません");
                case BID -> {
                    if (action.getQuantity() == null || action.getFace() == null) {
                        throw new IllegalArgumentException("BID には quantity と face が必要です");
                    }
                    game.bid(action.getPlayerId(), action.getQuantity(), action.getFace());
                }
                case CHALLENGE -> game.challenge(action.getPlayerId());
            }
            persistAfterMutation(game);
        }
    }

    public GameDetailView getGame(String gameId, String viewerPlayerId) {
        Object lock = gameLocks.computeIfAbsent(gameId, k -> new Object());
        synchronized (lock) {
            Game game = requireGame(gameId);
            return toDetailView(game, viewerPlayerId);
        }
    }

    public List<GameListItem> listGames() {
        List<GameListItem> out = new ArrayList<>();
        for (Game g : repository.findAll()) {
            out.add(new GameListItem(g.getId(), g.getState().name(), g.getPlayers().size()));
        }
        return out;
    }

    private void persistAfterMutation(Game game) {
        repository.save(game);
        runCpuUntilHumanOrFinished(game);
    }

    private void runCpuUntilHumanOrFinished(Game game) {
        while (game.getState() == GameState.PLAYING) {
            Player current = game.getPlayers().get(game.getCurrentPlayerIndex());
            if (!current.isCpu()) {
                repository.save(game);
                return;
            }
            cpuStrategy.executeTurn(game);
            repository.save(game);
        }
        if (game.getState() == GameState.FINISHED) {
            repository.save(game);
        }
    }

    private Game requireGame(String gameId) {
        Game game = repository.findById(gameId);
        if (game == null) {
            throw new GameNotFoundException(gameId);
        }
        return game;
    }

    private static GameDetailView toDetailView(Game game, String viewerPlayerId) {
        List<PlayerSummary> players = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            players.add(
                    new PlayerSummary(
                            p.getId(),
                            p.getName(),
                            p.isCpu(),
                            p.getDice().size(),
                            p.isEliminated()));
        }
        BidSnapshot bidSnap = null;
        Bid bid = game.getCurrentBid();
        if (bid != null) {
            bidSnap = new BidSnapshot(bid.getQuantity(), bid.getFace(), bid.getPlayerId());
        }
        String currentPlayerId = null;
        if (game.getState() == GameState.PLAYING && !game.getPlayers().isEmpty()) {
            currentPlayerId = game.getPlayers().get(game.getCurrentPlayerIndex()).getId();
        }
        List<Integer> myDice = null;
        if (viewerPlayerId != null) {
            for (Player p : game.getPlayers()) {
                if (p.getId().equals(viewerPlayerId)) {
                    myDice = List.copyOf(p.getDice());
                    break;
                }
            }
        }
        List<TurnLogSnapshot> actionLog = new ArrayList<>();
        for (TurnLogEntry e : game.getActionLog()) {
            actionLog.add(TurnLogSnapshot.from(e));
        }
        return new GameDetailView(
                game.getId(),
                game.getState().name(),
                players,
                currentPlayerId,
                bidSnap,
                game.getHostPlayerId(),
                winnerPlayerId(game),
                myDice,
                List.copyOf(actionLog));
    }

    private static String winnerPlayerId(Game game) {
        if (game.getState() != GameState.FINISHED) {
            return null;
        }
        for (Player p : game.getPlayers()) {
            if (!p.isEliminated()) {
                return p.getId();
            }
        }
        return null;
    }

    public record CreateGameResult(String gameId, String playerId) {}

    public record GameListItem(String id, String state, int playerCount) {}

    public record PlayerSummary(String id, String name, boolean cpu, int diceCount, boolean eliminated) {}

    public record BidSnapshot(Integer quantity, Integer face, String playerId) {}

    public record TurnLogSnapshot(
            int round,
            String playerId,
            String playerName,
            String type,
            Integer quantity,
            Integer face,
            Integer actualCount,
            String challengeResult,
            String penaltyDescription) {

        static TurnLogSnapshot from(TurnLogEntry e) {
            return new TurnLogSnapshot(
                    e.getRound(),
                    e.getPlayerId(),
                    e.getPlayerName(),
                    e.getType(),
                    e.getQuantity(),
                    e.getFace(),
                    e.getActualCount(),
                    e.getChallengeResult(),
                    e.getPenaltyDescription());
        }
    }

    public record GameDetailView(
            String id,
            String state,
            List<PlayerSummary> players,
            String currentPlayerId,
            BidSnapshot currentBid,
            String hostPlayerId,
            String winnerPlayerId,
            List<Integer> myDice,
            List<TurnLogSnapshot> actionLog) {}
}
