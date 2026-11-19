import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormGroup } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Address, UF } from '../../core/api';
import { MENSAGENS } from '../../core/messages/mensagens';
import { MensagemService } from '../../core/messages/mensagem.service';
import { criarEnderecoForm, enderecoDoForm } from './endereco-form';
import { EnderecoFormComponent } from './endereco-form.component';

/** Valor de partida do anfitrião (edição de um endereço salvo); `undefined` = formulário vazio. */
let ENDERECO_INICIAL: Address | undefined;

/**
 * Anfitrião: o "pai" real. Cria o grupo com `criarEnderecoForm()` e o coloca dentro do seu próprio
 * `FormGroup`, de modo que a validade do pai reflita a do endereço (D-27).
 */
@Component({
  imports: [EnderecoFormComponent],
  template: `<app-endereco-form [form]="endereco" />`,
})
class AnfitriaoComponent {
  readonly endereco = criarEnderecoForm(ENDERECO_INICIAL);
  readonly pai = new FormGroup({ endereco: this.endereco });
}

/**
 * Anfitrião com DOIS formulários de endereço na mesma tela (ex.: o endereço do idoso e o de um
 * contato de emergência, AC-002.2 e AC-002.5): ids, rótulos e estado não podem se misturar.
 */
@Component({
  imports: [EnderecoFormComponent],
  template: `
    <section data-testid="primeiro"><app-endereco-form [form]="a" /></section>
    <section data-testid="segundo"><app-endereco-form [form]="b" /></section>
  `,
})
class DoisEnderecosAnfitriaoComponent {
  readonly a = criarEnderecoForm();
  readonly b = criarEnderecoForm();
}

/**
 * Cobre o `<app-endereco-form>` (T-051, AC-011.1 a AC-011.4, D-27 rev. 2, D-40, P4, P5): o
 * formulário reativo único de endereço, com máscara de CEP e consulta ao ViaCEP DIRETO do
 * navegador (HTTP simulado, relógio simulado; nunca rede nem espera reais).
 *
 * Provado aqui, pelo DOM:
 *  - máscara `00000-000` só visual: o campo mostra a máscara e o controle guarda 8 dígitos;
 *  - AC-011.3: com 8 dígitos consulta o ViaCEP e preenche logradouro, bairro, cidade e UF, que
 *    seguem editáveis (nem `readonly` nem `disabled`); troca de CEP sobrescreve; consulta antiga
 *    é cancelada e a resposta atrasada NUNCA sobrescreve nada (corrida);
 *  - AC-011.4: `{"erro": true}`, HTTP de erro/rede e timeout de 15 s mostram NO ATO o texto do
 *    catálogo (pela chave `AC_011_4_CEP_FALHA`, via `MensagemService`) com `role="alert"`, sem
 *    bloquear nada: os campos ficam habilitados e o formulário vale ao ser preenchido à mão;
 *  - AC-011.1/011.2: número, 27 UFs, obrigatoriedade refletida em `aria-invalid`;
 *  - P4: rótulos ligados aos campos (`label for`), `inputmode`, `autocomplete`, ids únicos;
 *  - P5/D-27: nada de storage nem de chamada ao backend.
 *
 * T-052 (textos de validação APROVADOS pelo dono da spec): abaixo de cada campo TOCADO e inválido
 * o componente mostra a mensagem do catálogo (`VAL_CEP_INVALIDO`, `VAL_NUMERO_INVALIDO`,
 * `VAL_UF_OBRIGATORIA`, `VAL_OBRIGATORIO`), lida pelo `MensagemService` (D-40), ligada ao campo por
 * `aria-describedby` e SEM `role="alert"` (o único `role="alert"` do componente continua sendo o
 * aviso do CEP, AC-011.4). Complemento nunca mostra erro de obrigatório. A mensagem some quando o
 * campo fica válido, só aparece depois de tocado (ou quando o pai chama `markAllAsTouched`) e os
 * ids são únicos por instância.
 *
 * Decisões registradas nos testes: consultar de novo ao redigitar o mesmo CEP depois de apagar um
 * dígito; não consultar o CEP já presente no valor inicial (edição de endereço salvo); CEP vazio
 * e número vazio, depois de tocados, mostram `VAL_CEP_INVALIDO` e `VAL_NUMERO_INVALIDO` (os
 * textos aprovados orientam também o campo vazio); campo acima do limite de tamanho fica com
 * `aria-invalid` mas sem texto aprovado (não há mensagem de "texto longo demais" no catálogo).
 */
