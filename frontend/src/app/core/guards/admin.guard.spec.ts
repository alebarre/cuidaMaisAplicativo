import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, GuardResult, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom, isObservable } from 'rxjs';
import { AccountState, AccountStateValue, Role } from '../api';
import { provideApi } from '../api/provide-api';
import { AccountService } from '../auth/account.service';
import { SessionService } from '../auth/session.service';
import { ROTAS } from '../routes';
import { adminGuard } from './admin.guard';

/**
 * Cobre o `adminGuard` (T-050, D-20, D-33, AC-015.1, AC-012.1): guarda das rotas só do
 * Responsável (convites, pedidos, aprovação, exclusão do grupo...). Libera `ATIVO` com grupo e
 * `isAdmin=true`; `ATIVO` com grupo e `isAdmin=false` (Cuidador ou Familiar comum) vai para a
 * home; os demais estados e a ausência de sessão seguem os redirecionamentos do `memberAreaGuard`.
 * Há um teste por valor de `AccountStateValue`.
 *
 * Os guards só melhoram a experiência de uso: o backend SEMPRE revalida cada requisição (D-20),
 * e uma ação vedada chegando por outro caminho recebe 403 (AC-015.2).
 */
describe('adminGuard', () => {
  const URL_CONTA = '/api/v1/me/account';
  const URL_REFRESH = '/api/v1/auth/refresh';
  let http: HttpTestingController;
  let router: Router;
  let session: SessionService;
  let account: AccountService;

  const rota = {} as ActivatedRouteSnapshot;
  const estado = { url: '/convites' } as RouterStateSnapshot;

  const conta = (parcial: Partial<AccountState> = {}): AccountState => ({
    state: AccountStateValue.Ativo,
    hasGroup: true,
    elderId: '11111111-1111-1111-1111-111111111111',
    role: Role.Familiar,
    isAdmin: true,
    ...parcial,
  });

  /** Destinos por estado, com `isAdmin=true` para provar que nenhum estado fora de ATIVO libera. */
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

  function executar(): Promise<GuardResult> {
    const r = TestBed.runInInjectionContext(() => adminGuard(rota, estado));
    return Promise.resolve(isObservable(r) ? firstValueFrom(r) : r);
  }

  async function ativarComConta(corpo: AccountState): Promise<GuardResult> {
    session.start('tok');
    const resultado = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(corpo);
    return resultado;
  }

  function esperarRedirecionamento(resultado: GuardResult, rotaEsperada: string): void {
    expect(resultado).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(resultado as UrlTree)).toBe(router.serializeUrl(router.parseUrl(rotaEsperada)));
  }

  // Rede de segurança contra estado novo no contrato sem tratamento (ver memberAreaGuard).
  it('AC-012.1 a tabela de expectativas cobre TODOS os valores de AccountStateValue', () => {
    expect(Object.keys(ESPERADO).sort()).toEqual(Object.values(AccountStateValue).sort());
  });

  // Um teste por estado, SEMPRE com isAdmin=true no corpo: só ATIVO com grupo libera; nos
  // outros estados o campo isAdmin não vale (D-33) e o redirecionamento é o do memberAreaGuard.
  it.each(Object.values(AccountStateValue))(
    'AC-012.1 / D-20 estado %s (isAdmin=true) leva ao destino previsto para rotas de admin',
    async (valor) => {
      const { hasGroup, destino, encerraSessao } = ESPERADO[valor];
      const limparSessao = vi.spyOn(session, 'clear');
      const limparConta = vi.spyOn(account, 'clear');

      // Passo 1: ativa a rota de admin com uma conta nesse estado.
      const resultado = await ativarComConta(conta({ state: valor, hasGroup, isAdmin: true }));

      // Passo 2: libera (ATIVO + grupo + admin) ou redireciona.
      if (destino === true) {
        expect(resultado).toBe(true);
      } else {
        esperarRedirecionamento(resultado, destino);
      }

      // Passo 3: RECUSADO/EXPIRADO/REMOVIDO encerram a sessão; os outros não.
      if (encerraSessao) {
        expect(limparSessao).toHaveBeenCalled();
        expect(limparConta).toHaveBeenCalled();
        expect(session.isAuthenticated()).toBe(false);
      } else {
        expect(limparSessao).not.toHaveBeenCalled();
      }
    },
  );

  // AC-015.1: o Responsável (FAMILIAR com isAdmin=true, D-29) entra.
  it('AC-015.1 ATIVO com grupo e isAdmin=true (Responsável) entra', async () => {
    expect(await ativarComConta(conta({ role: Role.Familiar, isAdmin: true }))).toBe(true);
  });

  // AC-015.1: o Cuidador não é admin; a rota de admin leva à home (a ação nem aparece).
  it('AC-015.1 ATIVO com grupo e isAdmin=false (Cuidador) é levado à home', async () => {
    const resultado = await ativarComConta(conta({ role: Role.Cuidador, isAdmin: false }));

    esperarRedirecionamento(resultado, ROTAS.HOME);
  });

  // AC-015.1: um Familiar comum (não Responsável) também não entra.
  it('AC-015.1 ATIVO com grupo e isAdmin=false (Familiar comum) é levado à home', async () => {
    const resultado = await ativarComConta(conta({ role: Role.Familiar, isAdmin: false }));

    esperarRedirecionamento(resultado, ROTAS.HOME);
  });

  // AC-001.7 tem precedência: ATIVO sem grupo continua preso no cadastro do idoso, mesmo que o
  // corpo traga isAdmin=true (o campo só vale com vínculo, D-33).
  it('AC-001.7 ATIVO sem grupo continua preso no cadastro do idoso, mesmo com isAdmin=true', async () => {
    const resultado = await ativarComConta(conta({ hasGroup: false, role: null, elderId: null, isAdmin: true }));

    esperarRedirecionamento(resultado, ROTAS.CADASTRO_IDOSO);
  });

  // Sem sessão: login, sem consultar a conta.
  it('D-12 sem sessão (restore falha) redireciona ao login sem consultar /me/account', async () => {
    const resultado = executar();

    (await vi.waitFor(() => http.expectOne(URL_REFRESH))).flush(null, { status: 401, statusText: 'Unauthorized' });

    esperarRedirecionamento(await resultado, ROTAS.LOGIN);
    http.expectNone(URL_CONTA);
  });

  // Falha ao consultar a conta: login.
  it('D-20 falha 401 ao consultar /me/account redireciona ao login', async () => {
    session.start('tok');
    const resultado = executar();

    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(null, { status: 401, statusText: 'Unauthorized' });

    esperarRedirecionamento(await resultado, ROTAS.LOGIN);
  });

  // D-13: um GET por ativação; o Responsável rebaixado deixa de entrar na navegação seguinte.
  it('D-13 cada ativação consulta /me/account de novo (rebaixamento vale na hora)', async () => {
    session.start('tok');

    // Passo 1: ainda Responsável -> libera.
    const primeira = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta({ isAdmin: true }));
    expect(await primeira).toBe(true);

    // Passo 2: nova ativação com outro GET; agora sem admin -> home.
    const segunda = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta({ isAdmin: false }));
    esperarRedirecionamento(await segunda, ROTAS.HOME);
  });

  // Estado desconhecido nunca libera rota de admin.
  it('D-20 estado desconhecido nunca libera rota de admin (falha fechada)', async () => {
    const resultado = await ativarComConta({ ...conta(), state: 'ESTADO_NOVO' as AccountStateValue });

    expect(resultado).not.toBe(true);
  });
});
