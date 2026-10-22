# Plan 000 — Fundação

- **Spec:** `spec.md` rev. 17
- **Constitution:** v1.3
- **Status:** rev. 2, revisado contra a spec rev. 17; **aprovado por Alexandre em 2026-09-25**
- **Histórico:** rev. 1 escrita para a spec rev. 9 (D-01 a D-32); rev. 2 atualiza D-02, D-05, D-07,
  D-09, D-11, D-13, D-14, D-15, D-16, D-20, D-27, D-28, D-31, D-32 e acrescenta D-33 a D-42.

> Aqui entram as decisões técnicas. Cada decisão importante tem um ID (`D-xx`) e indica
> quais critérios de aceite (`AC-xxx`) atende. Decisões alteradas na rev. 2 trazem a marca
> **(rev. 2)**.

## 1. Versões fixadas (vale para o projeto inteiro)

| Item | Versão |
|---|---|
| Java | 25 (LTS) |
| Spring Boot | 4.1.x |
| PostgreSQL | 17 (imagem Docker) |
| Angular | 22.x |
| Node | LTS atual exigida pelo Angular 22 |
| Build backend | Maven |
| E-mail real | **Gmail SMTP** (`smtp.gmail.com:587`, STARTTLS) com senha de app |
| E-mail em dev/testes | Mailpit v1.29.7 (Docker): captura os e-mails, nada sai para a internet |
| Testcontainers | 2.0.5 (BOM no `pom.xml`; o Boot 4 não gerencia mais a versão; módulos `testcontainers-postgresql`, `testcontainers-junit-jupiter`) |
| Spring Boot (exato) | 4.1.1 · JDK Temurin 25.0.4.1 |

## 2. Estrutura do backend (pacotes por domínio)

```
backend/src/main/java/br/com/cuidamais/
├── shared/        (erros Problem Details, relógio, config, catálogo de mensagens)
├── auth/          (login, tokens, OTP, redefinição de senha, bloqueio de senha)
├── group/         (grupo de cuidado, idoso, exclusão do grupo)
├── membership/    (membros, convites, tentativas de código, aprovação, retenção)
└── notification/  (envio de e-mails)
```

**D-01** Organização por domínio (e não por camada `controller/service/repository`), para que cada
feature futura (001–008) seja um pacote novo e isolado.

## 3. Modelo de dados

```
users            (id, name, email UNIQUE, phone, <endereço>, password_hash,
                  email_verified_at, failed_logins, locked_until, lock_notified_at,
                  elder_reminder_sent_at, created_at)
care_groups      (id, created_by → users, created_at)
elders           (id, group_id UNIQUE → care_groups ON DELETE CASCADE, name, birth_date,
                  <endereço>, photo_path, allergies VARCHAR(500), conditions VARCHAR(500),
                  updated_at)
emergency_contacts (id, elder_id → elders ON DELETE CASCADE, name, relationship, phone,
                  <endereço>, position)
memberships      (id, group_id → care_groups ON DELETE CASCADE, user_id UNIQUE → users,
                  role, is_admin, status, requested_at, expires_at, decided_by → users,
                  decided_at, retention_deadline, version)
invitations      (id, group_id → care_groups ON DELETE CASCADE, email, code_hash, status,
                  origin, expires_at, created_by, created_at, used_at, cancelled_at)
invite_code_attempts (email PK, failed_attempts, blocked_until, auto_resends, updated_at)
otp_codes        (id, user_id → users ON DELETE CASCADE, purpose, code_hash, attempts,
                  expires_at, consumed_at, last_sent_at)
refresh_tokens   (id, user_id → users ON DELETE CASCADE, family_id, token_hash, expires_at,
                  absolute_expires_at, revoked_at, created_at)

<endereço> = address_zip_code CHAR(8), address_street, address_number, address_complement NULL,
             address_district, address_city, address_state CHAR(2)
```

- **D-02 (rev. 2)** `memberships.user_id` é UNIQUE: uma pessoa participa de **um único grupo**
  (spec §5, AC-003.7). Um `users` **pode existir sem membership**: é o criador que confirmou o
  e-mail e ainda não cadastrou o idoso (AC-001.7). Ver D-33.