describe('EnderecoFormComponent', () => {
  const CEP_RJ = '22041001';
  const CEP_SP = '01001000';
  const urlCep = (cep: string) => `https://viacep.com.br/ws/${cep}/json/`;
  const TEXTO_LITERAL = 'Não conseguimos consultar o CEP. Preencha o endereço manualmente.';

  /** Respostas reais do ViaCEP (com os campos extras que o componente deve ignorar). */
  const RESPOSTA_RJ = {
    cep: '22041-001',
    logradouro: 'Rua Barata Ribeiro',
    complemento: 'de 1 ao fim',
    bairro: 'Copacabana',
    localidade: 'Rio de Janeiro',
    uf: 'RJ',
    ibge: '3304557',
  };
  const RESPOSTA_SP = {
    cep: '01001-000',
    logradouro: 'Praça da Sé',
    complemento: 'lado ímpar',
    bairro: 'Sé',
    localidade: 'São Paulo',
    uf: 'SP',
    ibge: '3550308',
  };
  /** CEP geral: o ViaCEP devolve logradouro e bairro vazios. */
  const RESPOSTA_GERAL = { cep: '24900-000', logradouro: '', complemento: '', bairro: '', localidade: 'Cidade Geral', uf: 'RJ' };

  /** `data-testid` de cada campo do formulário (contrato entre o teste e o template). */
  const ID = {
    cep: 'campo-cep',
    logradouro: 'campo-logradouro',
    numero: 'campo-numero',
    complemento: 'campo-complemento',
    bairro: 'campo-bairro',
    cidade: 'campo-cidade',
    uf: 'campo-uf',
  } as const;
  const TODOS_OS_CAMPOS = Object.values(ID);

  /** Rótulos visíveis (texto simples, não são textos exatos da spec). */
  const ROTULOS: Record<string, string> = {
    [ID.cep]: 'CEP',
    [ID.logradouro]: 'Logradouro',
    [ID.numero]: 'Número',
    [ID.complemento]: 'Complemento',
    [ID.bairro]: 'Bairro',
    [ID.cidade]: 'Cidade',
    [ID.uf]: 'UF',
  };

  /** Os três jeitos de a consulta falhar (AC-011.4), cada um com a ação que provoca a falha. */
  const FALHAS: [string, (req: TestRequest) => void][] = [
    // Linha 1: ViaCEP responde 200 com {"erro": true} (CEP inexistente).
    ['{"erro": true}', (req) => req.flush({ erro: true })],
    // Linha 2: versão antiga do ViaCEP, {"erro": "true"}.
    ['{"erro": "true"}', (req) => req.flush({ erro: 'true' })],
    // Linha 3: HTTP 500.
    ['HTTP 500', (req) => req.flush({}, { status: 500, statusText: 'Server Error' })],
    // Linha 4: HTTP 404.
    ['HTTP 404', (req) => req.flush({}, { status: 404, statusText: 'Not Found' })],
    // Linha 5: sem rede (status 0).
    ['falha de rede (status 0)', (req) => req.error(new ProgressEvent('error'))],
    // Linha 6: nenhuma resposta em 15 s (a requisição fica pendente e o relógio avança).
    ['timeout de 15 s', () => vi.advanceTimersByTime(15_000)],
  ];

  let fixture: ComponentFixture<AnfitriaoComponent>;
  let host: HTMLElement;
  let http: HttpTestingController;
  let endereco: ReturnType<typeof criarEnderecoForm>;
  let pai: FormGroup;

  beforeEach(() => {
    // Relógio simulado em TODOS os testes: o timeout de 15 s nunca é esperado de verdade.
    vi.useFakeTimers();
    ENDERECO_INICIAL = undefined;
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture?.destroy();
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // ---------------------------------------------------------------------------------------
  // Utilitários de DOM
  // ---------------------------------------------------------------------------------------

  /** Cria o anfitrião (com ou sem endereço salvo) e renderiza. */
  function montar(inicial?: Address): void {
    ENDERECO_INICIAL = inicial;
    fixture = TestBed.createComponent(AnfitriaoComponent);
    host = fixture.nativeElement as HTMLElement;
    endereco = fixture.componentInstance.endereco;
    pai = fixture.componentInstance.pai;
    fixture.detectChanges();
  }

  /** Renderiza de novo (sem `whenStable`: uma requisição em voo manteria a estabilidade pendente). */
  function atualizar(): void {
    fixture.detectChanges();
  }

  function campo<T extends HTMLElement = HTMLInputElement>(id: string, raiz: ParentNode = host): T {
    const elemento = raiz.querySelector<T>(`[data-testid="${id}"]`);
    expect(elemento, `o campo ${id} deveria existir no DOM`).not.toBeNull();
    return elemento as T;
  }

  /** Simula a digitação (ou a colagem) do texto inteiro no campo: valor novo + evento `input`. */
  function digitar(id: string, valor: string, raiz: ParentNode = host): void {
    const entrada = campo(id, raiz);
    entrada.value = valor;
    entrada.dispatchEvent(new InputEvent('input', { bubbles: true, data: valor }));
    atualizar();
  }

  /** Escolhe uma UF no `<select>` como o usuário faria. */
  function escolherUf(sigla: string, raiz: ParentNode = host): void {
    const select = campo<HTMLSelectElement>(ID.uf, raiz);
    select.value = sigla;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    atualizar();
  }

  /** Simula sair do campo (marca como "tocado"). */
  function sairDe(id: string): void {
    campo(id).dispatchEvent(new Event('blur'));
    atualizar();
  }

  const avisos = (raiz: ParentNode = host) => Array.from(raiz.querySelectorAll('[role="alert"]'));
  const textoDe = (el: Element | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const valorDe = (id: string) => campo<HTMLInputElement | HTMLSelectElement>(id).value;
  const raizDoForm = () => campo<HTMLElement>('endereco-form');

  /** Digita um CEP de 8 dígitos e devolve a requisição ao ViaCEP já capturada. */
  function consultarCep(cep: string): TestRequest {
    digitar(ID.cep, cep);
    return http.expectOne(urlCep(cep));
  }

  /** Preenche à mão tudo o que a consulta preencheria, mais o número. */
  function preencherAMao(): void {
    digitar(ID.logradouro, 'Avenida Atlântica');
    digitar(ID.numero, '370');
    digitar(ID.bairro, 'Copacabana');
    digitar(ID.cidade, 'Rio de Janeiro');
    escolherUf('RJ');
  }

  /** Dispara a falha escolhida; para a linha do timeout, o relógio é quem faz o trabalho. */
  function falhar(req: TestRequest, acao: (req: TestRequest) => void): void {
    acao(req);
    atualizar();
  }

  // ---------------------------------------------------------------------------------------
  // Máscara do CEP (D-27: a máscara é só visual; o valor guardado tem 8 dígitos)
  // ---------------------------------------------------------------------------------------

  describe('máscara do CEP', () => {
    beforeEach(() => montar());

    // Os 8 dígitos digitados aparecem como 00000-000 e o controle guarda só os dígitos.
    it('AC-011.2 digitar 22041001 mostra 22041-001 e o controle guarda 22041001', () => {
      digitar(ID.cep, '22041001');
      http.expectOne(urlCep(CEP_RJ));

      expect(valorDe(ID.cep)).toBe('22041-001');
      expect(endereco.controls.zipCode.value).toBe('22041001');
      http.match(() => true);
    });

    // O hífen só aparece quando há dígito depois dele; o controle acompanha dígito a dígito.
    it.each([
      // Linha 1: 1 dígito.
      ['2', '2', '2'],
      // Linha 2: 5 dígitos, ainda sem hífen.
      ['22041', '22041', '22041'],
      // Linha 3: 6 dígitos, o hífen aparece.
      ['220410', '22041-0', '220410'],
      // Linha 4: 7 dígitos.
      ['2204100', '22041-00', '2204100'],
    ])('AC-011.2 CEP incompleto %j aparece como %j e o controle guarda %j', (digitado, mostrado, guardado) => {
      digitar(ID.cep, digitado);

      expect(valorDe(ID.cep)).toBe(mostrado);
      expect(endereco.controls.zipCode.value).toBe(guardado);
    });

    // Letras e símbolos são descartados na hora; só dígitos entram.
    it.each([
      // Linha 1: só letras: nada entra.
      ['abc', '', ''],
      // Linha 2: letras e símbolos misturados com dígitos incompletos.
      ['a2b2$0%4', '2204', '2204'],
      // Linha 3: letras em volta de um CEP completo.
      ['abc22041xyz001', '22041-001', '22041001'],
      // Linha 4: espaços entre os dígitos.
      ['22 041 001', '22041-001', '22041001'],
    ])('AC-011.2 digitar %j descarta o que não é dígito (mostra %j, guarda %j)', (digitado, mostrado, guardado) => {
      digitar(ID.cep, digitado);

      expect(valorDe(ID.cep)).toBe(mostrado);
      expect(endereco.controls.zipCode.value).toBe(guardado);
      http.match(() => true);
    });

    // Colar um CEP já formatado (com hífen ou com pontos) resulta no mesmo valor limpo.
    it.each([
      // Linha 1: colado com hífen.
      ['22041-001'],
      // Linha 2: colado com ponto e hífen.
      ['22.041-001'],
      // Linha 3: colado só com dígitos.
      ['22041001'],
    ])('AC-011.2 colar %j mostra 22041-001 e guarda 22041001', (colado) => {
      const campoCep = campo(ID.cep);
      campoCep.value = colado;
      campoCep.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste', data: colado }));
      atualizar();

      expect(valorDe(ID.cep)).toBe('22041-001');
      expect(endereco.controls.zipCode.value).toBe('22041001');
      http.match(() => true);
    });

    // Passou de 8 dígitos: o excedente é cortado, e só o CEP de 8 dígitos é consultado.
    it('AC-011.2 mais de 8 dígitos são cortados em 8 e só o CEP cortado é consultado', () => {
      digitar(ID.cep, '220410019999');

      expect(valorDe(ID.cep)).toBe('22041-001');
      expect(endereco.controls.zipCode.value).toBe('22041001');
      http.expectOne(urlCep(CEP_RJ));
      http.expectNone(() => true);
    });

    // Apagar um dígito do CEP completo volta a um CEP de 7 dígitos, ainda com a máscara.
    it('AC-011.2 apagar o último dígito de 22041-001 deixa 22041-00 e o controle com 7 dígitos', () => {
      digitar(ID.cep, '22041001');
      http.expectOne(urlCep(CEP_RJ));

      digitar(ID.cep, '22041-00');

      expect(valorDe(ID.cep)).toBe('22041-00');
      expect(endereco.controls.zipCode.value).toBe('2204100');
    });

    // O campo é de texto (não `type="number"`): senão o zero à esquerda do CEP se perderia.
    it('AC-011.2 o campo de CEP é type=text, para preservar o zero à esquerda', () => {
      expect(campo(ID.cep).type).toBe('text');

      digitar(ID.cep, '01001000');
      expect(endereco.controls.zipCode.value).toBe('01001000');
      http.match(() => true);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-011.3: preenchimento automático e campos editáveis
  // ---------------------------------------------------------------------------------------

  describe('AC-011.3 preenchimento pelo ViaCEP', () => {
    beforeEach(() => montar());

    // O pedido de ouro: 8 dígitos disparam UMA consulta ao ViaCEP, com a URL exata.
    it('AC-011.3 ao chegar a 8 dígitos faz uma única consulta GET ao ViaCEP', () => {
      digitar(ID.cep, '22041-001');

      const req = http.expectOne(urlCep(CEP_RJ));
      expect(req.request.method).toBe('GET');
      http.expectNone(() => true);
    });

    // Sucesso: logradouro, bairro, cidade e UF vão para os controles E para a tela.
    it('AC-011.3 no sucesso preenche logradouro, bairro, cidade e UF (controles e DOM)', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      // Passo 1: nos controles.
      expect(endereco.controls.street.value).toBe('Rua Barata Ribeiro');
      expect(endereco.controls.district.value).toBe('Copacabana');
      expect(endereco.controls.city.value).toBe('Rio de Janeiro');
      expect(endereco.controls.state.value).toBe('RJ');
      // Passo 2: na tela.
      expect(valorDe(ID.logradouro)).toBe('Rua Barata Ribeiro');
      expect(valorDe(ID.bairro)).toBe('Copacabana');
      expect(valorDe(ID.cidade)).toBe('Rio de Janeiro');
      expect(valorDe(ID.uf)).toBe('RJ');
    });

    // O que o ViaCEP não preenche (número) e o que o design não mapeia (complemento) não é tocado.
    it('AC-011.3 não mexe em número nem em complemento já digitados', () => {
      digitar(ID.numero, '370');
      digitar(ID.complemento, 'apto 502');

      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      expect(endereco.controls.number.value).toBe('370');
      expect(endereco.controls.complement.value).toBe('apto 502');
      expect(valorDe(ID.numero)).toBe('370');
      expect(valorDe(ID.complemento)).toBe('apto 502');
    });

    // Com a consulta bem-sucedida e número digitado, o formulário todo fica válido e convertível.
    it('AC-011.3 depois do preenchimento e do número o grupo e o pai ficam válidos', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      digitar(ID.numero, 'S/N');

      expect(endereco.valid).toBe(true);
      expect(pai.valid).toBe(true);
      expect(enderecoDoForm(endereco)).toStrictEqual({
        zipCode: '22041001',
        street: 'Rua Barata Ribeiro',
        number: 'S/N',
        complement: null,
        district: 'Copacabana',
        city: 'Rio de Janeiro',
        state: 'RJ',
      });
    });

    // Os campos preenchidos continuam editáveis: nem `readonly` nem `disabled`, nem no DOM nem nos controles.
    it('AC-011.3 os campos preenchidos não ficam readonly nem disabled', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      for (const id of TODOS_OS_CAMPOS) {
        const elemento = campo<HTMLInputElement | HTMLSelectElement>(id);
        expect(elemento.disabled, `${id} não pode estar disabled`).toBe(false);
        expect(elemento.hasAttribute('disabled'), `${id} sem atributo disabled`).toBe(false);
        expect(elemento.hasAttribute('readonly'), `${id} sem atributo readonly`).toBe(false);
        expect(elemento.getAttribute('aria-readonly'), `${id} sem aria-readonly`).not.toBe('true');
      }
      for (const controle of Object.values(endereco.controls)) {
        expect(controle.enabled).toBe(true);
      }
    });

    // Editar por cima do que veio do ViaCEP vale: o valor digitado é o que vai para o Address.
    it('AC-011.3 o usuário digita por cima do preenchido e o valor digitado vale', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      digitar(ID.logradouro, 'Rua Editada pela Pessoa');
      digitar(ID.bairro, 'Bairro Editado');
      digitar(ID.cidade, 'Cidade Editada');
      escolherUf('SP');
      digitar(ID.numero, '12B');

      expect(endereco.controls.street.value).toBe('Rua Editada pela Pessoa');
      expect(valorDe(ID.logradouro)).toBe('Rua Editada pela Pessoa');
      expect(enderecoDoForm(endereco)).toMatchObject({
        street: 'Rua Editada pela Pessoa',
        district: 'Bairro Editado',
        city: 'Cidade Editada',
        state: 'SP',
        number: '12B',
      });
      // Editar não dispara nova consulta nem restaura o valor do ViaCEP.
      http.expectNone(() => true);
    });

    // CEP incompleto não consulta: cada linha tem menos de 8 dígitos.
    it.each([
      // Linha 1: 1 dígito.
      ['2'],
      // Linha 2: 5 dígitos.
      ['22041'],
      // Linha 3: 7 dígitos (um a menos).
      ['2204100'],
      // Linha 4: 7 dígitos com máscara.
      ['22041-00'],
    ])('AC-011.3 CEP incompleto %j não consulta o ViaCEP', (cep) => {
      digitar(ID.cep, cep);

      http.expectNone(() => true);
      expect(avisos()).toHaveLength(0);
    });

    // Letras e símbolos em um CEP "de 8 caracteres" não completam 8 dígitos: nada de consulta.
    it('AC-011.3 8 caracteres com letras (6 dígitos úteis) não consulta', () => {
      digitar(ID.cep, '22AB0410');

      expect(endereco.controls.zipCode.value).toBe('220410');
      http.expectNone(() => true);
    });

    // Trocar de CEP consulta o novo e sobrescreve tudo o que a consulta anterior preencheu.
    it('AC-011.3 trocar o CEP consulta o novo e sobrescreve o que a consulta anterior preencheu', () => {
      // Passo 1: primeiro CEP preenche o Rio.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();
      expect(endereco.controls.city.value).toBe('Rio de Janeiro');

      // Passo 2: segundo CEP preenche São Paulo, trocando os quatro campos.
      consultarCep(CEP_SP).flush(RESPOSTA_SP);
      atualizar();

      expect(endereco.controls.street.value).toBe('Praça da Sé');
      expect(endereco.controls.district.value).toBe('Sé');
      expect(endereco.controls.city.value).toBe('São Paulo');
      expect(endereco.controls.state.value).toBe('SP');
      expect(valorDe(ID.uf)).toBe('SP');
    });

    // O novo CEP vence até com o que a pessoa editou do anterior, e um CEP geral limpa logradouro e bairro.
    it('AC-011.3 CEP geral (logradouro e bairro vazios) sobrescreve com vazio o que veio da consulta anterior', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      consultarCep('24900000').flush(RESPOSTA_GERAL);
      atualizar();

      expect(endereco.controls.street.value).toBe('');
      expect(endereco.controls.district.value).toBe('');
      expect(endereco.controls.city.value).toBe('Cidade Geral');
      expect(valorDe(ID.logradouro)).toBe('');
    });

    // Não repete a consulta para o mesmo CEP seguido (mesmo valor reenviado ao campo).
    it('AC-011.3 reenviar o mesmo CEP seguido não consulta duas vezes', () => {
      // Passo 1: primeira digitação consulta.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      // Passo 2: o mesmo valor de novo (ex.: colar o mesmo CEP por cima) não gera requisição.
      digitar(ID.cep, '22041-001');
      digitar(ID.cep, '22041001');

      http.expectNone(() => true);
    });

    // Mesmo com a consulta ainda em voo, repetir o mesmo CEP não abre uma segunda requisição.
    it('AC-011.3 reenviar o mesmo CEP com a consulta em voo não abre segunda requisição', () => {
      const req = consultarCep(CEP_RJ);

      digitar(ID.cep, '22041-001');

      http.expectNone(() => true);
      expect(req.cancelled).toBe(false);
      req.flush(RESPOSTA_RJ);
    });

    // DECISÃO: apagar um dígito e redigitar o MESMO CEP consulta de novo (a pessoa saiu do CEP e voltou).
    it('AC-011.3 apagar um dígito e redigitar o mesmo CEP consulta de novo (decisão)', () => {
      // Passo 1: primeira consulta.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      // Passo 2: apaga o último dígito: sem consulta.
      digitar(ID.cep, '2204100');
      http.expectNone(() => true);

      // Passo 3: redigita o 8º dígito igual: consulta de novo e preenche outra vez.
      digitar(ID.logradouro, 'Editado à mão');
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();
      expect(endereco.controls.street.value).toBe('Rua Barata Ribeiro');
    });

    // Corrida: o CEP muda com a consulta antiga em voo. A antiga é CANCELADA e a nova vale.
    it('AC-011.3 trocar de CEP com consulta em voo cancela a anterior e só a nova preenche', () => {
      // Passo 1: consulta A (Rio) fica em voo, sem resposta.
      const reqA = consultarCep(CEP_RJ);
      expect(reqA.cancelled).toBe(false);

      // Passo 2: a pessoa troca para o CEP B (São Paulo) antes de A responder: A é cancelada.
      const reqB = consultarCep(CEP_SP);
      expect(reqA.cancelled).toBe(true);
      expect(reqB.cancelled).toBe(false);

      // Passo 3: B responde: só os dados de São Paulo aparecem, os do Rio nunca chegam.
      reqB.flush(RESPOSTA_SP);
      atualizar();
      expect(endereco.controls.street.value).toBe('Praça da Sé');
      expect(endereco.controls.city.value).toBe('São Paulo');
      expect(endereco.controls.state.value).toBe('SP');
      expect(valorDe(ID.logradouro)).toBe('Praça da Sé');
    });

    // O resultado de uma consulta cancelada não pode mais ser entregue: tentar responder a A falha.
    it('AC-011.3 a resposta atrasada da consulta cancelada não tem como chegar (requisição abortada)', () => {
      const reqA = consultarCep(CEP_RJ);
      const reqB = consultarCep(CEP_SP);
      reqB.flush(RESPOSTA_SP);
      atualizar();

      // O HttpTestingController recusa responder a uma requisição abortada: nada de A vaza.
      expect(() => reqA.flush(RESPOSTA_RJ)).toThrow();
      atualizar();

      expect(endereco.controls.city.value).toBe('São Paulo');
      expect(valorDe(ID.cidade)).toBe('São Paulo');
    });

    // Apagar dígito (CEP vira incompleto) também cancela a consulta em voo: nada é preenchido depois.
    it('AC-011.3 deixar o CEP incompleto com consulta em voo cancela a consulta e nada é preenchido', () => {
      const req = consultarCep(CEP_RJ);

      digitar(ID.cep, '2204100');

      expect(req.cancelled).toBe(true);
      expect(endereco.controls.street.value).toBe('');
      expect(endereco.controls.city.value).toBe('');
      expect(avisos()).toHaveLength(0);
    });

    // Destruir o componente (sair da tela) cancela a consulta em voo, sem vazar requisição.
    it('AC-011.3 destruir o componente cancela a consulta em voo', () => {
      const req = consultarCep(CEP_RJ);

      fixture.destroy();

      expect(req.cancelled).toBe(true);
    });

    // Enquanto consulta: indicador acessível no grupo, e NENHUM campo desabilitado.
    it('AC-011.3 durante a consulta o grupo tem aria-busy=true e os campos seguem habilitados', () => {
      // Passo 1: antes de consultar, não está ocupado.
      expect(raizDoForm().getAttribute('aria-busy')).not.toBe('true');

      // Passo 2: consulta em voo: ocupado, campos livres.
      const req = consultarCep(CEP_RJ);
      expect(raizDoForm().getAttribute('aria-busy')).toBe('true');
      for (const id of TODOS_OS_CAMPOS) {
        const elemento = campo<HTMLInputElement | HTMLSelectElement>(id);
        expect(elemento.disabled, `${id} não pode ser desabilitado durante a consulta`).toBe(false);
        expect(elemento.hasAttribute('readonly')).toBe(false);
      }

      // Passo 3: resposta: deixa de estar ocupado.
      req.flush(RESPOSTA_RJ);
      atualizar();
      expect(raizDoForm().getAttribute('aria-busy')).not.toBe('true');
    });

    // Regra do orquestrador (edição em voo): o que a pessoa EDITA depois que a consulta começou vale
    // mais que a resposta daquela consulta; os campos que ela não tocou são preenchidos normalmente.
    it('AC-011.3 logradouro editado durante a consulta mantém o digitado e os demais campos são preenchidos', () => {
      // Passo 1: consulta em voo; a pessoa edita só o logradouro.
      const req = consultarCep(CEP_RJ);
      digitar(ID.logradouro, 'Rua que a pessoa digitou');

      // Passo 2: a resposta chega: logradouro intacto, bairro, cidade e UF preenchidos.
      req.flush(RESPOSTA_RJ);
      atualizar();

      expect(endereco.controls.street.value).toBe('Rua que a pessoa digitou');
      expect(valorDe(ID.logradouro)).toBe('Rua que a pessoa digitou');
      expect(endereco.controls.district.value).toBe('Copacabana');
      expect(endereco.controls.city.value).toBe('Rio de Janeiro');
      expect(endereco.controls.state.value).toBe('RJ');
      expect(valorDe(ID.uf)).toBe('RJ');
      // O estado da consulta não muda por causa da edição: terminou e sem aviso.
      expect(avisos()).toHaveLength(0);
      expect(raizDoForm().getAttribute('aria-busy')).not.toBe('true');
    });

    // Mesma regra com mais de um campo editado em voo (cidade e UF): só logradouro e bairro vêm da resposta.
    it('AC-011.3 cidade e UF editadas durante a consulta são mantidas e só logradouro e bairro vêm da resposta', () => {
      // Passo 1: consulta em voo; a pessoa edita cidade e UF.
      const req = consultarCep(CEP_RJ);
      digitar(ID.cidade, 'Cidade digitada');
      escolherUf('MG');

      // Passo 2: a resposta chega e só preenche o que a pessoa não tocou.
      req.flush(RESPOSTA_RJ);
      atualizar();

      expect(endereco.controls.city.value).toBe('Cidade digitada');
      expect(endereco.controls.state.value).toBe('MG');
      expect(valorDe(ID.cidade)).toBe('Cidade digitada');
      expect(valorDe(ID.uf)).toBe('MG');
      expect(endereco.controls.street.value).toBe('Rua Barata Ribeiro');
      expect(endereco.controls.district.value).toBe('Copacabana');
    });

    // O campo segue editável depois da resposta: uma nova edição vale, sem nova consulta nem restauração.
    it('AC-011.3 editar durante a consulta, receber a resposta e editar de novo: vale o último valor digitado', () => {
      // Passo 1: edita em voo e a resposta chega.
      const req = consultarCep(CEP_RJ);
      digitar(ID.logradouro, 'Primeira edição');
      req.flush(RESPOSTA_RJ);
      atualizar();
      expect(endereco.controls.street.value).toBe('Primeira edição');

      // Passo 2: edita de novo, também um campo que veio da resposta.
      digitar(ID.logradouro, 'Segunda edição');
      digitar(ID.bairro, 'Bairro depois da resposta');

      expect(endereco.controls.street.value).toBe('Segunda edição');
      expect(valorDe(ID.logradouro)).toBe('Segunda edição');
      expect(endereco.controls.district.value).toBe('Bairro depois da resposta');
      expect(campo(ID.logradouro).hasAttribute('readonly')).toBe(false);
      http.expectNone(() => true);
    });

    // Edição ANTES de a consulta começar não protege nada: a resposta sobrescreve (regra da troca de CEP).
    it('AC-011.3 logradouro e cidade editados ANTES de a consulta começar são sobrescritos pela resposta', () => {
      // Passo 1: a pessoa digita logradouro e cidade primeiro, depois o CEP.
      digitar(ID.logradouro, 'Digitado antes');
      digitar(ID.cidade, 'Cidade digitada antes');

      // Passo 2: a consulta começa e a resposta chega: sobrescreve os dois.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      expect(endereco.controls.street.value).toBe('Rua Barata Ribeiro');
      expect(endereco.controls.city.value).toBe('Rio de Janeiro');
      expect(valorDe(ID.logradouro)).toBe('Rua Barata Ribeiro');
    });

    // Um CEP novo cancela a consulta anterior; a resposta nova preenche tudo, mesmo o que foi editado
    // em voo durante a consulta ANTERIOR (a edição valia só para aquela consulta).
    it('AC-011.3 edição feita durante a consulta A não protege contra a resposta da consulta B (novo CEP)', () => {
      // Passo 1: consulta A em voo e edição do logradouro.
      const reqA = consultarCep(CEP_RJ);
      digitar(ID.logradouro, 'Editado durante A');

      // Passo 2: novo CEP cancela A; a resposta de B preenche tudo.
      const reqB = consultarCep(CEP_SP);
      expect(reqA.cancelled).toBe(true);
      reqB.flush(RESPOSTA_SP);
      atualizar();

      expect(endereco.controls.street.value).toBe('Praça da Sé');
      expect(endereco.controls.district.value).toBe('Sé');
      expect(endereco.controls.city.value).toBe('São Paulo');
      expect(endereco.controls.state.value).toBe('SP');
    });

    // Em falha (erro, HTTP ou timeout) o campo editado durante a consulta segue intacto, e o aviso aparece.
    it.each(FALHAS)('AC-011.4 falha por %s mantém intacto o logradouro editado durante a consulta', (_nome, acao) => {
      const req = consultarCep(CEP_RJ);
      digitar(ID.logradouro, 'Digitado em voo');

      falhar(req, acao);

      expect(endereco.controls.street.value).toBe('Digitado em voo');
      expect(valorDe(ID.logradouro)).toBe('Digitado em voo');
      expect(avisos()).toHaveLength(1);
    });

    // Editar o CEP com valor inicial (endereço salvo): a tela mostra o salvo, sem consultar sozinha.
    it('AC-011.3 com endereço salvo mostra o CEP mascarado e NÃO consulta o ViaCEP ao abrir (decisão)', async () => {
      fixture.destroy();
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      http = TestBed.inject(HttpTestingController);

      montar({
        zipCode: '22041001',
        street: 'Rua Salva Antes',
        number: '370',
        complement: 'apto 502',
        district: 'Copacabana',
        city: 'Rio de Janeiro',
        state: UF.Rj,
      });

      expect(valorDe(ID.cep)).toBe('22041-001');
      expect(valorDe(ID.logradouro)).toBe('Rua Salva Antes');
      expect(valorDe(ID.numero)).toBe('370');
      expect(valorDe(ID.complemento)).toBe('apto 502');
      expect(valorDe(ID.bairro)).toBe('Copacabana');
      expect(valorDe(ID.cidade)).toBe('Rio de Janeiro');
      expect(valorDe(ID.uf)).toBe('RJ');
      // Sem requisição, nem adiada (microtarefa, timer ou efeito): não sobrescreve o que foi salvo.
      await Promise.resolve();
      vi.advanceTimersByTime(1_000);
      atualizar();
      http.expectNone(() => true);
      expect(valorDe(ID.logradouro)).toBe('Rua Salva Antes');
      expect(pai.valid).toBe(true);
    });
  });

  // ---------------------------------------------------------------------------------------
  // AC-011.4: falha da consulta, aviso e cadastro nunca bloqueado
  // ---------------------------------------------------------------------------------------

  describe('AC-011.4 falha da consulta de CEP', () => {
    beforeEach(() => montar());

    // Sem consulta, sem falha: nenhum aviso de CEP na tela (não aparece "de enfeite").
    it('AC-011.4 sem falha não há aviso: nem ao abrir, nem em consulta bem-sucedida', () => {
      expect(avisos()).toHaveLength(0);

      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      expect(avisos()).toHaveLength(0);
      expect(host.textContent).not.toContain(MENSAGENS.AC_011_4_CEP_FALHA);
    });

    // Os três cenários da tarefa (e suas variações): o texto EXATO do catálogo, em role="alert".
    it.each(FALHAS)('AC-011.4 falha por %s mostra o aviso do catálogo em role=alert', (_nome, acao) => {
      const req = consultarCep(CEP_RJ);

      falhar(req, acao);

      const alertas = avisos();
      expect(alertas).toHaveLength(1);
      expect(textoDe(alertas[0])).toBe(MENSAGENS.AC_011_4_CEP_FALHA);
      expect(textoDe(alertas[0])).toBe(TEXTO_LITERAL);
      expect(textoDe(alertas[0])).toBe(TestBed.inject(MensagemService).texto('AC_011_4_CEP_FALHA'));
    });

    // Fronteira do timeout NO COMPONENTE: aos 14.999 ms nada de aviso (e ainda ocupado); aos 15.000 aparece.
    it('AC-011.4 timeout: aos 14.999 ms sem aviso (aguardando) e aos 15.000 ms aparece no ato', () => {
      const req = consultarCep(CEP_RJ);

      // Passo 1: 14.999 ms: ainda aguardando.
      vi.advanceTimersByTime(14_999);
      atualizar();
      expect(avisos()).toHaveLength(0);
      expect(raizDoForm().getAttribute('aria-busy')).toBe('true');
      expect(req.cancelled).toBe(false);

      // Passo 2: 1 ms depois (15.000): aviso aparece e deixa de estar ocupado.
      vi.advanceTimersByTime(1);
      atualizar();
      expect(textoDe(avisos()[0])).toBe(TEXTO_LITERAL);
      expect(raizDoForm().getAttribute('aria-busy')).not.toBe('true');
      expect(req.cancelled).toBe(true);
    });

    // O texto vem do catálogo pelo MensagemService (D-40): trocar o que o serviço devolve muda a tela.
    it('D-40 o texto do aviso vem de MensagemService.texto("AC_011_4_CEP_FALHA"), não é digitado no componente', () => {
      fixture.destroy();
      const mensagens = TestBed.inject(MensagemService);
      const texto = vi.spyOn(mensagens, 'texto').mockReturnValue('TEXTO VINDO DO CATALOGO (simulado)');
      montar();

      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));

      expect(texto).toHaveBeenCalledWith('AC_011_4_CEP_FALHA');
      expect(textoDe(avisos()[0])).toBe('TEXTO VINDO DO CATALOGO (simulado)');
    });

    // Guarda: o texto do catálogo é mesmo o da spec (AC-011.4), para o literal acima não mascarar uma troca.
    it('AC-011.4 o texto do catálogo é exatamente o da spec', () => {
      expect(MENSAGENS.AC_011_4_CEP_FALHA).toBe(TEXTO_LITERAL);
    });

    // Cadastro nunca bloqueado: em cada tipo de falha, todos os campos seguem habilitados e editáveis.
    it.each(FALHAS)('AC-011.4 após falha por %s nenhum campo é desabilitado nem readonly', (_nome, acao) => {
      falhar(consultarCep(CEP_RJ), acao);

      for (const id of TODOS_OS_CAMPOS) {
        const elemento = campo<HTMLInputElement | HTMLSelectElement>(id);
        expect(elemento.disabled, `${id} não pode estar disabled`).toBe(false);
        expect(elemento.hasAttribute('readonly'), `${id} não pode estar readonly`).toBe(false);
        expect(elemento.hasAttribute('disabled'), `${id} sem atributo disabled`).toBe(false);
      }
      for (const controle of Object.values(endereco.controls)) {
        expect(controle.enabled).toBe(true);
      }
    });

    // Preenchimento manual depois da falha: o grupo era inválido e passa a válido, e o pai acompanha.
    it.each(FALHAS)('AC-011.4 após falha por %s o formulário fica válido ao preencher à mão', (_nome, acao) => {
      falhar(consultarCep(CEP_RJ), acao);
      // Antes de preencher: inválido (o aviso não preenche nada nem libera por si só).
      expect(endereco.valid).toBe(false);
      expect(pai.valid).toBe(false);

      preencherAMao();

      expect(endereco.valid).toBe(true);
      expect(pai.valid).toBe(true);
      expect(enderecoDoForm(endereco)).toStrictEqual({
        zipCode: '22041001',
        street: 'Avenida Atlântica',
        number: '370',
        complement: null,
        district: 'Copacabana',
        city: 'Rio de Janeiro',
        state: 'RJ',
      });
    });

    // O que a pessoa já tinha digitado antes da falha fica (a falha nunca apaga nada).
    it('AC-011.4 a falha não apaga o que a pessoa já tinha digitado', () => {
      digitar(ID.logradouro, 'Rua Digitada Antes');
      digitar(ID.numero, '99');
      digitar(ID.cidade, 'Cidade Digitada');

      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));

      expect(endereco.controls.street.value).toBe('Rua Digitada Antes');
      expect(endereco.controls.number.value).toBe('99');
      expect(endereco.controls.city.value).toBe('Cidade Digitada');
      expect(valorDe(ID.logradouro)).toBe('Rua Digitada Antes');
    });

    // Falha não preenche nada sozinha (sem inventar dados).
    it('AC-011.4 a falha não preenche logradouro, bairro, cidade nem UF', () => {
      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));

      expect(endereco.controls.street.value).toBe('');
      expect(endereco.controls.district.value).toBe('');
      expect(endereco.controls.city.value).toBe('');
      expect(endereco.controls.state.value).toBe('');
    });

    // O aviso some quando a pessoa passa a digitar outro CEP (aqui, apagando um dígito).
    it('AC-011.4 o aviso some quando o CEP é editado', () => {
      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));
      expect(avisos()).toHaveLength(1);

      digitar(ID.cep, '22041-00');

      expect(avisos()).toHaveLength(0);
    });

    // O aviso some quando uma nova consulta começa (CEP novo completo) e não volta se ela der certo.
    it('AC-011.4 o aviso some quando uma nova consulta começa e continua sumido se ela der certo', () => {
      // Passo 1: primeira consulta falha.
      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));
      expect(avisos()).toHaveLength(1);

      // Passo 2: novo CEP completo: a consulta começa e o aviso antigo sai.
      const novo = consultarCep(CEP_SP);
      expect(avisos()).toHaveLength(0);

      // Passo 3: a consulta nova dá certo: segue sem aviso e os dados entram.
      novo.flush(RESPOSTA_SP);
      atualizar();
      expect(avisos()).toHaveLength(0);
      expect(endereco.controls.city.value).toBe('São Paulo');
    });

    // Uma nova falha mostra o aviso DE NOVO e só um (não empilha avisos).
    it('AC-011.4 duas falhas seguidas (CEPs diferentes) mostram um único aviso', () => {
      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));
      falhar(consultarCep(CEP_SP), (req) => req.flush({}, { status: 500, statusText: 'Server Error' }));

      expect(avisos()).toHaveLength(1);
      expect(textoDe(avisos()[0])).toBe(TEXTO_LITERAL);
    });

    // Consulta substituída: o prazo da antiga NÃO pode gerar aviso depois que o CEP já mudou.
    it('AC-011.4 o timeout de uma consulta cancelada não gera aviso', () => {
      // Passo 1: consulta A em t=0.
      const reqA = consultarCep(CEP_RJ);

      // Passo 2: em t=10 s a pessoa troca para o CEP B.
      vi.advanceTimersByTime(10_000);
      const reqB = consultarCep(CEP_SP);
      expect(reqA.cancelled).toBe(true);

      // Passo 3: em t=20 s o prazo de A (15 s) já passou, e nada aparece: B ainda tem 5 s.
      vi.advanceTimersByTime(10_000);
      atualizar();
      expect(avisos()).toHaveLength(0);

      // Passo 4: B responde a tempo: segue sem aviso.
      reqB.flush(RESPOSTA_SP);
      atualizar();
      expect(avisos()).toHaveLength(0);
      expect(endereco.controls.city.value).toBe('São Paulo');
    });

    // Falhou e a pessoa digita à mão: uma resposta tardia nunca chega (consulta encerrada/cancelada).
    it('AC-011.4 após o timeout a requisição é abandonada e o preenchimento manual não é sobrescrito', () => {
      const req = consultarCep(CEP_RJ);
      vi.advanceTimersByTime(15_000);
      atualizar();
      expect(req.cancelled).toBe(true);

      preencherAMao();

      expect(endereco.controls.street.value).toBe('Avenida Atlântica');
      expect(endereco.controls.city.value).toBe('Rio de Janeiro');
      expect(() => req.flush(RESPOSTA_RJ)).toThrow();
    });
  });

  // ---------------------------------------------------------------------------------------
  // Acessibilidade (P4) e campos (AC-011.1)
  // ---------------------------------------------------------------------------------------

  describe('acessibilidade e campos (P4, AC-011.1)', () => {
    beforeEach(() => montar());

    // Cada campo existe, tem id e um <label for> que o aponta, com o rótulo esperado.
    it.each(TODOS_OS_CAMPOS)('P4 o campo %s tem um label ligado por for/id com o rótulo esperado', (id) => {
      const elemento = campo<HTMLInputElement | HTMLSelectElement>(id);

      expect(elemento.id, 'o campo precisa de id').not.toBe('');
      const rotulo = host.querySelector<HTMLLabelElement>(`label[for="${elemento.id}"]`);
      expect(rotulo, 'precisa existir <label for> apontando para o campo').not.toBeNull();
      expect(rotulo?.control).toBe(elemento);
      expect(textoDe(rotulo ?? undefined)).toContain(ROTULOS[id]);
    });

    // Os 7 campos aparecem: os 6 obrigatórios e o complemento.
    it('AC-011.1 o formulário mostra os 7 campos: CEP, logradouro, número, complemento, bairro, cidade e UF', () => {
      expect(TODOS_OS_CAMPOS).toHaveLength(7);
      for (const id of TODOS_OS_CAMPOS) {
        expect(host.querySelector(`[data-testid="${id}"]`), id).not.toBeNull();
      }
      expect(campo(ID.uf).tagName).toBe('SELECT');
    });

    // Teclado numérico no celular e preenchimento automático do navegador para o CEP.
    it('P4 o CEP tem inputmode=numeric e autocomplete=postal-code', () => {
      expect(campo(ID.cep).getAttribute('inputmode')).toBe('numeric');
      expect(campo(ID.cep).getAttribute('autocomplete')).toBe('postal-code');
    });

    // Limites do contrato (`Address`) afirmados como ATRIBUTO no DOM, sem depender da validação.
    it.each([
      // Linha 1: logradouro, máximo de 150.
      [ID.logradouro, 150],
      // Linha 2: complemento, máximo de 80.
      [ID.complemento, 80],
      // Linha 3: bairro, máximo de 80.
      [ID.bairro, 80],
      // Linha 4: cidade, máximo de 80.
      [ID.cidade, 80],
    ])('AC-011.1 o campo %s tem o atributo maxlength=%i no DOM', (id, limite) => {
      expect(campo(id).getAttribute('maxlength')).toBe(String(limite));
      expect(campo(id).maxLength).toBe(limite);
    });

    // O maxlength não pode truncar um CEP colado com pontuação ("22.041-001" tem 10 caracteres).
    it('P4 o maxlength do CEP comporta 00000-000 e uma colagem com pontuação (>= 10 ou ausente)', () => {
      const maximo = campo(ID.cep).maxLength;

      expect(maximo === -1 || maximo >= 10).toBe(true);
    });

    // O <select> de UF: opção vazia inicial + as 27 siglas, sem repetir nem inventar.
    it('AC-011.2 a UF é um select com uma opção vazia inicial e as 27 siglas', () => {
      const select = campo<HTMLSelectElement>(ID.uf);
      const opcoes = Array.from(select.options);

      // Passo 1: 1 vazia + 27.
      expect(opcoes).toHaveLength(28);
      // Passo 2: a primeira é a vazia e é a selecionada de início.
      expect(opcoes[0].value).toBe('');
      expect(select.value).toBe('');
      // Passo 3: as outras 27 são exatamente as UFs do contrato, e o texto é a sigla.
      const siglas = opcoes.slice(1).map((o) => o.value);
      expect([...siglas].sort()).toEqual(Object.values(UF).sort());
      expect(opcoes.slice(1).map((o) => textoDe(o))).toEqual(siglas);
    });

    // Escolher uma UF atualiza o controle com a sigla.
    it('AC-011.2 escolher uma UF no select grava a sigla no controle', () => {
      escolherUf('MG');

      expect(endereco.controls.state.value).toBe('MG');
      expect(valorDe(ID.uf)).toBe('MG');
    });

    // Cada campo de texto grava no controle certo (e o número aceita "S/N").
    it('AC-011.1 digitar em cada campo grava no controle correspondente', () => {
      digitar(ID.logradouro, 'Rua A');
      digitar(ID.numero, 's/n');
      digitar(ID.complemento, 'fundos');
      digitar(ID.bairro, 'Centro');
      digitar(ID.cidade, 'Niterói');

      expect(endereco.controls.street.value).toBe('Rua A');
      expect(endereco.controls.number.value).toBe('s/n');
      expect(endereco.controls.complement.value).toBe('fundos');
      expect(endereco.controls.district.value).toBe('Centro');
      expect(endereco.controls.city.value).toBe('Niterói');
      expect(endereco.controls.number.valid).toBe(true);
    });

    // Ao abrir, nada está marcado como inválido (a tela não "grita" antes de a pessoa mexer).
    it('P4 ao abrir nenhum campo tem aria-invalid=true, mesmo com o formulário vazio e inválido', () => {
      expect(endereco.valid).toBe(false);

      for (const id of TODOS_OS_CAMPOS) {
        expect(campo(id).getAttribute('aria-invalid'), id).not.toBe('true');
      }
    });

    // Sair de um campo obrigatório vazio o marca como inválido (aria-invalid=true).
    it.each([ID.cep, ID.logradouro, ID.numero, ID.bairro, ID.cidade, ID.uf])(
      'P4 o campo obrigatório %s vazio e tocado fica com aria-invalid=true',
      (id) => {
        sairDe(id);

        expect(campo(id).getAttribute('aria-invalid')).toBe('true');
      },
    );

    // O complemento é opcional: vazio e tocado NÃO é inválido.
    it('P4 o complemento vazio e tocado não fica com aria-invalid=true', () => {
      sairDe(ID.complemento);

      expect(campo(ID.complemento).getAttribute('aria-invalid')).not.toBe('true');
    });

    // Valores fora do padrão marcam o campo (depois de tocado): cada linha é um caso de AC-011.1/011.2.
    it.each([
      // Linha 1: CEP incompleto.
      [ID.cep, '22041'],
      // Linha 2: número com duas letras.
      [ID.numero, '12AB'],
      // Linha 3: número com 7 dígitos.
      [ID.numero, '1234567'],
      // Linha 4: complemento acima de 80 caracteres.
      [ID.complemento, 'x'.repeat(81)],
      // Linha 5: bairro acima de 80 caracteres.
      [ID.bairro, 'x'.repeat(81)],
      // Linha 6: logradouro acima de 150 caracteres.
      [ID.logradouro, 'x'.repeat(151)],
    ])('P4 %s com %j, depois de tocado, fica com aria-invalid=true', (id, valor) => {
      digitar(id, valor);
      sairDe(id);

      expect(campo(id).getAttribute('aria-invalid')).toBe('true');
      http.match(() => true);
    });

    // Corrigir o valor tira a marca (o atributo acompanha a validade).
    it('P4 ao corrigir o valor o aria-invalid=true sai', () => {
      digitar(ID.numero, '12AB');
      sairDe(ID.numero);
      expect(campo(ID.numero).getAttribute('aria-invalid')).toBe('true');

      digitar(ID.numero, '12B');

      expect(campo(ID.numero).getAttribute('aria-invalid')).not.toBe('true');
    });

    // Números aceitos de AC-011.1 (incluindo S/N) não são marcados como inválidos.
    it.each([['370'], ['12B'], ['S/N'], ['s/n']])('AC-011.1 número %j tocado não fica com aria-invalid=true', (valor) => {
      digitar(ID.numero, valor);
      sairDe(ID.numero);

      expect(campo(ID.numero).getAttribute('aria-invalid')).not.toBe('true');
    });

    // T-052: as mensagens de validação aprovadas NÃO são alertas: tocar todos os campos vazios
    // mostra os textos do catálogo, mas o único role=alert possível continua sendo o do CEP (011.4).
    it('P4 tocar todos os campos vazios não cria nenhum role=alert (as mensagens de validação não são alertas)', () => {
      for (const id of TODOS_OS_CAMPOS) {
        sairDe(id);
      }

      expect(avisos()).toHaveLength(0);
    });

    // Dois formulários na mesma tela (idoso + contato): ids únicos e cada rótulo aponta para o seu campo.
    it('P4 dois formulários na mesma tela têm ids únicos e rótulos ligados ao campo do próprio formulário', () => {
      fixture.destroy();
      const duplo = TestBed.createComponent(DoisEnderecosAnfitriaoComponent);
      duplo.detectChanges();
      const raiz = duplo.nativeElement as HTMLElement;

      // Passo 1: nenhum id repetido no documento do anfitrião.
      const ids = Array.from(raiz.querySelectorAll('[id]')).map((e) => e.id);
      expect(ids.length).toBeGreaterThanOrEqual(14);
      expect(new Set(ids).size).toBe(ids.length);

      // Passo 2: em cada formulário, o rótulo "CEP" controla o CEP daquele formulário.
      for (const secao of ['primeiro', 'segundo']) {
        const regiao = campo<HTMLElement>(secao, raiz);
        for (const id of TODOS_OS_CAMPOS) {
          const elemento = campo<HTMLInputElement | HTMLSelectElement>(id, regiao);
          const rotulo = regiao.querySelector<HTMLLabelElement>(`label[for="${elemento.id}"]`);
          expect(rotulo?.control, `${secao}/${id}`).toBe(elemento);
        }
      }
      duplo.destroy();
    });

    // Estado independente: a falha de CEP do primeiro formulário não aparece no segundo.
    it('AC-011.4 em dois formulários, o aviso de falha aparece só no formulário que consultou', () => {
      fixture.destroy();
      const duplo = TestBed.createComponent(DoisEnderecosAnfitriaoComponent);
      duplo.detectChanges();
      const raiz = duplo.nativeElement as HTMLElement;
      const primeiro = campo<HTMLElement>('primeiro', raiz);
      const segundo = campo<HTMLElement>('segundo', raiz);

      // Passo 1: digita o CEP no primeiro formulário e a consulta falha.
      const entrada = campo(ID.cep, primeiro);
      entrada.value = CEP_RJ;
      entrada.dispatchEvent(new InputEvent('input', { bubbles: true }));
      duplo.detectChanges();
      http.expectOne(urlCep(CEP_RJ)).flush({ erro: true });
      duplo.detectChanges();

      // Passo 2: só o primeiro mostra o aviso; o segundo fica intacto.
      expect(avisos(primeiro)).toHaveLength(1);
      expect(avisos(segundo)).toHaveLength(0);
      expect(campo(ID.cep, segundo).value).toBe('');
      duplo.destroy();
    });
  });

  // ---------------------------------------------------------------------------------------
  // Mensagens de validação (T-052: textos aprovados; AC-011.1, AC-011.2, D-40, P4)
  // ---------------------------------------------------------------------------------------

  describe('mensagens de validação (T-052, AC-011.1, AC-011.2, D-40, P4)', () => {
    beforeEach(() => montar());

    /** Chaves do catálogo usadas pelo componente, com o texto literal aprovado (digitado à parte). */
    const MSG = {
      cep: { chave: 'VAL_CEP_INVALIDO', texto: 'Informe o CEP com 8 números.' },
      numero: { chave: 'VAL_NUMERO_INVALIDO', texto: 'Informe o número (ex.: 120 ou 12B) ou S/N.' },
      uf: { chave: 'VAL_UF_OBRIGATORIA', texto: 'Escolha o estado.' },
      obrigatorio: { chave: 'VAL_OBRIGATORIO', texto: 'Preencha este campo.' },
    } as const;

    /** Elementos apontados por `aria-describedby` do campo (ids que existem no documento). */
    function descritores(id: string, raiz: ParentNode = host): HTMLElement[] {
      const ids = (campo(id, raiz).getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
      return ids
        .map((ref) => document.getElementById(ref))
        .filter((el): el is HTMLElement => el !== null);
    }

    /** Elemento da mensagem de VALIDAÇÃO do campo (o descritor que não é o aviso `role=alert` do CEP). */
    function elementoDaMensagem(id: string, raiz: ParentNode = host): HTMLElement | undefined {
      return descritores(id, raiz).find((el) => el.getAttribute('role') !== 'alert');
    }

    /** Texto da mensagem de validação ligada ao campo ('' quando não há). */
    const mensagemDe = (id: string, raiz: ParentNode = host) => textoDe(elementoDaMensagem(id, raiz));

    /** Os seis campos obrigatórios com a mensagem esperada quando vazios (decisões na descrição). */
    const OBRIGATORIOS_VAZIOS: [string, string][] = [
      [ID.cep, MSG.cep.texto],
      [ID.logradouro, MSG.obrigatorio.texto],
      [ID.numero, MSG.numero.texto],
      [ID.bairro, MSG.obrigatorio.texto],
      [ID.cidade, MSG.obrigatorio.texto],
      [ID.uf, MSG.uf.texto],
    ];

    // Ao abrir, a tela não "grita": nenhuma das quatro mensagens existe, nem ligação por describedby.
    it('P4 ao abrir, com o formulário vazio e inválido, nenhum campo mostra mensagem de validação', () => {
      expect(endereco.valid).toBe(false);

      for (const id of TODOS_OS_CAMPOS) {
        expect(mensagemDe(id), id).toBe('');
      }
      for (const { texto } of Object.values(MSG)) {
        expect(textoDe(host)).not.toContain(texto);
      }
    });

    // Cada linha: um campo, o valor digitado e a mensagem do catálogo esperada depois de tocar.
    it.each([
      // Linha 1: CEP incompleto (5 dígitos) -> VAL_CEP_INVALIDO (AC-011.2).
      [ID.cep, '22041', MSG.cep.chave],
      // Linha 2: CEP vazio e tocado -> o mesmo texto (decisão: o texto aprovado orienta o vazio também).
      [ID.cep, '', MSG.cep.chave],
      // Linha 3: número com duas letras -> VAL_NUMERO_INVALIDO (AC-011.1).
      [ID.numero, '12AB', MSG.numero.chave],
      // Linha 4: número com 7 dígitos -> VAL_NUMERO_INVALIDO.
      [ID.numero, '1234567', MSG.numero.chave],
      // Linha 5: número vazio e tocado -> VAL_NUMERO_INVALIDO (decisão, igual ao CEP).
      [ID.numero, '', MSG.numero.chave],
      // Linha 6: logradouro vazio -> VAL_OBRIGATORIO.
      [ID.logradouro, '', MSG.obrigatorio.chave],
      // Linha 7: logradouro só com espaços conta como vazio -> VAL_OBRIGATORIO (AC-011.1).
      [ID.logradouro, '    ', MSG.obrigatorio.chave],
      // Linha 8: bairro vazio -> VAL_OBRIGATORIO.
      [ID.bairro, '', MSG.obrigatorio.chave],
      // Linha 9: bairro só com espaços -> VAL_OBRIGATORIO.
      [ID.bairro, '   ', MSG.obrigatorio.chave],
      // Linha 10: cidade vazia -> VAL_OBRIGATORIO.
      [ID.cidade, '', MSG.obrigatorio.chave],
      // Linha 11: cidade só com espaços -> VAL_OBRIGATORIO.
      [ID.cidade, '  ', MSG.obrigatorio.chave],
    ])('AC-011.1 %s com %j, depois de tocado, mostra a mensagem do catálogo %s', (id, valor, chave) => {
      if (valor !== '') {
        digitar(id, valor);
      }
      sairDe(id);

      // O texto vem do catálogo (pela chave) e é exatamente o aprovado (literal).
      const aprovado = Object.values(MSG).find((m) => m.chave === chave);
      expect(aprovado, `a chave ${chave} precisa estar na tabela MSG`).toBeDefined();
      expect(mensagemDe(id)).toBe(MENSAGENS[chave as keyof typeof MENSAGENS]);
      expect(mensagemDe(id)).toBe(aprovado?.texto);
      // E o campo continua marcado como inválido para leitores de tela.
      expect(campo(id).getAttribute('aria-invalid')).toBe('true');
    });

    // UF não escolhida e tocada -> VAL_UF_OBRIGATORIA (AC-011.2).
    it('AC-011.2 a UF não escolhida, depois de tocada, mostra "Escolha o estado." (VAL_UF_OBRIGATORIA)', () => {
      sairDe(ID.uf);

      expect(mensagemDe(ID.uf)).toBe(MENSAGENS.VAL_UF_OBRIGATORIA);
      expect(mensagemDe(ID.uf)).toBe(MSG.uf.texto);
      expect(campo(ID.uf).getAttribute('aria-invalid')).toBe('true');
    });

    // O complemento é opcional: nunca mostra erro de obrigatório, vazio ou só com espaços.
    it.each([
      // Linha 1: vazio.
      [''],
      // Linha 2: só espaços (conta como vazio).
      ['    '],
      // Linha 3: preenchido.
      ['apto 101'],
    ])('AC-011.1 o complemento %j, depois de tocado, nunca mostra mensagem de validação', (valor) => {
      if (valor !== '') {
        digitar(ID.complemento, valor);
      }
      sairDe(ID.complemento);

      expect(mensagemDe(ID.complemento)).toBe('');
      expect(descritores(ID.complemento)).toHaveLength(0);
      for (const { texto } of Object.values(MSG)) {
        expect(textoDe(campo<HTMLElement>('endereco-form'))).not.toContain(texto);
      }
    });

    // A mensagem só aparece depois de a pessoa passar pelo campo (tocado), nunca durante a digitação.
    it('P4 a mensagem só aparece depois de tocado: digitar um valor inválido sem sair do campo não mostra nada', () => {
      // Passo 1: número inválido digitado, sem blur.
      digitar(ID.numero, '12AB');
      expect(mensagemDe(ID.numero)).toBe('');

      // Passo 2: ao sair do campo a mensagem aparece.
      sairDe(ID.numero);
      expect(mensagemDe(ID.numero)).toBe(MSG.numero.texto);
    });

    // Cada linha: campo, valor inválido que gera a mensagem e valor que a faz sumir.
    it.each([
      // Linha 1: CEP incompleto -> 8 dígitos (a consulta ao ViaCEP é descartada, a mensagem some).
      [ID.cep, '22041', CEP_RJ],
      // Linha 2: número inválido -> número com letra.
      [ID.numero, '12AB', '12B'],
      // Linha 3: número vazio -> S/N.
      [ID.numero, '', 'S/N'],
      // Linha 4: logradouro vazio -> preenchido.
      [ID.logradouro, '', 'Rua A'],
      // Linha 5: bairro só espaços -> preenchido.
      [ID.bairro, '   ', 'Centro'],
      // Linha 6: cidade vazia -> preenchida.
      [ID.cidade, '', 'Niterói'],
    ])('P4 %s: a mensagem de %j some quando o valor passa a ser válido (%j)', (id, invalido, valido) => {
      // Passo 1: mostra a mensagem.
      if (invalido !== '') {
        digitar(id, invalido);
      }
      sairDe(id);
      expect(mensagemDe(id), 'a mensagem deveria aparecer antes da correção').not.toBe('');

      // Passo 2: corrige; mensagem, describedby e aria-invalid saem.
      digitar(id, valido);
      http.match(() => true);
      expect(mensagemDe(id)).toBe('');
      expect(descritores(id)).toHaveLength(0);
      expect(campo(id).getAttribute('aria-invalid')).not.toBe('true');
    });

    // UF: escolher uma sigla depois de tocada faz a mensagem sumir.
    it('AC-011.2 a mensagem da UF some quando uma UF é escolhida', () => {
      sairDe(ID.uf);
      expect(mensagemDe(ID.uf)).toBe(MSG.uf.texto);

      escolherUf('RJ');

      expect(mensagemDe(ID.uf)).toBe('');
      expect(campo(ID.uf).getAttribute('aria-invalid')).not.toBe('true');
    });

    // Cada campo com mensagem a liga por aria-describedby a um elemento que existe, único e sem role=alert.
    it.each(OBRIGATORIOS_VAZIOS)('P4 %s: aria-describedby aponta para um elemento com a mensagem, sem role=alert', (id, texto) => {
      sairDe(id);

      // Passo 1: há exatamente um elemento de mensagem ligado ao campo e o texto é o aprovado.
      const elemento = elementoDaMensagem(id);
      expect(elemento, 'a mensagem precisa estar ligada por aria-describedby').toBeDefined();
      expect(textoDe(elemento)).toBe(texto);
      // Passo 2: não é alerta (nem está dentro de um) e o id é único no documento.
      expect(elemento?.getAttribute('role')).not.toBe('alert');
      expect(elemento?.closest('[role="alert"]')).toBeNull();
      expect(document.querySelectorAll(`[id="${elemento?.id}"]`)).toHaveLength(1);
      expect(elemento?.id).not.toBe('');
    });

    // "Abaixo do campo": a mensagem fica no mesmo bloco do campo e DEPOIS dele no documento.
    it.each(OBRIGATORIOS_VAZIOS)('P4 %s: a mensagem aparece abaixo do campo (mesmo bloco, depois dele)', (id) => {
      sairDe(id);

      const entrada = campo(id);
      const mensagem = elementoDaMensagem(id) as HTMLElement;
      expect(entrada.parentElement?.contains(mensagem)).toBe(true);
      expect(entrada.compareDocumentPosition(mensagem) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // Tocar todos os campos mostra as seis mensagens (uma por campo obrigatório) e mais nada.
    it('AC-011.1 tocar todos os campos vazios mostra uma mensagem por campo obrigatório e nenhuma no complemento', () => {
      for (const id of TODOS_OS_CAMPOS) {
        sairDe(id);
      }

      // Um campo = uma mensagem, com o texto esperado por campo (decisões: CEP e número vazios).
      for (const [id, texto] of OBRIGATORIOS_VAZIOS) {
        expect(mensagemDe(id), id).toBe(texto);
      }
      expect(mensagemDe(ID.complemento)).toBe('');
      // Nenhum dos elementos de mensagem é alerta.
      expect(avisos()).toHaveLength(0);
    });

    // O pai pode marcar o formulário todo como tocado no submit: as mensagens aparecem sem blur algum.
    it('AC-011.1 o pai chamar markAllAsTouched (submit) mostra as mensagens de todos os campos inválidos', () => {
      // Passo 1: nada tocado, nada de mensagem.
      expect(mensagemDe(ID.cep)).toBe('');

      // Passo 2: o pai marca tudo como tocado, como fará ao tentar enviar.
      pai.markAllAsTouched();
      atualizar();

      for (const [id, texto] of OBRIGATORIOS_VAZIOS) {
        expect(mensagemDe(id), id).toBe(texto);
        expect(campo(id).getAttribute('aria-invalid'), id).toBe('true');
      }
      expect(mensagemDe(ID.complemento)).toBe('');
      expect(avisos()).toHaveLength(0);
    });

    // markAllAsTouched com campos válidos: só os inválidos mostram mensagem.
    it('AC-011.1 markAllAsTouched com o endereço todo válido não mostra nenhuma mensagem', () => {
      endereco.setValue({
        zipCode: '22041001',
        street: 'Rua A',
        number: '370',
        complement: '',
        district: 'Centro',
        city: 'Niterói',
        state: 'RJ',
      });

      pai.markAllAsTouched();
      atualizar();

      for (const id of TODOS_OS_CAMPOS) {
        expect(mensagemDe(id), id).toBe('');
        expect(campo(id).getAttribute('aria-invalid'), id).not.toBe('true');
      }
    });

    // AC-011.3: a consulta do CEP preenche os campos e as mensagens deles somem, sem tocar no número.
    it('AC-011.3 o preenchimento pelo ViaCEP faz sumir as mensagens de logradouro, bairro, cidade e UF', () => {
      // Passo 1: pessoa passou pelos campos vazios e vê as mensagens.
      for (const id of [ID.logradouro, ID.bairro, ID.cidade, ID.uf, ID.numero]) {
        sairDe(id);
      }
      expect(mensagemDe(ID.logradouro)).toBe(MSG.obrigatorio.texto);

      // Passo 2: digita o CEP e a consulta preenche os quatro campos.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();

      // Passo 3: as quatro mensagens saem; o número (que o ViaCEP não preenche) continua com a dele.
      for (const id of [ID.logradouro, ID.bairro, ID.cidade, ID.uf]) {
        expect(mensagemDe(id), id).toBe('');
      }
      expect(mensagemDe(ID.numero)).toBe(MSG.numero.texto);
    });

    // Campo acima do limite de tamanho: continua aria-invalid (T-051); não há texto aprovado para isso.
    it('P4 logradouro acima de 150 caracteres fica com aria-invalid e sem mensagem de "obrigatório"', () => {
      digitar(ID.logradouro, 'x'.repeat(151));
      sairDe(ID.logradouro);

      expect(campo(ID.logradouro).getAttribute('aria-invalid')).toBe('true');
      expect(mensagemDe(ID.logradouro)).not.toBe(MSG.obrigatorio.texto);
    });

    // AC-011.4 continua intacto: o aviso do CEP é o único role=alert e segue ligado por aria-describedby.
    it('AC-011.4 com todas as mensagens visíveis, o único role=alert é o aviso do CEP, ligado ao campo por aria-describedby', () => {
      // Passo 1: todas as mensagens de validação visíveis.
      for (const id of TODOS_OS_CAMPOS) {
        sairDe(id);
      }
      expect(avisos()).toHaveLength(0);

      // Passo 2: a consulta do CEP falha e o aviso aparece.
      falhar(consultarCep(CEP_RJ), (req) => req.flush({ erro: true }));

      const alertas = avisos();
      expect(alertas).toHaveLength(1);
      expect(textoDe(alertas[0])).toBe(MENSAGENS.AC_011_4_CEP_FALHA);
      // O aviso é um dos descritores do CEP.
      expect(descritores(ID.cep).map((el) => el.id)).toContain(alertas[0].id);
      expect(alertas[0].id).not.toBe('');
    });

    // Dois formulários na mesma tela: ids das mensagens únicos e cada campo aponta para a mensagem do seu formulário.
    it('P4 dois formulários com todas as mensagens visíveis têm ids únicos e cada campo aponta para a sua própria mensagem', () => {
      fixture.destroy();
      const duplo = TestBed.createComponent(DoisEnderecosAnfitriaoComponent);
      duplo.detectChanges();
      const raiz = duplo.nativeElement as HTMLElement;
      const primeiro = campo<HTMLElement>('primeiro', raiz);
      const segundo = campo<HTMLElement>('segundo', raiz);

      // Passo 1: toca todos os campos nos dois formulários.
      for (const regiao of [primeiro, segundo]) {
        for (const id of TODOS_OS_CAMPOS) {
          campo(id, regiao).dispatchEvent(new Event('blur'));
        }
      }
      duplo.detectChanges();

      // Passo 2: nenhum id repetido no documento do anfitrião.
      const ids = Array.from(raiz.querySelectorAll('[id]')).map((e) => e.id);
      expect(new Set(ids).size).toBe(ids.length);

      // Passo 3: em cada formulário, a mensagem ligada ao campo mora dentro do próprio formulário.
      for (const regiao of [primeiro, segundo]) {
        for (const [id, texto] of OBRIGATORIOS_VAZIOS) {
          const mensagem = elementoDaMensagem(id, regiao);
          expect(mensagem, id).toBeDefined();
          expect(regiao.contains(mensagem as HTMLElement), id).toBe(true);
          expect(textoDe(mensagem), id).toBe(texto);
        }
      }
      duplo.destroy();
    });

    // D-40: o texto vem do MensagemService, não é digitado no componente (troca o serviço e a tela muda).
    it('D-40 as mensagens de validação vêm de MensagemService.texto(chave), não são digitadas no componente', () => {
      fixture.destroy();
      const texto = vi
        .spyOn(TestBed.inject(MensagemService), 'texto')
        .mockImplementation((chave) => `«${chave}»`);
      montar();

      for (const id of TODOS_OS_CAMPOS) {
        sairDe(id);
      }

      // Cada campo mostra exatamente a marca da chave certa: prova a chave e que não há texto fixo.
      expect(mensagemDe(ID.cep)).toBe('«VAL_CEP_INVALIDO»');
      expect(mensagemDe(ID.logradouro)).toBe('«VAL_OBRIGATORIO»');
      expect(mensagemDe(ID.numero)).toBe('«VAL_NUMERO_INVALIDO»');
      expect(mensagemDe(ID.bairro)).toBe('«VAL_OBRIGATORIO»');
      expect(mensagemDe(ID.cidade)).toBe('«VAL_OBRIGATORIO»');
      expect(mensagemDe(ID.uf)).toBe('«VAL_UF_OBRIGATORIA»');
      expect(texto).toHaveBeenCalledWith('VAL_CEP_INVALIDO');
    });

    // Guarda: o catálogo tem mesmo os textos aprovados (para os literais acima não mascararem uma troca).
    it('D-40 o catálogo guarda exatamente os quatro textos de validação do endereço', () => {
      for (const { chave, texto } of Object.values(MSG)) {
        expect(MENSAGENS[chave], chave).toBe(texto);
      }
    });
  });

  // ---------------------------------------------------------------------------------------
  // Privacidade (P5) e escopo (D-27)
  // ---------------------------------------------------------------------------------------

  describe('privacidade e escopo (P5, D-27)', () => {
    beforeEach(() => montar());

    // O CEP e o endereço digitados nunca são persistidos no navegador (nada de storage/cookie).
    it('P5 nenhum dado do endereço vai para localStorage, sessionStorage ou cookie (sucesso e falha)', () => {
      const gravar = vi.spyOn(Storage.prototype, 'setItem');

      // Passo 1: fluxo com sucesso.
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      atualizar();
      digitar(ID.numero, '370');
      // Passo 2: fluxo com falha.
      falhar(consultarCep(CEP_SP), (req) => req.flush({ erro: true }));
      preencherAMao();

      expect(gravar).not.toHaveBeenCalled();
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
      expect(document.cookie).toBe('');
    });

    // A única chamada de rede é ao ViaCEP: o componente não fala com o nosso backend (D-27).
    it('D-27 o componente só chama o ViaCEP, nunca o backend (/api)', () => {
      consultarCep(CEP_RJ).flush(RESPOSTA_RJ);
      falhar(consultarCep(CEP_SP), (req) => req.flush({ erro: true }));
      digitar(ID.numero, '370');

      // Qualquer outra requisição (em voo ou não) faria esta busca devolver algo.
      expect(http.match((r) => !r.url.startsWith('https://viacep.com.br/ws/'))).toEqual([]);
    });
  });
});
