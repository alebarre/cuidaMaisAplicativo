import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { AccountState, AccountStateValue, Role } from '../api';
import { provideApi } from '../api/provide-api';
import { AccountService } from './account.service';
import { AdminOnlyDirective } from './admin-only.directive';

/**
 * Anfitrião: um botão por ação vedada a quem não é Responsável (AC-015.1), cada um dentro de
 * `*appAdminOnly`, mais um "Sair" FORA da diretiva (AC-008.5), que existe para todos.
 */
@Component({
  imports: [AdminOnlyDirective],
  template: `
    <button *appAdminOnly data-testid="btn-convidar">Convidar</button>
    <button *appAdminOnly data-testid="btn-aprovar">Aprovar</button>
    <button *appAdminOnly data-testid="btn-recusar">Recusar</button>
    <button *appAdminOnly data-testid="btn-alterar-papel">Alterar papel</button>
    <button *appAdminOnly data-testid="btn-remover">Remover</button>
    <button *appAdminOnly data-testid="btn-editar-idoso">Editar idoso</button>
    <button *appAdminOnly data-testid="btn-excluir-grupo">Excluir grupo</button>
    <button data-testid="btn-sair">Sair</button>
  `,
})
class AnfitriaoComponent {}

/**
 * Cobre a diretiva estrutural `AdminOnlyDirective` (`*appAdminOnly`, T-050, AC-015.1, D-20):
 * as ações vedadas (convidar, aprovar, recusar, alterar papel, remover, editar o idoso, excluir
 * o grupo) NÃO EXISTEM no DOM de quem não é Responsável (`isAdmin=false`), em vez de só ficarem
 * escondidas por CSS; para o Responsável existem; e a diretiva reage quando a conta muda
 * (rebaixada/removida) sem recarregar. O botão "Sair" fora da diretiva aparece para todos.
 *
 * O backend sempre revalida (D-20, AC-015.2): isto é só experiência de uso.
 */
