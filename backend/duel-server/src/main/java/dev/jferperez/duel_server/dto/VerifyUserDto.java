package dev.jferperez.duel_server.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Holds verification info for newly created account
 */
@Getter
@Setter
public class VerifyUserDto {
    private String email;
    private String verificationCode;
}
