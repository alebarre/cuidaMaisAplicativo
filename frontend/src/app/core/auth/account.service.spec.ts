import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { AccountState, AccountStateValue, Role } from '../api';
import { provideApi } from '../api/provide-api';
import { AccountService } from './account.service';

/**
 * Cobre o `AccountService` (T-050, D-20, D-33, AC-012.1): guarda em um signal o estado da conta
 * lido de `GET /api/v1/me/account` (operação `getMyAccountState` do cliente gerado), expõe
 * `isAdmin()`/`role()` para guards e diretiva e se comporta de forma segura em falha (sem estado,
 * sem admin). Cada `refresh()` consulta o servidor de novo, porque o estado muda a qualquer
 * momento e a remoção vale na hora (D-13).
 *
 * Sem HTTP real: HttpTestingController (sem interceptor; ele tem spec próprio).
 */
describe('AccountService', () => {
  const URL_CONTA = '/api/v1/me/account';
  let service: AccountService;
  let http: HttpTestingController;

  /** Monta um AccountState com o tipo gerado, sobrescrevendo só o que o teste precisa. */
  const conta = (parcial: Partial<AccountState> = {}): AccountState => ({
    state: AccountStateValue.Ativo,
    hasGroup: true,
    elderId: '11111111-1111-1111-1111-111111111111',
    role: Role.Familiar,
    isAdmin: false,
    ...parcial,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')],
    });
    service = TestBed.inject(AccountService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // Antes de consultar o servidor não se sabe nada da conta: nada de admin por padrão.
  it('D-20 começa sem conta, sem papel e sem ser admin', () => {
    expect(service.account()).toBeNull();
    expect(service.isAdmin()).toBe(false);
    expect(service.role() ?? null).toBeNull();
  });

  // O estado vem do endpoint do contrato (GET, sem corpo) e é entregue a quem assinou
  // e guardado no signal.
  it('AC-012.1 refresh() faz GET /api/v1/me/account, devolve o estado e o grava no signal', async () => {
    const esperado = conta({ state: AccountStateValue.Ativo, role: Role.Familiar, isAdmin: true });
    const resultado = firstValueFrom(service.refresh());

    const req = http.expectOne(URL_CONTA);
    expect(req.request.method).toBe('GET');
    req.flush(esperado);

    expect(await resultado).toEqual(esperado);
    expect(service.account()).toEqual(esperado);
  });

  // Responsável = FAMILIAR com isAdmin=true (D-29): isAdmin() reflete exatamente o campo.
  it('AC-015.1 isAdmin() e role() refletem o Responsável (FAMILIAR com isAdmin=true)', async () => {
    const resultado = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ role: Role.Familiar, isAdmin: true }));
    await resultado;

    expect(service.isAdmin()).toBe(true);
    expect(service.role()).toBe(Role.Familiar);
  });

  // Cuidador nunca é admin (AC-015.1): isAdmin() false, papel CUIDADOR.
  it('AC-015.1 isAdmin() é false para o Cuidador', async () => {
    const resultado = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ role: Role.Cuidador, isAdmin: false }));
    await resultado;

    expect(service.isAdmin()).toBe(false);
    expect(service.role()).toBe(Role.Cuidador);
  });

  // `isAdmin()` é estritamente booleano: `account()?.isAdmin === true`, nunca um valor truthy.
  it('AC-015.1 isAdmin() só é true quando o campo é exatamente true', async () => {
    const resultado = firstValueFrom(service.refresh());
    // Um valor "truthy" que não é boolean true (resposta inesperada do servidor).
    http.expectOne(URL_CONTA).flush({ ...conta(), isAdmin: 'true' });
    await resultado;

    expect(service.isAdmin()).toBe(false);
  });

  // D-20/D-13: o estado pode mudar a qualquer momento; cada refresh() é uma nova consulta e
  // o signal passa a refletir a resposta mais recente (conta rebaixada deixa de ser admin).
  it('D-13 cada refresh() consulta o servidor de novo e o signal acompanha a resposta mais recente', async () => {
    // Passo 1: admin.
    const primeira = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ isAdmin: true }));
    await primeira;
    expect(service.isAdmin()).toBe(true);

    // Passo 2: nova consulta (outra requisição, sem cache) e agora não é mais admin.
    const segunda = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ isAdmin: false }));
    await segunda;
    expect(service.isAdmin()).toBe(false);
  });

  // 401 (sessão inválida) não é exceção para o guard: devolve null e esquece o estado antigo,
  // senão uma conta já removida continuaria parecendo admin.
  it('D-20 refresh() devolve null (sem lançar) num 401 e limpa o estado anterior', async () => {
    const carga = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ isAdmin: true }));
    await carga;
    expect(service.isAdmin()).toBe(true);

    const resultado = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await resultado).toBeNull();
    expect(service.account()).toBeNull();
    expect(service.isAdmin()).toBe(false);
  });

  // Qualquer outra falha (500, rede) também resulta em null: na dúvida, sem acesso/admin.
  it.each([
    ['erro 500', { status: 500, statusText: 'Server Error' }],
    ['erro de rede (status 0)', null],
  ])('D-20 refresh() devolve null em %s e deixa o signal vazio', async (_nome, falha) => {
    const resultado = firstValueFrom(service.refresh());
    const req = http.expectOne(URL_CONTA);
    if (falha) {
      req.flush(null, falha);
    } else {
      req.error(new ProgressEvent('error'));
    }

    expect(await resultado).toBeNull();
    expect(service.account()).toBeNull();
    expect(service.isAdmin()).toBe(false);
  });

  // `clear()` é chamado no logout/sessão encerrada: nenhum resquício da conta anterior.
  it('AC-008.5 clear() esquece o estado da conta (isAdmin volta a false)', async () => {
    const carga = firstValueFrom(service.refresh());
    http.expectOne(URL_CONTA).flush(conta({ isAdmin: true, role: Role.Familiar }));
    await carga;

    service.clear();

    expect(service.account()).toBeNull();
    expect(service.isAdmin()).toBe(false);
    expect(service.role() ?? null).toBeNull();
  });
});
