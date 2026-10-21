package br.com.cuidamais.auth;

import br.com.cuidamais.shared.Endereco;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Conta de uma pessoa (V1__foundation.sql, T-010).
 *
 * <p>A T-017 mapeou só o id e {@code email_verified_at}, o mínimo que {@link AccountState#of}
 * precisa. O cadastro (T-020) completa o mapeamento com os demais campos da tabela {@code users}:
 * nome, e-mail (único, garantido pela migração), telefone, endereço embutido ({@link Endereco},
 * D-25), hash de senha e os contadores de bloqueio de senha (D-14, tarefa futura). {@link
 * #signup} é o único caminho de criação por cadastro: normaliza o e-mail para minúsculas (único
 * lugar que faz essa normalização na criação) e nasce em {@code AGUARDANDO_CONFIRMACAO_EMAIL}
 * (sem {@code email_verified_at}, D-33).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Embedded
    @AttributeOverrides({
        // zipCode e state são CHAR(8)/CHAR(2) na migração (V1__foundation.sql, T-010). Sem
        // columnDefinition o Hibernate assume VARCHAR (sempre o padrão para String) e a
        // validação do esquema (D-06, ddl-auto=validate) falha. O driver JDBC do PostgreSQL
        // reporta esse tipo como "bpchar" (nome interno do CHAR/CHARACTER no catálogo do
        // banco), não "char" — por isso o nome literal aqui é "bpchar", não "char".
        @AttributeOverride(
                name = "zipCode",
                column = @Column(name = "address_zip_code", columnDefinition = "bpchar(8)")),
        @AttributeOverride(name = "street", column = @Column(name = "address_street")),
        @AttributeOverride(name = "number", column = @Column(name = "address_number")),
        @AttributeOverride(name = "complement", column = @Column(name = "address_complement")),
        @AttributeOverride(name = "district", column = @Column(name = "address_district")),
        @AttributeOverride(name = "city", column = @Column(name = "address_city")),
        @AttributeOverride(
                name = "state",
                column = @Column(name = "address_state", columnDefinition = "bpchar(2)"))
    })
    private Endereco address;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** {@code null} enquanto o e-mail não foi confirmado (AC-001.2, D-05). */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lock_notified_at")
    private Instant lockNotifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Exigido pelo Hibernate. */
    protected User() {}

    /**
     * Monta uma conta com o mínimo que a T-017 usa.
     *
     * @param id identificador da conta ({@code users.id})
     * @param emailVerifiedAt quando o e-mail foi confirmado; {@code null} = ainda não confirmado
     */
    static User of(UUID id, Instant emailVerifiedAt) {
        User user = new User();
        user.id = id;
        user.emailVerifiedAt = emailVerifiedAt;
        return user;
    }

    /**
     * Cria a conta do cadastro (AC-001.1, T-020): e-mail normalizado para minúsculas, sem
     * {@code email_verified_at} ({@code AGUARDANDO_CONFIRMACAO_EMAIL}, D-33), sem bloqueio de
     * senha (D-14).
     *
     * @param name nome informado
     * @param email e-mail informado; é normalizado para minúsculas aqui
     * @param phone telefone informado (DDD + número, só dígitos)
     * @param address endereço já validado (D-25)
     * @param passwordHash hash BCrypt da senha (D-14); a senha em texto nunca chega aqui
     * @param clock relógio injetado (D-10) para {@code created_at}
     */
    public static User signup(
            String name, String email, String phone, Endereco address, String passwordHash, Clock clock) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.name = name;
        user.email = email.toLowerCase(Locale.ROOT);
        user.phone = phone;
        user.address = address;
        user.passwordHash = passwordHash;
        user.emailVerifiedAt = null;
        user.failedLogins = 0;
        user.lockedUntil = null;
        user.lockNotifiedAt = null;
        user.createdAt = clock.instant();
        return user;
    }

    /** Identificador imutável da conta, gerado em {@link #signup}/{@link #of}. */
    public UUID getId() {
        return id;
    }

    /** Nome informado no cadastro. */
    public String getName() {
        return name;
    }

    /** E-mail da conta, já normalizado para minúsculas. */
    public String getEmail() {
        return email;
    }

    /** Telefone informado no cadastro (DDD + número, só dígitos). */
    public String getPhone() {
        return phone;
    }

    /** Endereço da conta (D-25). */
    public Endereco getAddress() {
        return address;
    }

    /** Hash BCrypt da senha (D-14); a senha em texto nunca é persistida. */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** {@code null} enquanto o e-mail não foi confirmado. */
    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    /** Deriva de {@code emailVerifiedAt != null}: e-mail já confirmado (AC-001.2, D-05, D-33). */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /**
     * Confirma o e-mail da conta (AC-001.2, T-022): grava {@code emailVerifiedAt} com o "agora"
     * do relógio injetado (D-10), que é a única mudança de estado que a confirmação de e-mail
     * provoca na conta — vira {@code ATIVO} pela derivação de {@link #isEmailVerified()} (D-33).
     * Por decisão de D-05 rev. 2, confirmar o e-mail <strong>não</strong> cria grupo nem
     * membership; isso só acontece no cadastro do idoso ({@code POST /elders}, tarefa futura).
     *
     * @param clock relógio injetado (D-10), nunca o relógio de sistema
     */
    public void verifyEmail(Clock clock) {
        this.emailVerifiedAt = clock.instant();
    }

    /** Contador de tentativas de login erradas seguidas (D-14); zera no acerto. */
    public int getFailedLogins() {
        return failedLogins;
    }

    /** Até quando a conta fica bloqueada por senha errada; {@code null} = não bloqueada (D-14). */
    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /** Quando o dono da conta foi avisado do bloqueio por senha errada; {@code null} = ainda não avisado (D-14). */
    public Instant getLockNotifiedAt() {
        return lockNotifiedAt;
    }

    /** Quando a conta foi criada. */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
