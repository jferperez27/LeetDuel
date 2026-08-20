package dev.jferperez.duel_server.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Holds login credentials
 */
@Getter
@Setter
public class LoginUserDto {
    private String email;
    private String password;
}
