package br.com.cuidamais.membership.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Gerador do código de convite de 6 dígitos (AC-003.1, D-04). Existe para que o convite normal
 * ({@link InvitationService}) e o reenvio automático ({@code ReenvioAutomaticoRoutine}, T-048)
 * usem exatamente a mesma geração aleatória, sem duplicar lógica sensível. O texto do código só
 * vive na memória, a caminho do e-mail; no banco vai apenas o hash.
 */
@Component
public class InvitationCodeGenerator {

    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    /**
     * Sorteia um código numérico de 6 dígitos, com zeros à esquerda, usando {@link SecureRandom}
     * (imprevisível, pois o código é o segredo que dá acesso ao grupo de cuidado).
     */
    public String generate() {
        return String.format("%0" + CODE_DIGITS + "d", random.nextInt(CODE_BOUND));
    }
}
