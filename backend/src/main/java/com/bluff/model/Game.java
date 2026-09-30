package com.bluff.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class Game {
    private String id;
    private GameState state;
    private List<Player> players;
    private int currentPlayerIndex;
    private Bid currentBid;
    private String lastBidPlayerId;
    private String hostPlayerId;
    private String pendingOpeningPlayerId;
    private List<TurnLogEntry> actionLog;
    private int currentRound;
    private final Random random;

    public Game(String id, String hostPlayerId) {
        this(id, hostPlayerId, new Random());
    }

    public Game(String id, String hostPlayerId, Random random) {
        this.id = id;
        this.state = GameState.WAITING;
        this.players = new ArrayList<>();
        this.currentPlayerIndex = 0;
        this.currentBid = null;
        this.lastBidPlayerId = null;
        this.hostPlayerId = hostPlayerId;
        this.pendingOpeningPlayerId = null;
        this.actionLog = new ArrayList<>();
        this.currentRound = 0;
        this.random = random;
    }

    public String getId() {
        return id;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public int getCurrentPlayerIndex() {
        return currentPlayerIndex;
    }

    public void setCurrentPlayerIndex(int currentPlayerIndex) {
        this.currentPlayerIndex = currentPlayerIndex;
    }

    public Bid getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(Bid currentBid) {
        this.currentBid = currentBid;
    }

    public String getLastBidPlayerId() {
        return lastBidPlayerId;
    }

    public void setLastBidPlayerId(String lastBidPlayerId) {
        this.lastBidPlayerId = lastBidPlayerId;
    }

    public String getHostPlayerId() {
        return hostPlayerId;
    }

    public String getPendingOpeningPlayerId() {
        return pendingOpeningPlayerId;
    }

    public void setPendingOpeningPlayerId(String pendingOpeningPlayerId) {
        this.pendingOpeningPlayerId = pendingOpeningPlayerId;
    }

    public List<TurnLogEntry> getActionLog() {
        return actionLog;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    // ゲーム参加
    //   1. 現在の状態がwaiting
    //   2. プレイヤー数が6人未満であること(バリデーション)
    // それ以外は例外を投げる
    public void join(String playerId, String name) {
        if (state != GameState.WAITING) {
            throw new IllegalStateException("ゲームは待機中ではありません");
        }
        if (players.size() >= 6) {
            throw new IllegalStateException("プレイヤーが6人を超えています");
        }
        players.add(new Player(playerId, name, false));
    }

    // ゲームスタート
    //   1. プレイヤーがホストであること
    //   2. プレイヤーが2人以上いること
    //   3. 現在の状態がwaitingであること
    //   それ以外なら例外を投げる
    public void start(String playerId) {
        if (!Objects.equals(playerId, hostPlayerId)) {
            throw new IllegalStateException("プレイヤーIDがホストと一致しません");
        }
        if (players.size() < 2) {
            throw new IllegalStateException("プレイヤー数が2人未満です");
        }
        if (state != GameState.WAITING) {
            throw new IllegalStateException("ゲームは待機中ではありません");
        }

        state = GameState.PLAYING;

        for (Player player : players) {
            List<Integer> playerDices = new ArrayList<Integer>();
            for (int i = 0;i < 5;i++){
                int randomValue = random.nextInt(6) + 1;
                playerDices.add(randomValue);   
            }
            player.setDice(playerDices);
        }
        setCurrentPlayerIndex(0);
        setCurrentBid(null);
        setLastBidPlayerId(null);
        setPendingOpeningPlayerId(null);
        actionLog.clear();
        currentRound = 1;
    }

    public void bid(String playerId, int quantity, int face) {
        if (state != GameState.PLAYING) {
            throw new IllegalStateException("ゲームは進行中ではありません");
        }
        int idx = getCurrentPlayerIndex();
        if (!players.get(idx).getId().equals(playerId)) {
            throw new IllegalStateException("今の番の人が違います");
        }
        if (!isBidWithinTable(quantity, face)) {
            throw new IllegalStateException("無効なBidです");
        }
        Bid nextBid = new Bid(quantity, face, playerId);
        if (currentBid != null && !isValidBidAfter(currentBid, nextBid)) {
            throw new IllegalStateException("前の宣言より強い宣言ではありません。");
        }
        setCurrentBid(nextBid);
        setLastBidPlayerId(playerId);
        Player actor = players.get(idx);
        actionLog.add(TurnLogEntry.bid(currentRound, playerId, actor.getName(), quantity, face));
        nextTurn();
    }

    /**
     * 直前のBidに続けて許される次のBidか
     */
    public static boolean isValidBidAfter(Bid prev, Bid next) {
        int qP = prev.getQuantity();
        int fP = prev.getFace();
        int qN = next.getQuantity();
        int fN = next.getFace();
        if (qN < 1 || fN < 1 || fN > 6) {
            return false;
        }
        if (fP >= 1 && fP <= 5 && fN >= 1 && fN <= 5) {
            return qN > qP || (fN > fP && qN == qP);
        }
        if (fP >= 1 && fP <= 5 && fN == 6) {
            return qN * 2 > qP;
        }
        if (fP == 6 && fN >= 1 && fN <= 5) {
            return qN >= 2 * qP;
        }
        if (fP == 6 && fN == 6) {
            return qN > qP;
        }
        return false;
    }

    private boolean isBidWithinTable(int quantity, int face) {
        return quantity >= 1 && face >= 1 && face <= 6 && quantity <= totalSurvivorDiceCount();
    }

    private void nextTurn() {
        int n = players.size();
        if (n == 0) {
            return;
        }
        int idx = currentPlayerIndex;
        for (int step = 0; step < n; step++) {
            idx = (idx + 1) % n;
            if (!players.get(idx).isEliminated()) {
                currentPlayerIndex = idx;
                return;
            }
        }
    }

    public void challenge(String playerId) {
        if (state != GameState.PLAYING) {
            throw new IllegalStateException("ゲームは進行中ではありません");
        }
        int idx = getCurrentPlayerIndex();
        if (!players.get(idx).getId().equals(playerId)) {
            throw new IllegalStateException("今の番の人が違います");
        }
        if (currentBid == null) {
            throw new IllegalStateException("宣言がありません");
        }
        int q = currentBid.getQuantity();
        int f = currentBid.getFace();
        String bidderId = lastBidPlayerId != null ? lastBidPlayerId : currentBid.getPlayerId();
        Player bidder = requirePlayer(bidderId);
        int a = countActualQuantity(f);

        if (a < q) {
            removeDice(bidder, q - a);
        } else if (a > q) {
            removeDice(requirePlayer(playerId), a - q);
        } else {
            for (Player p : players) {
                if (!p.isEliminated() && !p.getId().equals(bidderId)) {
                    removeDice(p, 1);
                }
            }
        }

        if (countSurvivors() > 1) {
            String preferredOpener;
            if (a < q) {
                preferredOpener = bidderId;
            } else if (a > q) {
                preferredOpener = playerId;
            } else {
                preferredOpener = bidderId;
            }
            pendingOpeningPlayerId = resolveOpeningPlayerId(preferredOpener);
        } else {
            pendingOpeningPlayerId = null;
        }

        Player challenger = requirePlayer(playerId);
        String challengeResult;
        String penaltyDescription;
        if (a < q) {
            challengeResult = "BIDDER_LOSES";
            penaltyDescription = bidder.getName() + " がダイス" + (q - a) + "個失う";
        } else if (a > q) {
            challengeResult = "CHALLENGER_LOSES";
            penaltyDescription = challenger.getName() + " がダイス" + (a - q) + "個失う";
        } else {
            challengeResult = "EXACT_MATCH";
            penaltyDescription = "宣言者以外の全員がダイス1個ずつ失う";
        }
        actionLog.add(
                TurnLogEntry.challenge(currentRound, playerId, challenger.getName(), a, challengeResult, penaltyDescription));

        resolveRound();
    }

    /**
     * Challenge 直後のラウンド片付け
     */
    private void resolveRound() {
        int survivors = countSurvivors();
        if (survivors <= 1) {
            state = GameState.FINISHED;
            pendingOpeningPlayerId = null;
            currentBid = null;
            lastBidPlayerId = null;
            return;
        }

        for (Player p : players) {
            if (p.isEliminated()) {
                continue;
            }
            int count = p.getDice().size();
            List<Integer> rolled = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rolled.add(random.nextInt(6) + 1);
            }
            p.setDice(rolled);
        }

        currentBid = null;
        lastBidPlayerId = null;
        state = GameState.PLAYING;

        int openerIdx = indexOfPlayerId(pendingOpeningPlayerId);
        if (openerIdx < 0 || players.get(openerIdx).isEliminated()) {
            openerIdx = indexOfFirstAlive();
        }
        if (openerIdx < 0) {
            state = GameState.FINISHED;
            pendingOpeningPlayerId = null;
            return;
        }
        currentPlayerIndex = openerIdx;
        pendingOpeningPlayerId = null;

        currentRound++;
        Player opener = players.get(currentPlayerIndex);
        actionLog.add(TurnLogEntry.roundStart(currentRound, opener.getId(), opener.getName()));
    }

    private void removeDice(Player p, int n) {
        if (n <= 0) {
            return;
        }
        List<Integer> dice = p.getDice();
        int remove = Math.min(n, dice.size());
        for (int i = 0; i < remove; i++) {
            dice.remove(dice.size() - 1);
        }
        if (dice.isEmpty()) {
            p.setEliminated(true);
        }
    }

    /** 生存プレイヤー全員のダイスについて、宣言faceに対する実際の個数A */
    private int countActualQuantity(int face) {
        int total = 0;
        for (Player p : players) {
            if (p.isEliminated()) {
                continue;
            }
            for (int d : p.getDice()) {
                if (face >= 1 && face <= 5) {
                    if (d == face || d == 6) {
                        total++;
                    }
                } else if (face == 6) {
                    if (d == 6) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private String resolveOpeningPlayerId(String preferredId) {
        int idx = indexOfPlayerId(preferredId);
        if (idx >= 0 && !players.get(idx).isEliminated()) {
            return preferredId;
        }
        int n = players.size();
        if (idx < 0) {
            int first = indexOfFirstAlive();
            if (first < 0) {
                throw new IllegalStateException("生存者がいません");
            }
            return players.get(first).getId();
        }
        for (int step = 1; step <= n; step++) {
            int i = (idx + step) % n;
            if (!players.get(i).isEliminated()) {
                return players.get(i).getId();
            }
        }
        int first = indexOfFirstAlive();
        if (first < 0) {
            throw new IllegalStateException("生存者がいません");
        }
        return players.get(first).getId();
    }

    private int indexOfPlayerId(String playerId) {
        if (playerId == null) {
            return -1;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getId().equals(playerId)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfFirstAlive() {
        for (int i = 0; i < players.size(); i++) {
            if (!players.get(i).isEliminated()) {
                return i;
            }
        }
        return -1;
    }

    private int countSurvivors() {
        int c = 0;
        for (Player p : players) {
            if (!p.isEliminated()) {
                c++;
            }
        }
        return c;
    }

    private int totalSurvivorDiceCount() {
        int total = 0;
        for (Player p : players) {
            if (!p.isEliminated()) {
                total += p.getDice().size();
            }
        }
        return total;
    }

    private Player requirePlayer(String playerId) {
        int i = indexOfPlayerId(playerId);
        if (i < 0) {
            throw new IllegalStateException("プレイヤーが見つかりません");
        }
        return players.get(i);
    }
}
