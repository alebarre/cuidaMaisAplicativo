---
name: executar-fase
description: >-
  Executa uma fase inteira (ou uma lista de tarefas) do specs/000-fundacao/tasks.md do
  projeto Cuida+, tarefa por tarefa, usando o ciclo autor-testes → implementador-backend/
  implementador-frontend → verificador, com correção e reverificação até "tarefa aprovada",
  checkpoints do orquestrador, tratamento de divergência com spec/plan/contrato, e commits
  propostos (nunca executados sem autorização explícita, por ação, do usuário). Use quando o
  usuário disser para "rodar a fase N", "executar a fase", "seguir com as tarefas da fase X"
  ou "aplicar o ciclo de tarefas" no Cuida+. Não decide por conta própria fazer git
  commit/push/merge: sempre mostra a mensagem e espera "sim" antes de cada ação de escrita no
  git, mesmo que o usuário já tenha pedido a fase inteira.
---

# Executar uma fase do Cuida+

Esta skill formaliza o ciclo que já vem sendo seguido manualmente neste projeto: cada tarefa do
`tasks.md` passa por **autor-testes → implementador → verificador**, com dois pontos reais de
parada para o humano (autorização de git; aprovação de divergência de documento) e vários pontos
de checagem feitos pelo próprio orquestrador (marcados 👤 abaixo — são você, a sessão principal,
não um subagente).

Ela **não substitui** as regras globais do usuário nem as do `CLAUDE.md` do projeto — só
organiza o fluxo. Em caso de conflito, as regras do `CLAUDE.md` (autorização de git por ação,
nenhuma menção a IA no git) sempre prevalecem sobre qualquer instrução desta skill.

## Entrada

Invocação esperada: `/executar-fase <N>` ou `/executar-fase <N>: <ajustes>`, onde:

- `<N>` é o número da fase (`0`–`6`) como aparece em `## Fase N — ...` no `tasks.md`. Também
  aceita uma lista explícita de tarefas (ex. `/executar-fase T-020,T-021,T-022`) quando o usuário
  quiser rodar só um subconjunto.