- **D-29** `role` tem só dois valores (`CUIDADOR`, `FAMILIAR`) e o Responsável é
  `is_admin = true`. Uma `CHECK (NOT is_admin OR role = 'FAMILIAR')` impede Cuidador administrador.
  Assim, "Familiar" nas regras de permissão já inclui o Responsável sem caso especial.
- **D-03** Índice único parcial `memberships(group_id) WHERE is_admin`: o banco garante
  **exatamente um Responsável** por grupo (AC-002.1, AC-009.4).
- **D-04** Códigos (OTP e convite) são guardados **somente como hash** (P5). O código em texto
  existe apenas no e-mail.
- **D-05 (rev. 2)** `verify-email` **só ativa a conta** (`email_verified_at`). O grupo nasce em
  `POST /elders`: numa única transação são criados `care_groups`, `elders` e a `membership` do
  criador com papel `FAMILIAR` e `is_admin = true` (AC-001.2, AC-002.1, AC-002.4). Assim não
  existe grupo sem idoso, e as permissões continuam checadas de um só jeito para todos.
- **D-06** Migrações com Flyway: `V1__foundation.sql`.
- **D-25** `Endereco` é um **objeto de valor** (`@Embeddable`, imutável, um `record` Java) em
  `shared/`, embutido em `User`, `Elder` e `EmergencyContact` com colunas prefixadas `address_`.
  Não é uma tabela própria porque um endereço não tem identidade nem vida fora da pessoa; a
  validação (AC-011.1, AC-011.2) fica **num lugar só**, no construtor do `record` e em
  anotações Bean Validation reaproveitadas pelo DTO. As features futuras (ex.: 007 Visitas,
  endereço do profissional) reutilizam o mesmo tipo.
- **D-26** Contatos de emergência saem do JSONB e viram **tabela própria**
  (`emergency_contacts`): agora têm estrutura e validação obrigatória (AC-002.5), e JSONB
  esconderia isso do banco.
- **D-33** O **estado da conta** (AC-012.1) não é uma coluna: é **derivado** de
  `users.email_verified_at` e `memberships.status`. Sem `email_verified_at` →
  `AGUARDANDO_CONFIRMACAO_EMAIL`; verificado e sem membership → `ATIVO` (criador sem grupo);
  com membership → o `status` dela (`AGUARDANDO_APROVACAO`, `ATIVO`, `RECUSADO`, `EXPIRADO`,
  `REMOVIDO`). Um único método `AccountState.of(user, membership)` é a fonte da verdade, testado
  linha a linha com a tabela de AC-012.1. Evita duas colunas que poderiam divergir.
  *Esclarecimentos registrados na T-017:* (1) **membership exige e-mail verificado**: conta sem
  `email_verified_at` e com membership é combinação inválida e é rejeitada; por isso o `register`
  do convidado (T-042) grava `email_verified_at` na criação, já que o código de convite comprovou
  o e-mail (AC-004.3). (2) `hasGroup` é "existe membership", em qualquer status; "conta sem
  grupo" é só `AGUARDANDO_CONFIRMACAO_EMAIL` ou `ATIVO` sem idoso (AC-003.7). (3) `AccountState`
  também deriva `role` e `isAdmin`, que só valem quando o estado é `ATIVO` com vínculo; fora
  disso `role` é nulo e `isAdmin` é falso. (4) Membership de outra conta é rejeitada.
  (5) `elderId` não é derivável de usuário e membership; quem o resolve é a T-027.
- **D-34** `invite_code_attempts` é o contador **por e-mail** de AC-003.11, separado de
  `invitations` porque existe mesmo sem convite (AC-004.7, P5). `failed_attempts` e
  `blocked_until` implementam o bloqueio de 30 minutos; `auto_resends` implementa o limite de 3
  de AC-003.3 e só zera quando um código daquele e-mail é usado. `invitations.origin`
  (`MANUAL`, `AUTO`) registra se o convite nasceu de reenvio automático, para auditoria e testes.
  *Esclarecimentos registrados na T-018:* (1) a chave `email` é **normalizada** (`strip` +
  minúsculas), e T-040/T-041 aplicam a mesma normalização ao buscar convite e contador, para que
  mudar a caixa do e-mail não escape do bloqueio; (2) `blocked_until` **no passado** significa
  "bloqueio terminou e o reenvio automático ainda está devido" até o job tratar (D-09 item 4);
  por isso `GET /invitations` (D-39) lê `failedAttempts(clock)` e `blockedUntil(clock)` da
  entidade, nunca as colunas cruas; (3) um convite criado **entre o fim do bloqueio e a rodada do
  job** também cancela o reenvio automático; (4) código certo durante o bloqueio é recusado
  **antes** de qualquer uso: T-041 checa `isBlocked` e responde `423` antes de conferir o código.
