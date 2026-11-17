import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AccessToken, AccountStateValue, Role } from '../api';
import { provideApi } from '../api/provide-api';
import { ROTAS } from '../routes';
import { AccountService } from './account.service';
import { authInterceptor } from './auth.interceptor';
import { SessionService } from './session.service';

/** Destino fictício só para o Router real conseguir navegar até `/entrar` nos testes. */
@Component({ template: '' })
class TelaFalsa {}

/**
 * Cobre o `authInterceptor` (T-050, D-12, D-11): injeta `Authorization: Bearer` nas chamadas
 * da API (`/api/v1`), renova a sessão por `/auth/refresh` num 401 de rota protegida (uma única
 * renovação compartilhada entre requisições simultâneas, reenvio único da original), não
 * mexe nas rotas públicas (`/auth/*`, `/invitations/redeem`, `/invitations/register`), deixa
 * passar os demais erros e jamais manda o token para fora de `/api/v1` (ex.: ViaCEP).
 *
 * Sem HTTP real: HttpTestingController + Router real com uma rota `/entrar` fictícia.
 */
describe('authInterceptor', () => {
  const URL_REFRESH = '/api/v1/auth/refresh';
  const URL_PROTEGIDA = '/api/v1/me';
  let session: SessionService;
  let http: HttpTestingController;
  let client: HttpClient;
  let router: Router;

  const tokenNovo = (accessToken: string): AccessToken => ({
    accessToken,
    tokenType: AccessToken.TokenTypeEnum.Bearer,
    expiresIn: 900,
  });

  const naoAutorizado = { status: 401, statusText: 'Unauthorized' };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideApi('/api/v1'),
        provideRouter([{ path: 'entrar', component: TelaFalsa }]),
      ],
    });
    session = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  /** Espera a condição ficar verdadeira (a navegação do Router é assíncrona). */
  async function esperarAte(condicao: () => boolean): Promise<void> {
    for (let i = 0; i < 100 && !condicao(); i++) {
      await new Promise<void>((r) => setTimeout(r, 0));
    }
  }

  /** Dispara a chamada e captura o erro (ou a resposta) sem derrubar o teste. */
  const chamar = (metodo: string, url: string) =>
    firstValueFrom(client.request(metodo, url)).then(
      (corpo) => ({ corpo, erro: null as any }),
      (erro) => ({ corpo: null, erro }),
    );

  // ---------------------------------------------------------------------------------------
  // (a) Injeção do token
  // ---------------------------------------------------------------------------------------

  // Com sessão iniciada, toda chamada à API leva o access token do jeito que o backend espera.
  it('D-12 anexa Authorization: Bearer <token> em chamadas para /api/v1', () => {
    session.start('tok-1');

    client.get(URL_PROTEGIDA).subscribe();

    const req = http.expectOne(URL_PROTEGIDA);
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok-1');
    req.flush({});
  });

  // O token é lido a cada chamada: depois de uma renovação/novo login o valor novo é usado.
  it('D-12 usa o token mais recente da sessão a cada requisição', () => {
    session.start('tok-1');
    session.start('tok-2');

    client.get(URL_PROTEGIDA).subscribe();

    const req = http.expectOne(URL_PROTEGIDA);
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok-2');
    req.flush({});
  });

  // Sem token (ninguém logado) o cabeçalho não existe — nada de "Bearer null/undefined".
  it('D-12 não anexa nenhum Authorization quando não há token', () => {
    client.get(URL_PROTEGIDA).subscribe({ error: () => {} });

    const req = http.expectOne(URL_PROTEGIDA);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  // ---------------------------------------------------------------------------------------
  // (b) Rotas públicas
  // ---------------------------------------------------------------------------------------

  // Rotas públicas (sem token por contrato, `security: []`, e o refresh usa o cookie): mesmo
  // com token em memória o cabeçalho não pode ser anexado. EXCEÇÃO dentro de `/auth/`: o
  // `POST /auth/logout` NÃO é público (o contrato exige bearer): ele anexa o token, mas não
  // renova no 401; por isso fica fora desta lista e tem testes próprios logo abaixo.
  const ROTAS_PUBLICAS: [string, string][] = [
    ['POST', '/api/v1/auth/login'],
    ['POST', '/api/v1/auth/signup'],
    ['POST', '/api/v1/auth/verify-email'],
    ['POST', '/api/v1/auth/resend-code'],
    ['POST', '/api/v1/auth/password-reset/request'],
    ['POST', '/api/v1/auth/password-reset/confirm'],
    ['POST', '/api/v1/auth/refresh'],
    ['POST', '/api/v1/invitations/redeem'],
    ['POST', '/api/v1/invitations/register'],
  ];

  it.each(ROTAS_PUBLICAS)(
    'D-12 não anexa o token na rota pública %s %s',
    (metodo, url) => {
      session.start('tok-1');

      client.request(metodo, url, { body: {} }).subscribe();

      const req = http.expectOne(url);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush({});
    },
  );

  // Um 401 de rota pública tem significado próprio (ex.: login = credenciais inválidas, AC-008.1)
  // e precisa chegar intacto ao chamador, sem tentar renovar a sessão.
  it.each(ROTAS_PUBLICAS.filter(([, url]) => url !== URL_REFRESH))(
    'D-12 um 401 em rota pública (%s %s) é repassado intacto, sem chamar refresh',
    async (metodo, url) => {
      // Mesmo com token em memória a regra vale.
      session.start('tok-1');
      const resultado = chamar(metodo, url);

      http.expectOne(url).flush({ title: 'Credenciais inválidas' }, naoAutorizado);
      const { erro } = await resultado;

      expect(erro.status).toBe(401);
      expect(erro.error).toEqual({ title: 'Credenciais inválidas' });
      // Nenhum POST /auth/refresh pendente (http.verify() também garante).
      http.expectNone(URL_REFRESH);
      expect(session.accessToken()).toBe('tok-1');
    },
  );

  // O próprio /auth/refresh devolvendo 401 não pode recair em outro refresh (laço infinito).
  it('D-11 um 401 do próprio /auth/refresh não dispara outro refresh', async () => {
    const resultado = chamar('POST', URL_REFRESH);

    http.expectOne(URL_REFRESH).flush(null, naoAutorizado);
    const { erro } = await resultado;

    expect(erro.status).toBe(401);
    http.expectNone(URL_REFRESH);
  });

  // `/auth/logout` é a exceção dentro de `/auth/` (AC-008.5): o contrato exige bearer, então o
  // token é anexado quando há; mas um 401 nele nunca dispara refresh (a tela de saída limpa a
  // sessão local de qualquer forma).
  const URL_LOGOUT = '/api/v1/auth/logout';

  // Com token em memória o logout leva o Bearer, ao contrário das demais rotas de /auth/.
  it('AC-008.5 POST /auth/logout anexa Authorization: Bearer quando há token', () => {
    session.start('tok-1');

    client.post(URL_LOGOUT, null).subscribe();

    const req = http.expectOne(URL_LOGOUT);
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok-1');
    req.flush(null);
  });

  // Sem token (ex.: sessão já perdida) a requisição segue sem cabeçalho algum.
  it('AC-008.5 POST /auth/logout sem token segue sem Authorization', () => {
    client.post(URL_LOGOUT, null).subscribe();

    const req = http.expectOne(URL_LOGOUT);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null);
  });

  // Um 401 no logout é repassado intacto: sem refresh, sem reenvio e sem tocar na sessão
  // (quem chama o logout cuida de limpar o estado local).
  it('AC-008.5 um 401 em POST /auth/logout é repassado intacto, sem refresh nem reenvio', async () => {
    session.start('tok-1');
    const resultado = chamar('POST', URL_LOGOUT);

    http.expectOne(URL_LOGOUT).flush({ title: 'Não autorizado' }, naoAutorizado);
    const { erro } = await resultado;

    expect(erro.status).toBe(401);
    expect(erro.error).toEqual({ title: 'Não autorizado' });
    http.expectNone(URL_REFRESH);
    http.expectNone(URL_LOGOUT);
    expect(session.accessToken()).toBe('tok-1');
  });

  // ---------------------------------------------------------------------------------------
  // (c) Renovação no 401 de rota protegida
  // ---------------------------------------------------------------------------------------

  // Fluxo feliz: 401 -> POST /auth/refresh -> reenvio da original com o token novo -> o chamador
  // recebe a resposta como se nada tivesse acontecido.
  it('D-12 num 401 renova a sessão e reenvia a requisição original UMA vez com o token novo', async () => {
    session.start('velho');
    const resultado = chamar('GET', URL_PROTEGIDA);

    // Passo 1: a original sai com o token velho e recebe 401.
    const original = http.expectOne(URL_PROTEGIDA);
    expect(original.request.headers.get('Authorization')).toBe('Bearer velho');
    original.flush(null, naoAutorizado);

    // Passo 2: o interceptor pede a renovação (sem token, é rota pública).
    const refresh = await vi.waitFor(() => http.expectOne(URL_REFRESH));
    expect(refresh.request.method).toBe('POST');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush(tokenNovo('novo'));

    // Passo 3: a original é reenviada com o token novo e sua resposta chega ao chamador.
    const reenvio = await vi.waitFor(() => http.expectOne(URL_PROTEGIDA));
    expect(reenvio.request.headers.get('Authorization')).toBe('Bearer novo');
    reenvio.flush({ ok: true });

    const { corpo, erro } = await resultado;
    expect(erro).toBeNull();
    expect(corpo).toEqual({ ok: true });
    expect(session.accessToken()).toBe('novo');
  });

  // Refresh falhou (cookie expirado, 7 dias sem uso/30 dias, família revogada): a sessão acaba,
  // vai para o login e o chamador recebe o erro ORIGINAL (a requisição que deu 401), não o do refresh.
  it('D-12 se o refresh falha: limpa a sessão, navega para o login e propaga o erro original', async () => {
    session.start('velho');
    const limpar = vi.spyOn(session, 'clear');
    const resultado = chamar('GET', URL_PROTEGIDA);

    http.expectOne(URL_PROTEGIDA).flush(null, naoAutorizado);
    const refresh = await vi.waitFor(() => http.expectOne(URL_REFRESH));
    refresh.flush(null, naoAutorizado);

    const { erro } = await resultado;
    // O erro propagado é o da requisição original (url protegida), com status 401.
    expect(erro.status).toBe(401);
    expect(erro.url).toContain(URL_PROTEGIDA);
    expect(erro.url).not.toContain('/auth/refresh');
    // A sessão foi encerrada...
    expect(limpar).toHaveBeenCalled();
    expect(session.accessToken()).toBeNull();
    // ...e a pessoa foi levada ao login (rota do Router real).
    await esperarAte(() => router.url === ROTAS.LOGIN);
    expect(router.url).toBe(ROTAS.LOGIN);
    // A original não foi reenviada sem token.
    http.expectNone(URL_PROTEGIDA);
  });

  // Sem laço: se mesmo depois de renovar o servidor responde 401 de novo (ex.: conta REMOVIDA,
  // D-13), o interceptor não renova outra vez — o erro vai para o chamador.
  it('D-11 um 2º 401 depois do reenvio NÃO dispara novo refresh (sem laço)', async () => {
    session.start('velho');
    const resultado = chamar('GET', URL_PROTEGIDA);

    // Passo 1: 401 da original e renovação bem-sucedida.
    http.expectOne(URL_PROTEGIDA).flush(null, naoAutorizado);
    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(tokenNovo('novo'));

    // Passo 2: o reenvio também recebe 401.
    (await vi.waitFor(() => http.expectOne(URL_PROTEGIDA))).flush(null, naoAutorizado);

    const { erro } = await resultado;
    expect(erro.status).toBe(401);
    // Passo 3: nenhuma segunda renovação nem segundo reenvio (o verify() também cobre).
    http.expectNone(URL_REFRESH);
    http.expectNone(URL_PROTEGIDA);
  });

  // D-11: várias chamadas protegidas simultâneas recebem 401 (token expirou); só UMA renovação
  // acontece, senão a 2ª reapresentaria o refresh token já rotacionado e revogaria a família.
  it('D-11 401 simultâneos compartilham UMA renovação e todas as originais são reenviadas', async () => {
    session.start('velho');
    const a = chamar('GET', '/api/v1/me');
    const b = chamar('GET', '/api/v1/me/account');
    const c = chamar('GET', '/api/v1/elders/me');

    // Passo 1: as três originais recebem 401.
    http.expectOne('/api/v1/me').flush(null, naoAutorizado);
    http.expectOne('/api/v1/me/account').flush(null, naoAutorizado);
    http.expectOne('/api/v1/elders/me').flush(null, naoAutorizado);

    // Passo 2: expectOne falha se houver mais de uma renovação pendente.
    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(tokenNovo('novo'));

    // Passo 3: as três são reenviadas com o token novo e respondem.
    for (const url of ['/api/v1/me', '/api/v1/me/account', '/api/v1/elders/me']) {
      const reenvio = await vi.waitFor(() => http.expectOne(url));
      expect(reenvio.request.headers.get('Authorization')).toBe('Bearer novo');
      reenvio.flush({ url });
    }

    const respostas = await Promise.all([a, b, c]);
    expect(respostas.map((r) => r.erro)).toEqual([null, null, null]);
    http.expectNone(URL_REFRESH);
  });

  // ---------------------------------------------------------------------------------------
  // (d) Outros erros não renovam
  // ---------------------------------------------------------------------------------------

  // 403 (sem permissão, AC-015.2), 404, 409 (conflito), 500...: não são problema de token;
  // passam direto, sem refresh e sem reenvio.
  it.each([400, 403, 404, 409, 500])(
    'D-12 o erro %i passa direto, sem refresh nem reenvio',
    async (status) => {
      session.start('tok-1');
      const resultado = chamar('GET', URL_PROTEGIDA);

      http.expectOne(URL_PROTEGIDA).flush({ title: 'x' }, { status, statusText: 'Erro' });
      const { erro } = await resultado;

      expect(erro.status).toBe(status);
      http.expectNone(URL_REFRESH);
      http.expectNone(URL_PROTEGIDA);
      expect(session.accessToken()).toBe('tok-1');
    },
  );

  // ---------------------------------------------------------------------------------------
  // (e) Nunca vazar o token para terceiros
  // ---------------------------------------------------------------------------------------

  // ViaCEP (D-27) é chamado direto do navegador: receber o access token seria vazamento.
  it('D-12 não anexa o token em URLs fora de /api/v1 (ViaCEP)', () => {
    session.start('tok-1');
    const url = 'https://viacep.com.br/ws/01001000/json/';

    client.get(url).subscribe();

    const req = http.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  // Um host externo com caminho parecido (`/api/v1`) também não é a nossa API.
  it('D-12 não anexa o token em URL absoluta de outro host mesmo com caminho /api/v1', () => {
    session.start('tok-1');
    const url = 'https://terceiro.exemplo.com/api/v1/me';

    client.get(url).subscribe();

    const req = http.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  // Um 401 vindo de terceiro não é problema da nossa sessão: sem refresh e sem reenvio.
  it('D-12 um 401 de URL fora de /api/v1 não dispara refresh', async () => {
    session.start('tok-1');
    const url = 'https://viacep.com.br/ws/01001000/json/';
    const resultado = chamar('GET', url);

    http.expectOne(url).flush(null, naoAutorizado);
    const { erro } = await resultado;

    expect(erro.status).toBe(401);
    http.expectNone(URL_REFRESH);
    expect(session.accessToken()).toBe('tok-1');
  });

  // ---------------------------------------------------------------------------------------
  // Acréscimos pós-verificação: URLs "parecidas" com a API e limpeza da conta
  // ---------------------------------------------------------------------------------------

  // Só caminhos relativos exatamente em `/api/v1` ou `/api/v1/...` são nossa API. Prefixos
  // enganosos (`/api/v10`, `/api/v1.evil`), URL sem protocolo (`//host`) e URL absoluta de outro
  // host não podem receber o token (D-12) nem disparar renovação num 401.
  const URLS_PARECIDAS_COM_A_API = [
    '/api/v10/x', // prefixo de texto igual, mas é outra versão/rota
    '/api/v1.evil/x', // idem, com ponto
    '//host-externo/api/v1/x', // protocol-relative: o host é o externo
    'https://outro-host.com/api/v1/x', // absoluta de terceiro
  ];

  it.each(URLS_PARECIDAS_COM_A_API)(
    'D-12 não anexa o token em %s',
    (url) => {
      session.start('tok-1');

      client.get(url).subscribe();

      const req = http.expectOne(url);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush({});
    },
  );

  it.each(URLS_PARECIDAS_COM_A_API)(
    'D-12 um 401 em %s não dispara refresh',
    async (url) => {
      session.start('tok-1');
      const resultado = chamar('GET', url);

      http.expectOne(url).flush(null, naoAutorizado);
      const { erro } = await resultado;

      expect(erro.status).toBe(401);
      http.expectNone(URL_REFRESH);
      expect(session.accessToken()).toBe('tok-1');
    },
  );

  // Quando a renovação falha a sessão acaba por inteiro: além do token, o estado da conta em
  // memória (inclusive isAdmin) não pode sobrar para o próximo login no mesmo aparelho.
  it('D-13 se o refresh falha, além da sessão o interceptor limpa a conta (AccountService)', async () => {
    const account = TestBed.inject(AccountService);
    session.start('velho');

    // Passo 1: uma conta admin está carregada em memória.
    const carga = firstValueFrom(account.refresh());
    http.expectOne('/api/v1/me/account').flush({
      state: AccountStateValue.Ativo,
      hasGroup: true,
      elderId: '11111111-1111-1111-1111-111111111111',
      role: Role.Familiar,
      isAdmin: true,
    });
    await carga;
    expect(account.isAdmin()).toBe(true);

    // Passo 2: uma chamada protegida dá 401 e o refresh também (401 do /auth/refresh).
    const resultado = chamar('GET', URL_PROTEGIDA);
    http.expectOne(URL_PROTEGIDA).flush(null, naoAutorizado);
    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(null, naoAutorizado);
    const { erro } = await resultado;

    // Passo 3: erro original propagado, sessão e conta limpas.
    expect(erro.status).toBe(401);
    expect(session.accessToken()).toBeNull();
    expect(account.account()).toBeNull();
    expect(account.isAdmin()).toBe(false);
  });
});
