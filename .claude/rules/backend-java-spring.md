---
paths:
  - "backend/**"
---

# Backend (Java/Spring) — estrutura de pacotes e boas práticas

Regra **obrigatória** para todo prompt, agente (`autor-testes`, `implementador-backend`,
`verificador`) e skill que crie, mova ou altere código em `backend/`. Decisão D-01 (rev. 3) do
`plan.md` da Fundação. Base: `br.com.cuidamais`.

## 1. Organização: domínio (feature) primeiro, camada dentro

O primeiro nível é o **domínio** (`auth`, `group`, `membership`, `notification`, `shared` e as
features futuras, um pacote novo cada). Dentro de cada domínio, os arquivos ficam em **subpacotes
por papel**. Nenhuma classe fica solta na raiz de um domínio — a raiz só tem `package-info.java`.

| Subpacote     | O que vai nele |
|---------------|----------------|
| `controller`  | `@RestController`; só HTTP: valida entrada, chama o service, monta a resposta. Sem regra de negócio. |
| `service`     | Orquestração e casos de uso (`@Service`), componentes de apoio de negócio (storage, policy, templates, geração de tokens). |
| `repository`  | Interfaces Spring Data / acesso a dados. |
| `domain`      | Entidades JPA, `@Embeddable`, enums, value objects e resultados internos de serviço (`IssuedOtp`, `LoginSession`…). Regra de negócio mora aqui (D-07). |
| `dto`         | Requests/responses da API (records). Nunca devolver entidade pelo controller. |
| `exception`   | Exceções de negócio do domínio (e, em `shared`, o `GlobalExceptionHandler`). |
| `job`         | Rotinas agendadas do domínio (`ScheduledRoutine`). |
| `config`      | `@Configuration` do domínio. |
| `event` / `listener` | Eventos publicados e seus `@EventListener` (ex.: `notification`). |
| `util`        | Helpers estáticos sem estado e sem regra de negócio. Último recurso. |

Subpacote só existe se tiver conteúdo; não crie pastas vazias nem novos papéis sem necessidade.

## 2. Dependências

- Fluxo: `controller → service → repository`; `dto` e `domain` são usados por todos; `domain`
  **não** importa `controller`, `service` nem `dto`.
- Entre domínios, dependa só de tipos públicos de `domain`, `repository`, `service`, `event` e
  `exception` do outro domínio; nunca acople um domínio ao `controller`/`dto` de outro.
- `shared` não depende de domínios de negócio (exceto o que já existe documentado); é
  infraestrutura transversal: `config`, `security`, `jobs`, `exception`, `domain` (ex. `Endereco`).
- Injeção por **construtor**; nada de `@Autowired` em campo.

## 3. Nomes

- Classe: `PascalCase` com sufixo do papel (`LoginService`, `UserRepository`, `AuthController`,
  `SignupRequest`/`MeResponse`, `InvalidCodeException`, `…Routine`).
- Pacotes em minúsculas e singular (`controller`, não `controllers`).
- Um tipo público por arquivo; nome do arquivo = nome do tipo.

## 4. Testes espelham a produção

- O teste fica no **mesmo pacote completo** da classe testada (`auth.service.OtpServiceTest`).
- Teste de integração/endpoint (`@SpringBootTest` + MockMvc) fica no pacote `controller` do
  domínio. Teste de migração em `shared.migration`. Infra de teste em `shared.testsupport`.
- Visibilidade: prefira `public` só no necessário; se um teste em outro pacote precisar de
  acesso, exponha o mínimo ou teste pela API pública — não mova a classe de pacote para "caber".

## 5. Ao criar ou mover classes

1. Decida o subpacote pela tabela do item 1 **antes** de criar o arquivo.
2. Mover arquivo = atualizar `package`, imports (inclusive `{@link …}` em Javadoc) e o pacote do
   teste correspondente; rode `mvn clean verify` (o incremental pode mascarar erro).
3. Não renomeie IDs de spec (US/AC/D/R/T) por causa de mudança de pacote.
4. Refatoração de estrutura é feita **sem** mudar comportamento: o número de testes deve ser o
   mesmo antes e depois, todos verdes.
5. Mudou a regra de estrutura? Atualize **este arquivo**, o `plan.md` (D-01) e os agentes.

## 6. Ambiente de build

JDK 25 e Maven (não há `mvnw`): `JAVA_HOME=~/.jdks/openjdk-25.0.2`,
`~/.local/opt/apache-maven-3.9.9/bin/mvn`. O PATH padrão tem JDK 21, que não compila o projeto.