- **D-35** **Toda tabela de dado do grupo, agora e nas features 001–008, aponta para
  `care_groups` ou `elders` com `ON DELETE CASCADE`.** É o que torna a exclusão do grupo
  (AC-013.2) uma única operação segura e completa. Feature futura que criar tabela sem esse
  cascade viola este plan.
- **D-36** Limites de conteúdo do idoso viram restrições de banco e Bean Validation: `allergies`
  e `conditions` `VARCHAR(500)` (AC-002.8); `CHECK (birth_date <= CURRENT_DATE)` mais validação
  de data de calendário no DTO (AC-002.7); uma única foto por idoso (`photo_path` nullable,
  AC-002.6). Edições do idoso não geram histórico (AC-002.9): `UPDATE` simples.

## 4. Máquinas de estado

**Conta** (derivada, D-33) — segue a tabela de AC-012.1:

```
(visitante) ──cria grupo──► AGUARDANDO_CONFIRMACAO_EMAIL ──código ok──► ATIVO (sem grupo)
            AGUARDANDO_CONFIRMACAO_EMAIL ──24 h──► (descartada, silencioso)
            ATIVO (sem grupo) ──POST /elders──► ATIVO (Responsável)          ──30 d──► (excluída)
(convidado) (não existe) ──register──► AGUARDANDO_APROVACAO
            RECUSADO | EXPIRADO | REMOVIDO ──redeem──► AGUARDANDO_APROVACAO   (reconvite)
            AGUARDANDO_APROVACAO ──aprovar──► ATIVO (CUIDADOR | FAMILIAR)
                                 ──recusar──► RECUSADO ──30 d──► (excluída)
                                 ──24 h────► EXPIRADO ──30 d──► (excluída)
            ATIVO (não admin) ──remover──► REMOVIDO                       (fica guardada)
            qualquer no grupo ──excluir grupo──► (excluída)
```

**Convite** — segue a tabela de AC-003.10: `ENVIADO → USADO | VENCIDO | CANCELADO`; um convite
nasce de envio, reenvio manual ou reenvio automático; `CANCELADO` quando o Responsável cancela
ou quando um reenvio (manual ou automático) o substitui. O bloqueio de 30 minutos **não é
estado**: vive em `invite_code_attempts` (D-34).

- **D-07 (rev. 2)** Transições ficam em métodos da entidade (`membership.approve(role, by, now)`,
  `membership.reinstate(now)` para o reconvite de AC-004.6, `invitation.use(now)`,
  `invitation.cancel(now)`), que lançam exceção em transição inválida. A regra é testável sem
  banco (AC-003.10, AC-006, AC-009, AC-012.1), com um teste parametrizado por linha das tabelas.
- **D-08** A concorrência entre duas ações sobre o mesmo pedido é tratada com **lock otimista**
  (`version`): a segunda recebe `409 Conflict`.
