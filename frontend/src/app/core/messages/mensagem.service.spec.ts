import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MENSAGENS } from './mensagens';
import { MensagemService } from './mensagem.service';

/**
 * Cobre o `MensagemService` (T-061, D-40), a única porta de acesso das telas ao catálogo:
 *  - `texto(chave, params?)`: devolve o texto da chave e substitui os marcadores `{nome}` (AC-004.7:
 *    `{restantes}` e `{tempo}`). DECISÃO: marcador sem valor correspondente LANÇA `Error` com o
 *    nome do marcador (para nunca exibir `{restantes}` ao usuário); parâmetro extra é ignorado.
 *  - `chaveDoCodigo(code)`: tabela `code` -> chave; código desconhecido, ausente ou fora da
 *    tabela cai em `FALLBACK_ERRO_GENERICO` (inclusive nomes herdados do protótipo, como `constructor`).
 *  - `textoDoErro(erro, params?)`: aceita um `HttpErrorResponse` com Problem Details `{ code }`
 *    (ou qualquer outra coisa), extrai `error.code` e devolve o texto do CATÁLOGO. NUNCA lança e
 *    NUNCA exibe o `detail`/`title` do servidor (D-40: o texto vem do catálogo, não do backend;
 *    P5). DECISÃO: se o texto da chave exigir um marcador que não foi informado, `textoDoErro`
 *    também não lança: devolve o fallback.
 *
 * Sem HTTP real: usa instâncias reais de `HttpErrorResponse`.
 */
