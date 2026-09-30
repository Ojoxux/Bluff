package com.bluff.controller;

import com.bluff.DeterministicGameServiceTestConfig;
import com.bluff.model.Bid;
import com.bluff.model.Game;
import com.bluff.repository.GameRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DeterministicGameServiceTestConfig.class)
class GameFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GameRepository gameRepository;

    @Test
    void happyPath_createStart_get_bid_cpuAdvances() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());

        JsonNode before = getGame(cg.gameId(), cg.humanPlayerId());
        assertThat(before.get("state").asText()).isEqualTo("PLAYING");
        assertThat(before.get("currentPlayer").asText()).isEqualTo(cg.humanPlayerId());
        assertThat(before.get("currentBid")).isNotNull();
        assertThat(before.get("currentBid").isNull()).isFalse();

        JsonNode bidNode = before.get("currentBid");
        int[] legal = nextLegalBid(bidNode, cg.humanPlayerId());
        performBid(cg.gameId(), cg.humanPlayerId(), legal[0], legal[1]);

        JsonNode after = getGame(cg.gameId(), cg.humanPlayerId());
        JsonNode log = after.get("actionLog");
        int humanBidIndex = -1;
        for (int i = 0; i < log.size(); i++) {
            JsonNode e = log.get(i);
            if ("BID".equals(e.get("type").asText()) && cg.humanPlayerId().equals(e.get("playerId").asText())) {
                humanBidIndex = i;
            }
        }
        assertThat(humanBidIndex).isGreaterThanOrEqualTo(0).isLessThan(log.size() - 1);
        assertThat(log.get(humanBidIndex + 1).get("playerId").asText()).isNotEqualTo(cg.humanPlayerId());
    }

    @Test
    void happyPath_challenge_changesDiceOrResetsBid() throws Exception {
        CreatedGame cg = createGame(2);
        start(cg.gameId(), cg.humanPlayerId());

        JsonNode before = getGame(cg.gameId(), cg.humanPlayerId());
        int diceBefore = sumDiceCounts(before);
        assertThat(before.get("currentBid")).isNotNull();
        assertThat(before.get("currentBid").isNull()).isFalse();

        performChallenge(cg.gameId(), cg.humanPlayerId());

        JsonNode after = getGame(cg.gameId(), cg.humanPlayerId());
        int diceAfter = sumDiceCounts(after);
        boolean bidCleared = after.get("currentBid") == null || after.get("currentBid").isNull();
        boolean diceChanged = diceBefore != diceAfter;
        boolean finished = "FINISHED".equals(after.get("state").asText());
        assertThat(bidCleared || diceChanged || finished).isTrue();
    }

    @Test
    void error_unknownGame_returns404() throws Exception {
        mockMvc.perform(get("/games/not-a-real-id")).andExpect(status().isNotFound());
    }

    @Test
    void error_cpuCountZero_returns400() throws Exception {
        mockMvc.perform(
                        post("/games")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"cpuCount\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void error_cpuCountSix_returns400() throws Exception {
        mockMvc.perform(
                        post("/games")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"cpuCount\":6}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void error_nonHostStart_returns409() throws Exception {
        CreatedGame cg = createGame(1);
        mockMvc.perform(
                        post("/games/" + cg.gameId() + "/start")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"playerId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void error_actionWhileWaiting_returns409() throws Exception {
        CreatedGame cg = createGame(1);
        mockMvc.perform(
                        post("/games/" + cg.gameId() + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content(
                                        "{\"type\":\"BID\",\"playerId\":\""
                                                + cg.humanPlayerId()
                                                + "\",\"quantity\":1,\"face\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void error_wrongPlayerAction_returns409() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());
        JsonNode g = getGame(cg.gameId(), cg.humanPlayerId());
        String cpuId = firstCpuPlayerId(g);
        assertThat(cpuId).isNotNull();

        JsonNode bidNode = g.get("currentBid");
        int[] legal = nextLegalBid(bidNode, cg.humanPlayerId());
        mockMvc.perform(
                        post("/games/" + cg.gameId() + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content(
                                        "{\"type\":\"BID\",\"playerId\":\""
                                                + cpuId
                                                + "\",\"quantity\":"
                                                + legal[0]
                                                + ",\"face\":"
                                                + legal[1]
                                                + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void error_challengeWithoutBid_returns409() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());

        Game game = gameRepository.findById(cg.gameId());
        int humanIndex = -1;
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (cg.humanPlayerId().equals(game.getPlayers().get(i).getId())) {
                humanIndex = i;
                break;
            }
        }
        assertThat(humanIndex).isGreaterThanOrEqualTo(0);
        game.setCurrentPlayerIndex(humanIndex);
        game.setCurrentBid(null);
        game.setLastBidPlayerId(null);
        gameRepository.save(game);

        mockMvc.perform(
                        post("/games/" + cg.gameId() + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content(
                                        "{\"type\":\"CHALLENGE\",\"playerId\":\""
                                                + cg.humanPlayerId()
                                                + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void error_illegalBid_returns409() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());
        JsonNode g = getGame(cg.gameId(), cg.humanPlayerId());
        JsonNode bidNode = g.get("currentBid");
        int q = bidNode.get("quantity").asInt();
        int f = bidNode.get("face").asInt();
        int badQ = Math.max(1, q - 1);
        mockMvc.perform(
                        post("/games/" + cg.gameId() + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content(
                                        "{\"type\":\"BID\",\"playerId\":\""
                                                + cg.humanPlayerId()
                                                + "\",\"quantity\":"
                                                + badQ
                                                + ",\"face\":"
                                                + f
                                                + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void list_containsGameAfterCreate() throws Exception {
        CreatedGame cg = createGame(2);
        MvcResult res = mockMvc.perform(get("/games")).andExpect(status().isOk()).andReturn();
        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode e : arr) {
            if (cg.gameId().equals(e.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void finished_gameStillGettableWithActionLogAndListed() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());

        boolean finished = false;
        for (int iter = 0; iter < 800; iter++) {
            MvcResult getRes =
                    mockMvc.perform(get("/games/" + cg.gameId()).param("viewerPlayerId", cg.humanPlayerId()))
                            .andExpect(status().isOk())
                            .andReturn();
            JsonNode g = objectMapper.readTree(getRes.getResponse().getContentAsString());
            assertThat(g.has("actionLog")).isTrue();
            assertThat(g.get("actionLog").isArray()).isTrue();

            if ("FINISHED".equals(g.get("state").asText())) {
                assertThat(g.get("actionLog").size()).isGreaterThan(0);
                mockMvc.perform(get("/games/" + cg.gameId()).param("viewerPlayerId", cg.humanPlayerId()))
                        .andExpect(status().isOk());

                MvcResult listRes = mockMvc.perform(get("/games")).andExpect(status().isOk()).andReturn();
                JsonNode arr = objectMapper.readTree(listRes.getResponse().getContentAsString());
                boolean found = false;
                for (JsonNode e : arr) {
                    if (cg.gameId().equals(e.get("id").asText())) {
                        found = true;
                        assertThat(e.get("state").asText()).isEqualTo("FINISHED");
                        break;
                    }
                }
                assertThat(found).isTrue();
                finished = true;
                break;
            }

            assertThat(g.get("currentPlayer").asText()).isEqualTo(cg.humanPlayerId());

            JsonNode cb = g.get("currentBid");
            if (cb == null || cb.isNull()) {
                performBid(cg.gameId(), cg.humanPlayerId(), 1, 1);
            } else if (iter % 3 == 0) {
                performChallenge(cg.gameId(), cg.humanPlayerId());
            } else {
                int[] legal = nextLegalBid(cb, cg.humanPlayerId());
                performBid(cg.gameId(), cg.humanPlayerId(), legal[0], legal[1]);
            }
        }
        assertThat(finished).as("game should finish within iteration budget").isTrue();
    }

    private CreatedGame createGame(int cpuCount) throws Exception {
        MvcResult res =
                mockMvc.perform(
                                post("/games")
                                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                                        .content("{\"name\":\"flow\",\"cpuCount\":" + cpuCount + "}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode n = objectMapper.readTree(res.getResponse().getContentAsString());
        return new CreatedGame(n.get("gameId").asText(), n.get("playerId").asText());
    }

    private void start(String gameId, String humanPlayerId) throws Exception {
        mockMvc.perform(
                        post("/games/" + gameId + "/start")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"playerId\":\"" + humanPlayerId + "\"}"))
                .andExpect(status().isNoContent());
    }

    private JsonNode getGame(String gameId, String viewerPlayerId) throws Exception {
        MvcResult res =
                mockMvc.perform(get("/games/" + gameId).param("viewerPlayerId", viewerPlayerId))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void performBid(String gameId, String playerId, int quantity, int face) throws Exception {
        mockMvc.perform(
                        post("/games/" + gameId + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content(
                                        "{\"type\":\"BID\",\"playerId\":\""
                                                + playerId
                                                + "\",\"quantity\":"
                                                + quantity
                                                + ",\"face\":"
                                                + face
                                                + "}"))
                .andExpect(status().isNoContent());
    }

    private void performChallenge(String gameId, String playerId) throws Exception {
        mockMvc.perform(
                        post("/games/" + gameId + "/action")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"type\":\"CHALLENGE\",\"playerId\":\"" + playerId + "\"}"))
                .andExpect(status().isNoContent());
    }

    private static int[] nextLegalBid(JsonNode currentBid, String humanPlayerId) {
        int q = currentBid.get("quantity").asInt();
        int f = currentBid.get("face").asInt();
        String prevPid = currentBid.get("playerId").asText();
        Bid prev = new Bid(q, f, prevPid);
        for (int nq = 1; nq <= q + 60; nq++) {
            for (int nf = 1; nf <= 6; nf++) {
                if (Game.isValidBidAfter(prev, new Bid(nq, nf, humanPlayerId))) {
                    return new int[] {nq, nf};
                }
            }
        }
        throw new IllegalStateException("no legal bid found");
    }

    private static int sumDiceCounts(JsonNode game) {
        int s = 0;
        for (JsonNode p : game.get("players")) {
            s += p.get("diceCount").asInt();
        }
        return s;
    }

    private static String firstCpuPlayerId(JsonNode game) {
        for (JsonNode p : game.get("players")) {
            if (p.get("cpu").asBoolean()) {
                return p.get("id").asText();
            }
        }
        return null;
    }

    private record CreatedGame(String gameId, String humanPlayerId) {}
}