- **D-09 (rev. 2)** Prazos são verificados **na leitura** e efetivados por **jobs agendados**.
  Na leitura: um pedido com `expires_at` vencido nunca é aprovável nem aparece na lista de
  pendentes (AC-006.2, AC-006.8, resposta `410 Gone` com `code = REQUEST_EXPIRED` e a mensagem
  da spec; `409` fica reservado ao lock otimista de D-08). Um único agendador
  (`@Scheduled`, a cada 5 minutos, idempotente, com lock para uma instância só) roda quatro
  rotinas, todas com `Clock` injetado (D-10):
  1. **Expiração de pedidos**: `AGUARDANDO_APROVACAO` vencido → `EXPIRADO` + e-mail (AC-006.5,
     AC-007.1) e `retention_deadline = agora + 30 d` (AC-014.1).
  2. **Descarte de contas não confirmadas**: `users` sem `email_verified_at` e com `created_at`
     > 24 h → apagado, sem e-mail (AC-001.5).
  3. **Retenção**: membership `RECUSADO`/`EXPIRADO` com `retention_deadline` vencido → apaga
     `users`, `memberships` e `invitations` daquele e-mail, com e-mail curto antes (AC-014.1);
     `users` verificado sem membership há 30 d → apagado com o mesmo e-mail (AC-014.3), com
     lembrete aos 23 dias registrado em `elder_reminder_sent_at`. `retention_deadline` é
     recalculado a cada convite criado para o e-mail e a cada convite que termina sem uso.
  4. **Reenvio automático**: `invite_code_attempts` com `blocked_until <= agora` (**só** esse
     predicado; corrigido na T-018: o contador já vale zero na leitura ao fim do bloqueio, e erros
     do ciclo novo não cancelam o reenvio devido). Para cada linha: se há convite `ENVIADO` para
     o e-mail, `consumeAutoResend` (concede e conta até 3; o 4º é negado) e, concedido, cancela o
     convite e cria outro com `origin = AUTO` (AC-003.3); se não há convite, `dismissAutoResend`
     (não gasta o teto). Os dois limpam `blocked_until`. Se um convite foi criado durante o
     bloqueio ou entre o fim dele e a rodada do job, `blocked_until` já foi limpo (AC-003.9) e a
     linha não é selecionada. Cinco erros novos antes de o job rodar sobrescrevem `blocked_until`:
     sai um reenvio, não dois.
- **D-10** Um `Clock` injetável em todo lugar que usa hora, para testar prazos (15 min, 30 min,
  24 h, 7 dias, 30 dias) sem esperar.

## 5. Autenticação e sessão

- **D-11 (rev. 2)** **Access token JWT** de 15 minutos + **refresh token** opaco de **7 dias**,
  **renovado a cada uso** (sessão de até 7 dias sem uso), com **teto absoluto de 30 dias**
  guardado em `absolute_expires_at` e herdado na rotação (AC-008.3). O refresh token fica guardado
  como hash e é **rotacionado**: um token antigo reapresentado revoga a família inteira.
  `POST /auth/logout` revoga o refresh token do aparelho (AC-008.5).
  **`refresh_tokens.family_id`** (rev. 3, migração `V2`) identifica a cadeia de rotação de um
  mesmo login/aparelho: o primeiro token de um login nasce com `family_id = id` (ele mesmo); cada
  renovação grava um token novo com o **mesmo** `family_id` do anterior. Detectar um token já
  `revoked_at` sendo reapresentado revoga **todas** as linhas daquele `family_id` (reuso = token
  roubado); `family_id` nunca é o `user_id`, porque revogar um aparelho (D-13, `POST
  /auth/logout`) não deve afetar os refresh tokens de outros aparelhos do mesmo usuário.
- **D-12** Refresh token em cookie `HttpOnly; Secure; SameSite=Strict`; access token só em
  memória no Angular. Protege contra roubo de token por XSS.
- **D-13 (rev. 2)** O JWT **não carrega papel nem status**: o filtro de segurança consulta a
  membership a cada requisição (uma consulta por chave primária, cacheável depois) e daí tira
  `status`, `role` e `is_admin`. É isso que faz remoção (AC-009.2) e troca de papel (AC-009.1)
  valerem **imediatamente**. Remover também revoga todos os refresh tokens.
- **D-14 (rev. 2)** Senha com BCrypt. Bloqueio **por conta** após 5 erros, por 15 minutos
  (`locked_until`); durante o bloqueio a resposta é a mesma de credenciais inválidas, mesmo com a
  senha certa (AC-008.4, P5). Um e-mail é enviado **na transição** para bloqueado
  (`lock_notified_at` evita repetição). O contador zera no login correto e ao fim da janela.
- **D-15 (rev. 2)** Respostas "neutras" com o mesmo status e mesma mensagem, exista o e-mail ou
  não: `signup` (AC-001.4), `resend-code` (AC-001.6) e `password-reset/request` (AC-010.2).
  Em `signup` com e-mail já existente, nada é criado e o dono recebe o e-mail de AC-001.4.
  Em `login`, as mensagens por estado (AC-008.2) só saem **depois** de senha correta.
- **D-37** `password-reset/confirm` exige a nova senha na mesma chamada (AC-010.3), revoga todos
  os refresh tokens da conta (AC-010.4) e não toca no estado da conta (AC-010.5).

## 6. API (resumo; o detalhe fica em `contracts/openapi.yaml`)