describe('AdminOnlyDirective', () => {
  const URL_CONTA = '/api/v1/me/account';
  const ACOES_VEDADAS = [
    'btn-convidar',
    'btn-aprovar',
    'btn-recusar',
    'btn-alterar-papel',
    'btn-remover',
    'btn-editar-idoso',
    'btn-excluir-grupo',
  ];
  const TEXTOS_VEDADOS = ['Convidar', 'Aprovar', 'Recusar', 'Alterar papel', 'Remover', 'Editar idoso', 'Excluir grupo'];

  let fixture: ComponentFixture<AnfitriaoComponent>;
  let http: HttpTestingController;
  let account: AccountService;

  const conta = (parcial: Partial<AccountState> = {}): AccountState => ({
    state: AccountStateValue.Ativo,
    hasGroup: true,
    elderId: '11111111-1111-1111-1111-111111111111',
    role: Role.Familiar,
    isAdmin: false,
    ...parcial,
  });

  /** Carrega a conta no AccountService (GET /me/account simulado) e atualiza a tela. */
  async function definirConta(corpo: AccountState): Promise<void> {
    const resultado = firstValueFrom(account.refresh());
    http.expectOne(URL_CONTA).flush(corpo);
    await resultado;
    await atualizar();
  }

  /** Roda a detecção de mudanças e espera efeitos pendentes (a diretiva reage a signals). */
  async function atualizar(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const botao = (id: string) => (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [AnfitriaoComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')],
    });
    http = TestBed.inject(HttpTestingController);
    account = TestBed.inject(AccountService);
    fixture = TestBed.createComponent(AnfitriaoComponent);
    await atualizar();
  });

  afterEach(() => http.verify());

  // Pronto-quando da T-050: no DOM de um Cuidador nenhuma ação vedada existe. Conferimos
  // `querySelector` (null), o texto no HTML e a ausência de esconder-por-CSS como substituto.
  it('AC-015.1 para um CUIDADOR (isAdmin=false) nenhuma ação vedada existe no DOM', async () => {
    await definirConta(conta({ role: Role.Cuidador, isAdmin: false }));

    // Passo 1: cada botão vedado não existe (null), um a um.
    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} não deveria existir`).toBeNull();
    }
    // Passo 2: o HTML não contém o texto de nenhum deles.
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    for (const texto of TEXTOS_VEDADOS) {
      expect(html).not.toContain(texto);
    }
    // Passo 3: não foram só escondidos (hidden/display:none), pois nem existem no DOM.
    expect(html).not.toMatch(/hidden|display:\s*none/i);
  });

  // Um Familiar que não é o Responsável (isAdmin=false) também não enxerga as ações vedadas.
  it('AC-015.1 para um FAMILIAR comum (isAdmin=false) nenhuma ação vedada existe no DOM', async () => {
    await definirConta(conta({ role: Role.Familiar, isAdmin: false }));

    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} não deveria existir`).toBeNull();
    }
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    for (const texto of TEXTOS_VEDADOS) {
      expect(html).not.toContain(texto);
    }
  });

  // O Responsável (FAMILIAR com isAdmin=true, D-29) vê todas as ações.
  it('AC-015.1 para o Responsável (FAMILIAR com isAdmin=true) todas as ações existem', async () => {
    await definirConta(conta({ role: Role.Familiar, isAdmin: true }));

    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} deveria existir`).not.toBeNull();
    }
  });

  // Antes de a conta ser carregada (ou se o carregamento falhar) nada de admin aparece: fechado.
  it('AC-015.1 sem conta carregada nenhuma ação vedada existe no DOM', () => {
    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} não deveria existir`).toBeNull();
    }
  });

  // D-13: o Responsável é rebaixado (a próxima consulta de /me/account traz isAdmin=false);
  // os botões já renderizados somem do DOM sem recarregar a página.
  it('D-13 ao perder o isAdmin (rebaixado) os botões somem do DOM', async () => {
    // Passo 1: Responsável vê todos.
    await definirConta(conta({ role: Role.Familiar, isAdmin: true }));
    expect(botao('btn-convidar')).not.toBeNull();

    // Passo 2: nova consulta traz isAdmin=false; nenhum botão vedado sobrevive.
    await definirConta(conta({ role: Role.Familiar, isAdmin: false }));
    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} deveria ter sumido`).toBeNull();
    }
  });

  // Conta removida/sessão encerrada: `AccountService.clear()` também remove as ações do DOM.
  it('D-13 ao encerrar a conta (clear) os botões somem do DOM', async () => {
    await definirConta(conta({ role: Role.Familiar, isAdmin: true }));
    expect(botao('btn-excluir-grupo')).not.toBeNull();

    account.clear();
    await atualizar();

    for (const id of ACOES_VEDADAS) {
      expect(botao(id), `o botão ${id} deveria ter sumido`).toBeNull();
    }
  });

  // A volta também funciona: quem vira Responsável passa a ver as ações, sem recarregar.
  it('AC-015.1 ao ganhar isAdmin os botões passam a existir no DOM', async () => {
    await definirConta(conta({ role: Role.Familiar, isAdmin: false }));
    expect(botao('btn-convidar')).toBeNull();

    await definirConta(conta({ role: Role.Familiar, isAdmin: true }));

    expect(botao('btn-convidar')).not.toBeNull();
  });

  // O "Sair" não está sob a diretiva: existe para todos, em qualquer papel (AC-008.5, AC-001.7).
  it.each([
    ['Cuidador', conta({ role: Role.Cuidador, isAdmin: false })],
    ['Familiar comum', conta({ role: Role.Familiar, isAdmin: false })],
    ['Responsável', conta({ role: Role.Familiar, isAdmin: true })],
  ])('AC-008.5 o botão "Sair" (fora da diretiva) existe para %s', async (_nome, corpo) => {
    await definirConta(corpo);

    expect(botao('btn-sair')).not.toBeNull();
  });

  // Sem conta carregada o "Sair" continua existindo.
  it('AC-008.5 o botão "Sair" existe mesmo sem conta carregada', () => {
    expect(botao('btn-sair')).not.toBeNull();
  });
});
