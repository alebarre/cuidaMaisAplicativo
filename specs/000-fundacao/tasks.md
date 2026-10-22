# Tasks 000 — Fundação

- **Spec:** rev. 17 · **Plan:** rev. 2 (D-01…D-42) · **Contrato:** `contracts/openapi.yaml` 0.2.0
- **Status:** rev. 2, revisado contra spec rev. 17 e plan rev. 2; **aprovado por Alexandre em
  2026-09-25**, pronto para implementação
- **Histórico:** rev. 1 (47 tarefas, spec rev. 9). Rev. 2 mantém os IDs existentes, atualiza o
  conteúdo de quase todas e acrescenta T-017, T-018, T-019, T-027, T-028, T-033, T-034, T-048,
  T-049, T-061, T-062 (58 tarefas).

## Como ler este arquivo

- Cada tarefa é **pequena** (algumas horas), tem **uma** entrega verificável e diz quais **ACs** e
  **decisões** atende.
- **Pronto quando:** o teste que prova que a tarefa acabou. **Escreva o teste primeiro**, veja-o
  falhar e então implemente (Constitution §6).
- `[P]` = pode ser feita em paralelo com as outras `[P]` da mesma fase.
- `Depende de:` só aparece quando a dependência não é óbvia pela ordem.
- Ao final de cada fase há um **checkpoint**: não avance com checkpoint vermelho.
- Se durante a implementação algo não couber na spec ou no plan, **pare e atualize o documento
  primeiro** (Constitution §7).
- IDs de tarefa nunca são renumerados; tarefas novas entram no fim da fase.
- Textos exatos de tela vêm **sempre** do catálogo de mensagens (T-061, D-40), nunca digitados
  no componente.

---

## Fase 0 — Esqueleto do projeto

- [x] **T-001** `docker-compose.yml` na raiz com PostgreSQL 17 e Mailpit.
  *Pronto quando:* `docker compose up` sobe os dois e a UI do Mailpit abre em `localhost:8025`.
- [x] **T-002** Backend Spring Boot 4.1 / Java 25 (Maven): Web, Data JPA, Security, Validation,
  Flyway, Mail, PostgreSQL. Profiles `dev`, `test`, `prod`; bean `Clock` (D-10); pacotes por
  domínio (D-01). *Pronto quando:* `mvn verify` passa com um teste de contexto.
- [x] **T-003** Base de teste de integração com Testcontainers (PostgreSQL + Mailpit) reutilizável
  por todas as classes de teste, com `Clock` substituível para avançar o tempo (D-10).
  *Pronto quando:* um teste sobe os containers, roda uma migração vazia e avança o relógio.
- [x] **T-004** `[P]` Frontend Angular 22 standalone + signals, `@angular/pwa` só para o *app shell*
  (D-22), fonte base 16px e alvos ≥ 44px (P4). *Pronto quando:* `ng build` e `ng test` passam; o
  Lighthouse reconhece o app como instalável.
- [x] **T-005** `[P]` Geração do cliente TypeScript a partir do `openapi.yaml` 0.2.0 com script npm
  (D-19). *Pronto quando:* `npm run api:generate` gera o cliente sem erros e ele compila.
- [x] **T-006** Teste de contrato: validar requisições e respostas dos testes de API contra o
  `openapi.yaml` (ex.: `swagger-request-validator` no MockMvc). *Pronto quando:* uma rota
  propositalmente fora do contrato faz o teste falhar.
- [x] **T-007** Tratamento global de erros em Problem Details (RFC 9457) com `code` estável
  (D-40) e sem dados de saúde (Constitution §5, P5); `404` idêntico para recurso inexistente ou de
  outro grupo e `403` com `code = NOT_ALLOWED` para ação vedada ao papel (D-32).
  ACs: 015.2, 015.3. *Pronto quando:* teste de validação retorna `ValidationProblem` conforme o
  contrato e os três casos de AC-015.3 (próprio grupo, outro grupo, inexistente) têm teste.