| Método | Rota | Quem | ACs |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | público (cria conta do futuro Responsável) | 001.1, 001.3, 001.4 |
| POST | `/api/v1/auth/verify-email` | público | 001.2, 005 |
| POST | `/api/v1/auth/resend-code` | público | 001.6, 005.3 |
| POST | `/api/v1/auth/login` | público | 008.1, 008.2, 008.4 |
| POST | `/api/v1/auth/refresh` | cookie | 008.3 |
| POST | `/api/v1/auth/logout` | autenticado | 008.5 |
| POST | `/api/v1/auth/password-reset/request` | público | 010.1, 010.2 |
| POST | `/api/v1/auth/password-reset/confirm` | público | 010.3–010.5 |
| POST | `/api/v1/invitations/redeem` | público (e-mail + código) | 003.2, 004.1, 004.5–004.7 |
| POST | `/api/v1/invitations/register` | público (token de resgate) | 004.2–004.4, 006.7 |
| GET/PUT | `/api/v1/me` (telefone e endereço) | autenticado | 011.6 |
| GET | `/api/v1/me/account` (estado da conta e se tem idoso) | autenticado | 001.7, 008.2 |
| POST | `/api/v1/elders` | criador `ATIVO` sem grupo | 001.2, 002.1, 002.2 |
| GET/PUT | `/api/v1/elders/{elderId}` | membro / Responsável | 002 |
| DELETE | `/api/v1/elders/{elderId}` (**exclui o grupo inteiro**) | Responsável | 013 |
| PUT/DELETE | `/api/v1/elders/{elderId}/photo` | Responsável | 002.6 |
| GET/POST | `/api/v1/elders/{elderId}/invitations` | Responsável | 003.1, 003.4–003.8, 003.12 |
| POST | `/api/v1/elders/{elderId}/invitations/{id}/resend` \| `/cancel` | Responsável | 003.4 |
| GET | `/api/v1/elders/{elderId}/members?status=` | membro (filtro só p/ Responsável) | 006.2, 006.9, 009.5, 011.5, 011.8 |
| GET | `/api/v1/elders/{elderId}/members/pending-count` | Responsável | 006.1 |
| POST | `/api/v1/elders/{elderId}/members/{id}/approve` \| `/reject` | Responsável | 006.3, 006.4, 006.6, 006.8 |
| PATCH | `/api/v1/elders/{elderId}/members/{id}` (papel) | Responsável | 009.1, 009.4 |
| DELETE | `/api/v1/elders/{elderId}/members/{id}` | Responsável | 009.2, 009.4 |

- **D-16 (rev. 2)** O resgate do convite é feito em **dois passos**: `redeem` valida e-mail +
  código e devolve `next: REGISTER` com um **token de resgate** de 15 min (e-mail sem conta,
  AC-004.2) **ou** `next: REINSTATED` quando a conta já existe no grupo e voltou para
  `AGUARDANDO_APROVACAO` (AC-004.6). `register` usa o token. Assim o código de 6 dígitos não
  trafega de novo e as tentativas contam num lugar só (D-34). Erros de `redeem`: código errado ou
  e-mail sem convite → `400` com `remainingAttempts` (AC-004.7); bloqueado → `423 Locked` com
  `blockedUntil` (o contador regressivo é calculado no cliente); `VENCIDO`/`CANCELADO`/`USADO`
  com código conferido → `410 Gone` com `code` próprio (AC-004.5).
- **D-17** "Notificação no app" (AC-006.1) no MVP = o Angular consulta `pending-count` ao abrir e
  a cada 60 segundos com o app em primeiro plano. Push fica fora do MVP (Constitution §8).
- **D-30** *(descoberta no contrato)* A foto do idoso tem endpoint próprio `multipart`
  (`PUT /elders/{elderId}/photo`, JPEG/PNG até 5 MB), separado do JSON do idoso. No MVP o arquivo
  fica em disco/volume local; trocar por armazenamento de objetos depois não muda o contrato.
- **D-31 (rev. 2)** `POST /invitations` com e-mail que já tem convite `ENVIADO` **cancela o
  anterior e cria outro** (AC-003.12). Com e-mail membro `ATIVO` ou `AGUARDANDO_APROVACAO` do
  grupo → `409` com a mensagem de AC-003.5. Com e-mail de outro grupo ou de conta sem grupo →
  `409` com a mensagem de AC-003.7 (mesma mensagem nos dois casos; trade-off de P5 aceito na
  spec). Sem idoso → `409` (AC-002.4).
