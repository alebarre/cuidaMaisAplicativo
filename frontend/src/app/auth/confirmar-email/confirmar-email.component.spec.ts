import { Component, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { MockInstance } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideApi } from '../../core/api/provide-api';
import { SessionService } from '../../core/auth/session.service';
import { MensagemService } from '../../core/messages/mensagem.service';
import { MENSAGENS } from '../../core/messages/mensagens';
import { ROTAS } from '../../core/routes';
import { FluxoCadastroService } from '../fluxo-cadastro.service';
import { ConfirmarEmailComponent } from './confirmar-email.component';

/** Destino "burro" da navegação para o cadastro do idoso (a tela dele é de outra tarefa). */
@Component({ template: '<p>cadastro do idoso</p>' })
class DestinoComponent {}

/**
 * Cobre o `ConfirmarEmailComponent` (T-052; AC-001.1, AC-001.2, AC-001.6, AC-005.1, AC-005.2,
 * AC-005.3 e as decisões D-04, D-05, D-11, D-12, D-15, D-33, D-40, P4, P5): a tela pública em que
 * o criador digita o código de 6 dígitos recebido por e-mail e, se preciso, pede um novo código.
 *
 * Provado aqui, pelo comportamento da tela (DOM + HTTP simulado):
 *  - AC-001.1: quando `FluxoCadastroService.avisoPendente()` é verdadeiro, o aviso de 24 horas do
 *    catálogo aparece NO TOPO (antes dos campos); sem o flag (ex.: vindo do login) ele não aparece;
 *    o e-mail já vem preenchido pelo `FluxoCadastroService.email()` e continua editável;
 *  - validação no cliente sem requisição: e-mail vazio/inválido -> `VAL_EMAIL_INVALIDO`; código
 *    com menos de 6 dígitos (ou vazio) -> `VAL_OBRIGATORIO`; mensagens ligadas por
 *    `aria-describedby`, com `aria-invalid`, sem `role="alert"`;
 *  - campo do código: `inputmode=numeric`, `autocomplete=one-time-code`, descarta o que não é
 *    dígito, corta em 6 e preserva zero à esquerda;
 *  - AC-001.2: `200` de `verifyEmail` inicia a sessão (`SessionService.start(accessToken)`, só em
 *    memória), limpa o `FluxoCadastroService` e navega para `ROTAS.CADASTRO_IDOSO`;
 *  - AC-005.2: `400` com `remainingAttempts` (0 a 4) mostra `AC_005_2_CODIGO_INCORRETO` com o
 *    número de tentativas, em `role="alert"`, limpa o campo do código e leva o foco a ele; `400` sem
 *    `remainingAttempts` (validação) mostra o fallback;
 *  - AC-005.1/005.2: `410` (código expirado em 15 min ou invalidado após 5 erros) mostra
 *    `AC_005_1_CODIGO_EXPIRADO` e leva o foco à ação "Reenviar código"; demais erros -> fallback;
 *  - AC-001.6: "Reenviar código" faz `POST /auth/resend-code` com `{email, purpose:
 *    'EMAIL_VERIFICATION'}` (sem exigir o código); `202` mostra SEMPRE `AC_001_6_CODIGO_REENVIADO`
 *    em `role="status"` (mesmo texto exista ou não a conta, P5) e limpa o código; AC-005.3: `429`
 *    mostra `AC_005_3_AGUARDE_REENVIO` em `role="alert"`;
 *  - sem envio duplo: com requisição em voo os dois botões ficam desabilitados e nenhum envio
 *    extra sai; depois da resposta voltam ao normal (nada desabilitado de forma permanente);
 *  - D-40: nenhum texto é digitado na tela (com um `MensagemService` trocado todo texto muda) e o
 *    `detail`/`title` do servidor nunca aparece;
 *  - P5: e-mail, código e token nunca vão para URL, storage, cookie, estado do roteador nem
 *    `console`.
 *
 * "Contrato" dos seletores entre este teste e o template: campos achados pelo TEXTO EXATO do
 * `<label>` ("E-mail", "Código de 6 dígitos"); botões achados pelo texto exato ("Confirmar
 * e-mail" = `type=submit`, "Reenviar código" = `type=button`); erros = `[role=alert]`; resultado do
 * reenvio = `[role=status]` (ou região `aria-live`); mensagens de validação achadas por
 * `aria-describedby` do campo.
 */