**Checkpoint 0:** tudo compila, containers sobem, teste de contrato está ativo.

## Fase 1 — Domínio e dados (sem HTTP)

- [x] **T-010** Migração `V1__foundation.sql` com todas as tabelas do plan §3 rev. 2: `user_id`
  UNIQUE (D-02), índice único parcial `WHERE is_admin` (D-03), `CHECK (NOT is_admin OR
  role='FAMILIAR')` (D-29), `emergency_contacts` (D-26), `invite_code_attempts` e
  `invitations.origin` (D-34), `refresh_tokens.absolute_expires_at` (D-11),
  `memberships.retention_deadline` (D-09), `allergies`/`conditions` `VARCHAR(500)` e `CHECK
  (birth_date <= CURRENT_DATE)` (D-36), **`ON DELETE CASCADE` em toda tabela do grupo** (D-35).
  *Pronto quando:* testes de repositório provam que o banco **recusa** dois admins no mesmo grupo,
  um Cuidador admin, alergia com 501 caracteres e nascimento futuro, e que apagar um
  `care_groups` leva idoso, contatos, memberships e convites junto.
- [x] **T-011** `[P]` `Endereco` como `record` `@Embeddable` com validação (D-25).
  ACs: 011.1, 011.2. *Pronto quando:* testes cobrem CEP com 7/8/9 dígitos, UF inválida, número
  "S/N", complemento vazio.
- [x] **T-012** `[P]` Entidade `Membership` com a máquina de estados (D-07): `approve(role)`,
  `reject`, `expire`, `remove`, `changeRole`, `reinstate` (reconvite, AC-004.6), com `Clock`.
  ACs: 004.3, 004.6, 006.3, 006.4, 006.5, 009.1, 009.2, 009.4, 012.1.
  *Pronto quando:* `@ParameterizedTest` com **cada linha** da tabela de AC-012.1 que envolve
  membership, mais um teste por transição inválida (ex.: aprovar expirado, remover admin,
  tornar admin um Cuidador, reinstaurar um `ATIVO`).
- [x] **T-013** `[P]` Serviço de OTP: gera 6 dígitos, guarda hash (D-04), 15 min, 5 tentativas,
  reenvio 1/min. ACs: 005.1, 005.2, 005.3. *Pronto quando:* testes com `Clock` fixo cobrem
  expiração no minuto 15, 5ª tentativa errada e reenvio antes de 60 s.
- [x] **T-014** `[P]` Entidade `Invitation`: código hash, vence em 7 dias, uso único, `origin`
  (`MANUAL`/`AUTO`), status `ENVIADO`/`USADO`/`VENCIDO`/`CANCELADO`; reenvio cria convite novo e
  cancela o `ENVIADO` anterior (D-31). ACs: 003.2, 003.4, 003.10, 003.12.
  *Pronto quando:* `@ParameterizedTest` com **cada linha** da tabela de AC-003.10 e um teste por
  transição inválida (ex.: cancelar `USADO`, usar `VENCIDO`).
- [x] **T-015** `[P]` `MemberVisibilityPolicy` (D-28) para endereço, telefone e e-mail de uma vez.
  ACs: 011.5, 011.8. *Pronto quando:* `@ParameterizedTest` com **cada célula** da matriz da spec,
  para os três campos.
- [x] **T-016** Infra de e-mail: eventos de domínio + `@TransactionalEventListener(AFTER_COMMIT)`
  assíncrono (D-23); templates pt-BR sem dados de saúde (P5) para: código de confirmação,
  tentativa de cadastro duplicado (001.4), convite (003.1), pedido pendente ao Responsável
  (006.7), resultado do pedido (007.1), bloqueio de senha (008.4), grupo excluído (013.3),
  lembrete e exclusão por retenção (014.1, 014.3); profile `dev`→Mailpit, `prod`→Gmail via
  variáveis de ambiente (D-24). *Pronto quando:* teste prova que (a) o e-mail chega no Mailpit
  após o commit e (b) **nenhum** e-mail sai se a transação faz rollback.