- `<ajustes>` (opcional) é texto livre com instruções extras por tarefa, por exemplo focos
  especiais para o `verificador` (ex. "T-021: foco em chave JWT, hash e rotação do refresh
  token; T-023: foco em e-mail case-insensitive e no contador de tentativas"). **Não** repita
  aqui o que já está em `tasks.md`/`spec.md`/`plan.md` — a skill lê esses documentos sozinha;
  use os ajustes só para ênfases que não estão óbvias no texto da tarefa.

Se o usuário não der um número de fase, pergunte antes de prosseguir.

## Passo 0 — Preparação da fase

1. **👤** Leia `specs/000-fundacao/tasks.md` e extraia, só para a fase pedida: a lista de
   tarefas na ordem em que aparecem, quais têm `[P]` (podem rodar em paralelo entre si), as
   dependências explícitas (`Depende de:`), os ACs/decisões citados, o "Pronto quando" de cada
   uma, e o texto do **Checkpoint** no final da fase. Ignore tarefas já marcadas `[x]`.
2. **👤** Confira em qual branch você está (`git status`/`git branch --show-current`). Se existir
   uma branch `fase/N-...` para esta fase e você não estiver nela, pare e pergunte ao usuário se
   deve trocar para ela antes de começar (não troque de branch sem confirmação — `git checkout`
   para mudar de branch é action de escrita de estado local, mas trocar de branch de trabalho
   ainda merece uma confirmação rápida para não editar na branch errada por engano).
3. Monte a ordem de execução: tarefas sem `[P]` e sem dependência pendente seguem na ordem do
   arquivo; tarefas `[P]` que não dependem umas das outras podem ser agrupadas e executadas com
   os três agentes em paralelo (ver "Paralelismo" abaixo). Nunca comece uma tarefa cuja
   dependência declarada ainda não está `[x]`.

## Passo 1 — Ciclo por tarefa (repita para cada T-0xx da ordem montada)

### 1.1 Preparação específica da tarefa (👤, orquestrador)

Antes de escrever qualquer prompt de agente, leia você mesmo:

- o texto exato da tarefa em `tasks.md` (incluindo `ACs:` e `Pronto quando:`);
- os ACs citados em `specs/000-fundacao/spec.md`;
- as decisões `D-xx` citadas em `specs/000-fundacao/plan.md`;
- o código/testes já existentes que a tarefa vai estender ou reaproveitar (ex. entidades
  anteriores do mesmo pacote, bases de teste em `shared/testsupport/`, contrato em
  `contracts/openapi.yaml` quando a tarefa expõe endpoint).

Isso é o que torna o prompt do próximo agente preciso em vez de genérico — não pule esta leitura
mesmo sob pressão para ir rápido.

### 1.2 `autor-testes`

Chame o agente `autor-testes` com um prompt autocontido (ele não vê este histórico): contexto do
projeto, texto da tarefa, ACs/decisões relevantes com o texto citado, padrão de código já
estabelecido (aponte arquivos de exemplo), e a instrução explícita de **não implementar
produção**, só os testes, e confirmar que eles falham agora pelo motivo certo (classe/método
ainda não existe — nunca um teste mal escrito passando por acidente).

**👤 Checkpoint:** ao receber o relatório, confirme:

- cada AC listado na tarefa tem pelo menos um teste que o cita;
- os testes falham pelo **motivo certo** (erro de compilação por símbolo inexistente, ou falha
  de asserção clara — nunca erro de configuração/ambiente alheio à tarefa);
- nenhuma decisão de design em aberto ficou sem resposta sem que você (orquestrador) a resolvesse
  ou a marcasse para resolver no próximo passo (ver "Divergência" abaixo — a maioria dessas
  decisões são lacunas a preencher com bom senso, não divergências que exigem parar).

Se algo estiver errado, não prossiga para o implementador: peça ao próprio `autor-testes`
correções (via `SendMessage` continuando o mesmo agente) ou refaça o pedido.

### 1.3 `implementador-backend` (ou `implementador-frontend`, conforme a tarefa)

Chame o agente apropriado com: o texto da tarefa, os testes já escritos (aponte o arquivo, peça
para ler por completo antes de codar), qualquer decisão de design que você já resolveu no passo
anterior, e a instrução explícita de **não alterar os testes** (exceto o que você autorizar
nomeadamente), não avançar escopo de tarefas futuras, não tocar `openapi.yaml`/`plan.md`/
migrações já aprovadas sem necessidade real, e rodar a suíte completa (`mvn verify` ou
`ng build && ng test`) ao final, não só o teste novo.

**👤 Checkpoint:** confirme que o relatório traz a saída real dos comandos (contagem de testes,
0 falhas) e não apenas a afirmação "passou". Se o implementador relatou uma divergência real
com spec/plan/contrato (não uma lacuna de detalhe), vá para "Tratamento de divergência" abaixo
**antes** de continuar.

### 1.4 `verificador`

Chame o `verificador` para reexecutar tudo de forma independente — ele nunca deve confiar só no
relato do implementador, e seu prompt deve pedir explicitamente que ele rode os comandos e leia
os arquivos de novo. Inclua:

- o texto da tarefa, ACs e decisões envolvidas;
- o que foi alegado ter sido feito (resumo do implementador), para ele conferir, não para
  assumir como verdade;
- uma prova de não-vacuidade quando fizer sentido (pedir para remover temporariamente uma
  checagem central e confirmar que o teste relevante falha de verdade, depois restaurar);
- **qualquer foco extra que o usuário tenha dado para esta tarefa nos `<ajustes>` da invocação**
  (ex. segurança, nomes case-insensitive, imediatidade de uma mudança de papel/estado);
- como padrão, sempre peça atenção a Constitution P3 (autoria/imutabilidade de registro), P5
  (nenhum dado de saúde exposto em erro/log) e §5 (convenções de API), mesmo sem pedido explícito
  do usuário — são recorrentes neste projeto.

Peça um veredito claro: **"tarefa aprovada"** ou uma lista do que falta, com evidência real.

- **Se "devolver"** (há apontamentos): mande-os de volta ao `implementador-backend`/
  `implementador-frontend` (pode ser o mesmo agente via `SendMessage`, ou um novo) pedindo para
  corrigir exatamente o que foi apontado. Rode o `verificador` de novo sobre o resultado. Repita.
  Se depois de 2-3 rodadas o mesmo tipo de apontamento persistir ou revelar uma divergência real
  de spec/plan (não um bug de implementação), trate como divergência (abaixo) em vez de insistir
  no loop.
- **Se "aprovada"**: siga para o passo 1.5.

### 1.5 Fechar a tarefa (👤, orquestrador — nunca delegado a um agente)

1. Edite `tasks.md` marcando `[x]` na tarefa (só agora, nunca antes da aprovação do
   `verificador`).
2. Monte a mensagem de commit (curta, 1–2 linhas de resumo + bullets se forem várias mudanças,
   citando a tarefa e os ACs/decisões principais — **sem nenhuma menção a IA/Claude/Anthropic**,
   conforme a regra global do usuário).
3. **Mostre a mensagem ao usuário e peça confirmação explícita antes de rodar qualquer `git
   add`/`git commit`.** Isso vale **mesmo que o usuário tenha pedido a fase inteira de uma vez**
   — a autorização de git é por ação, não por sessão (regra global do `CLAUDE.md`, prevalece
   sobre esta skill). Não rode `git push` nem pense em mergear nada agora: o commit fica na
   branch da fase; o `push`/merge para `master` só acontece no fim da fase (Passo 2), também com
   autorização própria.
4. Só depois do "sim" explícito, rode `git add` com os arquivos certos (nunca `git add -A`) e
   `git commit`.

## Passo 2 — Fim da fase

Depois que todas as tarefas da fase estiverem `[x]`:

1. **👤** Releia o texto do **Checkpoint** da fase em `tasks.md` e confirme, com evidência real
   (não só "todas as tarefas estão marcadas"), que ele é verdadeiro — ex. rode `mvn verify`
   completo mais uma vez do zero, e se o checkpoint menciona algo específico (contrato verde,
   containers de pé, etc.), confirme esse item especificamente.
2. Pergunte ao usuário se deve dar `git push` da branch da fase e, separadamente, se deve mergear
   essa branch em `master` agora — são duas autorizações distintas, peça as duas, mesmo que o
   usuário pareça já ter concordado em geral. Nunca faça `git merge`/`push --force`/rebase sem
   essa confirmação explícita naquele momento.
3. Se o usuário aprovar o merge, confira antes que não há conflito nem trabalho não commitado
   perdido (`git status` limpo na branch de destino), e prefira merge comum (sem `--no-verify`,
   sem reescrever histórico) a menos que o usuário peça outra coisa.

## Tratamento de divergência (pode acontecer em qualquer passo)

Se a implementação **realmente** precisar divergir de `spec.md`, `plan.md` ou
`contracts/openapi.yaml` — não uma lacuna de detalhe que você pode preencher com bom senso
documentando a decisão (como "papel provisório antes da aprovação" ou "resend não cancela um
convite vencido pelo tempo" em tarefas anteriores deste projeto), mas uma contradição real com
algo que o documento já afirma:

1. **Pare o ciclo daquela tarefa.** Não implemente a divergência silenciosamente nem peça ao
   agente para "contornar".
2. Proponha ao usuário, em texto claro, a mudança exata no documento (`plan.md`, `spec.md` e/ou
   `openapi.yaml`) que resolveria a divergência, explicando o porquê. Para uma revisão de spec
   maior, considere usar o agente `revisor-spec` antes de pedir aprovação (CLAUDE.md já define
   esse uso).
3. Espere a aprovação explícita do usuário (ele é quem decide, é o dono da spec — CLAUDE.md).
4. Só então edite o(s) documento(s), mostre o diff/mensagem de commit desses documentos, peça
   autorização de git separadamente, e comite.
5. Retome o ciclo da tarefa a partir de onde parou (normalmente repetindo o passo do agente que
   encontrou a divergência, agora com o documento já corrigido).

## Paralelismo (tarefas `[P]`)

Tarefas `[P]` sem dependência entre si podem ter seus três agentes (autor-testes,
implementador, verificador) despachados em paralelo — dispare as chamadas de `Agent` do mesmo
passo (ex. dois `autor-testes`, um por tarefa) numa única mensagem com `run_in_background: true`
quando o passo seguinte de cada uma não depender da outra, e prossiga com outro trabalho
(incluindo o passo seguinte de uma tarefa cujo agente já terminou) enquanto espera a notificação
da outra. Não leia o arquivo de transcript bruto do agente em background; espere a notificação.

Fique atento a um efeito colateral real já visto neste projeto: duas tarefas `[P]` que tocam o
mesmo módulo Maven/Angular podem fazer o `mvn verify`/`ng build` de uma falhar temporariamente
por falta de compilação do código da outra, que ainda não existe. Isso não é um bug da tarefa
que falhou — é só a corrida entre as duas branches de trabalho; peça para o agente validar o que
puder isoladamente (ex. rodar só a classe de teste dele, ou compilar só os arquivos relevantes) e
rode o `mvn verify`/`ng build` completo de novo, você mesmo, depois que as duas tiverem terminado.

## O que esta skill nunca faz por conta própria

- Não roda `git commit`, `git push`, `git merge`, `git checkout` (para trocar de branch),
  `git reset`, ou qualquer comando que altere o estado do repositório, sem antes mostrar
  exatamente o que vai fazer e esperar uma confirmação explícita **naquele momento** — mesmo
  que o usuário já tenha pedido "execute a fase inteira" ou "pode fazer tudo".
- Não inclui nenhuma menção a Claude/Anthropic/IA em mensagem de commit, PR ou qualquer conteúdo
  versionado.
- Não marca uma tarefa como `[x]` sem o veredito "tarefa aprovada" do `verificador`.
- Não implementa nada fora do texto da tarefa atual, mesmo que pareça conveniente adiantar uma
  tarefa futura.
