import { CODIGO_PARA_CHAVE } from './codigos';
import { MENSAGENS, ChaveMensagem } from './mensagens';

/**
 * Cobre a tabela `CODIGO_PARA_CHAVE` (T-061, D-40): o `code` estável do Problem Details do
 * backend (`Problem`, `AccountStateProblem`, `InviteBlockedProblem`, `InvitationUnavailableProblem`,
 * `InvitationConflictProblem` no `openapi.yaml`) traduzido para a chave do catálogo de mensagens.
 *
 * Regras provadas:
 *  - os códigos que têm texto exato na spec apontam para a chave do AC certo;
 *  - `REJECTED` e `EXPIRED` (contas) compartilham a MESMA chave, porque AC-008.2 traz um único
 *    texto para "RECUSADO ou EXPIRADO";
 *  - `AWAITING_EMAIL_CONFIRMATION` NÃO está na tabela: AC-008.2 manda levar à tela de
 *    confirmação do e-mail, sem texto;
 *  - os demais códigos do backend (sem texto exato na spec) ficam FORA da tabela: o
 *    `MensagemService` devolve o fallback para eles; as telas das próximas tarefas definem seus textos;
 *  - todo valor da tabela é uma chave que existe de fato em `MENSAGENS`.
 */
describe('CODIGO_PARA_CHAVE (code do Problem Details -> chave do catálogo, D-40)', () => {
  // Mapeamentos obrigatórios: code, chave esperada e o AC que justifica.
  const MAPEAMENTOS: ReadonlyArray<readonly [string, string, ChaveMensagem]> = [
    ['AC-015.2', 'NOT_ALLOWED', 'AC_015_2_SEM_PERMISSAO'],
    ['AC-006.8', 'REQUEST_EXPIRED', 'AC_006_8_PEDIDO_EXPIRADO'],
    ['AC-004.7', 'INVITE_CODE_BLOCKED', 'AC_004_7_BLOQUEADO'],
    ['AC-004.5 (VENCIDO)', 'INVITATION_EXPIRED', 'AC_004_5_VENCIDO'],
    ['AC-004.5 (CANCELADO)', 'INVITATION_CANCELLED', 'AC_004_5_CANCELADO'],
    ['AC-004.5 (USADO)', 'INVITATION_USED', 'AC_004_5_USADO'],
    ['AC-003.5', 'ALREADY_MEMBER', 'AC_003_5_JA_NO_GRUPO'],
    ['AC-003.7', 'EMAIL_UNAVAILABLE', 'AC_003_7_EMAIL_VINCULADO'],
    ['AC-008.2 (aguardando aprovação)', 'AWAITING_APPROVAL', 'AC_008_2_AGUARDANDO_APROVACAO'],
    ['AC-008.2 (RECUSADO)', 'REJECTED', 'AC_008_2_NAO_APROVADO'],
    ['AC-008.2 (EXPIRADO)', 'EXPIRED', 'AC_008_2_NAO_APROVADO'],
    ['AC-008.2 (REMOVIDO)', 'REMOVED', 'AC_008_2_REMOVIDO'],
  ];

  // Uma linha por code: o valor da tabela é exatamente a chave do AC correspondente.
  it.each(MAPEAMENTOS)('%s o code %s aponta para a chave %s', (_ac, code, chave) => {
    expect(CODIGO_PARA_CHAVE[code]).toBe(chave);
  });

  // AC-008.2 tem um só texto para RECUSADO e EXPIRADO: as duas contas usam a mesma chave.
  it('AC-008.2 REJECTED e EXPIRED usam a MESMA chave (um único texto "não foi aprovado")', () => {
    expect(CODIGO_PARA_CHAVE['REJECTED']).toBeDefined();
    expect(CODIGO_PARA_CHAVE['REJECTED']).toBe(CODIGO_PARA_CHAVE['EXPIRED']);
  });

  // AC-008.2: conta aguardando confirmação de e-mail é levada à tela de confirmação, sem texto.
  it('AC-008.2 AWAITING_EMAIL_CONFIRMATION não tem texto: fica fora da tabela', () => {
    expect(CODIGO_PARA_CHAVE['AWAITING_EMAIL_CONFIRMATION']).toBeUndefined();
    expect(Object.keys(CODIGO_PARA_CHAVE)).not.toContain('AWAITING_EMAIL_CONFIRMATION');
  });

  // Códigos do backend sem texto exato na spec não entram: o serviço cai no fallback.
  it.each([
    'VERSION_CONFLICT',
    'REQUEST_ALREADY_DECIDED',
    'ADMIN_PROTECTED',
    'MEMBER_NOT_ACTIVE',
    'NO_ELDER',
    'INVALID_CODE',
    'CODE_EXPIRED_OR_EXHAUSTED',
    'TOO_MANY_REQUESTS',
    'ACCOUNT_STATE',
  ])('D-40 o code %s não tem texto exato na spec e fica fora da tabela', (code) => {
    expect(CODIGO_PARA_CHAVE[code]).toBeUndefined();
  });

  // Integridade: nenhuma entrada aponta para uma chave inexistente (erro de digitação).
  it('D-40 todo valor da tabela é uma chave existente em MENSAGENS', () => {
    const chaves = Object.keys(MENSAGENS);
    for (const [code, chave] of Object.entries(CODIGO_PARA_CHAVE)) {
      expect(chaves, `code ${code}`).toContain(chave);
    }
  });

  // A tabela traduz erros reais; o fallback é decisão do serviço e não pode ser mapeado por code.
  it('D-40 nenhum code aponta para FALLBACK_ERRO_GENERICO (o fallback é decidido pelo serviço)', () => {
    expect(Object.values(CODIGO_PARA_CHAVE)).not.toContain('FALLBACK_ERRO_GENERICO');
  });

  // Os codes são os do contrato: MAIÚSCULAS_COM_UNDERSCORE (estáveis, D-40).
  it('D-40 todos os codes da tabela estão no formato estável MAIUSCULAS_COM_UNDERSCORE', () => {
    for (const code of Object.keys(CODIGO_PARA_CHAVE)) {
      expect(code).toMatch(/^[A-Z][A-Z_]*$/);
    }
  });
});