- [x] **T-017** `[P]` `AccountState.of(user, membership)` (D-33): estado derivado de AC-012.1 e
  `hasGroup`. ACs: 001.7, 012.1. *Pronto quando:* um teste por estado, inclusive `ATIVO` sem
  grupo, e um teste de que nenhuma outra combinação é aceita.
- [x] **T-018** `[P]` `InviteCodeAttempts` (D-34): contador **por e-mail**, exista convite ou não;
  5 erros → `blocked_until = agora + 30 min`; zera ao fim do bloqueio, ao criar convite para o
  e-mail e ao usar um código; `auto_resends` com teto 3 que só zera com código usado.
  ACs: 003.3, 003.9, 003.11, 004.7. *Pronto quando:* testes com `Clock` cobrem 4ª e 5ª tentativa,
  tentativa com código certo durante o bloqueio, fim dos 30 min, zeragem por convite novo e o
  4º reenvio automático negado.
- [x] **T-019** Infraestrutura de jobs (D-09, D-42): um `@Scheduled` a cada 5 min chamando
  rotinas idempotentes com `SELECT ... FOR UPDATE SKIP LOCKED` e `Clock`. *Pronto quando:* teste
  prova que duas execuções concorrentes da mesma rotina não processam a mesma linha duas vezes.

**Checkpoint 1:** todas as regras de negócio testadas sem nenhum controller; as duas tabelas de
estados e a matriz de visibilidade têm um teste por linha/célula.

## Fase 2 — API de autenticação e conta

- [x] **T-020** `POST /auth/signup` (resposta neutra 202, D-15): cria `AGUARDANDO_CONFIRMACAO_EMAIL`;
  e-mail existente não cria nada e avisa o dono. ACs: 001.1, 001.3, 001.4, 011.1, 011.2.
  *Pronto quando:* testes para cadastro novo, e-mail repetido (mesma resposta, nada criado,
  e-mail de aviso no Mailpit), senha fraca e endereço inválido.
- [x] **T-021** JWT de 15 min + refresh token opaco de 7 dias renovado a cada uso, com **teto
  absoluto de 30 dias**, rotação e detecção de reuso; cookie `HttpOnly; Secure; SameSite=Strict`
  (D-11, D-12). AC: 008.3. *Pronto quando:* testes de renovação, expiração após 7 dias sem uso,
  expiração no dia 30 mesmo com uso diário, e revogação da família no reuso.
- [x] **T-022** `POST /auth/verify-email` ativa a conta e abre sessão; **não cria grupo** (D-05
  rev. 2). ACs: 001.2, 005.1, 005.2. Depende de: T-021. *Pronto quando:* após confirmar, `users`
  tem `email_verified_at` e **não** existe membership.
- [x] **T-023** `POST /auth/login`: `401` único para e-mail inexistente, senha errada e conta
  bloqueada (inclusive com senha certa); bloqueio por conta após 5 erros por 15 min com **um**
  e-mail na transição; contador zera no acerto e ao fim da janela; `403` com `code` por estado só
  com senha correta (D-14, D-15, D-33). ACs: 008.1, 008.2, 008.4.
  *Pronto quando:* um teste para cada `code` de `AccountStateProblem`, um para senha certa durante
  o bloqueio (recebe 401 igual), um para o e-mail único no Mailpit e um para `ATIVO` sem grupo
  entrando normalmente.
- [x] **T-024** Filtro de segurança que lê `status`, `role` e `is_admin` **do banco** a cada
  requisição (D-13) e aplica `x-required-account-state` e `x-required-permission` do contrato.
  ACs: 009.1, 009.2, 015.2, 015.3. *Pronto quando:* token válido de membro removido recebe 401 na
  requisição seguinte; token de Cuidador que virou Familiar já vê o novo papel sem novo login;
  Cuidador chamando rota de admin recebe 403 `NOT_ALLOWED`.
