import { ChaveMensagem } from './mensagens';

/**
 * Tabela `code` -> chave do catálogo (T-061, D-40).
 *
 * O backend devolve erros como Problem Details com um `code` estável (MAIÚSCULAS_COM_UNDERSCORE,
 * ver `openapi.yaml`). Esta tabela traduz apenas os codes que têm texto exato na spec; o texto
 * mostrado ao usuário vem sempre do catálogo, nunca do `detail`/`title` do servidor.
 *
 * Ficam FORA de propósito: `AWAITING_EMAIL_CONFIRMATION` (AC-008.2 manda levar à tela de
 * confirmação do e-mail, sem texto) e os codes sem texto na spec (ex.: `VERSION_CONFLICT`,
 * `ADMIN_PROTECTED`, `INVALID_CODE`); para eles o `MensagemService` devolve o fallback e as
 * telas das próximas tarefas definem seus textos. `REJECTED` e `EXPIRED` (contas) dividem a
 * mesma chave porque AC-008.2 traz um único texto para "RECUSADO ou EXPIRADO".
 *
 * O objeto é criado sem protótipo, para que nomes herdados (`constructor`, `__proto__`...)
 * nunca sejam confundidos com um code real.
 */
export const CODIGO_PARA_CHAVE: Readonly<Record<string, ChaveMensagem>> = Object.freeze(
  Object.assign(Object.create(null) as Record<string, ChaveMensagem>, {
    // AC-015.2: ação vedada ao papel.
    NOT_ALLOWED: 'AC_015_2_SEM_PERMISSAO',
    // AC-006.8: pedido com prazo de 24 horas vencido.
    REQUEST_EXPIRED: 'AC_006_8_PEDIDO_EXPIRADO',
    // AC-004.7: bloqueio por tentativas excessivas de código.
    INVITE_CODE_BLOCKED: 'AC_004_7_BLOQUEADO',
    // AC-004.5: situações do convite.
    INVITATION_EXPIRED: 'AC_004_5_VENCIDO',
    INVITATION_CANCELLED: 'AC_004_5_CANCELADO',
    INVITATION_USED: 'AC_004_5_USADO',
    // AC-003.5 e AC-003.7: conflitos ao convidar.
    ALREADY_MEMBER: 'AC_003_5_JA_NO_GRUPO',
    EMAIL_UNAVAILABLE: 'AC_003_7_EMAIL_VINCULADO',
    // AC-008.2: estados da conta ao entrar.
    AWAITING_APPROVAL: 'AC_008_2_AGUARDANDO_APROVACAO',
    REJECTED: 'AC_008_2_NAO_APROVADO',
    EXPIRED: 'AC_008_2_NAO_APROVADO',
    REMOVED: 'AC_008_2_REMOVIDO',
  } satisfies Record<string, ChaveMensagem>),
);
