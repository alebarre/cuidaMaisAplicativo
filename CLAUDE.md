# Cuida+ — instruções para o Claude Code

Este projeto segue **Spec-Driven Development**. A fonte da verdade são os documentos em `specs/`.

## Ordem de leitura

1. `specs/constitution.md` — princípios que valem sempre
2. `specs/roadmap.md` — em que feature estamos
3. `specs/<feature>/spec.md` → `plan.md` → `contracts/openapi.yaml` → `tasks.md`
4. `JORNADA-SDD.md` — mapa do aprendizado e status

## Regras de ouro

- **Nunca** implemente algo que não esteja em uma tarefa do `tasks.md`.
- Se o código precisar divergir da spec, do plan ou do contrato: **pare**, proponha a mudança no
  documento e só continue depois de aprovada (Constitution §7).
- Testes primeiro. Todo teste cita o AC que prova (`AC-xxx.y`).
- IDs (US, AC, D, R, T) nunca são renumerados.
- NUNCA commite, faça merges ou pushes no GIT de ABSOLUTAMENTE NADA sem minha permissão/pedido expresso.
- NUNCA insira mensagem nenhuma (co-autoria, co-participação, revisão, nada) no GIT sem minha expressa permissão/pedido.
- **NUNCA, em hipótese alguma, commite senha, credencial, chave/segredo (JWT, API key, token de
  serviço) ou qualquer outro dado sensível no repositório remoto** — nem em código, nem em
  `application*.yml`, nem em migração, nem em mensagem de commit/PR. Esses valores só existem no
  `.env` local (já no `.gitignore`, nunca commitado) e chegam ao código em tempo de execução via
  variável de ambiente (`${VAR_NAME}`/`@Value`). Um arquivo versionado só pode conter, no máximo,
  o **nome** da variável e um valor de exemplo claramente não-real em `.env.example` (ex.
  `JWT_SECRET` de desenvolvimento, nunca o segredo de produção). Antes de qualquer `git add`,
  confira o conteúdo de arquivos de configuração novos/alterados por esse motivo, mesmo que o
  nome do arquivo pareça inofensivo.

## Agentes (`.claude/agents/`)

Fluxo por tarefa: **autor-testes → implementador-backend / implementador-frontend → verificador**.

- `revisor-spec`: antes de uma spec avançar para o plan.
- Tarefas marcadas `[P]` podem rodar em paralelo, cada uma com seu trio.
- Só marque a tarefa como `[x]` no `tasks.md` depois do veredito "tarefa aprovada" do verificador.
- O humano (Alexandre) é o dono da spec e dá a palavra final.
