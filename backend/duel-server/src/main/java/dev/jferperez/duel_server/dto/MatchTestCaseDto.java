package dev.jferperez.duel_server.dto;

/**
 * DTO for sending local test-case updates to websocket.
 */
public class MatchTestCaseDto {
    private String username;
    private String matchId;
    private Integer testsPassed; // All local tests will be out of 3, such that a val of 3 == "All Tests Passed"
}
