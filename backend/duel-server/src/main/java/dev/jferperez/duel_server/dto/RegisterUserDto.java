package dev.jferperez.duel_server.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Contains data to create new user
 */
@Getter
@Setter
public class RegisterUserDto {
    private String email;
    private String password;
    private String username;
}
