package com.bluff.controller;

import com.bluff.dto.ActionRequest;
import com.bluff.dto.BidResponse;
import com.bluff.dto.CreateGameRequest;
import com.bluff.dto.CreateGameResponse;
import com.bluff.dto.GameResponse;
import com.bluff.dto.GameSummaryResponse;
import com.bluff.dto.JoinRequest;
import com.bluff.dto.JoinResponse;
import com.bluff.dto.PlayerResponse;
import com.bluff.dto.RevealedHandResponse;
import com.bluff.dto.StartRequest;
import com.bluff.dto.TurnLogEntryResponse;
import com.bluff.model.Action;
import com.bluff.model.RevealedHand;
import com.bluff.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<CreateGameResponse> createGame(@RequestBody CreateGameRequest request) {
        if (request.cpuCount() == null) {
            throw new IllegalArgumentException("cpuCount は必須です");
        }
        GameService.CreateGameResult result = gameService.createGame(request.name(), request.cpuCount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateGameResponse(result.gameId(), result.playerId()));
    }

    @PostMapping("/{gameId}/join")
    public ResponseEntity<JoinResponse> join(@PathVariable String gameId, @RequestBody JoinRequest request) {
        String playerId = gameService.joinGame(gameId, request.name());
        return ResponseEntity.ok(new JoinResponse(playerId));
    }

    @PostMapping("/{gameId}/start")
    public ResponseEntity<Void> start(@PathVariable String gameId, @RequestBody StartRequest request) {
        gameService.startGame(gameId, request.playerId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{gameId}/action")
    public ResponseEntity<Void> action(@PathVariable String gameId, @RequestBody ActionRequest request) {
        Action action =
                new Action(request.type(), request.playerId(), request.quantity(), request.face());
        gameService.performAction(gameId, action);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<GameResponse> getGame(
            @PathVariable String gameId, @RequestParam(required = false) String viewerPlayerId) {
        GameService.GameDetailView view = gameService.getGame(gameId, viewerPlayerId);
        return ResponseEntity.ok(toGameResponse(view));
    }

    @GetMapping
    public ResponseEntity<List<GameSummaryResponse>> listGames() {
        List<GameSummaryResponse> list = new ArrayList<>();
        for (GameService.GameListItem item : gameService.listGames()) {
            list.add(new GameSummaryResponse(item.id(), item.state(), item.playerCount()));
        }
        return ResponseEntity.ok(list);
    }

    private static GameResponse toGameResponse(GameService.GameDetailView v) {
        List<PlayerResponse> players = new ArrayList<>();
        for (GameService.PlayerSummary p : v.players()) {
            players.add(
                    new PlayerResponse(p.id(), p.name(), p.cpu(), p.diceCount(), p.eliminated()));
        }
        BidResponse bid = null;
        GameService.BidSnapshot snap = v.currentBid();
        if (snap != null) {
            bid = new BidResponse(snap.quantity(), snap.face(), snap.playerId());
        }
        List<TurnLogEntryResponse> actionLog = new ArrayList<>();
        for (GameService.TurnLogSnapshot e : v.actionLog()) {
            actionLog.add(
                    new TurnLogEntryResponse(
                            e.round(),
                            e.playerId(),
                            e.playerName(),
                            e.type(),
                            e.quantity(),
                            e.face(),
                            e.actualCount(),
                            e.challengeResult(),
                            e.penaltyDescription(),
                            revealedHands(e)));
        }
        return new GameResponse(
                v.id(),
                v.state(),
                players,
                v.currentPlayerId(),
                bid,
                v.hostPlayerId(),
                v.winnerPlayerId(),
                v.myDice(),
                actionLog);
    }

    private static List<RevealedHandResponse> revealedHands(GameService.TurnLogSnapshot e) {
        if (e.revealedHands() == null) {
            return null;
        }
        List<RevealedHandResponse> hands = new ArrayList<>();
        for (RevealedHand h : e.revealedHands()) {
            hands.add(new RevealedHandResponse(h.playerId(), h.playerName(), h.dice()));
        }
        return hands;
    }
}