- [ ] **T-025** `[P]` `POST /auth/refresh` e `POST /auth/logout`. ACs: 008.3, 008.5.
  *Pronto quando:* após `logout`, o refresh token daquele aparelho não renova mais e os de outros
  aparelhos continuam válidos.
- [ ] **T-026** `[P]` `POST /auth/resend-code` (neutro, 1/min) e redefinição de senha
  (`request` neutro, `confirm` com a nova senha, encerra todas as sessões, não muda o estado)
  (D-15, D-37). ACs: 001.6, 005.3, 010.1, 010.2, 010.3, 010.4, 010.5.
  *Pronto quando:* teste de conta `AGUARDANDO_APROVACAO` redefinindo a senha e continuando no
  mesmo estado; refresh tokens antigos inválidos após `confirm`.
- [ ] **T-027** `[P]` `GET /me/account` devolve o estado derivado e `hasGroup` (D-33, D-20).
  ACs: 001.7, 012.1. *Pronto quando:* um teste por estado devolvido.
- [ ] **T-028** Job de descarte de contas não confirmadas há mais de 24 h, **sem e-mail** (D-09
  item 2). AC: 001.5. Depende de: T-019. *Pronto quando:* com `Clock` avançado 24 h a conta some,
  o e-mail fica livre para novo `signup` e o Mailpit não recebe nada.

**Checkpoint 2:** criador consegue se cadastrar, confirmar e entrar; contrato verde.

## Fase 3 — Idoso, grupo e dados pessoais

- [ ] **T-030** `POST /elders` cria **grupo + idoso + membership admin** numa transação (D-05),
  só para `ATIVO` sem grupo (409 se já tem); `GET/PUT /elders/{id}` com contatos de emergência,
  limites de D-36 e sem histórico; 404 para outro grupo (D-32).
  ACs: 001.2, 002.1, 002.2, 002.3, 002.5, 002.7, 002.8, 002.9, 011.7, 015.3.
  *Pronto quando:* após o `POST` existem exatamente um grupo, um idoso e uma membership
  `FAMILIAR` admin; segundo `POST` recebe 409; nascimento futuro e alergia de 501 caracteres
  recebem 400; Cuidador vê o endereço do idoso e dos contatos.
- [ ] **T-031** `[P]` `PUT/DELETE /elders/{id}/photo`: JPEG/PNG até 5 MB, substitui a anterior,
  415 para outro formato, 413 acima do limite (D-30). AC: 002.6.
- [ ] **T-032** `[P]` `GET/PUT /me`, com `membership` nulo para conta sem grupo. AC: 011.6.
- [ ] **T-033** `DELETE /elders/{id}` exclui o grupo inteiro (D-35, D-38): coleta os e-mails,
  apaga via cascade, apaga os `users` do grupo, envia o e-mail de AC-013.3 a todos os status.
  ACs: 013.2, 013.3, 013.4, 013.5. *Pronto quando:* teste com membros `ATIVO`,
  `AGUARDANDO_APROVACAO`, `RECUSADO` e `REMOVIDO` prova que todas as linhas somem, todos recebem
  e-mail no Mailpit, o token do Responsável recebe 401 em seguida e os e-mails podem fazer
  `signup` de novo; membro comum chamando recebe 403.
- [ ] **T-034** `[P]` Job de retenção de conta `ATIVO` sem grupo: lembrete aos 23 dias, exclusão
  aos 30 com e-mail curto (D-09 item 3). AC: 014.3. Depende de: T-019.
  *Pronto quando:* com `Clock` avançado 23 e 30 dias, os dois e-mails chegam e a conta some.

