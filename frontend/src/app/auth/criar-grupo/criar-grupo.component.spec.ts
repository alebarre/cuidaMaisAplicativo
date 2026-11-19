import { Component, Provider, Type } from '@angular/core';
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
import { ConfirmarEmailComponent } from '../confirmar-email/confirmar-email.component';
import { FluxoCadastroService } from '../fluxo-cadastro.service';
import { CriarGrupoComponent } from './criar-grupo.component';

/** Destino "burro" das navegações: o que importa nos testes de unidade é ONDE a tela navega. */
@Component({ template: '<p>destino</p>' })
class DestinoComponent {}

/**
 * Cobre o `CriarGrupoComponent` (T-052; AC-001.1, AC-001.3, AC-001.4, AC-011.1/011.2 e as decisões
 * D-15, D-40, P4, P5): a tela pública "Criar grupo de cuidado", em que o futuro Responsável
 * informa nome, e-mail, telefone, endereço (formulário único da T-051) e senha.
 *
 * Provado aqui, pelo comportamento da tela (DOM + HTTP simulado), sem amarrar detalhes internos:
 *  - AC-001.1: botão com o rótulo do catálogo ("Criar grupo de cuidado"); envio válido faz
 *    exatamente UM `POST /api/v1/auth/signup` com o corpo do contrato (`name` aparado; `email`
 *    aparado e em minúsculas; `phone` só dígitos; `address` por `enderecoDoForm`; `password`
 *    exatamente como digitada); `202` guarda o e-mail em `FluxoCadastroService.iniciar` (liga o
 *    aviso de 24 h) e navega para `ROTAS.CONFIRMAR_EMAIL`, onde o aviso do catálogo aparece;
 *  - AC-001.3: tabela de senhas (7 caracteres, só letras, só números, 8, 128, 129);
 *  - validação no cliente com mensagens do catálogo abaixo de cada campo tocado (nome vazio ->
 *    `VAL_OBRIGATORIO`, 1 letra -> `VAL_NOME_CURTO`, e-mail -> `VAL_EMAIL_INVALIDO`, telefone ->
 *    `VAL_TELEFONE_INVALIDO`, senha -> `AC_001_3_SENHA_REGRA`), ligadas por `aria-describedby`, com
 *    `aria-invalid`, sem `role="alert"`; envio inválido NÃO faz requisição, marca todos os campos
 *    como tocados e leva o foco ao PRIMEIRO campo inválido (ordem do documento);
 *  - envio duplo: botão desabilitado com `aria-busy` enquanto a requisição está em voo; segundo
 *    clique (ou segundo `submit`) não envia nada;
 *  - AC-001.4 (P5): `202` leva ao mesmo destino com o mesmo texto para qualquer e-mail (a tela
 *    não diferencia e-mail novo de já cadastrado);
 *  - erros (`400`, `429`, `500`, rede): a tela fica, mostra `FALLBACK_ERRO_GENERICO` em
 *    `role="alert"`, mantém tudo o que foi digitado (inclusive a senha) e reabilita o botão; o
 *    `detail`/`title` do servidor nunca aparece (D-40);
 *  - D-40: nenhum texto é digitado na tela (com um `MensagemService` trocado, todo texto muda);
 *  - P4: rótulos `for`/`id` únicos, `type`/`autocomplete`/`inputmode`, mostrar/ocultar senha com
 *    `aria-pressed`, nenhum controle desabilitado de forma permanente;
 *  - P5: a senha NUNCA vai para URL, storage, `FluxoCadastroService`, estado do roteador nem
 *    `console`; o e-mail só vai para a memória do `FluxoCadastroService`.
 *
 * "Contrato" dos seletores entre este teste e o template: campos achados pelo TEXTO EXATO do
 * `<label>` ("Nome completo", "E-mail", "Telefone com DDD", "Senha"); botão de envio =
 * `button[type=submit]`; mostrar/ocultar senha = `button[aria-pressed]`; mensagens de validação
 * achadas via `aria-describedby` do campo; erro de envio = `[role=alert]`; os campos do endereço
 * usam os `data-testid` do `<app-endereco-form>` (`campo-cep`, `campo-numero`...).
 *
 * Decisões registradas (não vêm literalmente do texto aprovado): telefone vazio mostra
 * `VAL_TELEFONE_INVALIDO`; nome/e-mail/senha acima do limite ficam inválidos e bloqueiam o
 * envio, sem mensagem específica (não há texto aprovado para "longo demais").
 */
