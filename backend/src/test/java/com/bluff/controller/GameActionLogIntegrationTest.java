package com.bluff.controller;

import com.bluff.DeterministicGameServiceTestConfig;
import com.bluff.model.Bid;
import com.bluff.model.Game;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DeterministicGameServiceTestConfig.class)
class GameActionLogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void actionLog_containsBidAfterStartAndHumanBid() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());
        JsonNode before = getGame(cg.gameId(), cg.humanPlayerId());
        assertThat(before.get("actionLog").isArray()).isTrue();
        int nBefore = before.get("actionLog").size();

        int[] legal = nextLegalBid(before.get("currentBid"), cg.humanPlayerId());
        performBid(cg.gameId(), cg.humanPlayerId(), legal[0], legal[1]);

        JsonNode after = getGame(cg.gameId(), cg.humanPlayerId());
        assertThat(after.get("actionLog").size()).isGreaterThan(nBefore);
        boolean humanBid = false;
        for (JsonNode e : after.get("actionLog")) {
            if ("BID".equals(e.get("type").asText()) && cg.humanPlayerId().equals(e.get("playerId").asText())) {
                humanBid = true;
                assertThat(e.get("quantity").isNumber()).isTrue();
                assertThat(e.get("face").isNumber()).isTrue();
                assertThat(e.get("actualCount").isNull()).isTrue();
                assertThat(e.get("challengeResult").isNull()).isTrue();
                assertThat(e.get("penaltyDescription").isNull()).isTrue();
            }
        }
        assertThat(humanBid).isTrue();
    }

    @Test
    void actionLog_containsCpuBidAfterCpuAutoPlay() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());
        JsonNode g = getGame(cg.gameId(), cg.humanPlayerId());
        String cpuId = firstCpuPlayerId(g);
        assertThat(cpuId).isNotNull();
        boolean cpuBid = false;
        for (JsonNode e : g.get("actionLog")) {
            if ("BID".equals(e.get("type").asText()) && cpuId.equals(e.get("playerId").asText())) {
                cpuBid = true;
                break;
            }
        }
        assertThat(cpuBid).isTrue();
    }

    @Test
    void actionLog_challengeEntry_hasResolutionFields() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());
        performChallenge(cg.gameId(), cg.humanPlayerId());
        JsonNode g = getGame(cg.gameId(), cg.humanPlayerId());
        JsonNode lastChallenge = null;
        for (JsonNode e : g.get("actionLog")) {
            if ("CHALLENGE".equals(e.get("type").asText())) {
                lastChallenge = e;
            }
        }
        assertThat(lastChallenge).isNotNull();
        assertThat(lastChallenge.get("quantity").isNull()).isTrue();
        assertThat(lastChallenge.get("face").isNull()).isTrue();
        assertThat(lastChallenge.get("actualCount").isNumber()).isTrue();
        assertThat(lastChallenge.get("challengeResult").asText()).isNotBlank();
        assertThat(lastChallenge.get("penaltyDescription").asText()).isNotBlank();
        JsonNode hands = lastChallenge.get("revealedHands");
        assertThat(hands.size()).isEqualTo(2);
        for (JsonNode hand : hands) {
            assertThat(hand.get("playerId").asText()).isNotBlank();
            assertThat(hand.get("playerName").asText()).isNotBlank();
            assertThat(hand.get("dice").size()).isEqualTo(5);
        }
    }

    @Test
    void actionLog_containsRoundStartWhenRoundAdvances() throws Exception {
        CreatedGame cg = createGame(2);
        start(cg.gameId(), cg.humanPlayerId());

        boolean sawRoundStart = false;
        for (int i = 0; i < 600; i++) {
            JsonNode g = getGame(cg.gameId(), cg.humanPlayerId());
            for (JsonNode e : g.get("actionLog")) {
                if ("ROUND_START".equals(e.get("type").asText())) {
                    sawRoundStart = true;
                    assertThat(e.get("round").asInt()).isGreaterThanOrEqualTo(2);
                    break;
                }
            }
            if (sawRoundStart) {
                break;
            }
            if ("FINISHED".equals(g.get("state").asText())) {
                break;
            }
            assertThat(g.get("currentPlayer").asText()).isEqualTo(cg.humanPlayerId());

            JsonNode cb = g.get("currentBid");
            if (cb == null || cb.isNull()) {
                performBid(cg.gameId(), cg.humanPlayerId(), 1, 1);
            } else if (i % 4 == 0) {
                performChallenge(cg.gameId(), cg.humanPlayerId());
            } else {
                int[] legal = nextLegalBid(cb, cg.humanPlayerId());
                performBid(cg.gameId(), cg.humanPlayerId(), legal[0], legal[1]);
            }
        }
        assertThat(sawRoundStart).as("expected a second round marker in actionLog").isTrue();
    }

    @Test
    void getGame_whenFinished_returns200WithActionLogArray() throws Exception {
        CreatedGame cg = createGame(1);
        start(cg.gameId(), cg.humanPlayerId());

        for (int iter = 0; iter < 900; iter++) {
            MvcResult getRes =
                    mockMvc.perform(get("/games/" + cg.gameId()).param("viewerPlayerId", cg.humanPlayerId()))
                            .andExpect(status().isOk())
                            .andReturn();
            JsonNode g = objectMapper.readTree(getRes.getResponse().getContentAsString());
            assertThat(g.has("actionLog")).isTrue();
            assertThat(g.get("actionLog").isArray()).isTrue();

            if ("FINISHED".equals(g.get("state").asText())) {
                mockMvc.perform(get("/games/" + cg.gameId()).param("viewerPlayerId", cg.humanPlayerId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.actionLog").isArray());
                return;
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
        throw new AssertionError("game did not finish");
    }

    private CreatedGame createGame(int cpuCount) throws Exception {
        MvcResult res =
                mockMvc.perform(
                                post("/games")
                                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                                        .content("{\"name\":\"logtest\",\"cpuCount\":" + cpuCount + "}"))
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