**Checkpoint 3:** Responsável tem grupo e idoso; `GET /me/account` reflete cada etapa; exclusão do
grupo deixa o banco vazio.

## Fase 4 — Convites, aprovação e membros

- [ ] **T-040** Convites do Responsável: criar (409 `NO_ELDER` sem idoso, `ALREADY_MEMBER`,
  `EMAIL_UNAVAILABLE` para outro grupo ou conta sem grupo, reconvite permitido para
  `RECUSADO`/`EXPIRADO`/`REMOVIDO`, e-mail `ENVIADO` repetido = reenvio), listar (só admin, com
  `failedAttempts`, `blockedUntil`, `autoResends`), reenviar (201, zera tentativas e bloqueio),
  cancelar (só `ENVIADO`) (D-31, D-39). E-mail com nome do Responsável e **primeiro nome** do
  idoso. ACs: 002.4, 003.1, 003.4, 003.5, 003.6, 003.7, 003.8, 003.9, 003.12.
  *Pronto quando:* um teste por `code` de conflito, um por status reenviável, um provando que o
  e-mail não contém o sobrenome do idoso, e um provando que reenviar durante o bloqueio o
  encerra na hora.
- [ ] **T-041** `POST /invitations/redeem` (D-16, D-34): `next = REGISTER` com token de 15 min;
  `next = REINSTATED` quando a conta existe no grupo (volta a `AGUARDANDO_APROVACAO`, senha
  antiga, prazo novo, e-mail ao Responsável); `400` idêntico com `remainingAttempts` para código
  errado e e-mail sem convite; `423` com `blockedUntil` na 5ª e durante 30 min mesmo com código
  certo; `410` para `VENCIDO`/`CANCELADO`/`USADO` só com código conferido; rate limit por IP
  (R-03). ACs: 003.2, 004.1, 004.5, 004.6, 004.7.
  *Pronto quando:* testes provam que e-mail sem convite e código errado recebem **bytes iguais**
  no corpo (exceto `instance`), que código errado para convite `VENCIDO` recebe 400 e não 410, e
  que o `REINSTATED` mantém a senha antiga.
- [ ] **T-042** `POST /invitations/register` cria usuário (já com `email_verified_at` preenchido:
  o código de convite comprovou o e-mail, AC-004.3, D-33) + membership `AGUARDANDO_APROVACAO` com
  prazo de 24 h e dispara o e-mail ao Responsável. ACs: 004.2, 004.3, 004.4, 006.7.
- [ ] **T-043** `GET /members`: sem `status` só `ATIVO` para qualquer membro; `status` só para
  admin (403 para os demais); pendentes vencidos não aparecem; endereço, telefone e e-mail
  conforme a política (D-28, D-39). ACs: 006.2, 006.9, 009.5, 011.5, 011.8.
  *Pronto quando:* teste de API da matriz para Cuidador e Familiar nos três campos; Cuidador com
  `?status=REMOVIDO` recebe 403; Responsável vê telefone e endereço do `REMOVIDO`.
- [ ] **T-044** `[P]` `GET /members/pending-count`. AC: 006.1.
- [ ] **T-045** `approve` / `reject` com lock otimista (`version`, D-08), 410 `REQUEST_EXPIRED`
  para prazo vencido e e-mail do resultado. ACs: 006.3, 006.4, 006.6, 006.8, 007.1, 007.2.
  *Pronto quando:* teste com duas decisões simultâneas prova que só a primeira vale (a outra
  recebe 409); aprovar aos 24 h e 1 s recebe 410 antes do job rodar.
- [ ] **T-046** Job de expiração de pedidos (D-09 item 1): `EXPIRADO` + e-mail +
  `retention_deadline`. ACs: 006.5, 006.8, 007.1. Depende de: T-019.
  *Pronto quando:* com `Clock` avançado 24 h, aprovar retorna 410 **mesmo antes** do job, e
  depois do job o status é `EXPIRADO`, o e-mail foi enviado e `retention_deadline` = agora + 30 d.
