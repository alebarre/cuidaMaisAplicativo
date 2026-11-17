import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { AccessToken } from '../api';
import { provideApi } from '../api/provide-api';
import { SessionService } from './session.service';

/**
 * Cobre o `SessionService` (T-050): guarda do access token SOMENTE em memória (D-12),
 * renovação pelo cookie de refresh via `POST /api/v1/auth/refresh` com requisição única
 * compartilhada (single-flight, porque o refresh token rotaciona e o reuso revoga a família
 * inteira, D-11) e a restauração da sessão ao recarregar a página (`restore`), que tenta
 * renovar UMA vez por carga e nunca lança erro.
 *
 * Sem HTTP real: HttpTestingController. O interceptor não é registrado aqui de propósito
 * (ele tem o próprio spec); a base `/api/v1` vem de `provideApi`, igual ao app.config.
 */
describe('SessionService', () => {
  const URL_REFRESH = '/api/v1/auth/refresh';
  let session: SessionService;
  let http: HttpTestingController;

  /** Resposta 200 de `refreshSession`, montada com o tipo gerado. */
  const tokenNovo = (accessToken: string): AccessToken => ({
    accessToken,
    tokenType: AccessToken.TokenTypeEnum.Bearer,
    expiresIn: 900,
  });

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')],
    });
    session = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Espera algumas voltas do event loop (o `restore` pode ser assíncrono por dentro). */
  const esperar = () => new Promise<void>((r) => setTimeout(r, 0));

  // Estado inicial: ninguém entrou, então não há token e a sessão não está autenticada.
  it('D-12 começa sem token e não autenticada', () => {
    expect(session.accessToken()).toBeNull();
    expect(session.isAuthenticated()).toBe(false);
  });

  // `start` guarda o token recebido no login e passa a considerar a sessão autenticada.
  it('D-12 start guarda o token em memória e marca a sessão como autenticada', () => {
    session.start('abc');

    expect(session.accessToken()).toBe('abc');
    expect(session.isAuthenticated()).toBe(true);
  });

  // `clear` é o "Sair"/sessão encerrada (AC-008.5): o token some da memória.
  it('AC-008.5 clear apaga o token e a sessão deixa de estar autenticada', () => {
    session.start('abc');

    session.clear();

    expect(session.accessToken()).toBeNull();
    expect(session.isAuthenticated()).toBe(false);
  });

  // D-12: proteção contra roubo por XSS — o token não pode ir para nenhum armazenamento
  // persistente. Conferimos as três vias possíveis, antes e depois do `start`.
  it('D-12 o token nunca vai para localStorage, sessionStorage nem cookie', () => {
    // Passo 1: antes de iniciar, os três estão vazios (linha de base do teste).
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');

    session.start('abc');

    // Passo 2: depois de `start`, continuam vazios.
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  // D-12: mesmo o token vindo de uma renovação fica só em memória.
  it('D-12 o token obtido em refresh() também não é gravado em armazenamento do navegador', async () => {
    const resultado = firstValueFrom(session.refresh());
    http.expectOne(URL_REFRESH).flush(tokenNovo('renovado'));
    await resultado;

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  // `refresh` usa a operação `refreshSession` do cliente gerado (POST, sem corpo) e devolve
  // o novo access token, que também passa a ser o token da sessão em memória.
  it('AC-008.3 refresh faz POST /api/v1/auth/refresh e atualiza o token da sessão', async () => {
    const resultado = firstValueFrom(session.refresh());

    const req = http.expectOne(URL_REFRESH);
    expect(req.request.method).toBe('POST');
    req.flush(tokenNovo('renovado'));

    expect(await resultado).toBe('renovado');
    expect(session.accessToken()).toBe('renovado');
    expect(session.isAuthenticated()).toBe(true);
  });

  // D-11: o refresh token rotaciona; duas requisições simultâneas de renovação fariam a segunda
  // reapresentar um token já usado e revogariam a família. Por isso só UMA chamada HTTP existe
  // e todos os assinantes recebem o mesmo token.
  it('D-11 chamadas simultâneas de refresh() compartilham UMA requisição HTTP (single-flight)', async () => {
    const a = firstValueFrom(session.refresh());
    const b = firstValueFrom(session.refresh());
    const c = firstValueFrom(session.refresh());

    // expectOne falha se houver mais de uma requisição pendente para a URL.
    http.expectOne(URL_REFRESH).flush(tokenNovo('unico'));

    expect(await Promise.all([a, b, c])).toEqual(['unico', 'unico', 'unico']);
  });

  // O compartilhamento vale só enquanto a renovação está em voo: depois de concluída, uma
  // nova renovação (token expirou de novo) precisa de uma nova requisição.
  it('D-11 depois que o refresh termina, um novo refresh() faz nova requisição', async () => {
    const primeiro = firstValueFrom(session.refresh());
    http.expectOne(URL_REFRESH).flush(tokenNovo('t1'));
    await primeiro;

    const segundo = firstValueFrom(session.refresh());
    http.expectOne(URL_REFRESH).flush(tokenNovo('t2'));

    expect(await segundo).toBe('t2');
    expect(session.accessToken()).toBe('t2');
  });

  // Se o refresh falha (cookie ausente/expirado/reusado) o erro 401 chega ao chamador (o
  // interceptor decide o que fazer) e nenhum token novo passa a existir.
  it('AC-008.3 refresh() propaga o erro 401 e não deixa token na sessão', async () => {
    const resultado = firstValueFrom(session.refresh()).catch((e: any) => e);

    http.expectOne(URL_REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });

    const erro: any = await resultado;
    expect(erro.status).toBe(401);
    expect(session.accessToken()).toBeNull();
  });

  // Uma renovação que falhou não pode "travar" o single-flight: a próxima tentativa
  // (por exemplo, depois de um novo login) precisa chegar à rede.
  it('D-11 depois de um refresh que falhou, um novo refresh() volta a fazer requisição', async () => {
    const falha = firstValueFrom(session.refresh()).catch((e: any) => e);
    http.expectOne(URL_REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });
    await falha;

    const nova = firstValueFrom(session.refresh());
    http.expectOne(URL_REFRESH).flush(tokenNovo('depois'));

    expect(await nova).toBe('depois');
  });

  // restore: já há token em memória (a página não foi recarregada), então a sessão está de pé
  // e nenhuma requisição é feita (http.verify() no afterEach garante que não houve HTTP).
  it('D-12 restore() com token em memória devolve true sem fazer HTTP', async () => {
    session.start('abc');

    expect(await firstValueFrom(session.restore())).toBe(true);
    http.expectNone(URL_REFRESH);
  });

  // restore após recarregar a página (token perdido da memória, cookie de refresh ainda
  // válido): renova pela rede e devolve true com o token novo já na sessão.
  it('D-12 restore() sem token tenta renovar pelo cookie e devolve true quando funciona', async () => {
    const resultado = firstValueFrom(session.restore());

    await esperar();
    http.expectOne(URL_REFRESH).flush(tokenNovo('restaurado'));

    expect(await resultado).toBe(true);
    expect(session.accessToken()).toBe('restaurado');
  });

  // restore sem cookie válido: devolve false e NÃO lança (os guards precisam do booleano para
  // mandar a pessoa ao login).
  it('D-12 restore() devolve false, sem lançar erro, quando o refresh falha', async () => {
    const resultado = firstValueFrom(session.restore());

    await esperar();
    http.expectOne(URL_REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await resultado).toBe(false);
    expect(session.accessToken()).toBeNull();
  });

  // Falha de rede (status 0) também é "sem sessão", não uma exceção.
  it('D-12 restore() devolve false quando o refresh falha por erro de rede', async () => {
    const resultado = firstValueFrom(session.restore());

    await esperar();
    http.expectOne(URL_REFRESH).error(new ProgressEvent('error'));

    expect(await resultado).toBe(false);
  });

  // "UMA vez por carga da página": várias ativações de guard seguidas, sem sessão, não podem
  // martelar o endpoint de refresh — só a primeira tentativa vai à rede.
  it('D-12 depois de um restore() que falhou, novos restore() não repetem o HTTP', async () => {
    // Passo 1: primeira tentativa vai à rede e falha.
    const primeiro = firstValueFrom(session.restore());
    await esperar();
    http.expectOne(URL_REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(await primeiro).toBe(false);

    // Passo 2: as seguintes respondem false sem nenhuma requisição (verify no afterEach).
    expect(await firstValueFrom(session.restore())).toBe(false);
    expect(await firstValueFrom(session.restore())).toBe(false);
    http.expectNone(URL_REFRESH);
  });

  // Depois de um `start` (novo login) a sessão volta a existir e restore() é true sem HTTP.
  it('D-12 depois de uma tentativa que falhou, um start() restabelece a sessão para o restore()', async () => {
    const falha = firstValueFrom(session.restore());
    await esperar();
    http.expectOne(URL_REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });
    await falha;

    session.start('novo-login');

    expect(await firstValueFrom(session.restore())).toBe(true);
    http.expectNone(URL_REFRESH);
  });

  // Dois guards disparando restore() ao mesmo tempo (ex.: rota pai e filha) não podem gerar
  // duas renovações (D-11): compartilham a mesma requisição.
  it('D-11 restore() simultâneos compartilham UMA requisição de refresh', async () => {
    const a = firstValueFrom(session.restore());
    const b = firstValueFrom(session.restore());

    await esperar();
    http.expectOne(URL_REFRESH).flush(tokenNovo('restaurado'));

    expect(await Promise.all([a, b])).toEqual([true, true]);
  });

  // Depois de "Sair" (clear) nenhum guard pode reabrir a sessão pelo cookie na mesma carga da
  // página: restore() responde false sem tocar na rede. Um novo login (start) volta a valer.
  it('AC-008.5 depois de start() e clear(), restore() devolve false sem HTTP; um novo start() faz devolver true sem HTTP', async () => {
    // Passo 1: entrou e saiu.
    session.start('t');
    session.clear();

    // Passo 2: restore() não faz nenhuma requisição /auth/refresh e diz que não há sessão.
    expect(await firstValueFrom(session.restore())).toBe(false);
    http.expectNone(URL_REFRESH);

    // Passo 3: novo login; restore() é true e continua sem HTTP.
    session.start('novo');
    expect(await firstValueFrom(session.restore())).toBe(true);
    http.expectNone(URL_REFRESH);
  });
});
