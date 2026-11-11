package br.com.cuidamais.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.group.service.ElderPhotoStorage;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * T-033 — {@code DELETE /api/v1/elders/{elderId}} (contrato {@code operationId:
 * deleteCareGroup}, D-18: grupo e idoso são 1:1, então excluir o grupo inteiro é o {@code DELETE}
 * do idoso): exclui o grupo de cuidado inteiro — idoso, contatos de emergência, memberships
 * (todos os 5 estados possíveis, não só {@code ATIVO}) e todas as contas ({@code users}) do
 * grupo, inclusive a do Responsável (D-35, D-38).
 *
 * <p>ACs cobertos: AC-013.2 (apagamento completo via cascade de {@code care_groups} + apagamento
 * explícito de {@code users} das memberships coletadas antes, e liberação dos e-mails para novo
 * cadastro), AC-013.3 (e-mail de aviso a todas as 5 pessoas com conta no grupo, qualquer que seja
 * o status — {@code ATIVO}, {@code AGUARDANDO_APROVACAO}, {@code RECUSADO}, {@code EXPIRADO},
 * {@code REMOVIDO} —, sem nenhum dado de saúde no corpo, P5), AC-013.4 (só o Responsável
 * administrador exclui; um membro comum chamando a rota recebe 403 {@code NOT_ALLOWED}, nunca
 * exclui a própria conta), AC-013.5 (a exclusão é apagamento físico de linha, não uma flag de
 * soft-delete — é a única situação em que um registro de cuidado é apagado, exceção documentada
 * em P3/Constitution v1.3). Também cobre D-32/AC-015.3 (idoso de outro grupo é 404, mesmo critério
 * de {@code ElderService#resolveOwnElder} usado por {@code GET}/{@code PUT}/foto) e D-13 (a sessão
 * de quem chamou — e de qualquer outro membro do grupo excluído — morre junto com a conta, porque
 * o filtro JWT relê {@code User} a cada requisição e não a encontra mais).
 *
 * <p>Caixa-preta pela rota HTTP real (mesmo padrão de {@code ElderCreationTest}/{@code
 * ElderPhotoTest}/{@code MeTest}): o grupo com idoso nasce pelo fluxo real de {@code POST
 * /elders} (T-030, já implementado); os membros em estados diferentes de {@code ATIVO}
 * administrador são fabricados direto pela máquina de estados de {@link Membership} (sem
 * depender do fluxo HTTP de convite, que é escopo da Fase 4) — mesmo padrão de {@code
 * ElderRetrievalTest}/{@code MeTest}. O estado {@code EXPIRADO} é fabricado avançando o {@link
 * br.com.cuidamais.shared.testsupport.MutableClock} (D-10) 25 h além do pedido, igual ao padrão já
 * usado em {@code MembershipTest}/{@code InvitationTest}.
 *
 * <p>{@code ElderController} declara {@code @DeleteMapping("/{elderId}")} (T-033), que exclui o
 * grupo inteiro (idoso, contatos, convites, memberships, contas e a foto em disco, D-35/D-38).
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta/grupo/idoso, com e-mails
 * únicos ({@code UUID} aleatório), inclusive para poder reutilizar o e-mail antigo num novo
 * {@code signup} depois da exclusão sem colidir com o de outro teste.
 */
@AutoConfigureMockMvc
class DeleteGroupTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String ME = "/api/v1/me";
    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String ASSUNTO_GRUPO_EXCLUIDO = "Cuida+: o grupo de cuidado foi excluído";
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    ElderRepository elders;

    @Autowired
    JwtService jwtService;

    /**
     * Mesmo diretório configurado para {@link ElderPhotoStorage} ({@code
     * app.storage.elder-photos-dir}, ver {@code application-test.yml}) — usado só para resolver o
     * caminho físico do arquivo gravado por {@code PUT /elders/{elderId}/photo} e confirmar que
     * ele some depois do {@code DELETE} do grupo.
     */
    @Value("${app.storage.elder-photos-dir}")
    String elderPhotosDir;

    @BeforeEach
    void limpaCaixaDeEntrada() {
        mailpit.deleteAll();
    }

    // ============================================================ AC-013.2 / AC-013.5 apagamento completo

    /**
     * Cenário central do "Pronto quando": um grupo com 6 contas (Responsável administrador +
     * 5 membros, um para cada estado de {@code MembershipStatus}) e 1 contato de emergência. Prova
     * que o {@code DELETE} feito pelo Responsável apaga, de fato, TODAS as linhas envolvidas — não
     * só o idoso, mas também o contato, as 6 memberships (qualquer status) e as 6 contas
     * ({@code users}), inclusive a do próprio Responsável. A ausência total das linhas (nenhuma
     * flag, nenhuma coluna de "apagado") é a prova de que é um apagamento físico, não um
     * soft-delete — exatamente a exceção a P3 registrada em AC-013.5.
     */
    @Test
    @DisplayName("AC-013.2 / AC-013.5 DELETE apaga idoso, contato, as 6 memberships (todos os estados) e as 6 contas do grupo")
    void ac_013_2_delete_apaga_idoso_contato_memberships_e_contas_do_grupo() throws Exception {
        GrupoCompleto grupo = grupoCompletoComSeisContas();
        List<UUID> todosOsUserIds = grupo.todos().stream().map(User::getId).toList();

        // Sanity antes da exclusão: as 6 linhas de memberships e 1 contato existem de verdade.
        assertThat(countCareGroups(grupo.groupId())).as("care_groups antes").isEqualTo(1);
        assertThat(countElders(grupo.elderId())).as("elders antes").isEqualTo(1);
        assertThat(countContatos(grupo.elderId())).as("emergency_contacts antes").isEqualTo(1);
        assertThat(countMemberships(grupo.groupId())).as("memberships antes (6 estados)").isEqualTo(6);

        mockMvc.perform(excluir(grupo.elderId(), grupo.responsavel())).andExpect(status().isNoContent());

        // AC-013.2: care_groups, elders, emergency_contacts e as 6 memberships desaparecem (cascade de D-35).
        assertThat(countCareGroups(grupo.groupId())).as("care_groups depois").isZero();
        assertThat(countElders(grupo.elderId())).as("elders depois").isZero();
        assertThat(countContatos(grupo.elderId())).as("emergency_contacts depois").isZero();
        assertThat(countMemberships(grupo.groupId())).as("memberships depois").isZero();

        // AC-013.2 "inclusive a do Responsável" / AC-013.5 apagamento físico: as 6 contas (users)
        // desaparecem de verdade, não só o vínculo com o grupo.
        for (UUID userId : todosOsUserIds) {
            assertThat(users.findById(userId)).as("conta %s apagada por completo", userId).isEmpty();
        }
    }

    // ============================================================ AC-013.2 apaga também o arquivo físico da foto

    /**
     * Lacuna encontrada pelo verificador da T-033: {@code ElderService#deleteCareGroup} apaga a
     * linha de {@code elders} (via cascade de {@code care_groups}, D-35), mas nunca chama {@link
     * ElderPhotoStorage#delete} — então, se o idoso tinha uma foto salva (T-031, {@code PUT
     * /elders/{elderId}/photo}), o arquivo físico em disco fica órfão depois da exclusão, com dado
     * pessoal do idoso que já não existe mais em nenhuma linha do banco (viola o espírito de
     * AC-013.2 "a exclusão apaga o idoso" e a Constitution P5, minimização de dados). Este teste
     * sobe um idoso com foto, confirma que o arquivo existe antes do {@code DELETE} e prova que,
     * depois do {@code DELETE} (204), o arquivo físico correspondente ao {@code elderId} não existe
     * mais em {@code app.storage.elder-photos-dir} — hoje ele continua existindo, porque a produção
     * ainda não cuida desse caso.
     */
    @Test
    @DisplayName("AC-013.2 DELETE do grupo também apaga do disco o arquivo físico da foto do idoso")
    void ac_013_2_delete_apaga_tambem_a_foto_do_idoso_do_disco() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        MvcResult criacao = mockMvc.perform(criarIdoso(responsavel, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = idDoCorpo(criacao);

        // Etapa 1: envia uma foto JPEG válida (mesmo padrão de ElderPhotoTest) e resolve o caminho
        // físico esperado a partir do app.storage.elder-photos-dir configurado nos testes — o
        // arquivo é sempre nomeado só pelo elderId + extensão (D-30, ElderPhotoStorage#save).
        byte[] conteudoFoto = "conteudo-jpeg-fake-do-idoso-a-ser-excluido".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(enviarFoto(elderId, conteudoFoto, responsavel)).andExpect(status().isOk());
        Path arquivoDaFoto = Paths.get(elderPhotosDir).resolve(elderId + ".jpg");

        // Etapa 2: sanity antes da exclusão — o arquivo existe de verdade em disco.
        assertThat(Files.exists(arquivoDaFoto)).as("arquivo da foto existe antes do DELETE").isTrue();

        mockMvc.perform(excluir(elderId, responsavel)).andExpect(status().isNoContent());

        // Etapa 3 (ponto central do teste): depois do DELETE, o arquivo físico da foto não existe
        // mais — deleteCareGroup chama ElderPhotoStorage#delete, evitando um arquivo órfão com
        // dado pessoal do idoso (P5).
        assertThat(Files.exists(arquivoDaFoto))
                .as("arquivo da foto não deveria mais existir depois do DELETE do grupo (P5)")
                .isFalse();
    }

    // ============================================================ AC-013.3 e-mail a todos os estados, sem dado de saúde

    /**
     * AC-013.3: as 6 contas do grupo (qualquer status) recebem, cada uma, exatamente 1 e-mail com
     * o assunto do template de grupo excluído ({@code EmailTemplates.groupDeleted()}), e o corpo
     * não cita nenhum dado de saúde do idoso (nome, alergias, condições — P5). O envio é
     * pós-commit/assíncrono (D-23), por isso o {@code await} por destinatário. Uma conta de fora
     * do grupo (bystander, nunca envolvida nesta exclusão) não recebe nenhuma mensagem — prova de
     * que a lista de e-mails coletada por {@code DELETE /elders/{elderId}} (D-38) é exatamente a
     * do grupo excluído, nem mais nem menos.
     */
    @Test
    @DisplayName("AC-013.3 as 6 contas do grupo recebem o e-mail de grupo excluído, sem dado de saúde; bystander não recebe nada")
    void ac_013_3_seis_contas_recebem_email_sem_dado_de_saude_e_bystander_nao_recebe() throws Exception {
        GrupoCompleto grupo = grupoCompletoComSeisContas();
        List<String> emailsDoGrupo = grupo.todos().stream().map(User::getEmail).toList();
        User bystander = users.save(contaAtivaSemGrupo());

        mockMvc.perform(excluir(grupo.elderId(), grupo.responsavel())).andExpect(status().isNoContent());

        // Etapa: cada uma das 6 contas, em qualquer estado, recebe exatamente 1 e-mail correto.
        for (String email : emailsDoGrupo) {
            await().atMost(ESPERA_ENTREGA)
                    .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
            MailpitClient.Detail detalhe = mailpit.message(mailpit.messagesTo(email).getFirst().id());
            assertThat(detalhe.subject())
                    .as("assunto do template de grupo excluído (EmailTemplates.groupDeleted())")
                    .isEqualTo(ASSUNTO_GRUPO_EXCLUIDO);
            // P5: o corpo nunca cita dado de saúde do idoso, mesmo sendo genérico por natureza.
            assertThat(detalhe.text())
                    .as("corpo não contém nome/alergia/condição do idoso (P5)")
                    .doesNotContain("Francisca Oliveira", "Penicilina", "Hipertensão");
        }

        // D-38: a lista coletada é exatamente a do grupo excluído — ninguém de fora recebe nada.
        assertThat(mailpit.messagesTo(bystander.getEmail()))
                .as("conta de fora do grupo não recebe o e-mail de exclusão")
                .isEmpty();
    }

    // ============================================================ AC-013.2 e-mails ficam livres para novo cadastro

    /**
     * AC-013.2 "depois disso os e-mails ficam livres para novos cadastros": depois da exclusão, o
     * e-mail do Responsável e o de um membro {@code ATIVO} conseguem fazer {@code POST
     * /auth/signup} de novo, sem {@code 409}. A prova de que é mesmo um cadastro NOVO (e não a
     * rota "e-mail duplicado" de AC-001.4, que responde 202 do mesmo jeito mas não grava linha
     * nenhuma, D-15) é contar as linhas de {@code users} com aquele e-mail antes e depois do
     * {@code signup}: zero antes (conta antiga já apagada), uma depois.
     */
    @Test
    @DisplayName("AC-013.2 depois da exclusão, e-mail do Responsável e de um membro ATIVO fazem signup de novo, sem 409")
    void ac_013_2_emails_ficam_livres_para_novo_signup() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        MvcResult criacao = mockMvc.perform(criarIdoso(responsavel, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = idDoCorpo(criacao);
        UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
        User ativo = fabricarMembro(groupId, responsavel, MembershipStatus.ATIVO);
        String emailResponsavel = responsavel.getEmail();
        String emailAtivo = ativo.getEmail();

        mockMvc.perform(excluir(elderId, responsavel)).andExpect(status().isNoContent());

        // Etapa 1: as contas antigas já não existem (pré-condição para o signup contar como novo).
        assertThat(contasCom(emailResponsavel)).as("conta antiga do Responsável apagada").isZero();
        assertThat(contasCom(emailAtivo)).as("conta antiga do membro ATIVO apagada").isZero();

        // Etapa 2: os dois e-mails conseguem se cadastrar de novo, sem conflito.
        mockMvc.perform(signup(requisicaoValida(emailResponsavel))).andExpect(status().isAccepted());
        mockMvc.perform(signup(requisicaoValida(emailAtivo))).andExpect(status().isAccepted());

        // Etapa 3: prova de que foi um cadastro novo de verdade (gravou linha), não a rota de duplicidade.
        assertThat(contasCom(emailResponsavel)).as("signup criou uma conta nova de verdade").isEqualTo(1);
        assertThat(contasCom(emailAtivo)).as("signup criou uma conta nova de verdade").isEqualTo(1);
    }

    // ============================================================ D-13 a sessão morre com a conta

    /**
     * D-13: o token JWT do Responsável, emitido antes da exclusão do grupo, usado numa chamada
     * autenticada qualquer DEPOIS da exclusão (aqui, {@code GET /me}), recebe 401 — não porque o
     * token esteja expirado ou mal formado, mas porque o filtro JWT relê {@code User} no banco a
     * cada requisição e a conta não existe mais.
     */
    @Test
    @DisplayName("D-13 token do Responsável emitido antes da exclusão recebe 401 numa chamada autenticada depois")
    void d_13_token_antigo_do_responsavel_recebe_401_depois_da_exclusao() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        MvcResult criacao = mockMvc.perform(criarIdoso(responsavel, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = idDoCorpo(criacao);
        String tokenEmitidoAntes = tokenDe(responsavel);

        mockMvc.perform(excluir(elderId, responsavel)).andExpect(status().isNoContent());

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenEmitidoAntes))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ AC-013.4 membro comum não exclui

    /**
     * AC-013.4 "um membro não exclui a própria conta": um Cuidador {@code ATIVO} (sem
     * administração) chamando {@code DELETE /elders/{elderId}} do próprio grupo recebe 403
     * {@code NOT_ALLOWED} ({@code x-required-permission: admin} do contrato, verificado pelo
     * {@code AccountSecurityInterceptor} antes de chegar ao serviço) — e o grupo continua intacto
     * depois da tentativa, prova de que nada foi apagado por engano.
     */
    @Test
    @DisplayName("AC-013.4 Cuidador (sem administração) chamando DELETE recebe 403 NOT_ALLOWED; grupo continua intacto")
    void ac_013_4_membro_comum_tentando_excluir_recebe_403_e_grupo_continua_intacto() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        MvcResult criacao = mockMvc.perform(criarIdoso(responsavel, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = idDoCorpo(criacao);
        UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
        User cuidador = fabricarMembro(groupId, responsavel, MembershipStatus.ATIVO);

        MvcResult result = mockMvc.perform(excluir(elderId, cuidador))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");

        // Nada foi apagado: grupo, idoso e as 2 memberships (Responsável + Cuidador) continuam.
        assertThat(countCareGroups(groupId)).as("care_groups continua").isEqualTo(1);
        assertThat(countElders(elderId)).as("elders continua").isEqualTo(1);
        assertThat(countMemberships(groupId)).as("memberships continuam (Responsável + Cuidador)").isEqualTo(2);
    }

    // ============================================================ AC-015.3 / D-32 outro grupo

    /**
     * AC-015.3 / D-32: a mesma checagem de posse de {@code ElderService#resolveOwnElder} usada por
     * {@code GET}/{@code PUT}/foto vale para a exclusão — um Responsável administrador de OUTRO
     * grupo (grupo B, que passa a checagem de {@code x-required-permission: admin} porque é
     * admin do próprio grupo) chamando {@code DELETE} para o idoso do grupo A recebe 404 genérico
     * (indistinguível de "idoso inexistente"), e o grupo A continua intacto depois da tentativa.
     */
    @Test
    @DisplayName("AC-015.3 / D-32 Responsável de outro grupo (B) recebe 404 ao excluir o idoso do grupo A; grupo A continua intacto")
    void ac_015_3_responsavel_de_outro_grupo_recebe_404_e_grupo_a_continua_intacto() throws Exception {
        User responsavelA = users.save(contaAtivaSemGrupo());
        MvcResult criacaoA = mockMvc.perform(criarIdoso(responsavelA, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderIdA = idDoCorpo(criacaoA);
        UUID groupIdA = memberships.findByUserId(responsavelA.getId()).orElseThrow().getGroupId();

        User responsavelB = users.save(contaAtivaSemGrupo());
        mockMvc.perform(criarIdoso(responsavelB, elderInputComContato())).andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(excluir(elderIdA, responsavelB))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("code")).as("404 é Problem genérico, sem code").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();

        // Grupo A permanece intacto: a tentativa de outro grupo não apagou nada.
        assertThat(countCareGroups(groupIdA)).as("care_groups do grupo A continua").isEqualTo(1);
        assertThat(countElders(elderIdA)).as("elders do grupo A continua").isEqualTo(1);
    }

    // ============================================================ autenticação

    /** Controle básico: sem token nenhum, DELETE /elders/{elderId} responde 401. */
    @Test
    @DisplayName("sem sessão (sem token), DELETE /elders/{elderId} responde 401")
    void sem_sessao_delete_elder_responde_401() throws Exception {
        mockMvc.perform(delete(ELDERS + "/{elderId}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    // ============================================================ apoio

    /** Grupo completo com as 6 contas de AC-013.3 (Responsável + 1 por estado de membership). */
    private record GrupoCompleto(
            User responsavel,
            UUID groupId,
            UUID elderId,
            User ativo,
            User aguardandoAprovacao,
            User recusado,
            User expirado,
            User removido) {

        /** As 6 contas do grupo, na ordem em que foram fabricadas. */
        List<User> todos() {
            return List.of(responsavel, ativo, aguardandoAprovacao, recusado, expirado, removido);
        }
    }

    /**
     * Monta o cenário completo descrito no "Pronto quando" da T-033: o Responsável cria o grupo
     * pelo fluxo real de {@code POST /elders} (com 1 contato de emergência) e, em seguida, 5
     * contas extras ganham uma membership do mesmo grupo, uma para cada estado possível. A conta
     * {@code EXPIRADO} é fabricada por último porque depende de avançar o relógio 25 h — as
     * demais são decididas imediatamente, antes desse avanço, para nunca estourar o prazo de 24 h
     * de {@link Membership#approve}/{@link Membership#reject} (AC-006.8).
     */
    private GrupoCompleto grupoCompletoComSeisContas() throws Exception {
        User responsavel = users.save(contaAtivaSemGrupo());
        MvcResult criacao = mockMvc.perform(criarIdoso(responsavel, elderInputComContato()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = idDoCorpo(criacao);
        UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();

        // As 4 memberships com decisão imediata (dentro do prazo de 24h), antes de avançar o relógio.
        User ativo = fabricarMembro(groupId, responsavel, MembershipStatus.ATIVO);
        User aguardandoAprovacao = fabricarMembro(groupId, responsavel, MembershipStatus.AGUARDANDO_APROVACAO);
        User recusado = fabricarMembro(groupId, responsavel, MembershipStatus.RECUSADO);
        User removido = fabricarMembro(groupId, responsavel, MembershipStatus.REMOVIDO);

        // Por último: pedido que vence (avança o relógio 25h, além do prazo de 24h de AC-006.5).
        User expirado = fabricarMembroExpirado(groupId, responsavel);

        return new GrupoCompleto(
                responsavel, groupId, elderId, ativo, aguardandoAprovacao, recusado, expirado, removido);
    }

    /**
     * Fabrica uma conta confirmada com membership do grupo informado, já no estado terminal
     * pedido ({@code ATIVO}, {@code AGUARDANDO_APROVACAO}, {@code RECUSADO} ou {@code REMOVIDO}) —
     * mesmo padrão de {@code request}/{@code approve}/{@code reject}/{@code remove} já usado em
     * {@code ElderRetrievalTest}/{@code MeTest}. {@code EXPIRADO} tem fábrica própria, {@link
     * #fabricarMembroExpirado}, porque depende de avançar o relógio.
     */
    private User fabricarMembro(UUID groupId, User responsavel, MembershipStatus statusFinal) {
        User user = users.save(contaAtivaSemGrupo());
        Membership m = Membership.request(groupId, user.getId(), clock);
        switch (statusFinal) {
            case ATIVO -> m.approve(Role.CUIDADOR, responsavel.getId(), clock);
            case AGUARDANDO_APROVACAO -> { /* nada a fazer: fica pendente mesmo */ }
            case RECUSADO -> m.reject(responsavel.getId(), clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, responsavel.getId(), clock);
                m.remove(clock);
            }
            case EXPIRADO -> throw new IllegalArgumentException(
                    "EXPIRADO depende de avançar o relógio — use fabricarMembroExpirado");
        }
        memberships.save(m);
        return user;
    }

    /**
     * Fabrica uma conta cujo pedido de entrada venceu sem decisão (AC-006.5): pede entrada e,
     * depois, avança o {@link br.com.cuidamais.shared.testsupport.MutableClock} compartilhado 25h
     * (mais que o prazo de 24h) antes de chamar {@link Membership#expire}. Esse avanço é
     * permanente para o restante do teste (mesmo padrão de {@code MembershipTest}/{@code
     * InvitationTest}), por isso é sempre a última conta fabricada em {@link
     * #grupoCompletoComSeisContas()}.
     */
    private User fabricarMembroExpirado(UUID groupId, User responsavel) {
        User user = users.save(contaAtivaSemGrupo());
        Membership m = memberships.save(Membership.request(groupId, user.getId(), clock));
        clock.advance(Duration.ofHours(25));
        m.expire(clock);
        memberships.save(m);
        return user;
    }

    /** Conta {@code ATIVO} sem grupo, pronta para receber uma membership (mesmo padrão de T-030). */
    private User contaAtivaSemGrupo() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "delete-grupo-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private static Map<String, Object> enderecoValido() {
        Map<String, Object> endereco = new LinkedHashMap<>();
        endereco.put("zipCode", "22041001");
        endereco.put("street", "Rua Barata Ribeiro");
        endereco.put("number", "370");
        endereco.put("complement", "Apto 802");
        endereco.put("district", "Copacabana");
        endereco.put("city", "Rio de Janeiro");
        endereco.put("state", "RJ");
        return endereco;
    }

    private static Map<String, Object> contato(String nome, String parentesco, String telefone) {
        Map<String, Object> contato = new LinkedHashMap<>();
        contato.put("name", nome);
        contato.put("relationship", parentesco);
        contato.put("phone", telefone);
        contato.put("address", enderecoValido());
        return contato;
    }

    /**
     * Corpo de {@code ElderInput} com 1 contato de emergência e alergia/condição preenchidas — os
     * 3 dados de saúde/identificação que o teste de e-mail (AC-013.3/P5) confirma NÃO aparecerem
     * no corpo do e-mail de grupo excluído.
     */
    private Map<String, Object> elderInputComContato() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
        req.put("address", enderecoValido());
        req.put("allergies", "Penicilina");
        req.put("conditions", "Hipertensão");
        req.put("emergencyContacts", List.of(contato("Joana Oliveira", "Filha", "21988887777")));
        return req;
    }

    private RequestBuilder criarIdoso(User autor, Map<String, Object> corpo) throws Exception {
        return post(ELDERS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(corpo));
    }

    /** {@code DELETE /elders/{elderId}} (T-033) autenticado como {@code autor}. */
    private RequestBuilder excluir(UUID elderId, User autor) {
        return delete(ELDERS + "/{elderId}", elderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    /**
     * {@code PUT /elders/{elderId}/photo} (T-031) com um JPEG válido autenticado como {@code
     * autor} — mesmo padrão de {@code ElderPhotoTest#enviarFoto}, usado aqui só para preparar o
     * cenário do teste de AC-013.2 que confirma a remoção do arquivo físico após o {@code DELETE}
     * do grupo.
     */
    private RequestBuilder enviarFoto(UUID elderId, byte[] conteudo, User autor) {
        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, conteudo);
        return (MockMultipartHttpServletRequestBuilder) multipart(HttpMethod.PUT, ELDERS + "/{elderId}/photo", elderId)
                .file(arquivo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    private UUID idDoCorpo(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private int countCareGroups(UUID groupId) {
        return jdbc.queryForObject("SELECT count(*) FROM care_groups WHERE id = ?", Integer.class, groupId);
    }

    private int countElders(UUID elderId) {
        return jdbc.queryForObject("SELECT count(*) FROM elders WHERE id = ?", Integer.class, elderId);
    }

    private int countContatos(UUID elderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM emergency_contacts WHERE elder_id = ?", Integer.class, elderId);
    }

    private int countMemberships(UUID groupId) {
        return jdbc.queryForObject("SELECT count(*) FROM memberships WHERE group_id = ?", Integer.class, groupId);
    }

    private int contasCom(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    // ============================================================ signup (AC-013.2 "e-mails ficam livres")

    /** Corpo válido de {@code SignupRequest} (mesmo padrão de {@code SignupTest}). */
    private static Map<String, Object> requisicaoValida(String email) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Maria da Silva");
        req.put("email", email);
        req.put("phone", "21987654321");
        req.put("address", enderecoValido());
        req.put("password", "senhaForte1");
        return req;
    }

    private RequestBuilder signup(Map<String, Object> corpo) throws Exception {
        return post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }
}
