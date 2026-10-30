package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.group.Elder;
import br.com.cuidamais.group.ElderRepository;
import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.membership.MembershipStatus;
import br.com.cuidamais.membership.Role;
import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * T-032 — {@code GET/PUT /api/v1/me} (operationIds {@code getMe}/{@code updateMe}): dados da
 * própria conta (nome, e-mail, telefone, endereço) e do vínculo com o grupo, se houver (schema
 * {@code Me}), e a edição do próprio telefone/endereço (schema {@code UpdateMeRequest}, só esses
 * dois campos — {@code name}/{@code email} não são editáveis por aqui).
 *
 * <p>AC coberto: AC-011.6 ("o membro pode alterar o próprio telefone e endereço a qualquer
 * momento"). Essa "a qualquer momento" é o motivo de as duas rotas serem
 * {@code x-required-account-state: any} no contrato (diferente do padrão das demais rotas
 * autenticadas, T-024): até uma conta com membership {@code REMOVIDO} ou
 * {@code AGUARDANDO_APROVACAO} consegue ler e editar os próprios dados, não só uma conta
 * {@code ATIVO} com grupo. {@code membership} é {@code null} enquanto a conta não pertence a um
 * grupo (criador que ainda não cadastrou o idoso, AC-001.7) e, quando existe, nunca vem com campo
 * interno nulo (D-05: toda membership tem grupo e idoso).
 *
 * <p>Esta classe não repete a exaustão dos 8 estados de conta/membership — isso já está em
 * {@link MyAccountStateTest} (T-027/T-030). Aqui só os cenários que importam especificamente para
 * {@code GET/PUT /me}: sem grupo, Responsável, Cuidador, membership {@code REMOVIDO} (prova que
 * {@code any} realmente vale até removido), edição válida com persistência, dois casos de 400
 * (telefone malformado, endereço incompleto), edição por conta {@code AGUARDANDO_APROVACAO} (prova
 * que {@code any} também vale para o {@code PUT}) e 401 básico sem sessão.
 *
 * <p>Caixa-preta pela rota HTTP real (MockMvc), mesmo padrão de {@code ElderCreationTest}
 * (T-030)/{@code MyAccountStateTest} (T-027): contas nascem {@code ATIVO} direto no banco pelos
 * repositórios, o grupo com idoso nasce pelo fluxo real de {@code POST /elders} (T-030, já
 * implementado) para o cenário do Responsável, e membros fora de {@code ATIVO} são fabricados
 * direto pela máquina de estados de {@link Membership} (sem depender do fluxo HTTP de convite, que
 * é escopo da Fase 4). O token Bearer é emitido direto por {@link JwtService#issue}. Hoje nem
 * {@code GET} nem {@code PUT /me} (raiz do mapeamento) existem — só {@code /me/account}
 * (T-027/T-030) — então a maioria destes testes falha por 404/405 (rota/verbo inexistente), não por
 * erro de configuração.
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta (e-mail único).
 */
@AutoConfigureMockMvc
class MeTest extends IntegrationTest {

    private static final String ME = "/api/v1/me";
    private static final String ELDERS = "/api/v1/elders";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    ElderRepository elders;

    @Autowired
    JwtService jwtService;

    // ============================================================ GET /me sem grupo

    /**
     * AC-011.6: o criador {@code ATIVO} que ainda não cadastrou o idoso (AC-001.7) não tem
     * membership. Prova que {@code GET /me} devolve 200 com {@code membership: null} e os demais
     * campos (userId, name, email, phone, address) batendo com os dados reais gravados no cadastro
     * — é a base de "o membro pode alterar o próprio telefone e endereço": primeiro é preciso
     * conseguir lê-los de volta certos.
     */
    @Test
    @DisplayName("AC-011.6 GET /me de conta ATIVO sem grupo devolve membership null e os dados reais da conta")
    void ac_011_6_get_me_sem_grupo_devolve_membership_null() throws Exception {
        User user = users.save(contaAtivaSemGrupo());

        JsonNode body = consultar(user);

        assertThat(body.get("userId").asText()).isEqualTo(user.getId().toString());
        assertThat(body.get("name").asText()).isEqualTo(user.getName());
        assertThat(body.get("email").asText()).isEqualTo(user.getEmail());
        assertThat(body.get("phone").asText()).isEqualTo(user.getPhone());
        assertThat(body.get("address").get("city").asText()).isEqualTo(user.getAddress().city());
        assertNulo(body, "membership");
    }

    // ============================================================ GET /me com grupo

    /**
     * AC-011.6: o Responsável (admin) de um grupo já criado pelo fluxo real de {@code POST /elders}
     * (T-030) vê o próprio vínculo preenchido: papel {@code FAMILIAR}, administração verdadeira,
     * status {@code ATIVO} e o {@code elderId} exatamente igual ao idoso daquele grupo — prova que
     * {@code membership} não vem com nenhum campo interno nulo quando existe (D-05).
     */
    @Test
    @DisplayName("AC-011.6 GET /me do Responsável devolve membership FAMILIAR/admin/ATIVO com o elderId do próprio grupo")
    void ac_011_6_get_me_responsavel_devolve_membership_preenchida() throws Exception {
        Grupo grupo = grupoComIdosoViaFluxoReal();

        JsonNode body = consultar(grupo.responsavel());

        JsonNode membership = body.get("membership");
        assertThat(membership.get("role").asText()).isEqualTo("FAMILIAR");
        assertThat(membership.get("isAdmin").asBoolean()).isTrue();
        assertThat(membership.get("status").asText()).isEqualTo("ATIVO");
        assertThat(membership.get("elderId").asText()).isEqualTo(grupo.elderId().toString());
    }

    /**
     * AC-011.6: um Cuidador (fabricado via {@link Membership#request}/{@link Membership#approve},
     * sem passar pelo fluxo HTTP de convite da Fase 4) do mesmo grupo do Responsável vê
     * {@code role=CUIDADOR}, {@code isAdmin=false} e o mesmo {@code elderId} do grupo — o vínculo
     * de cada membro reflete exatamente o papel que ele tem, não o do Responsável.
     */
    @Test
    @DisplayName("AC-011.6 GET /me do Cuidador devolve membership CUIDADOR/sem administração com o mesmo elderId do grupo")
    void ac_011_6_get_me_cuidador_devolve_membership_sem_administracao() throws Exception {
        Grupo grupo = grupoComIdosoViaFluxoReal();
        User cuidador = membroAprovado(grupo, Role.CUIDADOR);

        JsonNode body = consultar(cuidador);

        JsonNode membership = body.get("membership");
        assertThat(membership.get("role").asText()).isEqualTo("CUIDADOR");
        assertThat(membership.get("isAdmin").asBoolean()).isFalse();
        assertThat(membership.get("elderId").asText()).isEqualTo(grupo.elderId().toString());
    }

    /**
     * AC-011.6 + contrato {@code x-required-account-state: any}: uma conta com membership
     * {@code REMOVIDO} ainda recebe <strong>200</strong> (não 401) em {@code GET /me} — prova que
     * {@code any} vale de verdade até para quem já foi removido do grupo (mesmo comportamento
     * intencional já provado para {@code GET /me/account} em {@link MyAccountStateTest}), e o corpo
     * reflete {@code membership.status=REMOVIDO}.
     */
    @Test
    @DisplayName("AC-011.6 GET /me de membership REMOVIDO ainda responde 200 com membership.status=REMOVIDO")
    void ac_011_6_get_me_removido_ainda_responde_200() throws Exception {
        Grupo grupo = grupoComIdosoViaFluxoReal();
        User cuidador = membroAprovado(grupo, Role.CUIDADOR);
        Membership m = memberships.findByUserId(cuidador.getId()).orElseThrow();
        m.remove(clock);
        memberships.save(m);

        JsonNode body = consultar(cuidador);

        assertThat(body.get("membership").get("status").asText()).isEqualTo("REMOVIDO");
    }

    // ============================================================ PUT /me caminho feliz

    /**
     * AC-011.6 "o membro pode alterar o próprio telefone e endereço a qualquer momento": o
     * Responsável chama {@code PUT /me} com telefone e endereço novos, recebe 200 com o corpo já
     * refletindo os valores novos, e um {@code GET /me} feito depois confirma que a mudança foi
     * persistida (não só devolvida na resposta do PUT) — e que {@code name}/{@code email}, que não
     * fazem parte de {@code UpdateMeRequest}, continuam os mesmos de antes.
     */
    @Test
    @DisplayName("AC-011.6 PUT /me com telefone e endereço novos persiste e não altera name/email")
    void ac_011_6_put_me_atualiza_telefone_e_endereco_e_persiste() throws Exception {
        User user = users.save(contaAtivaSemGrupo());
        String nomeOriginal = user.getName();
        String emailOriginal = user.getEmail();
        String telefoneNovo = "21955554444";
        Map<String, Object> enderecoNovo = endereco("04538133", "Avenida Brigadeiro Faria Lima", "2232", null,
                "Jardim Paulistano", "São Paulo", "SP");

        MvcResult result = mockMvc.perform(atualizar(corpoAtualizacao(telefoneNovo, enderecoNovo), user))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode corpoDoPut = json.readTree(result.getResponse().getContentAsString());
        // etapa 1: a resposta do PUT já reflete os valores novos.
        assertThat(corpoDoPut.get("phone").asText()).isEqualTo(telefoneNovo);
        assertThat(corpoDoPut.get("address").get("city").asText()).isEqualTo("São Paulo");

        JsonNode corpoDoGet = consultar(user);
        // etapa 2: um GET /me independente, feito depois, confirma a persistência real.
        assertThat(corpoDoGet.get("phone").asText()).isEqualTo(telefoneNovo);
        assertThat(corpoDoGet.get("address").get("zipCode").asText()).isEqualTo("04538133");
        // etapa 3: name/email não são editáveis por este endpoint, continuam os originais.
        assertThat(corpoDoGet.get("name").asText()).isEqualTo(nomeOriginal);
        assertThat(corpoDoGet.get("email").asText()).isEqualTo(emailOriginal);
    }

    // ============================================================ PUT /me validação

    /**
     * AC-011.6 / contrato {@code Phone} ({@code ^\d{10,11}$}): telefone com letras é recusado com
     * 400 {@code ValidationProblem} no campo {@code phone}, e nada é persistido (o telefone
     * original continua valendo).
     */
    @Test
    @DisplayName("AC-011.6 PUT /me com telefone contendo letras é recusado com 400 em phone")
    void ac_011_6_put_me_telefone_com_letras_e_recusado() throws Exception {
        User user = users.save(contaAtivaSemGrupo());
        String telefoneOriginal = user.getPhone();

        esperaValidationProblem(corpoAtualizacao("2195abc4444", enderecoValido()), "phone", user);

        assertThat(users.findById(user.getId()).orElseThrow().getPhone()).isEqualTo(telefoneOriginal);
    }

    /**
     * AC-011.6 / contrato {@code Phone}: telefone com menos de 10 dígitos (9 dígitos, abaixo do
     * mínimo DDD + 8) é recusado com 400 no campo {@code phone}.
     */
    @Test
    @DisplayName("AC-011.6 PUT /me com telefone de menos de 10 dígitos é recusado com 400 em phone")
    void ac_011_6_put_me_telefone_curto_e_recusado() throws Exception {
        User user = users.save(contaAtivaSemGrupo());

        esperaValidationProblem(corpoAtualizacao("219876543", enderecoValido()), "phone", user);
    }

    /**
     * AC-011.6 / contrato {@code Address} ({@code required: [zipCode, street, number, district,
     * city, state]}): endereço sem {@code city} é recusado com 400 no campo {@code address.city},
     * nada é persistido.
     */
    @Test
    @DisplayName("AC-011.6 PUT /me com endereço sem city é recusado com 400 em address.city")
    void ac_011_6_put_me_endereco_sem_city_e_recusado() throws Exception {
        User user = users.save(contaAtivaSemGrupo());
        Endereco enderecoOriginal = user.getAddress();
        Map<String, Object> enderecoIncompleto = enderecoValido();
        enderecoIncompleto.remove("city");

        esperaValidationProblem(corpoAtualizacao("21955554444", enderecoIncompleto), "address.city", user);

        assertThat(users.findById(user.getId()).orElseThrow().getAddress()).isEqualTo(enderecoOriginal);
    }

    // ============================================================ PUT /me fora de ATIVO

    /**
     * AC-011.6 "a qualquer momento" + contrato {@code x-required-account-state: any}: uma conta com
     * membership {@code AGUARDANDO_APROVACAO} (convidado ainda não decidido pelo Responsável) ainda
     * consegue editar o próprio telefone/endereço — 200, não 401/403. É a mesma garantia de
     * {@code any} do {@code GET}, agora do lado da escrita.
     */
    @Test
    @DisplayName("AC-011.6 PUT /me por conta AGUARDANDO_APROVACAO ainda responde 200 (any vale para escrita também)")
    void ac_011_6_put_me_aguardando_aprovacao_ainda_responde_200() throws Exception {
        Grupo grupo = grupoComIdosoViaFluxoReal();
        User convidado = users.save(contaConfirmada());
        memberships.save(Membership.request(grupo.groupId(), convidado.getId(), clock));
        assertThat(memberships.findByUserId(convidado.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);

        mockMvc.perform(atualizar(corpoAtualizacao("21955554444", enderecoValido()), convidado))
                .andExpect(status().isOk());
    }

    // ============================================================ autenticação

    /** Controle básico: sem token nenhum, GET /me responde 401 (exaustão é de T-024). */
    @Test
    @DisplayName("sem sessão (sem token), GET /me responde 401")
    void get_me_sem_sessao_responde_401() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }

    /** Controle básico: sem token nenhum, PUT /me responde 401 (exaustão é de T-024). */
    @Test
    @DisplayName("sem sessão (sem token), PUT /me responde 401")
    void put_me_sem_sessao_responde_401() throws Exception {
        mockMvc.perform(put(ME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpoAtualizacao("21955554444", enderecoValido()))))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ apoio

    /** Grupo de cuidado com idoso: Responsável, id do grupo e id do idoso. */
    record Grupo(User responsavel, UUID groupId, UUID elderId) {
    }

    /** Conta {@code ATIVO} sem grupo (mesmo padrão de {@code ElderCreationTest}/{@code MyAccountStateTest}). */
    private User contaAtivaSemGrupo() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "me-teste-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    /** Conta confirmada sem grupo ainda, pronta para ganhar uma membership fabricada diretamente. */
    private User contaConfirmada() {
        return contaAtivaSemGrupo();
    }

    /**
     * Grupo completo criado pelo fluxo HTTP real de {@code POST /elders} (T-030, já implementado):
     * o Responsável nasce {@code ATIVO} sem grupo, cadastra o idoso e, numa única transação, ganha
     * {@code care_groups} + {@code elders} + membership {@code FAMILIAR} administrador
     * {@code ATIVO} (D-05 rev. 2). O {@code elderId} é lido de volta pelo {@link ElderRepository}
     * a partir do {@code groupId} da membership — o mesmo caminho que {@code MeController} usa hoje
     * em {@code GET /me/account}.
     */
    private Grupo grupoComIdosoViaFluxoReal() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        mockMvc.perform(criarIdoso(responsavel)).andExpect(status().isCreated());

        Membership membership = memberships.findByUserId(responsavel.getId()).orElseThrow();
        Elder elder = elders.findByGroupId(membership.getGroupId()).orElseThrow();
        return new Grupo(responsavel, membership.getGroupId(), elder.getId());
    }

    /** Convidado aprovado pelo Responsável com o papel pedido, sem administração (AC-006.3). */
    private User membroAprovado(Grupo grupo, Role papel) {
        User user = users.save(contaConfirmada());
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        m.approve(papel, grupo.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private static Map<String, Object> enderecoValido() {
        return endereco("22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
    }

    private static Map<String, Object> endereco(
            String zipCode, String street, String number, String complement,
            String district, String city, String state) {
        Map<String, Object> endereco = new LinkedHashMap<>();
        endereco.put("zipCode", zipCode);
        endereco.put("street", street);
        endereco.put("number", number);
        endereco.put("complement", complement);
        endereco.put("district", district);
        endereco.put("city", city);
        endereco.put("state", state);
        return endereco;
    }

    /** Corpo mínimo de {@code ElderInput} (T-030), só os 3 campos obrigatórios. */
    private Map<String, Object> elderInputValido() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
        req.put("address", enderecoValido());
        return req;
    }

    private RequestBuilder criarIdoso(User autor) throws Exception {
        return post(ELDERS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(elderInputValido()));
    }

    /** Corpo de {@code UpdateMeRequest}: só {@code phone} e {@code address} (AC-011.6). */
    private Map<String, Object> corpoAtualizacao(String telefone, Map<String, Object> endereco) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("phone", telefone);
        corpo.put("address", endereco);
        return corpo;
    }

    private RequestBuilder atualizar(Map<String, Object> corpo, User autor) throws Exception {
        return put(ME)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(corpo));
    }

    /**
     * Chama {@code GET /me} com um JWT válido da conta e confere: 200, {@code application/json},
     * requisição e resposta conformes ao {@code openapi.yaml} ({@code matchesContract()}). Devolve
     * o corpo.
     */
    private JsonNode consultar(User user) throws Exception {
        MvcResult result = mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Envia o {@code PUT /me} com corpo inválido e confere: 400, {@code application/problem+json},
     * corpo conforme {@code ValidationProblem} do contrato e um item de {@code errors} com
     * {@code field} exatamente igual a {@code campoEsperado} (mesmo padrão de {@code SignupTest}/
     * {@code ElderCreationTest}). Não usa {@code matchesContract()} porque a requisição em si é
     * propositalmente inválida.
     */
    private JsonNode esperaValidationProblem(Map<String, Object> corpo, String campoEsperado, User autor)
            throws Exception {
        MvcResult result = mockMvc.perform(atualizar(corpo, autor))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem do contrato: %s", violacoes).isEmpty();

        List<String> campos = new ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
        return body;
    }

    /** Campo anulável do contrato: aceita {@code null} explícito ou ausente, nunca um valor. */
    private static void assertNulo(JsonNode body, String campo) {
        assertThat(body.path(campo).isNull() || body.path(campo).isMissingNode())
                .as("%s deve ser null (ou ausente), mas veio %s", campo, body.path(campo))
                .isTrue();
    }
}