describe('MensagemService (D-40)', () => {
  let service: MensagemService;

  /** Texto que o backend poderia mandar em `detail`/`title`; jamais pode chegar à tela. */
  const DETALHE_DO_SERVIDOR = 'DETALHE-DO-SERVIDOR-NAO-PODE-APARECER';
  const TITULO_DO_SERVIDOR = 'TITULO-DO-SERVIDOR-NAO-PODE-APARECER';

  /** Monta o erro HTTP que o `HttpClient` entrega: corpo = Problem Details do contrato. */
  const erroHttp = (status: number, corpo: unknown) =>
    new HttpErrorResponse({ status, statusText: 'x', error: corpo });

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MensagemService);
  });

  describe('texto(chave, params?)', () => {
    // Chave sem marcador: devolve o valor do catálogo sem mexer.
    it('AC-015.2 devolve o texto exato da chave quando não há marcadores', () => {
      expect(service.texto('AC_015_2_SEM_PERMISSAO')).toBe('Você não tem permissão para esta ação.');
      expect(service.texto('AC_015_2_SEM_PERMISSAO')).toBe(MENSAGENS.AC_015_2_SEM_PERMISSAO);
    });

    // AC-004.7: N vira o número de tentativas restantes (número é convertido em texto).
    it('AC-004.7 substitui {restantes} pelo número de tentativas', () => {
      expect(service.texto('AC_004_7_INVALIDO', { restantes: 3 })).toBe(
        'E-mail ou código inválidos. Restam 3 tentativas.',
      );
    });

    // AC-004.7: MM:SS vira o tempo formatado recebido do chamador.
    it('AC-004.7 substitui {tempo} pelo tempo formatado MM:SS', () => {
      expect(service.texto('AC_004_7_BLOQUEADO', { tempo: '29:59' })).toBe(
        'Bloqueado por tentativas excessivas. Tente novamente em 29:59.',
      );
    });

    // T-052, AC-005.2: o texto aprovado do código de confirmação também usa {restantes} (0 a 4).
    it('AC-005.2 substitui {restantes} em AC_005_2_CODIGO_INCORRETO (inclusive o 0)', () => {
      // Passo 1: um valor comum.
      expect(service.texto('AC_005_2_CODIGO_INCORRETO', { restantes: 3 })).toBe(
        'Código incorreto. Tentativas restantes: 3.',
      );
      // Passo 2: zero não é "falta de parâmetro": o texto sai com 0.
      expect(service.texto('AC_005_2_CODIGO_INCORRETO', { restantes: 0 })).toBe(
        'Código incorreto. Tentativas restantes: 0.',
      );
    });

    // T-052: faltar o parâmetro do marcador também lança aqui, para a tela nunca mostrar "{restantes}".
    it('AC-005.2 lança Error citando {restantes} quando falta o parâmetro de AC_005_2_CODIGO_INCORRETO', () => {
      expect(() => service.texto('AC_005_2_CODIGO_INCORRETO')).toThrow(/restantes/);
    });

    // T-052: textos aprovados sem marcador saem exatamente como estão no catálogo.
    it('AC-005.1 e AC-001.6 os textos aprovados sem marcador saem exatamente como no catálogo', () => {
      expect(service.texto('AC_005_1_CODIGO_EXPIRADO')).toBe('Este código expirou ou foi bloqueado. Peça um novo código.');
      expect(service.texto('AC_001_6_CODIGO_REENVIADO')).toBe(
        'Se o e-mail estiver cadastrado, enviaremos um novo código.',
      );
      expect(service.texto('AC_005_3_AGUARDE_REENVIO')).toBe('Aguarde um minuto para pedir outro código.');
    });

    // Parâmetro que ninguém usa não atrapalha nem aparece no texto.
    it('ignora parâmetro extra que não tem marcador no texto', () => {
      expect(service.texto('AC_006_8_PEDIDO_EXPIRADO', { restantes: 9, tempo: '01:00' })).toBe(
        'Este pedido expirou. Envie um novo convite.',
      );
    });

    // O valor é inserido literalmente: padrões especiais de replace ("$&", "$1") não são interpretados.
    it('AC-004.7 insere o valor do parâmetro literalmente (sem interpretar "$&")', () => {
      expect(service.texto('AC_004_7_BLOQUEADO', { tempo: '$&' })).toBe(
        'Bloqueado por tentativas excessivas. Tente novamente em $&.',
      );
    });

    // Marcador sem valor: erro claro em vez de exibir "{restantes}" na tela.
    it('AC-004.7 lança Error citando o marcador quando falta o parâmetro {restantes}', () => {
      expect(() => service.texto('AC_004_7_INVALIDO')).toThrow(/restantes/);
      expect(() => service.texto('AC_004_7_INVALIDO', { tempo: '01:00' })).toThrow(/restantes/);
    });

    // O resultado nunca deixa marcador pendente quando todos os parâmetros vieram.
    it('AC-004.7 o resultado com todos os parâmetros não contém chaves {}', () => {
      expect(service.texto('AC_004_7_INVALIDO', { restantes: 1 })).not.toMatch(/[{}]/);
      expect(service.texto('AC_004_7_BLOQUEADO', { tempo: '00:01' })).not.toMatch(/[{}]/);
    });
  });

  describe('chaveDoCodigo(code)', () => {
    // Etapas: um code de cada família do contrato (permissão, prazo, bloqueio, convite, conta).
    it('D-40 traduz os codes da tabela para as chaves dos ACs', () => {
      // AC-015.2: ação vedada ao papel.
      expect(service.chaveDoCodigo('NOT_ALLOWED')).toBe('AC_015_2_SEM_PERMISSAO');
      // AC-006.8: pedido com prazo vencido.
      expect(service.chaveDoCodigo('REQUEST_EXPIRED')).toBe('AC_006_8_PEDIDO_EXPIRADO');
      // AC-004.7: bloqueio por tentativas.
      expect(service.chaveDoCodigo('INVITE_CODE_BLOCKED')).toBe('AC_004_7_BLOQUEADO');
      // AC-004.5: três situações de convite.
      expect(service.chaveDoCodigo('INVITATION_EXPIRED')).toBe('AC_004_5_VENCIDO');
      expect(service.chaveDoCodigo('INVITATION_CANCELLED')).toBe('AC_004_5_CANCELADO');
      expect(service.chaveDoCodigo('INVITATION_USED')).toBe('AC_004_5_USADO');
      // AC-003.5 e AC-003.7: conflitos ao convidar.
      expect(service.chaveDoCodigo('ALREADY_MEMBER')).toBe('AC_003_5_JA_NO_GRUPO');
      expect(service.chaveDoCodigo('EMAIL_UNAVAILABLE')).toBe('AC_003_7_EMAIL_VINCULADO');
      // AC-008.2: estados da conta; RECUSADO e EXPIRADO dividem a chave.
      expect(service.chaveDoCodigo('AWAITING_APPROVAL')).toBe('AC_008_2_AGUARDANDO_APROVACAO');
      expect(service.chaveDoCodigo('REJECTED')).toBe('AC_008_2_NAO_APROVADO');
      expect(service.chaveDoCodigo('EXPIRED')).toBe('AC_008_2_NAO_APROVADO');
      expect(service.chaveDoCodigo('REMOVED')).toBe('AC_008_2_REMOVIDO');
    });

    // Código desconhecido, vazio ou ausente: sempre o fallback, nunca lança.
    it('D-40 code desconhecido, vazio, null ou undefined cai no fallback', () => {
      expect(service.chaveDoCodigo('CODIGO_QUE_NAO_EXISTE')).toBe('FALLBACK_ERRO_GENERICO');
      expect(service.chaveDoCodigo('')).toBe('FALLBACK_ERRO_GENERICO');
      expect(service.chaveDoCodigo(null)).toBe('FALLBACK_ERRO_GENERICO');
      expect(service.chaveDoCodigo(undefined)).toBe('FALLBACK_ERRO_GENERICO');
    });

    // Códigos reais do backend sem texto exato na spec também usam o fallback.
    it('D-40 codes do backend sem texto exato na spec usam o fallback', () => {
      for (const code of ['VERSION_CONFLICT', 'ADMIN_PROTECTED', 'INVALID_CODE', 'ACCOUNT_STATE']) {
        expect(service.chaveDoCodigo(code), code).toBe('FALLBACK_ERRO_GENERICO');
      }
    });

    // AC-008.2: AWAITING_EMAIL_CONFIRMATION leva à tela de confirmação, sem texto -> fallback aqui.
    it('AC-008.2 AWAITING_EMAIL_CONFIRMATION não tem texto e cai no fallback', () => {
      expect(service.chaveDoCodigo('AWAITING_EMAIL_CONFIRMATION')).toBe('FALLBACK_ERRO_GENERICO');
    });

    // Robustez: nomes herdados de Object.prototype não podem virar "chave" por acidente.
    it('D-40 nomes herdados do protótipo (constructor, toString, __proto__) caem no fallback', () => {
      for (const code of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
        expect(service.chaveDoCodigo(code), code).toBe('FALLBACK_ERRO_GENERICO');
      }
    });
  });

  describe('textoDoErro(erro, params?)', () => {
    // AC-015.2: 403 com code NOT_ALLOWED mostra o texto do catálogo, e não o detail do servidor.
    it('AC-015.2 HttpErrorResponse 403 NOT_ALLOWED vira o texto do catálogo, não o detail', () => {
      const erro = erroHttp(403, {
        type: '/problems/not-allowed',
        title: TITULO_DO_SERVIDOR,
        status: 403,
        detail: DETALHE_DO_SERVIDOR,
        code: 'NOT_ALLOWED',
      });
      const texto = service.textoDoErro(erro);
      expect(texto).toBe('Você não tem permissão para esta ação.');
      expect(texto).not.toContain(DETALHE_DO_SERVIDOR);
      expect(texto).not.toContain(TITULO_DO_SERVIDOR);
    });

    // AC-006.8: pedido vencido.
    it('AC-006.8 HttpErrorResponse com REQUEST_EXPIRED vira "Este pedido expirou. Envie um novo convite."', () => {
      const erro = erroHttp(409, { title: 'x', status: 409, code: 'REQUEST_EXPIRED' });
      expect(service.textoDoErro(erro)).toBe('Este pedido expirou. Envie um novo convite.');
    });

    // AC-004.5: convite em cada situação; AC-003.5/003.7 conflitos (uma etapa por situação).
    it('AC-004.5 HttpErrorResponse com INVITATION_USED vira o texto de convite já usado', () => {
      const erro = erroHttp(410, { title: 'x', status: 410, code: 'INVITATION_USED' });
      expect(service.textoDoErro(erro)).toBe(
        'Este código já foi usado. Se você já se cadastrou, entre com e-mail e senha.',
      );
    });

    // AC-004.7: o bloqueio traz o tempo restante formatado pelo chamador (contador, D-41).
    it('AC-004.7 INVITE_CODE_BLOCKED com params {tempo} preenche o contador no texto', () => {
      const erro = erroHttp(429, {
        title: 'x',
        status: 429,
        code: 'INVITE_CODE_BLOCKED',
        blockedUntil: '2026-01-01T10:30:00Z',
      });
      expect(service.textoDoErro(erro, { tempo: '12:34' })).toBe(
        'Bloqueado por tentativas excessivas. Tente novamente em 12:34.',
      );
    });

    // Marcador exigido e não informado: "nunca lança" prevalece, devolve o fallback (nunca "{tempo}").
    it('AC-004.7 INVITE_CODE_BLOCKED sem params não lança e devolve o fallback (nunca "{tempo}")', () => {
      const erro = erroHttp(429, { title: 'x', status: 429, code: 'INVITE_CODE_BLOCKED' });
      let texto = '';
      expect(() => (texto = service.textoDoErro(erro))).not.toThrow();
      expect(texto).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(texto).not.toMatch(/[{}]/);
    });

    // D-40: code fora da tabela mostra o fallback, e o detail do servidor continua escondido.
    it('D-40 code desconhecido devolve o fallback e esconde o detail do servidor', () => {
      const erro = erroHttp(409, {
        title: TITULO_DO_SERVIDOR,
        status: 409,
        detail: DETALHE_DO_SERVIDOR,
        code: 'VERSION_CONFLICT',
      });
      const texto = service.textoDoErro(erro);
      expect(texto).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(texto).not.toContain(DETALHE_DO_SERVIDOR);
      expect(texto).not.toContain(TITULO_DO_SERVIDOR);
    });

    // Problem Details sem `code`: só title/detail, que não podem ser mostrados.
    it('D-40 corpo sem code devolve o fallback e esconde title/detail', () => {
      const erro = erroHttp(500, {
        title: TITULO_DO_SERVIDOR,
        status: 500,
        detail: DETALHE_DO_SERVIDOR,
      });
      const texto = service.textoDoErro(erro);
      expect(texto).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(texto).not.toContain(DETALHE_DO_SERVIDOR);
      expect(texto).not.toContain(TITULO_DO_SERVIDOR);
    });

    // Casos sem corpo utilizável (um por forma): rede caiu, corpo vazio, corpo em texto puro, code que não é string.
    it('D-40 erro de rede, sem corpo, com corpo em texto ou code não-string devolve o fallback sem lançar', () => {
      const fallback = MENSAGENS.FALLBACK_ERRO_GENERICO;
      // Erro de rede: status 0 e `error` é um ProgressEvent/Error.
      expect(service.textoDoErro(erroHttp(0, new ProgressEvent('error')))).toBe(fallback);
      // Sem corpo.
      expect(service.textoDoErro(erroHttp(500, null))).toBe(fallback);
      // Corpo em texto puro (ex.: página de erro de proxy).
      expect(service.textoDoErro(erroHttp(502, '<html>Bad Gateway</html>'))).toBe(fallback);
      // `code` com tipo errado.
      expect(service.textoDoErro(erroHttp(400, { code: 123 }))).toBe(fallback);
    });

    // O argumento pode ser qualquer coisa: nunca lança.
    it('D-40 qualquer valor que não seja HttpErrorResponse devolve o fallback sem lançar', () => {
      for (const valor of [new Error('boom'), null, undefined, 'texto', 42, {}, []]) {
        let texto = '';
        expect(() => (texto = service.textoDoErro(valor)), String(valor)).not.toThrow();
        expect(texto, String(valor)).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      }
    });

    // Mesmo um Error cuja `message` traz texto do servidor não pode vazar para a tela.
    it('D-40 a message de um Error genérico não aparece no resultado', () => {
      const texto = service.textoDoErro(new Error(DETALHE_DO_SERVIDOR));
      expect(texto).not.toContain(DETALHE_DO_SERVIDOR);
    });

    // Code herdado do protótipo vindo do servidor (malicioso ou acidental) não quebra nem vaza.
    it('D-40 code "constructor" vindo do servidor devolve o fallback', () => {
      const erro = erroHttp(400, { code: 'constructor', detail: DETALHE_DO_SERVIDOR });
      expect(service.textoDoErro(erro)).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
    });
  });
});