- [ ] **T-047** `PATCH` (papel, vale na hora) e `DELETE` de membro (`REMOVIDO`, sessões
  encerradas, dados preservados só para o admin); admin intocável.
  ACs: 009.1, 009.2, 009.3, 009.4, 014.2. *Pronto quando:* após `DELETE`, a linha continua no
  banco com nome, telefone e endereço; o refresh token do removido está revogado. (AC-009.3 é
  provado aqui pela preservação do nome; a visibilidade nos registros fica para as features
  001–008.)
- [ ] **T-048** Job de reenvio automático (D-09 item 4): ao fim do bloqueio, se há convite
  `ENVIADO` e `auto_resends < 3`, cancela e cria outro com `origin = AUTO` e envia o e-mail; não
  faz nada se um convite manual foi criado durante o bloqueio. AC: 003.3.
  Depende de: T-018, T-019, T-040. *Pronto quando:* testes cobrem o 1º reenvio automático (novo
  código, prazo novo, e-mail no Mailpit), o 4º negado, e-mail sem convite (nada acontece) e o
  cancelamento do reenvio pendente por um reenvio manual.
- [ ] **T-049** Job de retenção de contas `RECUSADO`/`EXPIRADO` (D-09 item 3): exclusão aos 30
  dias com os convites do e-mail e e-mail curto; `retention_deadline` recalculado a cada convite
  criado e a cada convite que termina sem uso. AC: 014.1. Depende de: T-019, T-040.
  *Pronto quando:* testes com `Clock` provam a exclusão no dia 30, o reinício do prazo por convite
  enviado e por convite `VENCIDO`/`CANCELADO`, e que os convites do e-mail sumiram junto.

**Checkpoint 4:** backend completo; todo AC de backend tem teste verde; contrato 100% coberto;
os quatro jobs testados com relógio avançado.

## Fase 5 — Frontend (mobile-first)

- [ ] **T-050** Core: interceptor que injeta o access token e renova via `/auth/refresh` no 401;
  token só em memória (D-12); guards por estado da conta lido de `/me/account` (D-20, D-33):
  `ATIVO` sem grupo fica **preso** no cadastro do idoso, só com "Sair"; `isAdmin` esconde
  convites, aprovação, filtros, edição do idoso e exclusão do grupo. ACs: 001.7, 015.1.
  *Pronto quando:* testes de guard para cada estado e um teste de que os botões vedados não
  existem no DOM de um Cuidador.
- [ ] **T-051** `[P]` `<app-endereco-form>` com ViaCEP (timeout **15 s**), máscara de CEP e o
  aviso exato de AC-011.4 (D-27). ACs: 011.3, 011.4. *Pronto quando:* testes de componente com
  ViaCEP simulado para sucesso, `{"erro": true}` e timeout, conferindo o texto pelo catálogo.
- [ ] **T-052** Criar grupo (com o aviso de 24 h) + verificar e-mail + reenviar código.
  ACs: 001.1, 001.2, 001.3, 001.6, 005.1, 005.2, 005.3.
- [ ] **T-053** Login com a mensagem de cada estado (`AGUARDANDO_CONFIRMACAO_EMAIL` leva à tela
  de confirmação), sem nenhuma indicação de bloqueio, e ação "Sair" visível em toda a área
  logada. ACs: 008.1, 008.2, 008.4, 008.5. *Pronto quando:* um teste por `code` e um provando que
  401 mostra sempre o mesmo texto.
- [ ] **T-054** "Tenho um código de convite": mensagens de `VENCIDO`/`CANCELADO`/`USADO`, tentativas
  restantes, **contador regressivo** de 30 min (D-41) que reabilita o formulário ao chegar a
  zero, `REINSTATED` mostrando o aviso de 24 h, e cadastro com e-mail travado → tela de conclusão.
  ACs: 004.1, 004.2, 004.4, 004.5, 004.6, 004.7. *Pronto quando:* testes de componente com o
  relógio simulado para o contador e um teste por `code` de erro.
