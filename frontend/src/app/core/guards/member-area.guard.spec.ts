import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, GuardResult, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom, isObservable } from 'rxjs';
import { AccessToken, AccountState, AccountStateValue, Role } from '../api';
import { provideApi } from '../api/provide-api';
import { AccountService } from '../auth/account.service';
import { SessionService } from '../auth/session.service';
import { ROTAS } from '../routes';
import { memberAreaGuard } from './member-area.guard';

/**
 * Cobre o `memberAreaGuard` (T-050, D-20, D-33, AC-012.1, AC-001.7): guarda da área logada.
 * A cada ativação consulta `GET /api/v1/me/account` e decide pelo estado da conta:
 * `ATIVO` com grupo entra; `ATIVO` sem grupo fica PRESO no cadastro do idoso (AC-001.7);
 * `AGUARDANDO_CONFIRMACAO_EMAIL` e `AGUARDANDO_APROVACAO` vão para a tela do seu estado;
 * `RECUSADO`, `EXPIRADO` e `REMOVIDO` encerram a sessão e vão ao login; sem sessão ou com falha
 * ao consultar a conta, login. Há um teste por valor de `AccountStateValue` e um que falha se
 * um estado novo aparecer no contrato sem tratamento.
 *
 * Os guards só melhoram a experiência de uso: o backend SEMPRE revalida cada requisição (D-20).
 * Os guards são exercitados com `TestBed.runInInjectionContext`, sem HTTP real.
 */
