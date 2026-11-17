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
import { elderSetupGuard } from './elder-setup.guard';

/**
 * Cobre o `elderSetupGuard` (T-050, D-20, D-33, AC-001.7, AC-012.1): guarda da rota de cadastro
 * do idoso. Só o criador `ATIVO` SEM grupo pode estar ali; `ATIVO` com grupo já tem idoso e vai
 * para a home; os demais estados e a ausência de sessão seguem os mesmos redirecionamentos do
 * `memberAreaGuard`. Há um teste por valor de `AccountStateValue` e um que falha se um estado
 * novo surgir no contrato sem tratamento.
 *
 * Os guards só melhoram a experiência de uso: o backend SEMPRE revalida cada requisição (D-20).
 */
describe('elderSetupGuard', () => {
  const URL_CONTA = '/api/v1/me/account';
  const URL_REFRESH = '/api/v1/auth/refresh';
  let http: HttpTestingController;
  let router: Router;
  let session: SessionService;
  let account: AccountService;

  const rota = {} as ActivatedRouteSnapshot;
  const estado = { url: '/idoso/novo' } as RouterStateSnapshot;

  const conta = (parcial: Partial<AccountState> = {}): AccountState => ({
    state: AccountStateValue.Ativo,
    hasGroup: false,
    elderId: null,
    role: null,
    isAdmin: false,
    ...parcial,
  });

  /** Destinos por estado. ATIVO aqui é o criador SEM grupo (único que fica na rota). */
  const ESPERADO: Record<AccountStateValue, { hasGroup: boolean; destino: true | string; encerraSessao: boolean }> = {
    AGUARDANDO_CONFIRMACAO_EMAIL: { hasGroup: false, destino: ROTAS.CONFIRMAR_EMAIL, encerraSessao: false },
    ATIVO: { hasGroup: false, destino: true, encerraSessao: false },
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
    const r = TestBed.runInInjectionContext(() => elderSetupGuard(rota, estado));
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

  // Um teste por estado (cada linha da tabela de AC-012.1 vira uma linha do it.each).
  it.each(Object.values(AccountStateValue))(
    'AC-012.1 / D-20 estado %s leva ao destino previsto para o cadastro do idoso',
    async (valor) => {
      const { hasGroup, destino, encerraSessao } = ESPERADO[valor];
      const limparSessao = vi.spyOn(session, 'clear');
      const limparConta = vi.spyOn(account, 'clear');

      // Passo 1: ativa a rota de cadastro do idoso com uma conta nesse estado.
      const resultado = await ativarComConta(conta({ state: valor, hasGroup }));

      // Passo 2: libera só o ATIVO sem grupo; os demais vão para a tela do seu estado.
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

  // AC-001.7: o criador ATIVO sem idoso tem como única tela o cadastro do idoso.
  it('AC-001.7 ATIVO sem grupo permanece no cadastro do idoso', async () => {
    expect(await ativarComConta(conta({ state: AccountStateValue.Ativo, hasGroup: false }))).toBe(true);
  });

  // Quem já tem grupo não volta a cadastrar o idoso (o grupo tem exatamente um idoso, AC-002.1):
  // vai para a home.
  it('AC-001.7 ATIVO com grupo já cadastrado é levado à home', async () => {
    const resultado = await ativarComConta(
      conta({ state: AccountStateValue.Ativo, hasGroup: true, role: Role.Familiar, elderId: '22222222-2222-2222-2222-222222222222' }),
    );

    esperarRedirecionamento(resultado, ROTAS.HOME);
  });

  // Sem sessão: login, e a conta nem é consultada.
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

  // D-13: um GET por ativação. Segunda ativação após a conta passar a ter grupo (idoso cadastrado).
  it('D-13 cada ativação consulta /me/account de novo', async () => {
    session.start('tok');

    // Passo 1: sem grupo, fica na rota.
    const primeira = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta());
    expect(await primeira).toBe(true);

    // Passo 2: nova ativação, outro GET; agora com grupo, vai para a home.
    const segunda = executar();
    (await vi.waitFor(() => http.expectOne(URL_CONTA))).flush(conta({ hasGroup: true, role: Role.Familiar, isAdmin: true }));
    esperarRedirecionamento(await segunda, ROTAS.HOME);
  });

  // Estado desconhecido nunca libera a rota.
  it('D-20 estado desconhecido nunca libera o cadastro do idoso (falha fechada)', async () => {
    const resultado = await ativarComConta({ ...conta(), state: 'ESTADO_NOVO' as AccountStateValue });

    expect(resultado).not.toBe(true);
  });
});