describe('CriarGrupoComponent (AC-001.1, AC-001.3, AC-001.4)', () => {
  const URL_SIGNUP = '/api/v1/auth/signup';
  const urlCep = (cep: string) => `https://viacep.com.br/ws/${cep}/json/`;

  /** Texto literal exato dos textos aprovados (digitados à parte do catálogo). */
  const TEXTO = {
    rotuloBotao: 'Criar grupo de cuidado',
    aviso24h: 'Confirme seu e-mail em até 24 horas. Depois desse prazo o cadastro é descartado e será preciso começar de novo.',
    obrigatorio: 'Preencha este campo.',
    nomeCurto: 'O nome precisa ter pelo menos 2 letras.',
    emailInvalido: 'Informe um e-mail válido.',
    telefoneInvalido: 'Informe o telefone com DDD, só números (10 ou 11 dígitos).',
    senhaRegra: 'A senha deve ter no mínimo 8 caracteres, com pelo menos uma letra e um número.',
    cepInvalido: 'Informe o CEP com 8 números.',
    numeroInvalido: 'Informe o número (ex.: 120 ou 12B) ou S/N.',
    ufObrigatoria: 'Escolha o estado.',
    fallback: 'Não foi possível concluir a ação. Tente novamente.',
  } as const;

  /** Dados de um cadastro válido. */
  const DADOS = {
    nome: 'Maria da Silva',
    email: 'maria@exemplo.com',
    telefone: '21987654321',
    cep: '22041001',
    numero: '370',
    senha: 'senha1234',
  };

  /** Resposta (real) do ViaCEP que preenche logradouro, bairro, cidade e UF. */
  const RESPOSTA_RJ = {
    cep: '22041-001',
    logradouro: 'Rua Barata Ribeiro',
    complemento: 'de 1 ao fim',
    bairro: 'Copacabana',
    localidade: 'Rio de Janeiro',
    uf: 'RJ',
  };

  /** `address` esperado no corpo para os dados acima (complemento vazio vira `null`). */
  const ENDERECO_ESPERADO = {
    zipCode: '22041001',
    street: 'Rua Barata Ribeiro',
    number: '370',
    complement: null,
    district: 'Copacabana',
    city: 'Rio de Janeiro',
    state: 'RJ',
  };

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

  /**
   * Monta a tela dentro de um roteador de teste. `destino` é o componente da rota
   * `confirmar-email` (burro, por padrão; o real nos testes de integração); `provedores` troca
   * serviços (ex.: o `MensagemService`).
   */
  async function montar(destino: Type<unknown> = DestinoComponent, provedores: Provider[] = []): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi('/api/v1'),
        provideRouter([
          { path: ROTAS.CRIAR_GRUPO.slice(1), component: CriarGrupoComponent },
          { path: ROTAS.CONFIRMAR_EMAIL.slice(1), component: destino },
          { path: ROTAS.CADASTRO_IDOSO.slice(1), component: DestinoComponent },
        ]),
        ...provedores,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fluxo = TestBed.inject(FluxoCadastroService);
    session = TestBed.inject(SessionService);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(ROTAS.CRIAR_GRUPO, CriarGrupoComponent);
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

  /** Espera a navegação (e a renderização do destino) depois de uma resposta do servidor. */
  async function aguardarNavegacao(): Promise<void> {
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

  const nome = () => campoPorRotulo('Nome completo');
  const email = () => campoPorRotulo('E-mail');
  const telefone = () => campoPorRotulo('Telefone com DDD');
  const senha = () => campoPorRotulo('Senha');

  /** Campo do endereço pelo `data-testid` do `<app-endereco-form>`. */
  function enderecoCampo<T extends HTMLElement = HTMLInputElement>(id: string): T {
    const el = host().querySelector<T>(`[data-testid="${id}"]`);
    expect(el, `o campo ${id} do endereço deveria existir`).not.toBeNull();
    return el as T;
  }

  const botaoEnviar = () => {
    const botao = host().querySelector<HTMLButtonElement>('button[type="submit"]');
    expect(botao, 'deveria existir um botão de envio (button type=submit)').not.toBeNull();
    return botao as HTMLButtonElement;
  };

  const botaoSenha = () => {
    const botao = host().querySelector<HTMLButtonElement>('button[aria-pressed]');
    expect(botao, 'deveria existir o botão de mostrar/ocultar senha (aria-pressed)').not.toBeNull();
    return botao as HTMLButtonElement;
  };

  const alertas = () => Array.from(host().querySelectorAll('[role="alert"]'));

  /** Simula a digitação (ou colagem) do valor inteiro no campo e atualiza a tela. */
  function digitar(el: HTMLInputElement, valor: string): void {
    el.value = valor;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    atualizar();
  }

  /** Simula sair do campo (marca como "tocado"). */
  function sair(el: HTMLElement): void {
    el.dispatchEvent(new Event('blur'));
    atualizar();
  }

  /** Escolhe (ou limpa) a UF no `<select>` como a pessoa faria. */
  function escolherUf(sigla: string): void {
    const select = enderecoCampo<HTMLSelectElement>('campo-uf');
    select.value = sigla;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    atualizar();
  }

  /** Digita o CEP, responde a consulta do ViaCEP e digita o número (e o complemento, se houver). */
  function preencherEndereco(cep = DADOS.cep, numero = DADOS.numero, complemento = ''): void {
    digitar(enderecoCampo('campo-cep'), cep);
    http.expectOne(urlCep(cep)).flush(RESPOSTA_RJ);
    atualizar();
    digitar(enderecoCampo('campo-numero'), numero);
    if (complemento !== '') {
      digitar(enderecoCampo('campo-complemento'), complemento);
    }
  }

  /** Preenche a tela inteira com dados válidos (qualquer campo pode ser sobrescrito). */
  function preencherValido(sobre: Partial<typeof DADOS> = {}): void {
    const d = { ...DADOS, ...sobre };
    digitar(nome(), d.nome);
    digitar(email(), d.email);
    digitar(telefone(), d.telefone);
    preencherEndereco(d.cep, d.numero);
    digitar(senha(), d.senha);
  }

  /** Clica no botão de envio e atualiza a tela (sem esperar: pode haver requisição em voo). */
  function enviar(): void {
    botaoEnviar().click();
    atualizar();
  }

  /** Envio que NÃO deve gerar requisição: espera a estabilidade (foco, marcação de tocados). */
  async function enviarSemRequisicao(): Promise<void> {
    enviar();
    await harness.fixture.whenStable();
    atualizar();
  }

  /** Captura a requisição de signup e confere método e URL exatos. */
  function requisicaoSignup(): TestRequest {
    const req = http.expectOne(URL_SIGNUP);
    expect(req.request.method).toBe('POST');
    return req;
  }

  /** Responde `202 Accepted` sem corpo (resposta neutra do contrato). */
  const aceitar = (req: TestRequest) => req.flush(null, { status: 202, statusText: 'Accepted' });

  /** Elementos apontados por `aria-describedby` do campo (só os que existem no documento). */
  function descritores(el: HTMLElement): HTMLElement[] {
    return (el.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .map((id) => document.getElementById(id))
      .filter((d): d is HTMLElement => d !== null);
  }

  /** Texto da mensagem de validação ligada ao campo por `aria-describedby` ('' quando não há). */
  const mensagemDe = (el: HTMLElement) => descritores(el).map((d) => textoDe(d)).join(' ').trim();

  const invalido = (el: HTMLElement) => el.getAttribute('aria-invalid') === 'true';

  /** Primeiro campo (input/select) marcado como inválido na ordem do documento. */
  const primeiroInvalidoNoDocumento = () =>
    Array.from(host().querySelectorAll<HTMLElement>('input, select')).find((el) => invalido(el));

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
    // Espiões (sem silenciar): provam que nenhum dado sensível é escrito no console (P5).
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
  // AC-001.1: rótulo e corpo do envio
  // ---------------------------------------------------------------------------------------

  describe('AC-001.1 envio do cadastro', () => {
    beforeEach(() => montar());

    // O rótulo da opção que inicia o cadastro é o texto exato da spec, vindo do catálogo.
    it('AC-001.1 o botão de envio tem o rótulo "Criar grupo de cuidado" (do catálogo)', () => {
      expect(textoDe(botaoEnviar())).toBe(MENSAGENS.AC_001_1_ROTULO_CRIAR_GRUPO);
      expect(textoDe(botaoEnviar())).toBe(TEXTO.rotuloBotao);
    });

    // O envio válido faz UM POST com o corpo do contrato, campo por campo.
    it('AC-001.1 o envio válido faz um único POST /auth/signup com o corpo exato do contrato', () => {
      preencherValido();

      enviar();

      const req = requisicaoSignup();
      expect(req.request.body).toEqual({
        name: 'Maria da Silva',
        email: 'maria@exemplo.com',
        phone: '21987654321',
        address: ENDERECO_ESPERADO,
        password: 'senha1234',
      });
      // Sem parâmetros na URL: nada de dado pessoal em query string (P5).
      expect(req.request.urlWithParams).toBe(URL_SIGNUP);
      aceitar(req);
    });

    // Cada campo é normalizado para o que o backend espera (aparar, minúsculas, só dígitos).
    it('AC-001.1 o corpo normaliza: nome aparado, e-mail aparado em minúsculas, telefone só com dígitos', () => {
      // Passo 1: digita os valores "sujos" que um teclado de celular produz.
      digitar(nome(), '  Maria da Silva  ');
      digitar(email(), '  Maria@Exemplo.COM  ');
      digitar(telefone(), '(21) 98765-4321');
      preencherEndereco();
      digitar(senha(), DADOS.senha);

      // Passo 2: envia e confere o corpo normalizado.
      enviar();
      const req = requisicaoSignup();
      expect(req.request.body.name).toBe('Maria da Silva');
      expect(req.request.body.email).toBe('maria@exemplo.com');
      expect(req.request.body.phone).toBe('21987654321');
      aceitar(req);
    });

    // O complemento preenchido vai no corpo; "s/n" vai como "S/N" (enderecoDoForm, AC-011.1).
    it('AC-011.1 o endereço sai por enderecoDoForm: complemento preenchido e número s/n em maiúsculas', () => {
      digitar(nome(), DADOS.nome);
      digitar(email(), DADOS.email);
      digitar(telefone(), DADOS.telefone);
      preencherEndereco(DADOS.cep, 's/n', '  apto 101  ');
      digitar(senha(), DADOS.senha);

      enviar();

      const req = requisicaoSignup();
      expect(req.request.body.address).toEqual({ ...ENDERECO_ESPERADO, number: 'S/N', complement: 'apto 101' });
      aceitar(req);
    });

    // A senha segue exatamente como digitada: espaços nas pontas e maiúsculas NÃO são alterados.
    it('AC-001.3 a senha vai ao backend exatamente como foi digitada (sem aparar, sem mudar caixa)', () => {
      preencherValido({ senha: '  Senha 1234  ' });

      enviar();

      const req = requisicaoSignup();
      expect(req.request.body.password).toBe('  Senha 1234  ');
      aceitar(req);
    });

    // Uma senha de 128 caracteres (limite do contrato) segue inteira.
    it('AC-001.3 uma senha de 128 caracteres é enviada inteira', () => {
      const senha128 = `${'a'.repeat(127)}1`;
      preencherValido({ senha: senha128 });

      enviar();

      const req = requisicaoSignup();
      expect(req.request.body.password).toBe(senha128);
      expect(req.request.body.password).toHaveLength(128);
      aceitar(req);
    });

    // O único contato com o backend é o POST de signup: nenhuma consulta de e-mail, status ou conta.
    it('AC-001.1 não consulta o backend além de POST /auth/signup (nem ao digitar, nem ao enviar)', () => {
      preencherValido();
      for (const el of [nome(), email(), telefone(), senha()]) {
        sair(el);
      }
      // Antes de enviar: nenhuma requisição ao backend (só a do ViaCEP, já respondida).
      expect(http.match((r) => !r.url.startsWith('https://viacep.com.br/'))).toEqual([]);

      enviar();

      // Ao enviar: apenas o signup.
      const req = requisicaoSignup();
      aceitar(req);
      expect(http.match(() => true)).toEqual([]);
    });

    // 202: o e-mail normalizado vai para o fluxo (liga o aviso de 24 h) e a tela segue para a confirmação.
    it('AC-001.1 202 guarda o e-mail em FluxoCadastroService (com o aviso pendente) e navega para a confirmação', async () => {
      preencherValido({ email: '  Maria@Exemplo.COM ' });
      enviar();

      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(fluxo.email()).toBe('maria@exemplo.com');
      expect(fluxo.avisoPendente()).toBe(true);
    });

    // Antes da resposta nada acontece: a tela não navega nem liga o aviso por antecipação.
    it('AC-001.1 antes da resposta do servidor não navega nem liga o aviso', async () => {
      preencherValido();

      enviar();
      await Promise.resolve();

      expect(router.url).toBe(ROTAS.CRIAR_GRUPO);
      expect(fluxo.avisoPendente()).toBe(false);
      expect(fluxo.email()).toBe('');
      aceitar(requisicaoSignup());
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-001.1 + AC-001.4: a tela de confirmação mostra o aviso de 24 h; 202 igual para todo e-mail
  // ---------------------------------------------------------------------------------------

  describe('integração com a tela de confirmação (AC-001.1, AC-001.4, P5)', () => {
    beforeEach(() => montar(ConfirmarEmailComponent));

    // O aviso de 24 h aparece na tela seguinte, com o texto do catálogo.
    it('AC-001.1 depois do 202 a tela de confirmação mostra o aviso de 24 horas do catálogo', async () => {
      preencherValido();
      enviar();

      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(textoDe(host())).toContain(MENSAGENS.AC_001_1_AVISO_CONFIRMACAO);
      expect(textoDe(host())).toContain(TEXTO.aviso24h);
      // E o e-mail digitado já vem preenchido na confirmação (a pessoa não digita de novo).
      expect(campoPorRotulo('E-mail').value).toBe('maria@exemplo.com');
    });

    // P5: a resposta é a mesma para e-mail novo e já cadastrado; a tela não consegue (nem tenta) diferenciar.
    it('AC-001.4 202 para qualquer e-mail leva ao mesmo destino com o mesmo texto (a tela não diferencia)', async () => {
      const resultados: { url: string; texto: string; alertas: number }[] = [];

      // Passo 1 e 2: e-mail "novo" e e-mail "já cadastrado" recebem exatamente a mesma resposta 202.
      for (const [i, emailDoTeste] of ['novo@exemplo.com', 'ja.cadastrado@exemplo.com'].entries()) {
        // A segunda volta recomeça do zero na tela de cadastro (a primeira já está nela).
        if (i > 0) {
          await harness.navigateByUrl(ROTAS.CRIAR_GRUPO, CriarGrupoComponent);
          atualizar();
        }
        preencherValido({ email: emailDoTeste });
        enviar();
        aceitar(requisicaoSignup());
        await aguardarNavegacao();

        resultados.push({
          url: router.url,
          // O e-mail em si fica num campo (valor), não no texto da tela: o texto tem de ser igual.
          texto: textoDe(host()),
          alertas: alertas().length,
        });
        fluxo.limpar();
      }

      // Passo 3: mesmo destino, mesmo texto, nenhum alerta.
      expect(resultados[0]).toEqual(resultados[1]);
      expect(resultados[0].url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(resultados[0].texto).toContain(MENSAGENS.AC_001_1_AVISO_CONFIRMACAO);
      expect(resultados[0].alertas).toBe(0);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-001.3 e validações no cliente
  // ---------------------------------------------------------------------------------------

  describe('validação no cliente (AC-001.3, AC-011.x, D-40, P4)', () => {
    beforeEach(() => montar());

    // Ao abrir, a tela não "grita": nenhum campo inválido e nenhuma mensagem.
    it('P4 ao abrir nenhum campo está aria-invalid e nenhuma mensagem de validação aparece', () => {
      for (const el of [nome(), email(), telefone(), senha()]) {
        expect(invalido(el)).toBe(false);
        expect(mensagemDe(el)).toBe('');
      }
      expect(alertas()).toHaveLength(0);
      const texto = textoDe(host());
      for (const t of [TEXTO.obrigatorio, TEXTO.nomeCurto, TEXTO.emailInvalido, TEXTO.telefoneInvalido, TEXTO.senhaRegra]) {
        expect(texto).not.toContain(t);
      }
    });

    // Cada linha: o que a pessoa digita no nome e a mensagem do catálogo esperada (null = válido).
    it.each([
      // Linha 1: vazio -> obrigatório.
      ['', 'VAL_OBRIGATORIO', TEXTO.obrigatorio],
      // Linha 2: só espaços conta como vazio -> obrigatório.
      ['     ', 'VAL_OBRIGATORIO', TEXTO.obrigatorio],
      // Linha 3: 1 letra -> nome curto.
      ['A', 'VAL_NOME_CURTO', TEXTO.nomeCurto],
      // Linha 4: 1 letra cercada de espaços (o tamanho vale sem os espaços) -> nome curto.
      ['  A  ', 'VAL_NOME_CURTO', TEXTO.nomeCurto],
      // Linha 5: 2 letras -> válido.
      ['Al', null, ''],
      // Linha 6: nome comum -> válido.
      ['Maria da Silva', null, ''],
      // Linha 7: 120 caracteres (limite do contrato) -> válido.
      ['x'.repeat(120), null, ''],
    ])('AC-001.1 nome %j, depois de tocado, mostra %s', (valor, chave, texto) => {
      digitar(nome(), valor);
      sair(nome());

      expect(mensagemDe(nome())).toBe(texto);
      if (chave !== null) {
        expect(mensagemDe(nome())).toBe(MENSAGENS[chave as 'VAL_OBRIGATORIO' | 'VAL_NOME_CURTO']);
      }
      expect(invalido(nome())).toBe(chave !== null);
    });

    // Cada linha: e-mail digitado e a mensagem (formato inválido, vazio ou longo demais).
    it.each([
      // Linha 1: vazio.
      ['', TEXTO.emailInvalido],
      // Linha 2: só espaços.
      ['   ', TEXTO.emailInvalido],
      // Linha 3: sem arroba.
      ['maria', TEXTO.emailInvalido],
      // Linha 4: sem domínio.
      ['maria@', TEXTO.emailInvalido],
      // Linha 5: sem usuário.
      ['@exemplo.com', TEXTO.emailInvalido],
      // Linha 6: espaço no meio.
      ['ma ria@exemplo.com', TEXTO.emailInvalido],
      // Linha 7: 255 caracteres (um acima do limite de 254 do contrato).
      [emailComTamanho(255), TEXTO.emailInvalido],
      // Linha 8: e-mail comum -> válido.
      ['maria@exemplo.com', ''],
      // Linha 9: maiúsculas e espaços nas pontas -> válido depois da normalização.
      ['  Maria@Exemplo.COM  ', ''],
      // Linha 10: 254 caracteres (limite) -> válido.
      [emailComTamanho(254), ''],
    ])('AC-001.1 e-mail %j, depois de tocado, mostra %j', (valor, texto) => {
      digitar(email(), valor);
      sair(email());

      expect(mensagemDe(email())).toBe(texto);
      expect(invalido(email())).toBe(texto !== '');
      if (texto !== '') {
        expect(mensagemDe(email())).toBe(MENSAGENS.VAL_EMAIL_INVALIDO);
      }
    });

    // Cada linha: o que a pessoa digita no telefone (descarta o que não é dígito; 10 ou 11 dígitos).
    it.each([
      // Linha 1: vazio (decisão: o texto aprovado orienta também o campo vazio).
      ['', TEXTO.telefoneInvalido],
      // Linha 2: letras não viram dígitos -> vazio.
      ['abc', TEXTO.telefoneInvalido],
      // Linha 3: 9 dígitos (sem DDD).
      ['987654321', TEXTO.telefoneInvalido],
      // Linha 4: 12 dígitos.
      ['219876543210', TEXTO.telefoneInvalido],
      // Linha 5: com código do país (+55): 13 dígitos -> inválido (só DDD + número).
      ['+55 21 98765-4321', TEXTO.telefoneInvalido],
      // Linha 6: 10 dígitos (fixo) -> válido.
      ['2133334444', ''],
      // Linha 7: 11 dígitos (celular) -> válido.
      ['21987654321', ''],
      // Linha 8: com máscara -> os 11 dígitos valem.
      ['(21) 98765-4321', ''],
      // Linha 9: fixo com máscara -> os 10 dígitos valem.
      ['(21) 3333-4444', ''],
    ])('AC-001.1 telefone %j, depois de tocado, mostra %j', (valor, texto) => {
      digitar(telefone(), valor);
      sair(telefone());

      expect(mensagemDe(telefone())).toBe(texto);
      expect(invalido(telefone())).toBe(texto !== '');
      if (texto !== '') {
        expect(mensagemDe(telefone())).toBe(MENSAGENS.VAL_TELEFONE_INVALIDO);
      }
    });

    // AC-001.3: cada linha é um caso da regra (8 a 128 caracteres, com pelo menos uma letra e um número).
    it.each([
      // Linha 1: vazia.
      ['', false],
      // Linha 2: 7 caracteres com letra e número -> curta demais.
      ['abcdef1', false],
      // Linha 3: 8 caracteres só com letras -> falta número.
      ['abcdefgh', false],
      // Linha 4: 8 caracteres só com números -> falta letra.
      ['12345678', false],
      // Linha 5: 8 caracteres só com símbolos e um número -> falta letra.
      ['!!!!!!!1', false],
      // Linha 6: 8 espaços -> nem letra nem número.
      ['        ', false],
      // Linha 7: exatamente 8 com letra e número -> válida.
      ['abcdefg1', true],
      // Linha 8: maiúsculas e minúsculas -> válida.
      ['SENHA1234', true],
      // Linha 9: com espaço no meio -> válida.
      ['minha senha 1', true],
      // Linha 10: 128 caracteres (limite) -> válida.
      [`${'a'.repeat(127)}1`, true],
      // Linha 11: 129 caracteres -> longa demais.
      [`${'a'.repeat(128)}1`, false],
    ])('AC-001.3 a senha %j, depois de tocada, é aceita? %s', (valor, valida) => {
      digitar(senha(), valor);
      sair(senha());

      // Inválida mostra o texto da regra (catálogo = literal) ligado ao campo; válida não mostra nada.
      expect(mensagemDe(senha())).toBe(valida ? '' : TEXTO.senhaRegra);
      expect(invalido(senha())).toBe(!valida);
      if (!valida) {
        expect(mensagemDe(senha())).toBe(MENSAGENS.AC_001_3_SENHA_REGRA);
      }
    });

    // A mensagem só aparece depois de a pessoa passar pelo campo (tocado) e some ao ficar válido.
    it('P4 a mensagem só aparece depois de tocado e some quando o valor fica válido', () => {
      // Passo 1: digitar sem sair do campo não mostra nada.
      digitar(email(), 'maria');
      expect(mensagemDe(email())).toBe('');

      // Passo 2: ao sair, a mensagem aparece, com aria-invalid e ligada por aria-describedby.
      sair(email());
      expect(mensagemDe(email())).toBe(TEXTO.emailInvalido);
      expect(invalido(email())).toBe(true);
      expect(descritores(email())).toHaveLength(1);

      // Passo 3: corrigir faz tudo sumir.
      digitar(email(), 'maria@exemplo.com');
      expect(mensagemDe(email())).toBe('');
      expect(invalido(email())).toBe(false);
    });

    // A mensagem fica abaixo do campo, no mesmo bloco, sem ser um alerta.
    it.each([
      ['nome', () => nome()],
      ['e-mail', () => email()],
      ['telefone', () => telefone()],
      ['senha', () => senha()],
    ])('P4 a mensagem do campo %s fica abaixo dele, no mesmo bloco, e não é role=alert', (_rotulo, obter) => {
      const campo = obter();
      sair(campo);

      const mensagem = descritores(campo)[0];
      expect(mensagem, 'a mensagem precisa estar ligada por aria-describedby').toBeDefined();
      expect(campo.parentElement?.contains(mensagem)).toBe(true);
      expect(campo.compareDocumentPosition(mensagem) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(mensagem.getAttribute('role')).not.toBe('alert');
      expect(mensagem.closest('[role="alert"]')).toBeNull();
      expect(document.querySelectorAll(`[id="${mensagem.id}"]`)).toHaveLength(1);
    });

    // O nome acima de 120 caracteres bloqueia o envio (limite do contrato), sem requisição.
    it('AC-001.1 nome com 121 caracteres fica inválido e bloqueia o envio', async () => {
      preencherValido({ nome: 'x'.repeat(121) });

      await enviarSemRequisicao();

      expect(invalido(nome())).toBe(true);
      http.expectNone(URL_SIGNUP);
    });

    // Senha acima do limite de 128 bloqueia o envio.
    it('AC-001.3 senha com 129 caracteres bloqueia o envio (nenhuma requisição)', async () => {
      preencherValido({ senha: `${'a'.repeat(128)}1` });

      await enviarSemRequisicao();

      expect(invalido(senha())).toBe(true);
      http.expectNone(URL_SIGNUP);
    });

    // Nenhum controle fica desabilitado de forma permanente: nem o envio, com o formulário inválido.
    it('P4 o botão de envio nunca fica desabilitado por o formulário estar inválido', () => {
      expect(botaoEnviar().disabled).toBe(false);
      digitar(email(), 'maria');
      sair(email());
      expect(botaoEnviar().disabled).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Envio inválido: sem requisição, todos os campos tocados, foco no primeiro inválido
  // ---------------------------------------------------------------------------------------

  describe('envio inválido (AC-001.1, P4)', () => {
    beforeEach(() => montar());

    // Formulário vazio: nenhuma requisição, todas as mensagens de uma vez, foco no primeiro campo.
    it('AC-001.1 enviar o formulário vazio não faz requisição, mostra a mensagem de cada campo e foca o nome', async () => {
      await enviarSemRequisicao();

      // Passo 1: nenhuma requisição saiu.
      http.expectNone(URL_SIGNUP);
      expect(http.match(() => true)).toEqual([]);
      // Passo 2: cada campo mostra a sua mensagem do catálogo.
      expect(mensagemDe(nome())).toBe(TEXTO.obrigatorio);
      expect(mensagemDe(email())).toBe(TEXTO.emailInvalido);
      expect(mensagemDe(telefone())).toBe(TEXTO.telefoneInvalido);
      expect(mensagemDe(senha())).toBe(TEXTO.senhaRegra);
      expect(mensagemDe(enderecoCampo('campo-cep'))).toBe(TEXTO.cepInvalido);
      expect(mensagemDe(enderecoCampo('campo-logradouro'))).toBe(TEXTO.obrigatorio);
      expect(mensagemDe(enderecoCampo('campo-numero'))).toBe(TEXTO.numeroInvalido);
      expect(mensagemDe(enderecoCampo('campo-bairro'))).toBe(TEXTO.obrigatorio);
      expect(mensagemDe(enderecoCampo('campo-cidade'))).toBe(TEXTO.obrigatorio);
      expect(mensagemDe(enderecoCampo<HTMLSelectElement>('campo-uf'))).toBe(TEXTO.ufObrigatoria);
      // Passo 3: todos marcados e o foco no primeiro campo do documento.
      expect(invalido(nome())).toBe(true);
      expect(invalido(senha())).toBe(true);
      expect(document.activeElement).toBe(nome());
      // Passo 4: mensagens de validação não são alertas.
      expect(alertas()).toHaveLength(0);
    });

    // O foco vai para o campo inválido: cada linha quebra UM campo de um formulário válido.
    it.each([
      // Linha 1: nome.
      ['nome', () => nome(), (): void => digitar(nome(), '')],
      // Linha 2: e-mail.
      ['e-mail', () => email(), (): void => digitar(email(), 'maria')],
      // Linha 3: telefone.
      ['telefone', () => telefone(), (): void => digitar(telefone(), '123')],
      // Linha 4: CEP incompleto (os demais campos do endereço seguem preenchidos).
      ['CEP', () => enderecoCampo('campo-cep'), (): void => digitar(enderecoCampo('campo-cep'), '2204')],
      // Linha 5: logradouro.
      ['logradouro', () => enderecoCampo('campo-logradouro'), (): void => digitar(enderecoCampo('campo-logradouro'), '')],
      // Linha 6: número.
      ['número', () => enderecoCampo('campo-numero'), (): void => digitar(enderecoCampo('campo-numero'), '12AB')],
      // Linha 7: bairro.
      ['bairro', () => enderecoCampo('campo-bairro'), (): void => digitar(enderecoCampo('campo-bairro'), '')],
      // Linha 8: cidade.
      ['cidade', () => enderecoCampo('campo-cidade'), (): void => digitar(enderecoCampo('campo-cidade'), '')],
      // Linha 9: UF.
      ['UF', () => enderecoCampo<HTMLSelectElement>('campo-uf'), (): void => escolherUf('')],
      // Linha 10: senha.
      ['senha', () => senha(), (): void => digitar(senha(), 'curta1')],
    ])('P4 só o campo %s inválido: nenhuma requisição e o foco vai para ele', async (_rotulo, obter, quebrar) => {
      preencherValido();
      quebrar();

      await enviarSemRequisicao();

      http.expectNone(URL_SIGNUP);
      expect(invalido(obter())).toBe(true);
      expect(document.activeElement).toBe(obter());
    });

    // Vários inválidos: o foco vai para o PRIMEIRO na ordem do documento, não para o último.
    it('P4 com vários campos inválidos o foco vai para o primeiro na ordem do documento', async () => {
      // Passo 1: senha e cidade inválidas; a cidade vem antes da senha no documento.
      preencherValido();
      digitar(senha(), 'curta1');
      digitar(enderecoCampo('campo-cidade'), '');
      await enviarSemRequisicao();
      expect(document.activeElement).toBe(enderecoCampo('campo-cidade'));

      // Passo 2: corrige a cidade e reenvia; sobra só a senha e o foco vai para ela.
      digitar(enderecoCampo('campo-cidade'), 'Rio de Janeiro');
      await enviarSemRequisicao();
      expect(document.activeElement).toBe(senha());

      // Passo 3: em todos os casos o foco está no primeiro inválido do documento.
      expect(document.activeElement).toBe(primeiroInvalidoNoDocumento());
      http.expectNone(URL_SIGNUP);
    });

    // A ordem do documento é a natural da tela: nome, e-mail, telefone, endereço, senha.
    it('P4 a ordem dos campos no documento é nome, e-mail, telefone, endereço e senha', () => {
      const ordem = [
        nome(),
        email(),
        telefone(),
        enderecoCampo('campo-cep'),
        enderecoCampo('campo-logradouro'),
        enderecoCampo('campo-numero'),
        enderecoCampo('campo-complemento'),
        enderecoCampo('campo-bairro'),
        enderecoCampo('campo-cidade'),
        enderecoCampo('campo-uf'),
        senha(),
      ];
      for (let i = 1; i < ordem.length; i++) {
        expect(
          ordem[i - 1].compareDocumentPosition(ordem[i]) & Node.DOCUMENT_POSITION_FOLLOWING,
          `o campo ${i} deveria vir depois do campo ${i - 1}`,
        ).toBeTruthy();
      }
    });

    // Os campos de endereço aparecem como inválidos também (o pai marca todos como tocados).
    it('AC-011.1 enviar vazio marca como inválidos os campos do endereço (o pai marca tudo como tocado)', async () => {
      await enviarSemRequisicao();

      for (const id of ['campo-cep', 'campo-logradouro', 'campo-numero', 'campo-bairro', 'campo-cidade', 'campo-uf']) {
        expect(invalido(enderecoCampo(id)), id).toBe(true);
      }
      // O complemento é opcional: nunca inválido.
      expect(invalido(enderecoCampo('campo-complemento'))).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Envio duplo
  // ---------------------------------------------------------------------------------------

  describe('envio duplo (AC-001.1)', () => {
    beforeEach(() => montar());

    // Em voo: botão desabilitado e ocupado; segundo clique e segundo submit não enviam nada.
    it('AC-001.1 com a requisição em voo o botão fica desabilitado (aria-busy) e um segundo envio não faz outra requisição', () => {
      preencherValido();

      // Passo 1: primeiro envio.
      enviar();
      const req = requisicaoSignup();
      expect(botaoEnviar().disabled).toBe(true);
      expect(botaoEnviar().getAttribute('aria-busy')).toBe('true');

      // Passo 2: segundo clique no botão (desabilitado) e um segundo submit direto no formulário.
      botaoEnviar().click();
      host().querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      atualizar();

      // Passo 3: continua uma única requisição.
      http.expectNone(URL_SIGNUP);
      aceitar(req);
    });

    // Depois da resposta com erro, o botão volta e um novo envio é possível (nada fica preso).
    it('AC-001.1 depois de um erro o botão é reabilitado e um novo envio faz nova requisição', () => {
      preencherValido();
      enviar();
      requisicaoSignup().flush({ type: 'x', title: 'x', status: 500 }, { status: 500, statusText: 'Server Error' });
      atualizar();
      expect(botaoEnviar().disabled).toBe(false);
      expect(botaoEnviar().getAttribute('aria-busy')).not.toBe('true');

      enviar();

      aceitar(requisicaoSignup());
    });
  });

  // ---------------------------------------------------------------------------------------
  // Erros do servidor: a tela fica, mostra o fallback e mantém os dados
  // ---------------------------------------------------------------------------------------

  describe('erros do envio (D-40, AC-001.1)', () => {
    beforeEach(() => montar());

    /** Cada tipo de falha com a ação que a provoca (todas devem ter o MESMO tratamento). */
    const FALHAS: [string, (req: TestRequest) => void][] = [
      // Linha 1: 400 ValidationProblem (dados rejeitados pelo servidor), com detail/title para o teste de vazamento.
      [
        '400 ValidationProblem',
        (req) =>
          req.flush(
            {
              type: '/problems/validation',
              title: TITULO_DO_SERVIDOR,
              status: 400,
              detail: DETALHE_DO_SERVIDOR,
              code: 'VALIDATION_ERROR',
              errors: [{ field: 'address.zipCode', message: 'CEP deve ter 8 dígitos' }],
            },
            { status: 400, statusText: 'Bad Request' },
          ),
      ],
      // Linha 2: 429 muitas requisições (mesmo tratamento: fallback).
      [
        '429 TooManyRequests',
        (req) =>
          req.flush(
            { type: '/problems/too-many', title: TITULO_DO_SERVIDOR, status: 429, detail: DETALHE_DO_SERVIDOR },
            { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '60' } },
          ),
      ],
      // Linha 3: 500 erro do servidor.
      [
        '500 erro do servidor',
        (req) =>
          req.flush(
            { type: '/problems/internal', title: TITULO_DO_SERVIDOR, status: 500, detail: DETALHE_DO_SERVIDOR },
            { status: 500, statusText: 'Server Error' },
          ),
      ],
      // Linha 4: 503 indisponível, com corpo que não é JSON.
      ['503 com corpo de texto', (req) => req.flush('Service Unavailable', { status: 503, statusText: 'Service Unavailable' })],
      // Linha 5: falha de rede (status 0).
      ['falha de rede', (req) => req.error(new ProgressEvent('error'))],
    ];

    // Cada falha (400, 429, 500, 503, rede) tem o MESMO tratamento: a tela fica, um único alerta com o fallback do catálogo e nada do servidor aparece (D-40, P5).
    it.each(FALHAS)('D-40 %s: fica na tela e mostra o fallback do catálogo em um único role=alert', async (_nome, falhar) => {
      preencherValido();
      enviar();

      falhar(requisicaoSignup());
      await aguardarNavegacao();

      // Passo 1: continua na tela de cadastro, sem navegar nem ligar o aviso.
      expect(router.url).toBe(ROTAS.CRIAR_GRUPO);
      expect(fluxo.avisoPendente()).toBe(false);
      expect(fluxo.email()).toBe('');
      // Passo 2: exatamente um alerta, com o texto do catálogo (e o literal).
      expect(alertas()).toHaveLength(1);
      expect(textoDe(alertas()[0])).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
      expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      // Passo 3: nada do servidor aparece na tela.
      expect(textoDe(host())).not.toContain(DETALHE_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain(TITULO_DO_SERVIDOR);
      expect(textoDe(host())).not.toContain('CEP deve ter 8 dígitos');
    });

    // Em cada falha a pessoa não perde nada do que digitou (inclusive a senha, que só vive no campo) e o botão volta a funcionar.
    it.each(FALHAS)('AC-001.1 %s: mantém tudo o que foi digitado (inclusive a senha) e reabilita o botão', async (_nome, falhar) => {
      preencherValido({ senha: 'Senha 1234' });
      digitar(enderecoCampo('campo-complemento'), 'apto 101');
      enviar();

      falhar(requisicaoSignup());
      await aguardarNavegacao();

      // Passo 1: todos os campos conservam o valor digitado.
      expect(nome().value).toBe(DADOS.nome);
      expect(email().value).toBe(DADOS.email);
      expect(telefone().value).toBe(DADOS.telefone);
      expect(senha().value).toBe('Senha 1234');
      expect(enderecoCampo('campo-cep').value).toBe('22041-001');
      expect(enderecoCampo('campo-logradouro').value).toBe('Rua Barata Ribeiro');
      expect(enderecoCampo('campo-numero').value).toBe('370');
      expect(enderecoCampo('campo-complemento').value).toBe('apto 101');
      expect(enderecoCampo('campo-bairro').value).toBe('Copacabana');
      expect(enderecoCampo('campo-cidade').value).toBe('Rio de Janeiro');
      expect(enderecoCampo<HTMLSelectElement>('campo-uf').value).toBe('RJ');
      // Passo 2: o botão volta a funcionar e não fica ocupado.
      expect(botaoEnviar().disabled).toBe(false);
      expect(botaoEnviar().getAttribute('aria-busy')).not.toBe('true');
    });

    // Depois de um erro, corrigir o problema e enviar de novo conclui o cadastro.
    it('AC-001.1 depois de um erro o reenvio com 202 conclui e navega para a confirmação', async () => {
      preencherValido();
      enviar();
      requisicaoSignup().flush({ type: 'x', title: 'x', status: 500 }, { status: 500, statusText: 'Server Error' });
      await aguardarNavegacao();
      expect(alertas()).toHaveLength(1);

      enviar();
      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(fluxo.email()).toBe(DADOS.email);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Acessibilidade e campos (P4)
  // ---------------------------------------------------------------------------------------

  describe('acessibilidade e campos (P4)', () => {
    beforeEach(() => montar());

    // Rótulos em texto simples, ligados por for/id, com ids únicos no documento.
    it.each([
      ['Nome completo', () => nome()],
      ['E-mail', () => email()],
      ['Telefone com DDD', () => telefone()],
      ['Senha', () => senha()],
    ])('P4 o campo "%s" tem <label for> ligado ao próprio campo, com id único', (rotulo, obter) => {
      const campo = obter();

      expect(campo.id).not.toBe('');
      expect(document.querySelectorAll(`[id="${campo.id}"]`)).toHaveLength(1);
      expect(host().querySelector<HTMLLabelElement>(`label[for="${campo.id}"]`)?.control).toBe(campo);
      expect(textoDe(host().querySelector(`label[for="${campo.id}"]`))).toBe(rotulo);
    });

    // Nenhum id se repete na tela inteira (inclusive o formulário de endereço embutido).
    it('P4 todos os ids da tela são únicos', () => {
      const ids = Array.from(host().querySelectorAll('[id]')).map((e) => e.id);

      expect(ids.length).toBeGreaterThanOrEqual(11);
      expect(new Set(ids).size).toBe(ids.length);
    });

    // Teclado e preenchimento certos no celular: tipo, inputmode e autocomplete de cada campo.
    it('P4 telefone: type=tel, inputmode=numeric, autocomplete=tel', () => {
      expect(telefone().type).toBe('tel');
      expect(telefone().getAttribute('inputmode')).toBe('numeric');
      expect(telefone().getAttribute('autocomplete')).toBe('tel');
    });

    // Senha oculta por padrão e marcada como nova senha (gerenciadores de senha oferecem gerar uma).
    it('P4 senha: type=password (oculta por padrão) e autocomplete=new-password', () => {
      expect(senha().type).toBe('password');
      expect(senha().getAttribute('autocomplete')).toBe('new-password');
    });

    // Teclado de e-mail no celular e preenchimento automático correto de e-mail e nome.
    it('P4 e-mail: type=email e autocomplete=email; nome: autocomplete=name', () => {
      expect(email().type).toBe('email');
      expect(email().getAttribute('autocomplete')).toBe('email');
      expect(nome().getAttribute('autocomplete')).toBe('name');
    });

    // Mostrar/ocultar a senha: botão com aria-pressed, que alterna o tipo do campo sem enviar nem apagar nada.
    it('P4 mostrar/ocultar senha: botão type=button com aria-pressed que alterna password/text sem perder o valor', () => {
      digitar(senha(), 'Senha1234');
      const botao = botaoSenha();

      // Passo 1: estado inicial, oculta; o botão não envia o formulário.
      expect(botao.type).toBe('button');
      expect(botao.getAttribute('aria-pressed')).toBe('false');
      expect(senha().type).toBe('password');
      // Passo 2: acessível por leitor de tela (nome acessível não vazio).
      expect(textoDe(botao) !== '' || (botao.getAttribute('aria-label') ?? '') !== '').toBe(true);

      // Passo 3: mostrar.
      botao.click();
      atualizar();
      expect(senha().type).toBe('text');
      expect(botaoSenha().getAttribute('aria-pressed')).toBe('true');
      expect(senha().value).toBe('Senha1234');

      // Passo 4: ocultar de novo.
      botaoSenha().click();
      atualizar();
      expect(senha().type).toBe('password');
      expect(botaoSenha().getAttribute('aria-pressed')).toBe('false');
      expect(senha().value).toBe('Senha1234');

      // Passo 5: nada foi enviado, nem validado por causa do clique.
      http.expectNone(URL_SIGNUP);
      expect(mensagemDe(senha())).toBe('');
    });

    // O botão de mostrar/ocultar fica na ordem natural logo depois do campo da senha, antes do envio.
    it('P4 a ordem de foco é natural: senha, botão de mostrar/ocultar, botão de envio', () => {
      expect(senha().compareDocumentPosition(botaoSenha()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(botaoSenha().compareDocumentPosition(botaoEnviar()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // Nenhum campo ou botão fica desabilitado/readonly de forma permanente.
    it('P4 ao abrir nenhum controle está desabilitado nem readonly', () => {
      for (const el of host().querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('input, select, button')) {
        expect(el.disabled, el.outerHTML.slice(0, 60)).toBe(false);
        expect(el.hasAttribute('readonly'), el.outerHTML.slice(0, 60)).toBe(false);
      }
    });

    // Sem role=alert até que algo dê errado (o aviso do CEP só existe em falha da consulta).
    it('P4 ao abrir não há nenhum role=alert', () => {
      expect(alertas()).toHaveLength(0);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Privacidade (P5)
  // ---------------------------------------------------------------------------------------

  describe('privacidade (P5)', () => {
    beforeEach(() => montar(ConfirmarEmailComponent));

    const SENHA_SECRETA = 'SenhaSecreta#9876';

    // A senha e o e-mail nunca vão para storage, cookie, URL, estado do roteador, console ou serviço de fluxo.
    it('P5 a senha nunca vai para storage, cookie, URL, estado do roteador, console nem FluxoCadastroService', async () => {
      const gravar = vi.spyOn(Storage.prototype, 'setItem');
      preencherValido({ senha: SENHA_SECRETA });
      enviar();

      const req = requisicaoSignup();
      // Passo 1: a senha vai SÓ no corpo da requisição (nunca na URL).
      expect(req.request.urlWithParams).not.toContain('Secreta');
      aceitar(req);
      await aguardarNavegacao();

      // Passo 2: nenhum lugar persistente ou observável contém a senha.
      expect(gravar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(document.cookie).toBe('');
      expect(router.url).not.toContain('Secreta');
      expect(window.location.href).not.toContain('Secreta');
      expect(JSON.stringify(destinosNavegados())).not.toContain('Secreta');
      expect(textoDoConsole()).not.toContain('Secreta');
      expect(fluxo.email()).not.toContain('Secreta');
      expect(textoDe(host())).not.toContain('Secreta');
    });

    // O e-mail só vive na memória do FluxoCadastroService: nunca em URL, storage, cookie ou console.
    it('P5 o e-mail vai só para a memória do FluxoCadastroService: nunca para URL, storage, cookie nem console', async () => {
      const gravar = vi.spyOn(Storage.prototype, 'setItem');
      preencherValido({ email: 'pessoa.privada@exemplo.com' });
      enviar();

      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      expect(fluxo.email()).toBe('pessoa.privada@exemplo.com');
      expect(router.url).toBe(ROTAS.CONFIRMAR_EMAIL);
      expect(window.location.href).not.toContain('privada');
      expect(JSON.stringify(destinosNavegados())).not.toContain('privada');
      expect(gravar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(document.cookie).toBe('');
      expect(textoDoConsole()).not.toContain('privada');
    });

    // Mesmo no erro, a senha não vaza para console, storage nem URL.
    it('P5 no erro do servidor a senha também não vaza para console, storage nem URL', async () => {
      preencherValido({ senha: SENHA_SECRETA });
      enviar();

      requisicaoSignup().flush({ type: 'x', title: 'x', status: 500 }, { status: 500, statusText: 'Server Error' });
      await aguardarNavegacao();

      expect(textoDoConsole()).not.toContain('Secreta');
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(router.url).toBe(ROTAS.CRIAR_GRUPO);
      expect(window.location.href).not.toContain('Secreta');
    });

    // A senha digitada não é escrita no HTML como atributo (value fica só na propriedade do campo).
    it('P5 a senha não aparece como atributo nem como texto no HTML da tela', () => {
      preencherValido({ senha: SENHA_SECRETA });

      expect(host().innerHTML).not.toContain('Secreta');
    });

    // O cadastro não inicia sessão: quem cria o grupo só entra depois de confirmar o e-mail (AC-001.2).
    it('AC-001.2 o cadastro (202) não inicia sessão: o token só existe depois de confirmar o e-mail', async () => {
      preencherValido();
      enviar();

      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      expect(session.accessToken()).toBeNull();
      expect(session.isAuthenticated()).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------------------
  // Navegação que falha depois do 202 (rota inexistente, chunk lazy que não carrega offline)
  // ---------------------------------------------------------------------------------------

  describe('navegação que falha depois do 202 (D-40, P4)', () => {
    beforeEach(() => montar());

    /** Os dois jeitos de a navegação não acontecer: a promise REJEITA ou resolve `false`. */
    const FALHAS_DE_NAVEGACAO: [string, () => void][] = [
      // Linha 1: rejeita (ex.: NG04002 de rota inexistente, ou chunk lazy que falha offline).
      ['a promise rejeita (NG04002)', () => espiaNavegar.mockRejectedValue(new Error('NG04002'))],
      // Linha 2: resolve false (navegação cancelada ou recusada por um guard).
      ['a promise resolve false', () => espiaNavegar.mockResolvedValue(false)],
    ];

    it.each(FALHAS_DE_NAVEGACAO)(
      'AC-001.1 se %s, a tela volta a ser utilizável: botão reabilitado, dados mantidos e um único alerta com o fallback',
      async (_nome, simular) => {
        simular();
        preencherValido({ senha: 'Senha 1234' });
        enviar();

        aceitar(requisicaoSignup());
        await harness.fixture.whenStable();
        await new Promise<void>((r) => setTimeout(r));
        atualizar();

        // Passo 1: a navegação foi tentada e a tela continua nela mesma.
        expect(espiaNavegar).toHaveBeenCalledWith(ROTAS.CONFIRMAR_EMAIL);
        expect(router.url).toBe(ROTAS.CRIAR_GRUPO);
        // Passo 2: o botão não fica preso (nem desabilitado, nem ocupado).
        expect(botaoEnviar().disabled).toBe(false);
        expect(botaoEnviar().getAttribute('aria-busy')).not.toBe('true');
        // Passo 3: tudo o que foi digitado continua nos campos (inclusive a senha).
        expect(nome().value).toBe(DADOS.nome);
        expect(email().value).toBe(DADOS.email);
        expect(telefone().value).toBe(DADOS.telefone);
        expect(senha().value).toBe('Senha 1234');
        expect(enderecoCampo('campo-numero').value).toBe(DADOS.numero);
        // Passo 4: um único alerta, com o fallback do catálogo (e o literal).
        expect(alertas()).toHaveLength(1);
        expect(textoDe(alertas()[0])).toBe(MENSAGENS.FALLBACK_ERRO_GENERICO);
        expect(textoDe(alertas()[0])).toBe(TEXTO.fallback);
      },
    );

    // Utilizável de verdade: um novo clique faz uma nova requisição (o botão não estava só "parecendo" livre).
    it.each(FALHAS_DE_NAVEGACAO)('AC-001.1 se %s, um novo envio faz nova requisição de signup', async (_nome, simular) => {
      simular();
      preencherValido();
      enviar();
      aceitar(requisicaoSignup());
      await harness.fixture.whenStable();
      await new Promise<void>((r) => setTimeout(r));
      atualizar();

      enviar();

      aceitar(requisicaoSignup());
      await harness.fixture.whenStable();
      await new Promise<void>((r) => setTimeout(r));
    });
  });

  // ---------------------------------------------------------------------------------------
  // P5: o que a navegação leva (state, queryParams, fragment)
  // ---------------------------------------------------------------------------------------

  describe('navegação sem dados sensíveis (P5)', () => {
    beforeEach(() => montar(ConfirmarEmailComponent));

    // A única navegação do cadastro vai para a confirmação, só com o caminho: nada de state/query/fragment.
    it('P5 o 202 navega uma única vez para a confirmação, sem state, queryParams, fragment nem dado na URL', async () => {
      preencherValido({ email: 'pessoa.privada@exemplo.com', senha: 'SenhaSecreta#9876' });
      enviar();

      aceitar(requisicaoSignup());
      await aguardarNavegacao();

      const chamadas = destinosNavegados();
      // Passo 1: exatamente uma navegação, para o caminho puro (sem "?" nem "#").
      expect(chamadas).toHaveLength(1);
      expect(chamadas[0].url).toBe(ROTAS.CONFIRMAR_EMAIL);
      // Passo 2: nenhum extra de navegação carrega estado.
      const extras = chamadas[0].extras ?? {};
      expect(extras['state']).toBeUndefined();
      expect(extras['queryParams']).toBeUndefined();
      expect(extras['fragment']).toBeUndefined();
      // Passo 3: nada de sensível em lugar nenhum do que foi pedido ao roteador.
      for (const proibido of ['Secreta', 'privada', '987654']) {
        expect(JSON.stringify(chamadas), proibido).not.toContain(proibido);
      }
    });
  });

  // ---------------------------------------------------------------------------------------
  // h1 e limites de tamanho (maxlength)
  // ---------------------------------------------------------------------------------------

  describe('título e limites de tamanho', () => {
    beforeEach(() => montar());

    // O título da tela é o rótulo do catálogo ("Criar grupo de cuidado"), único h1.
    it('AC-001.1 a tela tem um único <h1> com o texto do catálogo "Criar grupo de cuidado"', () => {
      const titulos = Array.from(host().querySelectorAll('h1'));

      expect(titulos).toHaveLength(1);
      expect(textoDe(titulos[0])).toBe(MENSAGENS.AC_001_1_ROTULO_CRIAR_GRUPO);
      expect(textoDe(titulos[0])).toBe(TEXTO.rotuloBotao);
    });

    // Cada linha: o campo e o limite do contrato afirmado como ATRIBUTO no DOM (independe da validação).
    it.each([
      // Linha 1: nome (PersonName, máx 120).
      ['nome', () => nome(), 120],
      // Linha 2: e-mail (Email, máx 254).
      ['e-mail', () => email(), 254],
      // Linha 3: senha (Password, máx 128).
      ['senha', () => senha(), 128],
    ])('AC-001.1 o campo %s tem o atributo maxlength=%i no DOM', (_nome, obter, limite) => {
      expect(obter().getAttribute('maxlength')).toBe(String(limite));
      expect(obter().maxLength).toBe(limite);
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

    beforeEach(() => montar(DestinoComponent, [{ provide: MensagemService, useValue: mensagensDeTeste }]));

    // O título (h1) também vem do catálogo: com o serviço trocado, o h1 mostra a marca da chave.
    it('D-40 o <h1> da tela vem do MensagemService (AC_001_1_ROTULO_CRIAR_GRUPO), não é digitado', () => {
      const titulos = Array.from(host().querySelectorAll('h1'));

      expect(titulos).toHaveLength(1);
      expect(textoDe(titulos[0])).toBe('«AC_001_1_ROTULO_CRIAR_GRUPO»');
    });

    // Se a tela digitasse algum texto, ele não mudaria quando o serviço muda: aqui todo texto muda.
    it('D-40 rótulo do botão, mensagens de validação e fallback vêm do MensagemService (nada digitado na tela)', async () => {
      // Passo 1: o rótulo do botão vem da chave.
      expect(textoDe(botaoEnviar())).toBe('«AC_001_1_ROTULO_CRIAR_GRUPO»');

      // Passo 2: as mensagens de validação vêm das chaves certas.
      await enviarSemRequisicao();
      expect(mensagemDe(nome())).toBe('«VAL_OBRIGATORIO»');
      expect(mensagemDe(email())).toBe('«VAL_EMAIL_INVALIDO»');
      expect(mensagemDe(telefone())).toBe('«VAL_TELEFONE_INVALIDO»');
      expect(mensagemDe(senha())).toBe('«AC_001_3_SENHA_REGRA»');
      expect(mensagemDe(enderecoCampo('campo-cep'))).toBe('«VAL_CEP_INVALIDO»');
      expect(mensagemDe(enderecoCampo('campo-numero'))).toBe('«VAL_NUMERO_INVALIDO»');
      digitar(nome(), 'A');
      sair(nome());
      expect(mensagemDe(nome())).toBe('«VAL_NOME_CURTO»');

      // Passo 3: o fallback do erro vem da chave.
      preencherValido();
      enviar();
      requisicaoSignup().flush(null, { status: 500, statusText: 'Server Error' });
      await aguardarNavegacao();
      expect(textoDe(alertas()[0])).toBe('«FALLBACK_ERRO_GENERICO»');
    });
  });
});

/**
 * Monta um e-mail sintaticamente válido com exatamente `tamanho` caracteres (rótulos de domínio
 * de até 63), para provar o limite de 254 do contrato (`Email`).
 */
function emailComTamanho(tamanho: number): string {
  const local = 'a'.repeat(30);
  // Estrutura: local@bbb(60).ccc(60).ddd(60).eee(k).com  -> 218 + k caracteres.
  const k = tamanho - 218;
  return `${local}@${'b'.repeat(60)}.${'c'.repeat(60)}.${'d'.repeat(60)}.${'e'.repeat(k)}.com`;
}
