package br.com.cuidamais.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consulta pontual do idoso de um grupo de cuidado (T-027, D-33). A entidade/repositório
 * {@code Elder} completos nascem só na T-030; até lá, {@code GET /me/account} precisa do
 * {@code elderId} do grupo da conta (AC-001.7, D-20) e esta classe existe só para isso — uma
 * leitura mínima e direta na tabela {@code elders}, sem mapear o restante das colunas.
 */
@Component
public class ElderIdLookup {

    private final JdbcTemplate jdbc;

    /** @param jdbc acesso a dados compartilhado (nenhuma migração/entidade nova nesta tarefa) */
    public ElderIdLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca o id do idoso cadastrado no grupo informado. {@code elders.group_id} é {@code UNIQUE}
     * (plan §3, D-05): um grupo tem no máximo um idoso, então a consulta nunca precisa de mais de
     * uma linha. "Nenhuma linha" (grupo ainda sem idoso) devolve {@code null} — quem chama decide
     * o que isso significa; aqui não há nenhuma regra de negócio, só a leitura.
     *
     * @param groupId grupo de cuidado da membership da conta
     * @return id do idoso do grupo, ou {@code null} se o grupo não tiver idoso cadastrado
     */
    public UUID findByGroupId(UUID groupId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM elders WHERE group_id = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                groupId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