describe('memberAreaGuard', () => {
  const URL_CONTA = '/api/v1/me/account';
  const URL_REFRESH = '/api/v1/auth/refresh';
  let http: HttpTestingController;
  let router: Router;
  let session: SessionService;
  let account: AccountService;

  const rota = {} as ActivatedRouteSnapshot;
  const estado = { url: '/inicio' } as RouterStateSnapshot;

  /** Monta o AccountState com o tipo gerado. */
  const conta = (parcial: Partial<AccountState> = {}): AccountState => ({
    state: AccountStateValue.Ativo,
    hasGroup: true,
    elderId: '11111111-1111-1111-1111-111111111111',
    role: Role.Familiar,
    isAdmin: false,
    ...parcial,
  });

  /** Destinos esperados por estado (todos os valores do contrato precisam estar aqui). */
  const ESPERADO: Record<AccountStateValue, { hasGroup: boolean; destino: true | string; encerraSessao: boolean }> = {
    AGUARDANDO_CONFIRMACAO_EMAIL: { hasGroup: false, destino: ROTAS.CONFIRMAR_EMAIL, encerraSessao: false },
    ATIVO: { hasGroup: true, destino: true, encerraSessao: false },
    AGUARDANDO_APROVACAO: { hasGroup: true, destino: ROTAS.AGUARDANDO_APROVACAO, encerraSessao: false },
    RECUSADO: { hasGroup: true, destino: ROTAS.LOGIN, encerraSessao: true },
    EXPIRADO: { hasGroup: true, destino: ROTAS.LOGIN, encerraSessao: true },
    REMOVIDO: { hasGroup: true, destino: ROTAS.LOGIN, encerraSessao: true },
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    session = TestBed.inject(SessionService);
    account = TestBed.inject(AccountService);
  });

  afterEach(() => http.verify());

  /** Executa o guard num contexto de injeção e normaliza boolean/UrlTree/Promise/Observable. */
  function executar(): Promise<GuardResult> {
    const r = TestBed.runInInjectionContext(() => memberAreaGuard(rota, estado));
    return Promise.resolve(isObservable(r) ? firstValueFrom(r) : r);
  }

  /** Roda o guard com sessão ativa e responde o GET /me/account com o `corpo` indicado. */
  async function ativarComConta(corpo: AccountState): Promise<GuardResult> {
    session.start('tok');
    const resultado = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(corpo);
    return resultado;
  }

  /** Afirma que o resultado é um redirecionamento (UrlTree) para a rota indicada. */
  function esperarRedirecionamento(resultado: GuardResult, rotaEsperada: string): void {
    expect(resultado).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(resultado as UrlTree)).toBe(router.serializeUrl(router.parseUrl(rotaEsperada)));
  }

  // Rede de segurança: a tabela de expectativas acima precisa cobrir exatamente os estados do
  // contrato. Se o openapi ganhar um estado novo, este teste (e a tipagem do Record) falham
  // até alguém decidir o que o guard faz com ele.
  it('AC-012.1 a tabela de expectativas cobre TODOS os valores de AccountStateValue', () => {
    expect(Object.keys(ESPERADO).sort()).toEqual(Object.values(AccountStateValue).sort());
  });

  // Um teste por estado do enum (parametrizado): cada linha da tabela de AC-012.1 é uma linha
  // do it.each; o ATIVO sem grupo (AC-001.7) tem teste próprio logo abaixo.
  it.each(Object.values(AccountStateValue))(
    'AC-012.1 / D-20 estado %s leva ao destino previsto',
    async (valor) => {
      const { hasGroup, destino, encerraSessao } = ESPERADO[valor];
      const limparSessao = vi.spyOn(session, 'clear');
      const limparConta = vi.spyOn(account, 'clear');

      // Passo 1: ativa o guard com uma conta nesse estado.
      const resultado = await ativarComConta(conta({ state: valor, hasGroup }));

      // Passo 2: libera (true) ou redireciona para a rota do estado.
      if (destino === true) {
        expect(resultado).toBe(true);
      } else {
        esperarRedirecionamento(resultado, destino);
      }

      // Passo 3: só RECUSADO/EXPIRADO/REMOVIDO encerram a sessão (sessão e conta em memória).
      if (encerraSessao) {
        expect(limparSessao).toHaveBeenCalled();
        expect(limparConta).toHaveBeenCalled();
        expect(session.isAuthenticated()).toBe(false);
      } else {
        expect(limparSessao).not.toHaveBeenCalled();
      }
    },
  );

  // AC-001.7: o criador ATIVO sem idoso é levado ao cadastro do idoso toda vez, e a área
  // logada (qualquer outra tela) não está disponível para ele. A sessão NÃO é encerrada (ele
  // precisa dela para cadastrar o idoso e para o "Sair").
  it('AC-001.7 ATIVO sem grupo fica preso: redireciona ao cadastro do idoso e mantém a sessão', async () => {
    const limparSessao = vi.spyOn(session, 'clear');

    const resultado = await ativarComConta(conta({ state: AccountStateValue.Ativo, hasGroup: false, role: null, elderId: null }));

    esperarRedirecionamento(resultado, ROTAS.CADASTRO_IDOSO);
    expect(limparSessao).not.toHaveBeenCalled();
    expect(session.isAuthenticated()).toBe(true);
  });

  // D-20: ATIVO com idoso é o único caso que entra na área logada.
  it('D-20 ATIVO com grupo (hasGroup=true) entra na área logada', async () => {
    expect(await ativarComConta(conta({ state: AccountStateValue.Ativo, hasGroup: true }))).toBe(true);
  });

  // D-33: a conta consultada fica disponível no AccountService para o resto da interface
  // (diretiva, telas) sem nova chamada.
  it('D-33 depois de liberar a navegação, a conta fica no AccountService', async () => {
    const corpo = conta({ role: Role.Cuidador, isAdmin: false });

    await ativarComConta(corpo);

    expect(account.account()).toEqual(corpo);
  });

  // Sem token em memória e sem cookie de refresh válido: não há sessão, vai ao login e o
  // endpoint da conta nem é chamado (seria 401 inútil).
  it('D-12 sem sessão (restore falha) redireciona ao login sem consultar /me/account', async () => {
    const resultado = executar();

    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(null, { status: 401, statusText: 'Unauthorized' });

    esperarRedirecionamento(await resultado, ROTAS.LOGIN);
    http.expectNone(URL_CONTA);
  });

  // Recarregar a página perde o token da memória (D-12), mas o cookie de refresh ainda vale:
  // o guard restaura a sessão e só então consulta a conta.
  it('D-12 sem token mas com cookie de refresh válido, restaura a sessão e segue para /me/account', async () => {
    const resultado = executar();

    // Passo 1: renovação pelo cookie.
    const novo: AccessToken = { accessToken: 'restaurado', tokenType: AccessToken.TokenTypeEnum.Bearer, expiresIn: 900 };
    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(novo);
    // Passo 2: consulta da conta já com a sessão de pé.
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta());

    expect(await resultado).toBe(true);
    expect(session.accessToken()).toBe('restaurado');
  });

  // Falha ao consultar a conta (401 depois de a sessão expirar): na dúvida, login.
  it('D-20 falha 401 ao consultar /me/account redireciona ao login', async () => {
    session.start('tok');
    const resultado = executar();

    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(null, { status: 401, statusText: 'Unauthorized' });

    esperarRedirecionamento(await resultado, ROTAS.LOGIN);
  });

  // Falha de rede ao consultar a conta: também login (não libera a área por engano).
  it('D-20 falha de rede ao consultar /me/account redireciona ao login', async () => {
    session.start('tok');
    const resultado = executar();

    (await vi.waitFor(() => http.expectOne(URL_CONTA))).error(new ProgressEvent('error'));

    esperarRedirecionamento(await resultado, ROTAS.LOGIN);
  });

  // D-13: a remoção vale na hora, então cada ativação do guard consulta o servidor de novo
  // (um GET por navegação protegida). Dois passos: a conta é ATIVO e depois vira REMOVIDO.
  it('D-13 cada ativação consulta /me/account de novo (uma remoção vale na próxima navegação)', async () => {
    session.start('tok');

    // Passo 1: primeira navegação, conta ATIVO com grupo -> libera.
    const primeira = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta());
    expect(await primeira).toBe(true);

    // Passo 2: segunda navegação gera OUTRO GET (sem cache); agora a conta foi removida.
    const segunda = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta({ state: AccountStateValue.Removido }));

    esperarRedirecionamento(await segunda, ROTAS.LOGIN);
    expect(session.isAuthenticated()).toBe(false);
  });

  // Defesa em profundidade: um valor de estado desconhecido (contrato mais novo que o app) não
  // pode liberar a área logada. O destino não é especificado; só exigimos "não libera".
  it('D-20 estado desconhecido nunca libera a área logada (falha fechada)', async () => {
    const resultado = await ativarComConta({ ...conta(), state: 'ESTADO_NOVO' as AccountStateValue });

    expect(resultado).not.toBe(true);
  });
});