- [ ] **T-055** `[P]` Esqueci a senha: pedir código, digitar código e nova senha, voltar ao login.
  ACs: 010.1, 010.2, 010.3, 010.4, 010.5.
- [ ] **T-056** Cadastro/edição/visualização do idoso, contatos e foto: data em dd/mm/aaaa com
  validação, limites de 500 caracteres, uma foto com substituir/remover.
  ACs: 002.2, 002.5, 002.6, 002.7, 002.8.
- [ ] **T-057** Convites: lista com status, tentativas e horário de fim do bloqueio; novo convite
  com as mensagens de conflito; reenviar; cancelar. ACs: 003.4, 003.5, 003.7, 003.8.
- [ ] **T-058** Pedidos pendentes com badge (polling de 60 s em primeiro plano, D-17, D-21),
  aprovação escolhendo o papel, recusa e a mensagem de pedido vencido.
  ACs: 006.1, 006.2, 006.3, 006.4, 006.8.
- [ ] **T-059** `[P]` Lista de membros (endereço, telefone e e-mail só quando vierem da API; filtro
  de status só para o Responsável), alterar papel, remover.
  ACs: 006.9, 009.1, 009.2, 009.5, 011.5, 011.8.
- [ ] **T-060** `[P]` Meus dados (telefone e endereço). AC: 011.6.
- [ ] **T-061** `[P]` Catálogo de mensagens pt-BR (D-40): uma chave por texto exato da spec,
  nomeada pelo AC, e a tabela `code` → chave. ACs: 001.1, 003.5, 003.7, 004.4, 004.5, 004.7,
  006.8, 008.2, 011.4, 012.2, 013.1, 015.2. *Pronto quando:* um teste percorre a lista de textos
  exatos da spec e falha se algum não estiver no catálogo, caractere por caractere.
- [ ] **T-062** Excluir grupo: tela com o texto exato de AC-013.1, botão "Excluir
  definitivamente", aviso "O grupo de cuidado foi excluído." e retorno ao login (AC-012.2).
  ACs: 012.2, 013.1. Depende de: T-061.

**Checkpoint 5:** todas as telas usam só o catálogo; nenhuma ação vedada aparece para Cuidador.

## Fase 6 — Validação da feature

- [ ] **T-070** E2E Playwright em viewport de celular: criar grupo → confirmar e-mail (lido no
  Mailpit) → cadastrar idoso → convidar → errar o código 5 vezes e ver o contador → resgatar
  código → cadastrar → aprovar → convidado entra → Responsável exclui o grupo → login recusado.
- [ ] **T-071** Script de rastreabilidade: lista todo `AC-xxx.y` da spec (85) e falha se algum não
  aparecer em nenhum teste; aceita como prova alternativa as listas de
  `x-acceptance-criteria-outside-contract` do contrato (tabelas de estado, jobs, e-mails,
  frontend, registros futuros), mas exige teste para todas elas exceto `care-records-future-features`.
  *Pronto quando:* roda no `mvn verify`/CI e está verde.
- [ ] **T-072** Revisão de acessibilidade (P1: ações frequentes em ≤ 3 toques; P4: contraste AA,
  alvos ≥ 44px) nas telas da 000, inclusive o contador regressivo e a confirmação de exclusão.
- [ ] **T-073** Retrospectiva: o que a spec previu, o que escapou, o que mudou nos documentos
  durante a implementação (incluindo as 17 revisões da spec antes de qualquer código). Registrar
  em `JORNADA-SDD.md`.

**Checkpoint final (Definition of Done da feature):** 85 ACs com teste verde, contrato conferido,
E2E verde, nenhuma `[DÚVIDA]` nova sem resposta, documentos coerentes com o código.
