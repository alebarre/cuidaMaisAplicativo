package br.com.cuidamais.auth.service;

import br.com.cuidamais.auth.controller.AuthController;
import br.com.cuidamais.auth.domain.IssuedOtp;
import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.dto.AddressRequest;
import br.com.cuidamais.auth.dto.SignupRequest;
import br.com.cuidamais.auth.repository.OtpCodeRepository;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import br.com.cuidamais.notification.event.DuplicateSignupEmail;
import br.com.cuidamais.shared.domain.Endereco;
import java.time.Clock;
import java.util.Locale;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de negócio de {@code POST /auth/signup} (T-020, AC-001.1, AC-001.3, AC-001.4; D-15).
 *
 * <p>E-mail já cadastrado (em qualquer status, AC-001.4): nada é criado nem alterado, só o aviso
 * de {@link DuplicateSignupEmail} é publicado — o controller sempre responde 202, então quem
 * chama este serviço não precisa saber qual dos dois caminhos ocorreu.
 *
 * <p>E-mail novo: cria a conta ({@code AGUARDANDO_CONFIRMACAO_EMAIL}, D-33), emite o código de
 * confirmação (D-04, só o hash é persistido) e publica {@link ConfirmationCodeEmail} com o
 * código em texto puro, que só existe em memória (D-04). Os dois eventos são publicados dentro
 * desta transação para saírem só depois do commit (D-23).
 */
@Service
public class SignupService {

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    /**
     * @param userRepository busca/persiste contas, usado para checar duplicidade de e-mail e salvar a nova conta
     * @param otpCodeRepository persiste o hash do código de confirmação emitido (D-04)
     * @param otpService emite o código de confirmação (texto puro + hash, D-04)
     * @param passwordEncoder gera o hash BCrypt da senha informada (D-14)
     * @param events publica os eventos de e-mail (confirmação ou aviso de duplicidade) dentro da transação (D-23)
     */
    public SignupService(
            UserRepository userRepository,
            OtpCodeRepository otpCodeRepository,
            OtpService otpService,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    /**
     * Processa um pedido de cadastro (AC-001.1, AC-001.4, D-15): a resposta ao chamador é sempre
     * neutra (202 sem corpo, ver {@link AuthController#signup}), então este método nunca lança
     * nada — ou o e-mail já existe (nada é criado, só avisa o dono) ou é novo (cria a conta e
     * dispara a confirmação).
     *
     * @param request dados do formulário de cadastro
     * @param clock relógio injetado (D-10) usado para {@code created_at}
     */
    @Transactional
    public void signup(SignupRequest request, Clock clock) {
        // 1. normaliza o e-mail para minúsculas (único lugar que faz essa normalização na criação)
        String email = request.email().toLowerCase(Locale.ROOT);

        // 2. e-mail já existe: avisa o dono e não cria nem altera nada (AC-001.4, D-15)
        if (userRepository.findByEmail(email).isPresent()) {
            events.publishEvent(new DuplicateSignupEmail(email));
            return;
        }

        // 3. e-mail novo: cria a conta em AGUARDANDO_CONFIRMACAO_EMAIL (D-33)
        Endereco address = toEndereco(request.address());
        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.signup(request.name(), email, request.phone(), address, passwordHash, clock);
        userRepository.save(user);

        // 4. emite e persiste o código de confirmação (só o hash, D-04)
        IssuedOtp issuedOtp = otpService.issue(user.getId(), OtpPurpose.CONFIRMACAO_EMAIL);
        otpCodeRepository.save(issuedOtp.otp());

        // 5. publica o e-mail de confirmação com o código em texto puro (só sai do banco após o
        // commit da transação, D-23)
        events.publishEvent(new ConfirmationCodeEmail(email, issuedOtp.plainCode(), OtpPurpose.CONFIRMACAO_EMAIL));
    }

    /** Converte o endereço do formulário de cadastro no {@link Endereco} embutido da entidade (D-25). */
    private static Endereco toEndereco(AddressRequest address) {
        return new Endereco(
                address.zipCode(),
                address.street(),
                address.number(),
                address.complement(),
                address.district(),
                address.city(),
                address.state());
    }
}
