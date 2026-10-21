# Constitution — Cuida+ (nome provisório)

> Princípios não negociáveis do projeto. Toda spec, plano e tarefa deve respeitar este documento.
> Mudanças aqui exigem registro no histórico ao final e revisão das specs afetadas.

## 1. Propósito

Aplicação web **mobile-first** para organizar a rotina de cuidado de uma pessoa idosa,
usada por **vários cuidadores que se revezam em turnos** e acompanhada por **familiares**.
Abre direto no navegador do celular, sem loja de apps, e pode ser instalada como PWA.

## 2. Princípios de produto

- **P1 — Uso com uma mão, em pé, com pressa.** As ações frequentes (marcar remédio, registrar
  refeição, anotar ocorrência) devem levar **no máximo 3 toques** a partir da tela inicial.
- **P2 — O turno é a unidade de trabalho.** O cuidador deve saber, ao abrir o app, o que está
  pendente, o que atrasou e o que o turno anterior deixou de aviso.
- **P3 — Registro é imutável para auditoria.** Registros de cuidado (medicação, sinais vitais,
  ocorrências) não são apagados; correções geram novo registro que referencia o anterior.
  Todo registro guarda **quem** fez e **quando** (horário do fato e horário do lançamento).
  Única exceção: a **exclusão do grupo inteiro pelo Responsável** (spec 000, AC-013), que apaga
  todo o acervo do grupo de uma vez.
- **P4 — Acessibilidade e legibilidade.** Fonte base ≥ 16px, alvos de toque ≥ 44px,
  contraste WCAG AA, linguagem simples em português (pt-BR).
- **P5 — Dados de saúde são sensíveis (LGPD).** Mínimo de dados necessário, acesso somente a
  membros vinculados ao idoso, nada de dados de saúde em logs, URLs ou mensagens de erro.

## 3. Papéis (base do modelo de permissão)

Existem **dois papéis**: **Cuidador** e **Familiar**. Além do papel, um Familiar pode ter a
permissão de **administrador**; o Familiar administrador é chamado de **Responsável**.

| Papel | Pode |
|---|---|
| **Cuidador** | Registrar e consultar a rotina, criar avisos e pedidos de compra |
| **Familiar** | Consultar tudo; criar avisos e pedidos de compra; não registra cuidado clínico |
| **+ administrador** (= Responsável) | Tudo o que o Familiar pode, mais: gerenciar o idoso, convidar/aprovar/remover membros, configurar a rotina, excluir o grupo (spec 000, AC-013) |

- Cada grupo tem **exatamente um** administrador, que é sempre um Familiar: quem criou o grupo.
- A permissão de administrador é fixa: não é transferida nem concedida a outro membro.
- Um Cuidador nunca é administrador.

Detalhes finos de permissão são definidos em cada spec e **testados**.

## 4. Stack e arquitetura

- **Backend:** Java (LTS atual) + Spring Boot (versão estável atual), Spring Web, Spring Data JPA,
  Spring Security com **JWT**, Bean Validation, **Flyway** para migrações.
- **Banco:** **PostgreSQL** (via Docker Compose no ambiente local). Testes de integração com
  **Testcontainers**.
- **Frontend:** Angular (versão estável atual), **standalone components + signals**,
  roteamento com lazy loading, **PWA** (`@angular/pwa`). **Online-only no MVP.**
- **Contrato primeiro:** a API é descrita em **OpenAPI 3** em `specs/<feature>/contracts/`
  *antes* da implementação. Backend e frontend são construídos a partir do contrato; o cliente
  TypeScript é gerado dele.
- **Monorepo:** `backend/`, `frontend/`, `specs/`, `docker-compose.yml` na raiz.
- Versões exatas são fixadas no `plan.md` da feature 000 e só mudam por decisão registrada.

## 5. Convenções de API

- REST, JSON, recursos no plural, `kebab-case` nas URLs, `camelCase` no JSON.
- Todo recurso de rotina fica sob o idoso: `/api/v1/elders/{elderId}/...`
- Datas/horas em **ISO-8601 com fuso** (`OffsetDateTime`); exibição em `America/Sao_Paulo`.
- Erros no formato **RFC 9457 (Problem Details)**.
- Listagens paginadas (`page`, `size`) e filtráveis por período.
- **Toda operação com corpo de requisição (`requestBody`) declara uma resposta `400`** (em geral
  `ValidationError`/`ValidationProblem`, ou um `oneOf` que a inclua) desde a primeira versão do
  contrato — mesmo que a tarefa de implementação pareça distante. Faltar isso é o tipo de lacuna
  que só aparece quando a tarefa já está sendo implementada, forçando uma pausa para corrigir o
  contrato (Constitution §7) no meio do trabalho; `revisor-spec` e quem escrever/revisar
  `contracts/openapi.yaml` devem checar essa regra explicitamente antes de aprovar o contrato.

## 6. Qualidade e testes

- **Todo critério de aceite da spec vira pelo menos um teste automatizado** e o teste cita o ID
  do critério (ex.: `// AC-001.3`).
- Backend: testes unitários de domínio + testes de integração de API (Testcontainers).
- Frontend: testes de componente; fluxos críticos com teste E2E (Playwright) em viewport de celular.
- Teste de contrato: a implementação do backend deve estar conforme o OpenAPI da feature.
- Nenhuma feature é "pronta" com critério de aceite sem teste.

## 7. Fluxo Spec-Driven (obrigatório)

1. `spec.md` — **o quê e por quê**: histórias, critérios de aceite (Dado/Quando/Então),
   fora de escopo, dúvidas abertas marcadas `[DÚVIDA]`. **Sem decisões técnicas.**
2. `plan.md` — **como**: modelo de dados, endpoints, telas, decisões e trade-offs.
3. `contracts/openapi.yaml` — contrato da API da feature.
4. `tasks.md` — tarefas pequenas, ordenadas, cada uma ligada a critérios de aceite.
5. Implementação e testes. Se o código precisar divergir da spec, **a spec é atualizada primeiro**.

Uma spec só avança para `plan.md` quando não houver `[DÚVIDA]` em aberto.

## 8. Fora do escopo do MVP

Modo offline com sincronização, notificações push, múltiplos idosos por conta de agência,
integrações com farmácias ou prontuários, app nativo.

## Histórico

- 2026-09-25 — v1.0 — versão inicial.
- 2026-09-25 — v1.1 — §3: um único Responsável por grupo, sem transferência do papel (spec 000 rev. 3/4).
- 2026-09-25 — v1.2 — §3: Responsável deixa de ser um terceiro papel e passa a ser um **Familiar com permissão de administrador** (spec 000 rev. 9).
- 2026-09-25 — v1.3 — P3: registrada a única exceção à imutabilidade, a **exclusão do grupo pelo Responsável** (spec 000 rev. 12, AC-013); §3: "excluir o grupo" entra na lista de poderes do administrador.
- 2026-10-21 — v1.4 — §5: toda operação com corpo de requisição declara `400` desde a primeira versão do contrato (achado em T-022/T-023: `/auth/verify-email`, `/auth/login`, `/auth/resend-code` e `PATCH /elders/{elderId}/members/{memberId}` tinham essa lacuna no contrato 000, já corrigida).
