package dev.jferperez.duel_server.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for sending chats from client to server.
 */
@Getter
@Setter
public class MatchChatDto {
    private String username;
    private String message;
    private String matchID;
}