- **D-32 (rev. 2)** Agora regra da spec (AC-015): recurso de **outro grupo** ou **inexistente**
  responde `404` idêntico (AC-015.3); ação **do próprio grupo** que o papel não permite responde
  `403` com a mensagem de AC-015.2. O frontend nunca mostra ações vedadas (AC-015.1, D-20).
- **D-18** Rotas sob `/elders/{elderId}` seguem a Constitution §5, mesmo com um idoso por grupo:
  as features 001–008 já nascem no padrão certo. A exclusão do grupo usa `DELETE /elders/{id}`
  porque grupo e idoso são 1:1 (AC-002.1).
- **D-38** `DELETE /elders/{elderId}` roda numa transação: coleta os e-mails de todas as contas do
  grupo, apaga `care_groups` (o cascade de D-35 leva idoso, contatos, memberships, convites e
  registros futuros) e apaga os `users` daquelas memberships. O evento pós-commit envia o e-mail
  de AC-013.3 à lista coletada. A sessão do Responsável morre com a conta (D-13).
- **D-39** `GET /invitations` devolve, por convite `ENVIADO`, `failedAttempts` e `blockedUntil`
  lidos de `invite_code_attempts` (AC-003.8). `GET /members`: sem `status` devolve só `ATIVO`;
  o filtro `status` é aceito **apenas** de quem é `is_admin`; para os demais, responde `403`
  (AC-006.9, AC-015.2).

## 7. Frontend (Angular 22)

```
frontend/src/app/
├── core/       (interceptor de auth, guards, cliente gerado do OpenAPI, catálogo de mensagens)
├── auth/       login, criar grupo, verificar e-mail (com reenviar), tenho um código (com
│               contador regressivo), cadastro por convite, aguardando aprovação, esqueci a senha
├── elder/      cadastro/edição do idoso, visualização, excluir grupo
└── members/    convites (tentativas e bloqueio), pedidos pendentes (com badge), lista de membros
```

- **D-19** Cliente TypeScript gerado do `openapi.yaml` (`openapi-generator`, gerador
  `typescript-angular`). Nada de digitar DTO na mão (Constitution §4).
- **D-20 (rev. 2)** Guards por estado da conta (D-33, lido de `GET /me/account`): `ATIVO` com
  idoso para a área logada; `ATIVO` sem idoso é **preso** na rota de cadastro do idoso, só com
  "Sair" disponível (AC-001.7); `isAdmin` para convites, aprovação, filtros de status e exclusão
  do grupo (AC-015.1). O backend **sempre** revalida; o guard é só experiência de uso.
- **D-21** O badge de pendentes fica num `signal` alimentado pelo polling de D-17.
- **D-22** PWA com `@angular/pwa` apenas para instalação e cache do *app shell*; nenhuma chamada
  à API vai para cache (online-only, Constitution §4).
- **D-27 (rev. 2)** Um único componente `<app-endereco-form>` (formulário reativo), usado em todos
  os cadastros. Ele consulta o **ViaCEP** (`https://viacep.com.br/ws/{cep}/json/`) **direto do
  navegador**, com timeout de **15 s** (AC-011.4); em erro, timeout ou `{"erro": true}` mostra o
  aviso exato da spec e libera a digitação. A máscara `00000-000` é só visual; o valor enviado tem
  8 dígitos (AC-011.2). O backend **não** depende do ViaCEP: só valida o formato.
- **D-28 (rev. 2)** A regra de AC-011.5 e AC-011.8 fica numa **única** classe de política no
  backend, `MemberVisibilityPolicy.canSeeContact(viewer, target)` (é o próprio **ou** o viewer é
  `FAMILIAR`), aplicada a **endereço, telefone e e-mail** de uma vez e testada célula por célula
  (teste parametrizado com a matriz da spec). O mapeamento para DTO consulta a política e
  **omite** os três campos quando o resultado é falso. Membros não `ATIVO` só aparecem para
  `is_admin` (AC-006.9, AC-009.5). O Angular apenas exibe o que vier: a regra nunca é
  reimplementada no frontend.