describe('ConfirmarEmailComponent (AC-001.2, AC-001.6, AC-005.1, AC-005.2, AC-005.3)', () => {
  const URL_VERIFY = '/api/v1/auth/verify-email';
  const URL_RESEND = '/api/v1/auth/resend-code';

  /** Textos literais exatos (aprovados), digitados à parte do catálogo. */
  const TEXTO = {
    aviso24h: 'Confirme seu e-mail em até 24 horas. Depois desse prazo o cadastro é descartado e será preciso começar de novo.',
    obrigatorio: 'Preencha este campo.',
    emailInvalido: 'Informe um e-mail válido.',
    expirado: 'Este código expirou ou foi bloqueado. Peça um novo código.',
    reenviado: 'Se o e-mail estiver cadastrado, enviaremos um novo código.',
    aguarde: 'Aguarde um minuto para pedir outro código.',
    fallback: 'Não foi possível concluir a ação. Tente novamente.',
  } as const;

  const EMAIL = 'maria@exemplo.com';
  const CODIGO = '123456';
  const TOKEN = 'jwt-token-SECRETO-abc123';

  /** Texto que o backend poderia mandar em `detail`/`title`: jamais pode chegar à tela (D-40). */
  const DETALHE_DO_SERVIDOR = 'DETALHE-DO-SERVIDOR-NAO-PODE-APARECER';
  const TITULO_DO_SERVIDOR = 'TITULO-DO-SERVIDOR-NAO-PODE-APARECER';

  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;
  let fluxo: FluxoCadastroService;
  let session: SessionService;

  // ---------------------------------------------------------------------------------------
  // Montagem e utilitários de DOM
  // ---------------------------------------------------------------------------------------

  interface OpcoesDeMontagem {
    /** Quando informado, `FluxoCadastroService.iniciar(email)` (liga o aviso de 24 h). */
    iniciar?: string;
    /** Quando informado, `FluxoCadastroService.definirEmail(email)` (sem o aviso). */
    definirEmail?: string;
    /** Serviços trocados (ex.: o `MensagemService`). */
    provedores?: Provider[];
  }

  /** Prepara o estado do fluxo e abre a tela de confirmação dentro de um roteador de teste. */
  async function montar(opcoes: OpcoesDeMontagem = {}): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi('/api/v1'),
        provideRouter([
          { path: ROTAS.CONFIRMAR_EMAIL.slice(1), component: ConfirmarEmailComponent },
          { path: ROTAS.CADASTRO_IDOSO.slice(1), component: DestinoComponent },
        ]),
        ...(opcoes.provedores ?? []),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fluxo = TestBed.inject(FluxoCadastroService);
    session = TestBed.inject(SessionService);
    if (opcoes.iniciar !== undefined) {
      fluxo.iniciar(opcoes.iniciar);
    }
    if (opcoes.definirEmail !== undefined) {
      fluxo.definirEmail(opcoes.definirEmail);
    }
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(ROTAS.CONFIRMAR_EMAIL, ConfirmarEmailComponent);
    // Espia (chamando o original) as navegações feitas A PARTIR daqui: é o que prova o que vai em state/query/fragment (P5).
    espiaNavegar = vi.spyOn(router, 'navigateByUrl');
    atualizar();
  }

  /** Espião de `Router.navigateByUrl`, instalado por `montar`. */
  let espiaNavegar: MockInstance;

  /** Cada navegação pedida pela tela: a URL serializada e os `extras` (state, queryParams, fragment). */
  const destinosNavegados = () =>
    espiaNavegar.mock.calls.map(([alvo, extras]) => ({
      url: typeof alvo === 'string' ? alvo : router.serializeUrl(alvo),
      extras: extras as Record<string, unknown> | undefined,
    }));

  const host = () => harness.routeNativeElement as HTMLElement;
  const atualizar = () => harness.detectChanges();
  const textoDe = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  /** Espera a navegação/estabilização depois de uma resposta do servidor e redesenha a tela. */
  async function estabilizar(): Promise<void> {
    await harness.fixture.whenStable();
    atualizar();
  }

  /** Campo achado pelo texto EXATO do `<label>` (a ligação `for`/`id` faz parte da prova). */
  function campoPorRotulo(rotulo: string): HTMLInputElement {
    const achado = Array.from(host().querySelectorAll('label')).find((l) => textoDe(l) === rotulo);
    expect(achado, `deveria existir um <label> com o texto "${rotulo}"`).toBeDefined();
    const controle = (achado as HTMLLabelElement).control as HTMLInputElement | null;
    expect(controle, `o label "${rotulo}" precisa estar ligado a um campo (for/id)`).not.toBeNull();
    return controle as HTMLInputElement;
  }

  const email = () => campoPorRotulo('E-mail');
  const codigo = () => campoPorRotulo('Código de 6 dígitos');

  /** Botão achado pelo texto exato. */
  function botaoPorTexto(texto: string): HTMLButtonElement {
    const achado = Array.from(host().querySelectorAll('button')).find((b) => textoDe(b) === texto);
    expect(achado, `deveria existir um botão "${texto}"`).toBeDefined();
    return achado as HTMLButtonElement;
  }

  const botaoConfirmar = () => botaoPorTexto('Confirmar e-mail');
  const botaoReenviar = () => botaoPorTexto('Reenviar código');
  const alertas = () => Array.from(host().querySelectorAll('[role="alert"]'));
  const regiaoDeStatus = () => Array.from(host().querySelectorAll('[role="status"], [aria-live]'));
  const textoDosStatus = () => regiaoDeStatus().map((e) => textoDe(e)).join(' ').trim();

  /** Simula a digitação (ou colagem) do valor inteiro e atualiza a tela. */
  function digitar(el: HTMLInputElement, valor: string): void {
    el.value = valor;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    atualizar();
  }

  function sair(el: HTMLElement): void {
    el.dispatchEvent(new Event('blur'));
    atualizar();
  }

  /** Clica em "Confirmar e-mail" (sem esperar: pode haver requisição em voo). */
  function confirmar(): void {
    botaoConfirmar().click();
    atualizar();
  }

  /** Clica em "Reenviar código" (sem esperar). */
  function reenviar(): void {
    botaoReenviar().click();
    atualizar();
  }

  /** Digita um código e confirma; devolve a requisição de `verify-email`. */
  function confirmarComCodigo(valor = CODIGO): TestRequest {
    digitar(codigo(), valor);
    confirmar();
    return requisicaoVerify();
  }

  function requisicaoVerify(): TestRequest {
    const req = http.expectOne(URL_VERIFY);
    expect(req.request.method).toBe('POST');
    return req;
  }

  function requisicaoResend(): TestRequest {
    const req = http.expectOne(URL_RESEND);
    expect(req.request.method).toBe('POST');
    return req;
  }

  /** Resposta 200 do contrato (`AccessToken`). */
  const sessaoIniciada = (req: TestRequest, accessToken = TOKEN) =>
    req.flush({ accessToken, tokenType: 'Bearer', expiresIn: 900 });

  /** Resposta 400 `InvalidCodeProblem` com as tentativas restantes. */
  const codigoIncorreto = (req: TestRequest, remainingAttempts: number) =>
    req.flush(
      {
        type: '/problems/invalid-code',
        title: TITULO_DO_SERVIDOR,
        status: 400,
        detail: DETALHE_DO_SERVIDOR,
        code: 'INVALID_CODE',
        remainingAttempts,
      },
      { status: 400, statusText: 'Bad Request' },
    );

  /** Resposta 410 (código expirado ou invalidado). */
  const codigoExpirado = (req: TestRequest) =>
    req.flush(
      {
        type: '/problems/code-expired',
        title: TITULO_DO_SERVIDOR,
        status: 410,
        detail: DETALHE_DO_SERVIDOR,
        code: 'CODE_EXPIRED_OR_EXHAUSTED',
      },
      { status: 410, statusText: 'Gone' },
    );

  /** Resposta 202 neutra do reenvio. */
  const aceito = (req: TestRequest) => req.flush(null, { status: 202, statusText: 'Accepted' });

  /** Elementos apontados por `aria-describedby` do campo (só os que existem no documento). */
  function descritores(el: HTMLElement): HTMLElement[] {
    return (el.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .map((id) => document.getElementById(id))
      .filter((d): d is HTMLElement => d !== null);
  }

  const mensagemDe = (el: HTMLElement) => descritores(el).map((d) => textoDe(d)).join(' ').trim();
  const invalido = (el: HTMLElement) => el.getAttribute('aria-invalid') === 'true';

  /** Tudo o que foi escrito no console durante o teste, como um texto só. */
  let consoles: MockInstance[];
  const textoDoConsole = () =>
    consoles
      .flatMap((c) => c.mock.calls as unknown[][])
      .flat()
      .map((a) => {
        try {
          return typeof a === 'string' ? a : JSON.stringify(a);
        } catch {
          return String(a);
        }
      })
      .join('\n');

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    consoles = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m));
  });

  afterEach(() => {
    // O try/finally garante a limpeza mesmo quando há requisição sem resposta: sem ele, uma falha
    // aqui deixaria o TestBed instanciado e derrubaria em cascata os testes seguintes.
    try {
      http?.verify({ ignoreCancelled: true });
    } finally {
      vi.restoreAllMocks();
      TestBed.resetTestingModule();
    }
  });

  // ---------------------------------------------------------------------------------------
  // AC-001.1: aviso de 24 h e e-mail preenchido
  // ---------------------------------------------------------------------------------------

  describe('aviso de 24 horas e e-mail preenchido (AC-001.1)', () => {
    // Vindo do cadastro: o aviso do catálogo (texto exato da spec) aparece no topo, antes dos campos.
    it('AC-001.1 com o aviso pendente (vindo do cadastro) mostra o aviso de 24 horas do catálogo', async () => {
      await montar({ iniciar: EMAIL });

      expect(textoDe(host())).toContain(MENSAGENS.AC_001_1_AVISO_CONFIRMACAO);
      expect(textoDe(host())).toContain(TEXTO.aviso24h);
    });

    // "No topo": o aviso vem antes do primeiro campo no documento (e não é um alerta de erro).
    it('AC-001.1 o aviso aparece no topo (antes dos campos) e não é role=alert', async () => {
      await montar({ iniciar: EMAIL });

      const aviso = Array.from(host().querySelectorAll('*')).find(
        (el) => el.children.length === 0 && textoDe(el).includes(TEXTO.aviso24h),
      );
      expect(aviso, 'deveria existir um elemento com o texto do aviso').toBeDefined();
      expect((aviso as Element).compareDocumentPosition(email()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect((aviso as Element).compareDocumentPosition(codigo()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect((aviso as Element).closest('[role="alert"]')).toBeNull();
      expect(alertas()).toHaveLength(0);
    });

    // Vindo do login (T-053): só o e-mail é definido, sem o aviso de 24 h.
    it('AC-001.1 sem o aviso pendente (e-mail definido pelo login) o aviso de 24 horas NÃO aparece', async () => {
      await montar({ definirEmail: EMAIL });

      expect(textoDe(host())).not.toContain(TEXTO.aviso24h);
      expect(email().value).toBe(EMAIL);
    });

    // Aberta direto (sem fluxo): sem aviso e sem e-mail.
    it('AC-001.1 sem nenhum estado do fluxo: sem aviso e com o campo de e-mail vazio', async () => {
      await montar();

      expect(textoDe(host())).not.toContain(TEXTO.aviso24h);
      expect(email().value).toBe('');
      expect(codigo().value).toBe('');
    });

    // O e-mail do cadastro já vem preenchido (normalizado pelo serviço) e segue editável.
    it('AC-001.1 o e-mail do FluxoCadastroService vem preenchido e continua editável', async () => {
      await montar({ iniciar: '  Maria@Exemplo.COM ' });

      expect(email().value).toBe('maria@exemplo.com');
      expect(email().disabled).toBe(false);
      expect(email().hasAttribute('readonly')).toBe(false);

      digitar(email(), 'outra@exemplo.com');
      expect(email().value).toBe('outra@exemplo.com');
    });
  });

  // ---------------------------------------------------------------------------------------
  // Campo do código
  // ---------------------------------------------------------------------------------------

  describe('campo do código de 6 dígitos (AC-005.1, P4)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Teclado numérico e preenchimento do código recebido por e-mail/SMS pelo navegador.
    it('P4 o código tem inputmode=numeric e autocomplete=one-time-code', () => {
      expect(codigo().getAttribute('inputmode')).toBe('numeric');
      expect(codigo().getAttribute('autocomplete')).toBe('one-time-code');
    });

    // Cada linha: o que chega ao campo (digitado/colado) e o que sobra nele.
    it.each([
      // Linha 1: só dígitos, 6.
      ['123456', '123456'],
      // Linha 2: letras e símbolos são descartados.
      ['12ab34-5', '12345'],
      // Linha 3: mais de 6 dígitos: corta em 6 (colagem).
      ['12345678', '123456'],
      // Linha 4: colagem com espaços no meio ("123 456").
      ['123 456', '123456'],
      // Linha 5: nada além de letras -> vazio.
      ['abcdef', ''],
      // Linha 6: zero à esquerda é preservado (o código é texto, não número).
      ['012345', '012345'],
      // Linha 7: dígitos fora do ASCII (árabe-índicos) não valem.
      ['١٢٣٤٥٦', ''],
    ])('AC-005.1 digitar %j deixa o campo com %j (só dígitos, no máximo 6)', (digitado, esperado) => {
      digitar(codigo(), digitado);

      expect(codigo().value).toBe(esperado);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Validação no cliente (sem requisição)
  // ---------------------------------------------------------------------------------------

  describe('validação no cliente (AC-005.1, D-40, P4)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Ao abrir, nenhuma mensagem de validação.
    it('P4 ao abrir nenhum campo está aria-invalid e nenhuma mensagem aparece', () => {
      for (const el of [email(), codigo()]) {
        expect(invalido(el)).toBe(false);
        expect(mensagemDe(el)).toBe('');
      }
      expect(textoDe(host())).not.toContain(TEXTO.obrigatorio);
      expect(textoDe(host())).not.toContain(TEXTO.emailInvalido);
    });

    // Cada linha: código digitado e o resultado ao confirmar (menos de 6 dígitos -> obrigatório).
    it.each([
      // Linha 1: vazio.
      [''],
      // Linha 2: 1 dígito.
      ['1'],
      // Linha 3: 5 dígitos (falta um).
      ['12345'],
    ])('AC-005.1 confirmar com o código %j mostra "Preencha este campo." e não faz requisição', (valor) => {
      digitar(codigo(), valor);

      confirmar();

      http.expectNone(URL_VERIFY);
      expect(mensagemDe(codigo())).toBe(MENSAGENS.VAL_OBRIGATORIO);
      expect(mensagemDe(codigo())).toBe(TEXTO.obrigatorio);
      expect(invalido(codigo())).toBe(true);
      expect(alertas()).toHaveLength(0);
    });

    // Cada linha: e-mail digitado e a mensagem ao confirmar (vazio/inválido -> e-mail inválido).
    it.each([
      // Linha 1: vazio.
      [''],
      // Linha 2: só espaços.
      ['   '],
      // Linha 3: sem arroba.
      ['maria'],
      // Linha 4: sem domínio.
      ['maria@'],
      // Linha 5: sem usuário.
      ['@exemplo.com'],
    ])('AC-001.6 confirmar com o e-mail %j mostra "Informe um e-mail válido." e não faz requisição', (valor) => {
      digitar(email(), valor);
      digitar(codigo(), CODIGO);

      confirmar();

      http.expectNone(URL_VERIFY);
      expect(mensagemDe(email())).toBe(MENSAGENS.VAL_EMAIL_INVALIDO);
      expect(mensagemDe(email())).toBe(TEXTO.emailInvalido);
      expect(invalido(email())).toBe(true);
      expect(alertas()).toHaveLength(0);
    });

    // Os dois inválidos ao mesmo tempo: as duas mensagens aparecem e nada é enviado.
    it('AC-005.1 e-mail e código inválidos mostram as duas mensagens de uma vez, sem requisição', () => {
      digitar(email(), 'maria');
      digitar(codigo(), '12');

      confirmar();

      http.expectNone(URL_VERIFY);
      expect(mensagemDe(email())).toBe(TEXTO.emailInvalido);
      expect(mensagemDe(codigo())).toBe(TEXTO.obrigatorio);
    });

    // Enviar o formulário pelo teclado (Enter) passa pela mesma validação do clique.
    it('AC-005.1 o envio do formulário (Enter) com código incompleto também não faz requisição', () => {
      digitar(codigo(), '123');

      host().querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      atualizar();

      http.expectNone(URL_VERIFY);
      expect(mensagemDe(codigo())).toBe(TEXTO.obrigatorio);
    });

    // A mensagem sai quando o valor passa a ser válido.
    it('P4 a mensagem do código some quando ele passa a ter 6 dígitos', () => {
      confirmar();
      expect(mensagemDe(codigo())).toBe(TEXTO.obrigatorio);

      digitar(codigo(), CODIGO);

      expect(mensagemDe(codigo())).toBe('');
      expect(invalido(codigo())).toBe(false);
    });

    // A mensagem fica ligada por aria-describedby, abaixo do campo, e não é um alerta.
    it.each([
      ['e-mail', () => email(), () => digitar(email(), 'maria')],
      ['código', () => codigo(), () => digitar(codigo(), '12')],
    ])('P4 a mensagem do campo %s fica abaixo dele, ligada por aria-describedby, e não é role=alert', (_nome, obter, preparar) => {
      preparar();
      confirmar();

      const campo = obter();
      const mensagem = descritores(campo)[0];
      expect(mensagem, 'a mensagem precisa estar ligada por aria-describedby').toBeDefined();
      expect(campo.parentElement?.contains(mensagem)).toBe(true);
      expect(campo.compareDocumentPosition(mensagem) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(mensagem.getAttribute('role')).not.toBe('alert');
      expect(mensagem.closest('[role="alert"]')).toBeNull();
      expect(document.querySelectorAll(`[id="${mensagem.id}"]`)).toHaveLength(1);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-001.2: confirmar com sucesso
  // ---------------------------------------------------------------------------------------

  describe('confirmar o e-mail (AC-001.2)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // O corpo é exatamente { email, code } e a rota é a do contrato.
    it('AC-001.2 confirmar faz um único POST /auth/verify-email com o corpo { email, code }', () => {
      const req = confirmarComCodigo(CODIGO);

      expect(req.request.body).toEqual({ email: EMAIL, code: CODIGO });
      expect(req.request.urlWithParams).toBe(URL_VERIFY);
      sessaoIniciada(req);
    });

    // O e-mail editado e "sujo" (maiúsculas e espaços) segue normalizado, como no cadastro.
    it('AC-001.2 o e-mail editado vai aparado e em minúsculas', () => {
      digitar(email(), '  Outra.Pessoa@Exemplo.COM  ');

      const req = confirmarComCodigo(CODIGO);

      expect(req.request.body).toEqual({ email: 'outra.pessoa@exemplo.com', code: CODIGO });
      sessaoIniciada(req);
    });

    // O zero à esquerda do código é preservado como texto (um número viraria 12345).
    it('AC-005.1 o código com zero à esquerda vai como texto "012345"', () => {
      const req = confirmarComCodigo('012345');

      expect(req.request.body.code).toBe('012345');
      expect(typeof req.request.body.code).toBe('string');
      sessaoIniciada(req);
    });

    // 200: o criador (agora ATIVO) recebe a sessão em memória e vai ao cadastro do idoso.
    it('AC-001.2 200 inicia a sessão com o accessToken e navega para o cadastro do idoso', async () => {
      const req = confirmarComCodigo();

      sessaoIniciada(req, 'token-de-acesso-novo');
      await estabilizar();

      expect(session.accessToken()).toBe('token-de-acesso-novo');
      expect(session.isAuthenticated()).toBe(true);
      expect(router.url).toBe(ROTAS.CADASTRO_IDOSO);
    });

    // O estado temporário do cadastro não sobra depois de confirmar.
    it('AC-001.2 200 limpa o FluxoCadastroService (e-mail e aviso de 24 horas)', async () => {
      expect(fluxo.avisoPendente()).toBe(true);
      const req = confirmarComCodigo();

      sessaoIniciada(req);
      await estabilizar();

      expect(fluxo.email()).toBe('');
      expect(fluxo.avisoPendente()).toBe(false);
    });

    // Antes da resposta nada acontece: sem sessão, sem navegação.
    it('AC-001.2 antes da resposta do servidor não há sessão nem navegação', async () => {
      const req = confirmarComCodigo();
      await Promise.resolve();

      expect(session.accessToken()).toBeNull();
      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(fluxo.email()).toBe(EMAIL);
      sessaoIniciada(req);
    });

    // O token vai só para a memória da sessão: nem storage, nem cookie, nem URL, nem console.
    it('D-12 o token da sessão fica só em memória: nunca em storage, cookie, URL, estado do roteador, console nem tela', async () => {
      const gravar = vi.spyOn(Storage.prototype, 'setItem');
      const req = confirmarComCodigo();

      sessaoIniciada(req);
      await estabilizar();

      expect(session.accessToken()).toBe(TOKEN);
      expect(gravar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(document.cookie).toBe('');
      expect(router.url).not.toContain('SECRETO');
      expect(window.location.href).not.toContain('SECRETO');
      expect(JSON.stringify(destinosNavegados())).not.toContain('SECRETO');
      expect(textoDoConsole()).not.toContain('SECRETO');
      expect(textoDe(host())).not.toContain('SECRETO');
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-005.2: código incorreto, com as tentativas restantes
  // ---------------------------------------------------------------------------------------

  describe('código incorreto (AC-005.2)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Cada linha: `remainingAttempts` do servidor (0 a 4) e a mensagem exata esperada.
    it.each([
      // Linha 1: 1º erro, restam 4.
      [4, 'Código incorreto. Tentativas restantes: 4.'],
      // Linha 2: restam 3.
      [3, 'Código incorreto. Tentativas restantes: 3.'],
      // Linha 3: restam 2.
      [2, 'Código incorreto. Tentativas restantes: 2.'],
      // Linha 4: restam 1.
      [1, 'Código incorreto. Tentativas restantes: 1.'],
      // Linha 5: restam 0 (zero NÃO é "sem informação": o texto sai com 0).
      [0, 'Código incorreto. Tentativas restantes: 0.'],
    ])('AC-005.2 400 com remainingAttempts=%i mostra "%s" em role=alert', async (restantes, texto) => {
      const req = confirmarComCodigo('654321');

      codigoIncorreto(req, restantes);
      await estabilizar();

      // Passo 1: um único alerta, com o texto do catálogo preenchido com N (e o literal).
      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(TestBed.inject(MensagemService).texto('AC_005_2_CODIGO_INCORRETO', { restantes }));
      expect(textoDe(alertas()[0])).toBe(texto);
      // Passo 2: nada do servidor aparece e a tela não navega nem inicia sessão.
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(session.accessToken()).toBeNull();
    });

    // O campo do código é limpo e recebe o foco para a nova tentativa; o e-mail é mantido.
    it('AC-005.2 depois do código incorreto o campo do código fica vazio e focado, e o e-mail é mantido', async () => {
      digitar(email(), 'maria@exemplo.com');
      const req = confirmarComCodigo('654321');

      codigoIncorreto(req, 3);
      await estabilizar();

      expect(codigo().value).toBe('');
      expect(document.activeElement).toBe(codigo());
      expect(email().value).toBe('maria@exemplo.com');
    });

    // O botão volta a funcionar e a pessoa pode tentar de novo; o erro novo substitui o antigo.
    it('AC-005.2 depois do código incorreto o botão é reabilitado e uma nova tentativa mostra um único alerta atualizado', async () => {
      // Passo 1: primeira tentativa errada.
      codigoIncorreto(confirmarComCodigo('111111'), 4);
      await estabilizar();
      expect(botaoConfirmar().disabled).toBe(false);
      expect(botaoConfirmar().getAttribute('aria-busy')).not.toBe('true');

      // Passo 2: segunda tentativa errada; o alerta é um só, com o número novo.
      codigoIncorreto(confirmarComCodigo('222222'), 3);
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe('Código incorreto. Tentativas restantes: 3.');
    });

    // Depois de errar, acertar o código leva ao cadastro do idoso.
    it('AC-005.2 errar e depois acertar o código inicia a sessão e navega para o cadastro do idoso', async () => {
      codigoIncorreto(confirmarComCodigo('111111'), 4);
      await estabilizar();

      sessaoIniciada(confirmarComCodigo(CODIGO));
      await estabilizar();

      expect(session.isAuthenticated()).toBe(true);
      expect(router.url).toBe(ROTAS.CADASTRO_IDOSO);
    });

    // 400 de validação (sem remainingAttempts): não é "código incorreto", é erro genérico.
    it('D-40 400 sem remainingAttempts (ValidationProblem) mostra o fallback, não "código incorreto"', async () => {
      const req = confirmarComCodigo();

      req.flush(
        {
          type: '/problems/validation',
          title: TITULO_DO_SERVIDOR,
          status: 400,
          detail: DETALHE_DO_SERVIDOR,
          errors: [{ field: 'email', message: 'e-mail malformado' }],
        },
        { status: 400, statusText: 'Bad Request' },
      );
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      expect(textoDe(host())).not.toContain('Tentativas restantes');
      expect(textoDe(host())).not.toContain('e-mail malformado');
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-005.1 / AC-005.2: código expirado ou invalidado (410)
  // ---------------------------------------------------------------------------------------

  describe('código expirado ou invalidado (AC-005.1, AC-005.2)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // 410: texto do catálogo em alerta e foco na ação de reenviar.
    it('AC-005.1 410 mostra "Este código expirou ou foi bloqueado. Peça um novo código." em role=alert', async () => {
      const req = confirmarComCodigo();

      codigoExpirado(req);
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(MENSAGENS.AC_005_1_CODIGO_EXPIRADO);
      expect(textoDe(alertas()[0])).toBe(TEXTO.expirado);
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(session.accessToken()).toBeNull();
    });

    // O foco vai para a saída natural: pedir um novo código.
    it('AC-005.2 410 leva o foco para a ação "Reenviar código" (habilitada)', async () => {
      const req = confirmarComCodigo();

      codigoExpirado(req);
      await estabilizar();

      expect(botaoReenviar().disabled).toBe(false);
      expect(document.activeElement).toBe(botaoReenviar());
    });

    // 410 também pode vir sem corpo: o tratamento é o mesmo.
    it('AC-005.1 410 sem corpo mostra o mesmo texto de código expirado', async () => {
      const req = confirmarComCodigo();

      req.flush(null, { status: 410, statusText: 'Gone' });
      await estabilizar();

      expect(textoDe(alertas()[0])).toBe(TEXTO.expirado);
    });

    // A saída funciona: depois do 410, "Reenviar código" faz o pedido de novo código.
    it('AC-005.1 depois do 410 a pessoa pede um novo código e o reenvio funciona', async () => {
      codigoExpirado(confirmarComCodigo());
      await estabilizar();

      reenviar();
      const req = requisicaoResend();
      expect(req.request.body).toEqual({ email: EMAIL, purpose: 'EMAIL_VERIFICATION' });
      aceito(req);
      await estabilizar();

      expect(textoDosStatus()).toContain(TEXTO.reenviado);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Demais erros da confirmação
  // ---------------------------------------------------------------------------------------

  describe('demais erros da confirmação (D-40)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    /** Falhas sem tratamento próprio na confirmação: todas caem no fallback. */
    const FALHAS: [string, (req: TestRequest) => void][] = [
      // Linha 1: 429 não existe na confirmação (contrato), mas tem de cair no fallback.
      [
        '429',
        (req) =>
          req.flush(
            { type: 'x', title: TITULO_DO_SERVIDOR, status: 429, detail: DETALHE_DO_SERVIDOR },
            { status: 429, statusText: 'Too Many Requests' },
          ),
      ],
      // Linha 2: 500.
      [
        '500',
        (req) =>
          req.flush(
            { type: 'x', title: TITULO_DO_SERVIDOR, status: 500, detail: DETALHE_DO_SERVIDOR },
            { status: 500, statusText: 'Server Error' },
          ),
      ],
      // Linha 3: 404.
      ['404', (req) => req.flush(null, { status: 404, statusText: 'Not Found' })],
      // Linha 4: 403 (com um code qualquer).
      [
        '403',
        (req) =>
          req.flush(
            { type: 'x', title: TITULO_DO_SERVIDOR, status: 403, detail: DETALHE_DO_SERVIDOR, code: 'NOT_ALLOWED' },
            { status: 403, statusText: 'Forbidden' },
          ),
      ],
      // Linha 5: corpo de texto.
      ['503 com corpo de texto', (req) => req.flush('Service Unavailable', { status: 503, statusText: 'Service Unavailable' })],
      // Linha 6: falha de rede.
      ['falha de rede', (req) => req.error(new ProgressEvent('error'))],
    ];

    // Cada falha sem tratamento próprio cai no MESMO fallback, num único alerta, sem expor o servidor e sem perder o que foi digitado.
    it.each(FALHAS)('D-40 %s na confirmação mostra o fallback do catálogo em um único role=alert e mantém os dados', async (_nome, falhar) => {
      const req = confirmarComCodigo('654321');

      falhar(req);
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      // Nada do servidor na tela; dados mantidos; botão reabilitado; sem navegação nem sessão.
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      expect(email().value).toBe(EMAIL);
      expect(botaoConfirmar().disabled).toBe(false);
      expect(botaoConfirmar().getAttribute('aria-busy')).not.toBe('true');
      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(session.accessToken()).toBeNull();
      expect(fluxo.email()).toBe(EMAIL);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-001.6 e AC-005.3: reenviar o código
  // ---------------------------------------------------------------------------------------

  describe('reenviar o código (AC-001.6, AC-005.3)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // O pedido segue o contrato: método, URL e corpo exatos; o código digitado NÃO é exigido.
    it('AC-001.6 reenviar faz um único POST /auth/resend-code com { email, purpose: "EMAIL_VERIFICATION" }', () => {
      reenviar();

      const req = requisicaoResend();
      expect(req.request.body).toEqual({ email: EMAIL, purpose: 'EMAIL_VERIFICATION' });
      expect(req.request.urlWithParams).toBe(URL_RESEND);
      aceito(req);
    });

    // Reenviar não exige o código: o campo vazio não gera mensagem nem impede o pedido.
    it('AC-001.6 reenviar com o campo do código vazio envia o pedido e não mostra "Preencha este campo."', () => {
      expect(codigo().value).toBe('');

      reenviar();

      const req = requisicaoResend();
      expect(mensagemDe(codigo())).toBe('');
      expect(textoDe(host())).not.toContain(TEXTO.obrigatorio);
      aceito(req);
    });

    // O botão de reenviar não é de submit (não dispara a validação do código nem a confirmação).
    it('AC-001.6 "Reenviar código" é type=button e "Confirmar e-mail" é type=submit', () => {
      expect(botaoReenviar().type).toBe('button');
      expect(botaoConfirmar().type).toBe('submit');
    });

    // O e-mail editado é normalizado também no reenvio.
    it('AC-001.6 o reenvio usa o e-mail editado, aparado e em minúsculas', () => {
      digitar(email(), '  Outra@Exemplo.COM ');

      reenviar();

      const req = requisicaoResend();
      expect(req.request.body).toEqual({ email: 'outra@exemplo.com', purpose: 'EMAIL_VERIFICATION' });
      aceito(req);
    });

    // E-mail inválido: mensagem do catálogo e nenhuma requisição.
    it.each([[''], ['maria'], ['maria@']])('AC-001.6 reenviar com o e-mail %j mostra "Informe um e-mail válido." e não faz requisição', (valor) => {
      digitar(email(), valor);

      reenviar();

      http.expectNone(URL_RESEND);
      expect(mensagemDe(email())).toBe(MENSAGENS.VAL_EMAIL_INVALIDO);
      expect(mensagemDe(email())).toBe(TEXTO.emailInvalido);
      expect(invalido(email())).toBe(true);
    });

    // 202: texto do catálogo numa região de status (não de erro).
    it('AC-001.6 202 mostra "Se o e-mail estiver cadastrado, enviaremos um novo código." em role=status', async () => {
      reenviar();

      aceito(requisicaoResend());
      await estabilizar();

      expect(textoDosStatus()).toContain(MENSAGENS.AC_001_6_CODIGO_REENVIADO);
      expect(textoDosStatus()).toContain(TEXTO.reenviado);
      expect(alertas()).toHaveLength(0);
      // A região de status é de verdade um status (não um alerta disfarçado).
      const regiao = regiaoDeStatus().find((e) => textoDe(e).includes(TEXTO.reenviado));
      expect(regiao?.getAttribute('role')).not.toBe('alert');
    });

    // P5: o texto é o mesmo para qualquer e-mail (exista a conta ou não): a tela não diferencia.
    it('AC-001.6 o texto de sucesso é o mesmo para qualquer e-mail (a tela não diferencia conta existente)', async () => {
      const textos: string[] = [];

      // Passo 1 e 2: e-mail de conta "existente" e e-mail de conta "inexistente" têm a mesma resposta 202.
      for (const emailDoTeste of ['existente@exemplo.com', 'nao.existe@exemplo.com']) {
        digitar(email(), emailDoTeste);
        reenviar();
        aceito(requisicaoResend());
        await estabilizar();
        textos.push(textoDosStatus());
      }

      // Passo 3: textos idênticos e sem nenhum eco do e-mail digitado.
      expect(textos[0]).toBe(textos[1]);
      expect(textos[0]).toContain(TEXTO.reenviado);
      expect(textos[0]).not.toContain('exemplo.com');
    });

    // Ao reenviar, o código antigo (que não vale mais) sai do campo.
    it('AC-001.6 o campo do código é limpo depois do reenvio', async () => {
      digitar(codigo(), '123456');

      reenviar();
      aceito(requisicaoResend());
      await estabilizar();

      expect(codigo().value).toBe('');
    });

    // AC-005.3: 1 reenvio por minuto; o excesso vira o aviso do catálogo, em alerta.
    it('AC-005.3 429 mostra "Aguarde um minuto para pedir outro código." em role=alert', async () => {
      reenviar();

      requisicaoResend().flush(
        { type: 'x', title: TITULO_DO_SERVIDOR, status: 429, detail: DETALHE_DO_SERVIDOR },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '60' } },
      );
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(MENSAGENS.AC_005_3_AGUARDE_REENVIO);
      expect(textoDe(alertas()[0])).toBe(TEXTO.aguarde);
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      // 429 não é sucesso: o texto de "enviaremos um novo código" NÃO aparece.
      expect(textoDosStatus()).not.toContain(TEXTO.reenviado);
      expect(textoDe(host())).not.toContain(TEXTO.reenviado);
    });

    // Depois do 429 o botão volta (a pessoa pode tentar de novo passado o minuto).
    it('AC-005.3 depois do 429 os botões são reabilitados e um novo pedido faz nova requisição', async () => {
      reenviar();
      requisicaoResend().flush(null, { status: 429, statusText: 'Too Many Requests' });
      await estabilizar();
      expect(botaoReenviar().disabled).toBe(false);

      reenviar();

      aceito(requisicaoResend());
    });

    // Demais erros do reenvio: fallback.
    it.each([
      // Linha 1: 400 de validação.
      ['400', (req: TestRequest) => req.flush({ type: 'x', title: TITULO_DO_SERVIDOR, status: 400, detail: DETALHE_DO_SERVIDOR, errors: [] }, { status: 400, statusText: 'Bad Request' })],
      // Linha 2: 500.
      ['500', (req: TestRequest) => req.flush({ type: 'x', title: TITULO_DO_SERVIDOR, status: 500, detail: DETALHE_DO_SERVIDOR }, { status: 500, statusText: 'Server Error' })],
      // Linha 3: falha de rede.
      ['falha de rede', (req: TestRequest) => req.error(new ProgressEvent('error'))],
    ])('D-40 %s no reenvio mostra o fallback do catálogo em role=alert', async (_nome, falhar) => {
      reenviar();

      falhar(requisicaoResend());
      await estabilizar();

      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      expect(textoDosStatus()).not.toContain(TEXTO.reenviado);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Sem envio duplo
  // ---------------------------------------------------------------------------------------

  describe('sem envio duplo (AC-001.6, AC-005.3)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Confirmação em voo: os dois botões ficam desabilitados e nada extra sai.
    it('AC-001.2 com a confirmação em voo os dois botões ficam desabilitados e um segundo envio não faz outra requisição', () => {
      const req = confirmarComCodigo();

      // Passo 1: os dois botões bloqueados, o de confirmar com aria-busy.
      expect(botaoConfirmar().disabled).toBe(true);
      expect(botaoConfirmar().getAttribute('aria-busy')).toBe('true');
      expect(botaoReenviar().disabled).toBe(true);

      // Passo 2: cliques e um submit direto do formulário não enviam nada.
      botaoConfirmar().click();
      botaoReenviar().click();
      host().querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      atualizar();
      http.expectNone(URL_VERIFY);
      http.expectNone(URL_RESEND);

      sessaoIniciada(req);
    });

    // Reenvio em voo: idem; e a confirmação também fica bloqueada.
    it('AC-005.3 com o reenvio em voo os dois botões ficam desabilitados e um segundo clique não faz outra requisição', () => {
      digitar(codigo(), CODIGO);
      reenviar();
      const req = requisicaoResend();

      expect(botaoReenviar().disabled).toBe(true);
      expect(botaoConfirmar().disabled).toBe(true);

      botaoReenviar().click();
      botaoConfirmar().click();
      atualizar();
      http.expectNone(URL_RESEND);
      http.expectNone(URL_VERIFY);

      aceito(req);
    });

    // Nada fica desabilitado de forma permanente: ao terminar, os dois botões voltam.
    it('P4 depois de cada resposta os dois botões voltam a ficar habilitados', async () => {
      // Passo 1: depois de uma confirmação com erro.
      codigoIncorreto(confirmarComCodigo('111111'), 4);
      await estabilizar();
      expect(botaoConfirmar().disabled).toBe(false);
      expect(botaoReenviar().disabled).toBe(false);

      // Passo 2: depois de um reenvio com sucesso.
      reenviar();
      aceito(requisicaoResend());
      await estabilizar();
      expect(botaoConfirmar().disabled).toBe(false);
      expect(botaoReenviar().disabled).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Acessibilidade (P4)
  // ---------------------------------------------------------------------------------------

  describe('acessibilidade e campos (P4)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Rótulos em texto simples, ligados por for/id, com ids únicos.
    it.each([
      ['E-mail', () => email()],
      ['Código de 6 dígitos', () => codigo()],
    ])('P4 o campo "%s" tem <label for> ligado ao próprio campo, com id único', (rotulo, obter) => {
      const campo = obter();

      expect(campo.id).not.toBe('');
      expect(document.querySelectorAll(`[id="${campo.id}"]`)).toHaveLength(1);
      expect(host().querySelector<HTMLLabelElement>(`label[for="${campo.id}"]`)?.control).toBe(campo);
      expect(textoDe(host().querySelector(`label[for="${campo.id}"]`))).toBe(rotulo);
    });

    // Nenhum id se repete (rótulos for/id e mensagens por aria-describedby dependem disso).
    it('P4 todos os ids da tela são únicos', () => {
      const ids = Array.from(host().querySelectorAll('[id]')).map((e) => e.id);

      expect(new Set(ids).size).toBe(ids.length);
    });

    // O e-mail usa o teclado de e-mail e o preenchimento automático correto.
    it('P4 e-mail: type=email e autocomplete=email', () => {
      expect(email().type).toBe('email');
      expect(email().getAttribute('autocomplete')).toBe('email');
    });

    // Ordem natural de foco: e-mail, código, depois os botões.
    it('P4 a ordem de foco é natural: e-mail, código, botão de confirmar', () => {
      expect(email().compareDocumentPosition(codigo()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(codigo().compareDocumentPosition(botaoConfirmar()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // Nada desabilitado ou somente leitura ao abrir, nem alerta algum.
    it('P4 ao abrir nenhum controle está desabilitado nem readonly e não há role=alert', () => {
      for (const el of host().querySelectorAll<HTMLInputElement | HTMLButtonElement>('input, select, button')) {
        expect(el.disabled, el.outerHTML.slice(0, 60)).toBe(false);
        expect(el.hasAttribute('readonly'), el.outerHTML.slice(0, 60)).toBe(false);
      }
      expect(alertas()).toHaveLength(0);
    });

    // Sem o resultado do reenvio, a região de status (se existir) está vazia.
    it('P4 ao abrir a região de status do reenvio está vazia', () => {
      expect(textoDosStatus()).toBe('');
    });
  });

  // ---------------------------------------------------------------------------------------
  // Privacidade (P5)
  // ---------------------------------------------------------------------------------------

  describe('privacidade (P5)', () => {
    beforeEach(() => montar({ iniciar: 'pessoa.privada@exemplo.com' }));

    // Em todos os caminhos (código errado, expirado, reenvio, sucesso) nada sensível vaza.
    it('P5 e-mail e código nunca vão para URL, storage, cookie, estado do roteador nem console (erro, reenvio e sucesso)', async () => {
      const gravar = vi.spyOn(Storage.prototype, 'setItem');

      // Passo 1: código incorreto.
      codigoIncorreto(confirmarComCodigo('987654'), 4);
      await estabilizar();
      // Passo 2: reenvio.
      reenviar();
      aceito(requisicaoResend());
      await estabilizar();
      // Passo 3: código expirado.
      codigoExpirado(confirmarComCodigo('987654'));
      await estabilizar();
      // Passo 4: sucesso.
      sessaoIniciada(confirmarComCodigo('987654'));
      await estabilizar();

      expect(gravar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(document.cookie).toBe('');
      for (const proibido of ['privada', '987654', 'SECRETO']) {
        expect(router.url, proibido).not.toContain(proibido);
        expect(window.location.href, proibido).not.toContain(proibido);
        expect(JSON.stringify(destinosNavegados()), proibido).not.toContain(proibido);
        expect(textoDoConsole(), proibido).not.toContain(proibido);
      }
    });
  });

  // ---------------------------------------------------------------------------------------
  // Navegação que falha depois do 200 (rota inexistente, chunk lazy que não carrega offline)
  // ---------------------------------------------------------------------------------------

  describe('navegação que falha depois do 200 (AC-001.2, D-40, P4)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    /** Os dois jeitos de a navegação não acontecer: a promise REJEITA ou resolve `false`. */
    const FALHAS_DE_NAVEGACAO: [string, () => void][] = [
      // Linha 1: rejeita (ex.: NG04002 de rota inexistente, ou chunk lazy que falha offline).
      ['a promise rejeita (NG04002)', () => espiaNavegar.mockRejectedValue(new Error('NG04002'))],
      // Linha 2: resolve false (navegação cancelada ou recusada por um guard).
      ['a promise resolve false', () => espiaNavegar.mockResolvedValue(false)],
    ];

    /** Confirma com sucesso (200) e espera a promise da navegação se resolver. */
    async function confirmarEEsperar(): Promise<void> {
      sessaoIniciada(confirmarComCodigo(CODIGO));
      await harness.fixture.whenStable();
      await new Promise<void>((r) => setTimeout(r));
      atualizar();
    }

    it.each(FALHAS_DE_NAVEGACAO)(
      'AC-001.2 se %s, a sessão (já válida) é mantida e a tela volta a ser utilizável com um único alerta de fallback',
      async (_nome, simular) => {
        simular();

        await confirmarEEsperar();

        // Passo 1: a navegação foi tentada, a tela continua nela mesma.
        expect(espiaNavegar).toHaveBeenCalledWith(ROTAS.CADASTRO_IDOSO);
        expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
        // Passo 2: a sessão iniciada pelo 200 NÃO é desfeita (o servidor já ativou a conta).
        expect(session.accessToken()).toBe(TOKEN);
        expect(session.isAuthenticated()).toBe(true);
        // Passo 3: os dois botões livres, sem aria-busy.
        expect(botaoConfirmar().disabled).toBe(false);
        expect(botaoReenviar().disabled).toBe(false);
        expect(botaoConfirmar().getAttribute('aria-busy')).not.toBe('true');
        expect(botaoReenviar().getAttribute('aria-busy')).not.toBe('true');
        // Passo 4: o que foi digitado continua nos campos.
        expect(email().value).toBe(EMAIL);
        expect(codigo().value).toBe(CODIGO);
        // Passo 5: um único alerta, com o fallback do catálogo (e o literal).
        expect(alertas()).toHaveLength(1);
        expect(textoDe(alertas()[0])).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
        expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      },
    );

    // Utilizável de verdade: depois da falha da navegação, "Reenviar código" ainda funciona.
    it.each(FALHAS_DE_NAVEGACAO)('AC-001.6 se %s, o botão "Reenviar código" continua funcionando', async (_nome, simular) => {
      simular();
      await confirmarEEsperar();

      reenviar();

      const req = requisicaoResend();
      expect(req.request.body).toEqual({ email: EMAIL, purpose: 'EMAIL_VERIFICATION' });
      aceito(req);
    });
  });

  // ---------------------------------------------------------------------------------------
  // P5: o que a navegação leva (state, queryParams, fragment)
  // ---------------------------------------------------------------------------------------

  describe('navegação sem dados sensíveis (P5)', () => {
    beforeEach(() => montar({ iniciar: 'pessoa.privada@exemplo.com' }));

    // A única navegação da confirmação vai ao cadastro do idoso, só com o caminho.
    it('P5 o 200 navega uma única vez para o cadastro do idoso, sem state, queryParams, fragment nem dado na URL', async () => {
      sessaoIniciada(confirmarComCodigo('987654'));
      await estabilizar();

      const chamadas = destinosNavegados();
      // Passo 1: exatamente uma navegação, para o caminho puro (sem "?" nem "#").
      expect(chamadas).toHaveLength(1);
      expect(chamadas[0].url).toBe(ROTAS.CADASTRO_IDOSO);
      // Passo 2: nenhum extra de navegação carrega estado.
      const extras = chamadas[0].extras ?? {};
      expect(extras['state']).toBeUndefined();
      expect(extras['queryParams']).toBeUndefined();
      expect(extras['fragment']).toBeUndefined();
      // Passo 3: nem e-mail, nem código, nem token em nada do que foi pedido ao roteador.
      for (const proibido of ['privada', '987654', 'SECRETO']) {
        expect(JSON.stringify(chamadas), proibido).not.toContain(proibido);
      }
    });
  });

  // ---------------------------------------------------------------------------------------
  // Foco na validação (P4)
  // ---------------------------------------------------------------------------------------

  describe('foco no campo inválido (P4)', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // Código incompleto com e-mail válido: o foco vai para o código.
    it('P4 confirmar com o código inválido e o e-mail válido leva o foco ao campo do código', async () => {
      digitar(codigo(), '123');

      confirmar();
      await estabilizar();

      http.expectNone(URL_VERIFY);
      expect(document.activeElement).toBe(codigo());
    });

    // E-mail inválido (com código válido): o foco vai para o e-mail.
    it('P4 confirmar com o e-mail inválido leva o foco ao campo do e-mail', async () => {
      digitar(email(), 'maria');
      digitar(codigo(), CODIGO);

      confirmar();
      await estabilizar();

      http.expectNone(URL_VERIFY);
      expect(document.activeElement).toBe(email());
    });

    // Os dois inválidos: o primeiro do documento (e-mail) recebe o foco.
    it('P4 confirmar com e-mail e código inválidos leva o foco ao primeiro (e-mail)', async () => {
      digitar(email(), 'maria');
      digitar(codigo(), '12');

      confirmar();
      await estabilizar();

      expect(document.activeElement).toBe(email());
    });

    // Reenviar com e-mail inválido: o foco vai para o e-mail e nada é enviado.
    it('P4 reenviar com o e-mail inválido leva o foco ao campo do e-mail', async () => {
      digitar(email(), 'maria@');

      reenviar();
      await estabilizar();

      http.expectNone(URL_RESEND);
      expect(document.activeElement).toBe(email());
    });
  });

  // ---------------------------------------------------------------------------------------
  // Limite de tamanho (maxlength)
  // ---------------------------------------------------------------------------------------

  describe('limites de tamanho', () => {
    beforeEach(() => montar({ iniciar: EMAIL }));

    // O e-mail respeita o limite de 254 do contrato (`Email`), afirmado como atributo no DOM.
    it('AC-001.6 o campo de e-mail tem o atributo maxlength=254 no DOM', () => {
      expect(email().getAttribute('maxlength')).toBe('254');
      expect(email().maxLength).toBe(254);
    });
  });

  // ---------------------------------------------------------------------------------------
  // D-40: nenhum texto digitado na tela
  // ---------------------------------------------------------------------------------------

  describe('catálogo de mensagens (D-40)', () => {
    /** `MensagemService` trocado: cada texto vira uma marca com a chave (e os parâmetros). */
    const mensagensDeTeste = {
      texto: (chave: string, params?: Record<string, unknown>) =>
        params === undefined ? `«${chave}»` : `«${chave}|${JSON.stringify(params)}»`,
      textoDoErro: () => '«textoDoErro»',
      chaveDoCodigo: () => 'FALLBACK_ERRO_GENERICO',
    };

    beforeEach(() => montar({ iniciar: EMAIL, provedores: [{ provide: MensagemService, useValue: mensagensDeTeste }] }));

    // Se a tela digitasse algum texto, ele não mudaria quando o serviço muda: aqui todo texto muda.
    it('D-40 aviso, validações, erros e resultado do reenvio vêm do MensagemService (nada digitado na tela)', async () => {
      // Passo 1: o aviso de 24 horas.
      expect(textoDe(host())).toContain('«AC_001_1_AVISO_CONFIRMACAO»');

      // Passo 2: validações.
      digitar(email(), 'maria');
      confirmar();
      expect(mensagemDe(email())).toBe('«VAL_EMAIL_INVALIDO»');
      expect(mensagemDe(codigo())).toBe('«VAL_OBRIGATORIO»');
      digitar(email(), EMAIL);

      // Passo 3: código incorreto, com o parâmetro.
      codigoIncorreto(confirmarComCodigo('111111'), 3);
      await estabilizar();
      expect(textoDe(alertas()[0])).toBe('«AC_005_2_CODIGO_INCORRETO|{"restantes":3}»');

      // Passo 4: código expirado.
      codigoExpirado(confirmarComCodigo('222222'));
      await estabilizar();
      expect(textoDe(alertas()[0])).toBe('«AC_005_1_CODIGO_EXPIRADO»');

      // Passo 5: reenvio com sucesso e com 429.
      reenviar();
      aceito(requisicaoResend());
      await estabilizar();
      expect(textoDosStatus()).toContain('«AC_001_6_CODIGO_REENVIADO»');
      reenviar();
      requisicaoResend().flush(null, { status: 429, statusText: 'Too Many Requests' });
      await estabilizar();
      expect(textoDe(alertas()[0])).toBe('«AC_005_3_AGUARDE_REENVIO»');

      // Passo 6: fallback.
      confirmarComCodigo('333333').flush(null, { status: 500, statusText: 'Server Error' });
      await estabilizar();
      expect(textoDe(alertas()[0])).toBe('«FALLBACK_ERRO_GENERICO»');
    });
  });
});
