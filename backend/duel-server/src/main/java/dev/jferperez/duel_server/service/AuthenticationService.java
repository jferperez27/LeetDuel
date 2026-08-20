package dev.jferperez.duel_server.service;

import dev.jferperez.duel_server.dto.LoginUserDto;
import dev.jferperez.duel_server.dto.RegisterUserDto;
import dev.jferperez.duel_server.dto.VerifyUserDto;
import dev.jferperez.duel_server.model.User;
import dev.jferperez.duel_server.repository.UserRepository;
import jakarta.mail.MessagingException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class AuthenticationService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.emailService = emailService;
    }

    public User signup(RegisterUserDto dto) {
        User user = new User(dto.getUsername(), dto.getEmail(), passwordEncoder.encode(dto.getPassword()));
        user.setVerificationCode(generateVerificationCode());
        user.setVerificationExpiration(LocalDateTime.now().plusMinutes(15));
        user.setEnabled(false);
        sendVerificationEmail(user);
        return userRepository.save(user);
    }

    public User authenticate(LoginUserDto dto) {
        User user = userRepository.findByEmail(dto.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isEnabled()) {
            throw new RuntimeException("Account not verified. Please verify account");
        }
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        dto.getEmail(),
                        dto.getPassword()
                )
        );

        return user;
    }

    public void verifyUser(VerifyUserDto dto) {
        Optional<User> optionalUser = userRepository.findByEmail(dto.getEmail());

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();

            // If verification is expired
            if (user.getVerificationExpiration().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Verification code has expired");
            }
            if (user.getVerificationCode().equals(dto.getVerificationCode())) {
                user.setEnabled(true);
                user.setVerificationCode(null);
                user.setVerificationExpiration(null);
                userRepository.save(user);
            } else {
                // if code is incorrect
                throw new RuntimeException("Invalid verification code");
            }
        } else {
            throw new RuntimeException("User not found");
        }
    }

    public void resendVerificationCode(String email) {
        Optional<User> optionalUser = userRepository.findByEmail(email);
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            if (user.isEnabled()) {
                throw new RuntimeException("Account is already verified");
            }

            user.setVerificationCode(generateVerificationCode());
            user.setVerificationExpiration(LocalDateTime.now().plusMinutes(10));
            sendVerificationEmail(user);
            userRepository.save(user);
        } else {
            throw new RuntimeException("User not found");
        }
    }

    public void sendVerificationEmail(User user) {
        String subject = "LeetDuel Account Verification";
        String verificationCode = user.getVerificationCode();
        String htmlMessage = """
                <html>
                  <body style="margin: 0; padding: 0; background-color: #f4f5f7; font-family: Arial, Helvetica, sans-serif;">
                    <div style="max-width: 480px; margin: 40px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; border: 1px solid #e2e4e8;">
                      <div style="background-color: #1a1a2e; padding: 24px; text-align: center;">
                        <h1 style="margin: 0; color: #ffffff; font-size: 22px; letter-spacing: 1px;">LeetDuel</h1>
                      </div>
                      <div style="padding: 32px 40px; text-align: center;">
                        <h2 style="margin: 0 0 8px; color: #222222; font-size: 18px;">Verify your email</h2>
                        <p style="margin: 0 0 24px; color: #555555; font-size: 14px; line-height: 1.5;">
                          Enter this code to finish setting up your account:
                        </p>
                        <div style="display: inline-block; background-color: #f4f5f7; border: 1px dashed #c9ccd3; border-radius: 6px; padding: 14px 28px; margin-bottom: 24px;">
                          <span style="font-size: 28px; font-weight: bold; color: #1a1a2e; letter-spacing: 6px;">"""
                        + verificationCode + """
                </span>
                        </div>
                        <p style="margin: 0; color: #999999; font-size: 12px; line-height: 1.5;">
                          This code expires shortly. If you didn't create a LeetDuel account, you can safely ignore this email.
                        </p>
                      </div>
                    </div>
                  </body>
                </html>
                """;

        try {
            emailService.sendVerificationEmail(user.getEmail(), subject, htmlMessage);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    private String generateVerificationCode() {
        Random random = new Random();
        int code = random.nextInt(900000) + 100000;
        return String.valueOf(code);
    }

}