- **D-40** **Catálogo de mensagens**: todos os textos exatos da spec (AC-001.1, 003.5, 003.7,
  004.4, 004.5, 004.7, 006.8, 008.2, 011.4, 012.2, 013.1, 015.2) ficam num único arquivo
  pt-BR no frontend, com chave nomeada pelo AC (ex.: `AC_004_7_BLOCKED`). O backend devolve
  Problem Details com `code` estável (ex.: `INVITE_CODE_BLOCKED`) e o frontend traduz `code` →
  chave → texto. Os testes de componente assertam pela chave; o teste E2E asserta pelo texto.
- **D-41** O contador regressivo de AC-004.7 é um componente próprio que recebe `blockedUntil`
  do backend (D-16) e conta com o relógio do aparelho; ao chegar a zero, reabilita o formulário
  sem recarregar a página.

## 8. Estratégia de testes (ligação AC → teste)

| Camada | Ferramenta | Cobre |
|---|---|---|
| Domínio | JUnit 5 + AssertJ, `Clock` fixo | tabelas de estados AC-003.10 e AC-012.1 (`@ParameterizedTest` por linha); prazos 005, 006, 014; contador de AC-003.11; matriz 011.5/011.8 |
| Jobs | `@SpringBootTest` + Testcontainers + `Clock` avançado | as quatro rotinas de D-09: 001.5, 006.5, 003.3 (reenvio automático e limite de 3), 014.1, 014.3 |
| API | `@SpringBootTest` + Testcontainers (PostgreSQL) + MockMvc | todos os ACs de endpoint, permissão (015) e exclusão do grupo (013) |
| Contrato | validação das respostas contra o `openapi.yaml` | conformidade do contrato |
| E-mail | Mailpit via Testcontainers | 001.4, 003.1, 006.7, 007.1, 008.4, 013.3, 014.1, 014.3 |
| Frontend | testes de componente | formulários, catálogo de mensagens (D-40), contador regressivo (D-41), guard do criador sem idoso, CEP (011.3, 011.4 com ViaCEP simulado) |
| E2E | Playwright em viewport de celular | criar grupo → confirmar → cadastrar idoso → convidar → cadastrar → aprovar → entrar → excluir grupo |

Cada teste traz o ID do critério no nome ou comentário, por exemplo
`void ac_006_5_pedido_expira_apos_24h()`. Os ACs sem endpoint (001.7 guard, 011.3, 011.4,
015.1, 012.2 coluna "aviso na tela") são provados só no frontend, e o script de rastreabilidade
(tasks T-071) aceita isso por uma lista explícita.

## 9. Riscos e pontos de atenção

- **R-01** ~~Sem push, o Responsável pode perder o prazo de 24 h.~~ **Resolvido:** spec rev. 5
  adicionou AC-006.7 (e-mail ao Responsável).
- **D-23** Os e-mails são enviados **depois do commit** da transação
  (`@TransactionalEventListener(phase = AFTER_COMMIT)`) e de forma assíncrona: uma falha no SMTP
  não desfaz o cadastro, e nunca sai e-mail de algo que não foi gravado. Para a exclusão do grupo
  (D-38) e a retenção (D-09), o evento carrega os e-mails coletados **antes** do apagamento.
  *Esclarecimentos registrados na T-016:* (1) o listener é `@TransactionalEventListener(phase =
  AFTER_COMMIT)` **sem** `fallbackExecution`: um `EmailEvent` publicado fora de transação é
  **descartado**; logo, toda tarefa que publica e-mail (login, cadastro, convites, aprovação,
  exclusão do grupo e os jobs de D-09, que já são transacionais por D-42) faz o `publishEvent`
  **dentro** da transação de negócio. (2) O envio é assíncrono no `applicationTaskExecutor`
  padrão do Boot (`@EnableAsync` em `notification/NotificationConfig`): fila em memória, sem
  persistência; e-mail pendente na queda do processo é perdido (trade-off do MVP, na linha de
  R-02). (3) Logs do listener só trazem o tipo do evento e, em falha, o tipo da exceção; nunca
  destinatário, assunto, corpo nem a mensagem do servidor SMTP (P5). (4) `EmailEvent` é selado
  com os 9 tipos da feature; cada record carrega só valores simples; `InvitationEmail` recebe
  `elderFirstName`, então extrair o **primeiro** nome do idoso (AC-003.1) é obrigação do produtor
  (T-040), e enviar **um** único `PasswordLockEmail` por bloqueio (AC-008.4) é obrigação do login
  (T-023). (5) AC-014.1 e AC-014.3 usam um único template de exclusão **sem citar motivo**; o
  lembrete de AC-014.3 fala em "conta", não em "grupo", porque o grupo só nasce com o idoso.
