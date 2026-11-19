import { TestBed } from '@angular/core/testing';
import { FluxoCadastroService } from './fluxo-cadastro.service';

/**
 * Cobre o `FluxoCadastroService` (T-052, AC-001.1, AC-001.6, P5): a "memória curta" que leva o
 * e-mail digitado em "Criar grupo de cuidado" até a tela de confirmação do e-mail, e liga o
 * aviso de 24 horas (AC-001.1) só nesse caminho.
 *
 * O que se prova aqui:
 *  - estado inicial vazio: e-mail '' e sem aviso pendente;
 *  - `iniciar(email)` guarda o e-mail NORMALIZADO (trim + minúsculas, igual ao que o backend
 *    guarda) e liga `avisoPendente`;
 *  - `definirEmail(email)` (usado pelo login na T-053) guarda o e-mail normalizado SEM ligar o
 *    aviso: quem só quer confirmar o e-mail não deve ver "Confirme em até 24 horas" de novo;
 *  - `limpar()` volta ao estado inicial (depois de a confirmação dar certo);
 *  - P5: estado só em memória (nada de storage nem cookie) e nenhum HTTP: o serviço é criado sem
 *    nenhum provider de HTTP, e portanto não depende de `HttpClient`.
 */
describe('FluxoCadastroService', () => {
  let fluxo: FluxoCadastroService;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    // Nenhum provider de HTTP de propósito: se o serviço pedisse o HttpClient, o inject falharia.
    TestBed.configureTestingModule({});
    fluxo = TestBed.inject(FluxoCadastroService);
  });

  afterEach(() => vi.restoreAllMocks());

  // Estado de partida: nada guardado, nenhum aviso.
  it('AC-001.1 começa sem e-mail e sem aviso pendente', () => {
    expect(fluxo.email()).toBe('');
    expect(fluxo.avisoPendente()).toBe(false);
  });

  // iniciar: o e-mail fica guardado como o backend o guarda (trim + minúsculas) e o aviso liga.
  it('AC-001.1 iniciar guarda o e-mail normalizado (trim + minúsculas) e liga o aviso', () => {
    fluxo.iniciar('  Maria.Silva@Exemplo.COM  ');

    expect(fluxo.email()).toBe('maria.silva@exemplo.com');
    expect(fluxo.avisoPendente()).toBe(true);
  });

  // Um e-mail já normalizado não é alterado (idempotência da normalização).
  it('AC-001.1 iniciar com e-mail já normalizado o mantém igual', () => {
    fluxo.iniciar('maria@exemplo.com');

    expect(fluxo.email()).toBe('maria@exemplo.com');
  });

  // Um novo cadastro substitui o anterior (a pessoa voltou e usou outro e-mail).
  it('AC-001.1 iniciar de novo substitui o e-mail anterior e mantém o aviso ligado', () => {
    fluxo.iniciar('primeiro@exemplo.com');
    fluxo.iniciar('Segundo@Exemplo.com');

    expect(fluxo.email()).toBe('segundo@exemplo.com');
    expect(fluxo.avisoPendente()).toBe(true);
  });

  // definirEmail: usado pelo login (T-053) para abrir a confirmação já com o e-mail, sem o aviso.
  it('AC-001.6 definirEmail guarda o e-mail normalizado e NÃO liga o aviso de 24 h', () => {
    fluxo.definirEmail('  Joao@Exemplo.com ');

    expect(fluxo.email()).toBe('joao@exemplo.com');
    expect(fluxo.avisoPendente()).toBe(false);
  });

  // limpar: depois de confirmar o e-mail (AC-001.2) nada do cadastro deve sobrar na memória.
  it('AC-001.2 limpar volta ao estado inicial (e-mail vazio, sem aviso)', () => {
    fluxo.iniciar('maria@exemplo.com');

    fluxo.limpar();

    expect(fluxo.email()).toBe('');
    expect(fluxo.avisoPendente()).toBe(false);
  });

  // limpar também desfaz o que definirEmail guardou.
  it('AC-001.2 limpar apaga o e-mail guardado por definirEmail', () => {
    fluxo.definirEmail('maria@exemplo.com');

    fluxo.limpar();

    expect(fluxo.email()).toBe('');
  });

  // É um serviço único da aplicação: a tela de cadastro e a de confirmação enxergam o mesmo estado.
  it('AC-001.1 é um serviço único (providedIn root): a mesma instância em qualquer ponto', () => {
    expect(TestBed.inject(FluxoCadastroService)).toBe(fluxo);
  });

  // P5: o e-mail é dado pessoal; vive só em memória (nada de storage nem cookie, nada em URL).
  it('P5 nenhum dado vai para localStorage, sessionStorage ou cookie', () => {
    const gravar = vi.spyOn(Storage.prototype, 'setItem');

    fluxo.iniciar('maria@exemplo.com');
    fluxo.definirEmail('outra@exemplo.com');
    fluxo.limpar();

    expect(gravar).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });
});
