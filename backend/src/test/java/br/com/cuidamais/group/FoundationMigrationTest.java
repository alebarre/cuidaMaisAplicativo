package br.com.cuidamais.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.IntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-010 "Pronto quando": testes de repositório provam que o banco criado por
 * {@code V1__foundation.sql} <strong>recusa</strong> dois admins no mesmo grupo (D-03), um
 * Cuidador admin (D-29), alergia com 501 caracteres e nascimento futuro (D-36), e que apagar um
 * {@code care_groups} leva idoso, contatos, memberships e convites junto (D-35).
 *
 * <p>Usa SQL puro via {@link JdbcTemplate}: as entidades JPA chegam em tarefas futuras
 * (T-011, T-012, T-014...). Cada teste usa ids e e-mails novos, então os testes não dependem de
 * ordem nem de limpeza entre si.
 *
 * <p>Toda recusa é conferida pelo SQLState do PostgreSQL ({@code 23505} unique, {@code 23514}
 * check, {@code 22001} valor longo demais), para que o teste só passe pela restrição certa — e
 * não, por exemplo, por uma coluna NOT NULL esquecida. Antes de cada recusa há um controle
 * positivo (a mesma operação com valor válido é aceita).
 */
class FoundationMigrationTest extends IntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String STRING_DATA_RIGHT_TRUNCATION = "22001";

    @Autowired
    JdbcTemplate jdbc;

    // ---------------------------------------------------------------- D-03

    @Test
    @DisplayName("D-03 (AC-002.1, AC-009.4) banco recusa dois admins no mesmo grupo")
    void d_03_banco_recusa_dois_admins_no_mesmo_grupo() {
        UUID criador = insertUser();
        UUID grupo = insertCareGroup(criador);
        insertMembership(grupo, criador, "FAMILIAR", true);

        UUID outro = insertUser();

        assertRecusado(() -> insertMembership(grupo, outro, "FAMILIAR", true), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("D-03 índice é parcial: vários membros não-admin no mesmo grupo são aceitos")
    void d_03_indice_parcial_aceita_varios_nao_admin_no_mesmo_grupo() {
        UUID criador = insertUser();
        UUID grupo = insertCareGroup(criador);
        insertMembership(grupo, criador, "FAMILIAR", true);

        assertThatCode(() -> {
            insertMembership(grupo, insertUser(), "FAMILIAR", false);
            insertMembership(grupo, insertUser(), "CUIDADOR", false);
        }).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- D-29

    @Test
    @DisplayName("D-29 banco recusa Cuidador administrador (CHECK NOT is_admin OR role='FAMILIAR')")
    void d_29_banco_recusa_cuidador_admin() {
        UUID criador = insertUser();
        UUID grupo = insertCareGroup(criador);
        // todo care_group nasce com uma membership admin na aplicação real (D-05); sem isso o
        // criador ficaria "ATIVO sem grupo" e seria apagado pelo job de retenção da T-034.
        insertMembership(grupo, criador, "FAMILIAR", true);
        // controle positivo: Cuidador não-admin é aceito
        insertMembership(grupo, insertUser(), "CUIDADOR", false);

        // usuário novo (não o criador, que já tem membership): a tentativa precisa violar a CHECK,
        // não a UNIQUE de memberships.user_id, que já dispararia antes se reusássemos o criador.
        UUID candidatoRejeitado = insertUser();
        assertRecusado(() -> insertMembership(grupo, candidatoRejeitado, "CUIDADOR", true), CHECK_VIOLATION);
    }

    // ---------------------------------------------------------------- D-02

    @Test
    @DisplayName("D-02 (AC-003.7) banco recusa a mesma pessoa em dois grupos (memberships.user_id UNIQUE)")
    void d_02_banco_recusa_mesma_pessoa_em_dois_grupos() {
        UUID pessoa = insertUser();
        UUID criadorA = insertUser();
        UUID grupoA = insertCareGroup(criadorA);
        insertMembership(grupoA, criadorA, "FAMILIAR", true); // realista: todo grupo nasce com admin (D-05)
        UUID criadorB = insertUser();
        UUID grupoB = insertCareGroup(criadorB);
        insertMembership(grupoB, criadorB, "FAMILIAR", true);
        insertMembership(grupoA, pessoa, "CUIDADOR", false);

        assertRecusado(() -> insertMembership(grupoB, pessoa, "CUIDADOR", false), UNIQUE_VIOLATION);
    }

    // ---------------------------------------------------------------- D-36

    @Test
    @DisplayName("D-36 (AC-002.8) banco recusa alergias com 501 caracteres")
    void d_36_banco_recusa_alergias_com_501_caracteres() {
        UUID criador = insertUser();
        // controle positivo: 500 caracteres cabem
        assertThatCode(() -> insertElder(insertCareGroup(criador), "a".repeat(500), null,
                "CURRENT_DATE - INTERVAL '80 years'"))
                .doesNotThrowAnyException();

        UUID grupo = insertCareGroup(criador);
        // realista: ao menos uma das duas membresias do criador vira admin (D-05); sem isso o
        // criador ficaria "ATIVO sem grupo" e seria apagado pelo job de retenção da T-034.
        insertMembership(grupo, criador, "FAMILIAR", true);

        assertRecusado(() -> insertElder(grupo, "a".repeat(501), null,
                "CURRENT_DATE - INTERVAL '80 years'"), STRING_DATA_RIGHT_TRUNCATION);
    }

    @Test
    @DisplayName("D-36 (AC-002.8) banco recusa condições de saúde com 501 caracteres")
    void d_36_banco_recusa_condicoes_com_501_caracteres() {
        UUID criador = insertUser();
        assertThatCode(() -> insertElder(insertCareGroup(criador), null, "c".repeat(500),
                "CURRENT_DATE - INTERVAL '80 years'"))
                .doesNotThrowAnyException();

        UUID grupo = insertCareGroup(criador);
        insertMembership(grupo, criador, "FAMILIAR", true); // idem ao teste de alergias acima

        assertRecusado(() -> insertElder(grupo, null, "c".repeat(501),
                "CURRENT_DATE - INTERVAL '80 years'"), STRING_DATA_RIGHT_TRUNCATION);
    }

    @Test
    @DisplayName("D-36 (AC-002.7) banco recusa data de nascimento futura (CHECK birth_date <= CURRENT_DATE)")
    void d_36_banco_recusa_nascimento_futuro() {
        UUID criador = insertUser();
        // controle positivo: nascido hoje é aceito (limite inclusivo)
        assertThatCode(() -> insertElder(insertCareGroup(criador), null, null, "CURRENT_DATE"))
                .doesNotThrowAnyException();

        UUID grupo = insertCareGroup(criador);
        insertMembership(grupo, criador, "FAMILIAR", true); // idem ao teste de alergias acima

        assertRecusado(() -> insertElder(grupo, null, null, "CURRENT_DATE + 1"), CHECK_VIOLATION);
    }

    // ---------------------------------------------------------------- AC-002.1 (um idoso)

    @Test
    @DisplayName("AC-002.1 banco recusa segundo idoso no mesmo grupo (elders.group_id UNIQUE)")
    void ac_002_1_banco_recusa_segundo_idoso_no_mesmo_grupo() {
        UUID criador = insertUser();
        UUID grupo = insertCareGroup(criador);
        insertMembership(grupo, criador, "FAMILIAR", true); // realista: todo grupo nasce com admin (D-05)
        insertElder(grupo, null, null, "CURRENT_DATE - INTERVAL '80 years'");

        assertRecusado(() -> insertElder(grupo, null, null, "CURRENT_DATE - INTERVAL '70 years'"),
                UNIQUE_VIOLATION);
    }

    // ---------------------------------------------------------------- D-35

    @Test
    @DisplayName("D-35 (AC-013.2) apagar care_groups leva idoso, contatos, memberships e convites junto")
    void d_35_apagar_grupo_apaga_idoso_contatos_memberships_e_convites() {
        UUID criador = insertUser();
        UUID cuidador = insertUser();
        UUID grupo = insertCareGroup(criador);
        UUID idoso = insertElder(grupo, "Dipirona", "Hipertensão", "CURRENT_DATE - INTERVAL '80 years'");
        UUID contato = insertEmergencyContact(idoso, 1);
        UUID contato2 = insertEmergencyContact(idoso, 2);
        UUID membershipAdmin = insertMembership(grupo, criador, "FAMILIAR", true);
        UUID membershipCuidador = insertMembership(grupo, cuidador, "CUIDADOR", false);
        UUID convite = insertInvitation(grupo, criador);

        // pré-condição: tudo existe
        assertThat(count("elders", idoso)).isEqualTo(1);
        assertThat(count("emergency_contacts", contato)).isEqualTo(1);
        assertThat(count("emergency_contacts", contato2)).isEqualTo(1);
        assertThat(count("memberships", membershipAdmin)).isEqualTo(1);
        assertThat(count("memberships", membershipCuidador)).isEqualTo(1);
        assertThat(count("invitations", convite)).isEqualTo(1);

        int apagados = jdbc.update("DELETE FROM care_groups WHERE id = ?", grupo);

        assertThat(apagados).isEqualTo(1);
        assertThat(count("care_groups", grupo)).isZero();
        assertThat(count("elders", idoso)).isZero();
        assertThat(count("emergency_contacts", contato)).isZero();
        assertThat(count("emergency_contacts", contato2)).isZero();
        assertThat(count("memberships", membershipAdmin)).isZero();
        assertThat(count("memberships", membershipCuidador)).isZero();
        assertThat(count("invitations", convite)).isZero();
    }

    // ================================================================ helpers

    private void assertRecusado(ThrowingCallable operacao, String sqlStateEsperado) {
        assertThatThrownBy(operacao)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e))
                        .as("SQLState da violação")
                        .isEqualTo(sqlStateEsperado));
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private int count(String tabela, UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, name, email, phone,
                    address_zip_code, address_street, address_number, address_complement,
                    address_district, address_city, address_state,
                    password_hash, email_verified_at, failed_logins, created_at)
                VALUES (?, 'Pessoa Teste', ?, '11999998888',
                    '01310100', 'Avenida Paulista', '1000', NULL,
                    'Bela Vista', 'São Paulo', 'SP',
                    '$2a$10$hashdeteste', now(), 0, now())
                """, id, "u-" + id + "@teste.local");
        return id;
    }

    private UUID insertCareGroup(UUID criador) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by, created_at) VALUES (?, ?, now())",
                id, criador);
        return id;
    }

    /** {@code birthDateSql} é uma expressão SQL (ex.: {@code CURRENT_DATE + 1}) para que "hoje" seja o do banco. */
    private UUID insertElder(UUID grupo, String allergies, String conditions, String birthDateSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO elders (id, group_id, name, birth_date,
                    address_zip_code, address_street, address_number, address_complement,
                    address_district, address_city, address_state,
                    photo_path, allergies, conditions, updated_at)
                VALUES (?, ?, 'Idoso Teste', (%s)::date,
                    '01310100', 'Avenida Paulista', '1000', NULL,
                    'Bela Vista', 'São Paulo', 'SP',
                    NULL, ?, ?, now())
                """.formatted(birthDateSql), id, grupo, allergies, conditions);
        return id;
    }

    private UUID insertEmergencyContact(UUID idoso, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO emergency_contacts (id, elder_id, name, relationship, phone,
                    address_zip_code, address_street, address_number, address_complement,
                    address_district, address_city, address_state, position)
                VALUES (?, ?, 'Contato Teste', 'Filho', '11988887777',
                    '01310100', 'Avenida Paulista', '1000', NULL,
                    'Bela Vista', 'São Paulo', 'SP', ?)
                """, id, idoso, position);
        return id;
    }

    /** {@code role} vai como literal (só valores fixos dos testes) para funcionar com VARCHAR ou tipo enum. */
    private UUID insertMembership(UUID grupo, UUID usuario, String role, boolean isAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memberships (id, group_id, user_id, role, is_admin, status,
                    requested_at, expires_at, decided_by, decided_at, retention_deadline, version)
                VALUES (?, ?, ?, '%s', ?, 'ATIVO', now(), NULL, NULL, NULL, NULL, 0)
                """.formatted(role), id, grupo, usuario, isAdmin);
        return id;
    }

    private UUID insertInvitation(UUID grupo, UUID criadoPor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invitations (id, group_id, email, code_hash, status, origin,
                    expires_at, created_by, created_at, used_at, cancelled_at)
                VALUES (?, ?, ?, 'hash-do-codigo', 'ENVIADO', 'MANUAL',
                    now() + INTERVAL '7 days', ?, now(), NULL, NULL)
                """, id, grupo, "convidado-" + id + "@teste.local", criadoPor);
        return id;
    }
}