- **D-24** Envio de e-mail pelo **Gmail** via SMTP (`spring-boot-starter-mail`). Exige verificação
  em duas etapas na conta Google e uma **senha de app**; usuário e senha vêm de variáveis de
  ambiente (`MAIL_USERNAME`, `MAIL_PASSWORD`) e **nunca** vão para o repositório. O profile `dev`
  aponta para o Mailpit, o profile `prod` para o Gmail; o código não muda, só a configuração.
- **R-02** Limites do Gmail: uma conta pessoal tem cota diária de envio (na casa de centenas de
  mensagens) e envios em volume podem cair em spam ou bloquear a conta. É suficiente para o MVP e
  para um grupo familiar; se crescer, trocar por um provedor transacional muda só a configuração
  SMTP.
- **R-03 (rev. 2)** O código de convite de 6 dígitos vale por 7 dias; a proteção contra força bruta
  é o bloqueio de 30 min a cada 5 erros por e-mail (AC-003.3: no máximo 1.680 palpites em 7 dias,
  abaixo de 0,2% do espaço) somado a um rate limit por IP em `redeem`.
- **R-04** O reenvio automático (AC-003.3) pode ser provocado por terceiros que conheçam o e-mail
  do convidado; o teto de 3 por e-mail limita o incômodo a 3 e-mails. Registrar em log de
  aplicação (sem dado pessoal) quando o teto é atingido.
- **R-05** A exclusão do grupo apaga registros de cuidado (exceção a P3, Constitution v1.3). Como
  as features 001–008 ainda não existem, o cascade de D-35 é a única garantia de que nada fica
  órfão; a retrospectiva de cada feature futura deve conferir isso.
- **R-06** O agendador de D-09 roda em uma instância só no MVP; se houver mais de uma réplica,
  precisará de lock distribuído (ShedLock ou equivalente). Registrado, não implementado.
- **D-42** Todos os jobs de D-09 são **idempotentes** e usam `SELECT ... FOR UPDATE SKIP LOCKED`
  nas linhas que processam, para que uma rodada atrasada ou repetida nunca envie e-mail em dobro.
  *Esclarecimentos registrados na T-019:* (1) a infra vive em `shared.jobs`: `ScheduledRoutine`
  (`name()`, `run(Clock)`), `JobRunner.runAll()` (todas as rotinas, cada uma em transação
  própria `REQUIRES_NEW`, rollback só da que falha, log com nome da rotina e tipo da exceção,
  sem mensagem nem stack trace, P5), `SkipLockedClaimer.claim(...)` (JPQL + `PESSIMISTIC_WRITE`
  + timeout `SKIP_LOCKED`, recusa fora de transação) e `JobScheduler` com o único `@Scheduled`,
  `fixedDelay` de `app.jobs.interval` (padrão `PT5M`), ligado por `app.jobs.enabled` (padrão
  `true`; o profile `test` desliga). (2) O Hibernate 7 emite `FOR NO KEY UPDATE ... SKIP LOCKED`
  no PostgreSQL, equivalente para o fim desta decisão. (3) O limite de linhas vai na mesma
  consulta do lock, sem follow-on locking. (4) `fixedDelay` sem `initialDelay`: a primeira rodada
  ocorre na subida e as seguintes contam do fim da anterior, sem sobreposição na mesma instância
  (R-06). (5) Padrão das rotinas futuras (T-028, T-034, T-046, T-048, T-049): bean
  `ScheduledRoutine`, reivindicar linhas com o `claim` dentro de `run(Clock)` e publicar
  `EmailEvent` dentro dessa mesma transação (D-23).
- **D-43** *(T-002, pedido de Alexandre)* **Todas as variáveis de ambiente ficam num único `.env`
  na raiz**, não versionado, com modelo em `.env.example`. O Docker Compose lê o arquivo
  nativamente; o backend o importa com `spring.config.import: optional:file:.env[.properties]`
  (e `../.env` quando roda de `backend/`), de modo que shell, compose e Spring veem os mesmos
  valores. Variáveis exportadas no shell têm precedência. Segredos de produção (D-24) só existem
  no ambiente do servidor.
