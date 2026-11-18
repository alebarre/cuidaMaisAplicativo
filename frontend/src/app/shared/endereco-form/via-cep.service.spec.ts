import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Subscription } from 'rxjs';
import { provideApi } from '../../core/api/provide-api';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { SessionService } from '../../core/auth/session.service';
import { EnderecoViaCep, VIACEP_TIMEOUT_MS, ViaCepErro, ViaCepService } from './via-cep.service';

/**
 * Cobre o `ViaCepService` (T-051, AC-011.3, AC-011.4, D-27 rev. 2, P5): a consulta de CEP feita
 * DIRETO do navegador ao ViaCEP (`GET https://viacep.com.br/ws/{cep}/json/`), sem passar pelo
 * nosso backend.
 *
 * O que fica provado aqui: o mapeamento dos quatro campos do endereço (AC-011.3); toda forma de
 * falha vira um `ViaCepErro` com o motivo certo (`{"erro": true}`, `{"erro": "true"}`, HTTP
 * 4xx/5xx, rede, timeout) para a tela poder mostrar o aviso de AC-011.4; o timeout é de 15 s
 * EXATOS (14.999 ms ainda aguarda, 15.000 ms falha), medido com relógio simulado; CEP que não
 * tem 8 dígitos numéricos nem gera requisição; e a chamada ao ViaCEP NUNCA leva o `Authorization`
 * da nossa sessão (P5, D-12).
 *
 * Sem rede real nem espera real: HttpTestingController + `vi.useFakeTimers()`.
 */
describe('ViaCepService', () => {
  const CEP = '22041001';
  const URL_VIACEP = `https://viacep.com.br/ws/${CEP}/json/`;

  /** Resposta real do ViaCEP para um CEP de rua (traz campos que NÃO fazem parte do nosso endereço). */
  const RESPOSTA_RUA = {
    cep: '22041-001',
    logradouro: 'Rua Barata Ribeiro',
    complemento: 'de 1 ao fim',
    bairro: 'Copacabana',
    localidade: 'Rio de Janeiro',
    uf: 'RJ',
    ibge: '3304557',
    gia: '',
    ddd: '21',
    siafi: '6001',
  };

  /** Registro do que o Observable devolveu, para conferir valor, erro e conclusão. */
  interface Resultado {
    valores: EnderecoViaCep[];
    erros: unknown[];
    completou: boolean;
    inscricao: Subscription;
  }

  let service: ViaCepService;
  let http: HttpTestingController;

  /** Inscreve-se na consulta e guarda tudo o que acontece (valores, erros, conclusão). */
  function consultar(cep: string): Resultado {
    const resultado = { valores: [], erros: [], completou: false } as unknown as Resultado;
    resultado.inscricao = service.consultar(cep).subscribe({
      next: (valor) => resultado.valores.push(valor),
      error: (erro: unknown) => resultado.erros.push(erro),
      complete: () => (resultado.completou = true),
    });
    return resultado;
  }

  /** Confere que houve exatamente UM erro e que ele é um `ViaCepErro` com o motivo esperado. */
  function esperarErro(resultado: Resultado, motivo: ViaCepErro['motivo']): void {
    expect(resultado.valores).toEqual([]);
    expect(resultado.erros).toHaveLength(1);
    const erro = resultado.erros[0];
    expect(erro).toBeInstanceOf(ViaCepErro);
    expect(erro).toBeInstanceOf(Error);
    expect((erro as ViaCepErro).motivo).toBe(motivo);
  }

  describe('consulta (HttpTestingController)', () => {
    beforeEach(() => {
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      service = TestBed.inject(ViaCepService);
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      http.verify();
      vi.useRealTimers();
    });

    // O prazo de AC-011.4 (15 s) é uma constante exportada; os testes de tempo abaixo provam que
    // o serviço de fato a usa, mas a constante em si também precisa valer exatamente 15.000 ms.
    it('AC-011.4 o timeout exportado é de 15.000 ms (15 segundos)', () => {
      expect(VIACEP_TIMEOUT_MS).toBe(15000);
    });

    // A requisição vai ao host externo, com o CEP só em dígitos no caminho, por GET.
    it('D-27 chama GET https://viacep.com.br/ws/22041001/json/ (URL exata)', () => {
      consultar(CEP);

      const req = http.expectOne(URL_VIACEP);
      expect(req.request.method).toBe('GET');
      expect(req.request.url).toBe('https://viacep.com.br/ws/22041001/json/');
      req.flush(RESPOSTA_RUA);
    });

    // Sucesso: os quatro campos do nosso endereço saem de logradouro/bairro/localidade/uf e os
    // demais campos do ViaCEP (cep, complemento, ibge, ddd...) não vazam para o resultado.
    it('AC-011.3 em sucesso mapeia logradouro, bairro, localidade e uf para street, district, city e state', () => {
      const resultado = consultar(CEP);

      http.expectOne(URL_VIACEP).flush(RESPOSTA_RUA);

      expect(resultado.valores).toEqual([
        { street: 'Rua Barata Ribeiro', district: 'Copacabana', city: 'Rio de Janeiro', state: 'RJ' },
      ]);
      expect(resultado.erros).toEqual([]);
      expect(resultado.completou).toBe(true);
    });

    // CEP "geral" (cidade pequena): o ViaCEP devolve logradouro e bairro vazios. Isso é sucesso,
    // não falha: a pessoa só completa à mão o que faltou (e o campo continua editável, AC-011.3).
    it('AC-011.3 CEP geral com logradouro e bairro vazios é sucesso e preserva os vazios', () => {
      const resultado = consultar('24900000');

      http.expectOne('https://viacep.com.br/ws/24900000/json/').flush({
        cep: '24900-000',
        logradouro: '',
        complemento: '',
        bairro: '',
        localidade: 'Cidade de CEP Geral',
        uf: 'RJ',
      });

      expect(resultado.valores).toEqual([{ street: '', district: '', city: 'Cidade de CEP Geral', state: 'RJ' }]);
      expect(resultado.erros).toEqual([]);
    });

    // Cancelar a inscrição (o componente troca de CEP) tem de abortar a requisição HTTP, para o
    // resultado atrasado da consulta antiga nunca chegar a lugar nenhum.
    it('AC-011.3 cancelar a inscrição cancela a requisição HTTP em voo', () => {
      const resultado = consultar(CEP);
      const req = http.expectOne(URL_VIACEP);

      resultado.inscricao.unsubscribe();

      expect(req.cancelled).toBe(true);
      expect(resultado.valores).toEqual([]);
      expect(resultado.erros).toEqual([]);
    });

    // O ViaCEP responde 200 com {"erro": true} quando o CEP não existe: para nós é falha.
    it('AC-011.4 resposta {"erro": true} vira ViaCepErro NAO_ENCONTRADO', () => {
      const resultado = consultar(CEP);

      http.expectOne(URL_VIACEP).flush({ erro: true });

      esperarErro(resultado, 'NAO_ENCONTRADO');
    });

    // Versões antigas do ViaCEP mandavam o erro como texto: {"erro": "true"}. Mesmo tratamento.
    it('AC-011.4 resposta {"erro": "true"} (texto, versão antiga) vira ViaCepErro NAO_ENCONTRADO', () => {
      const resultado = consultar(CEP);

      http.expectOne(URL_VIACEP).flush({ erro: 'true' });

      esperarErro(resultado, 'NAO_ENCONTRADO');
    });

    // Falhas de HTTP/rede: cada linha é um jeito de o ViaCEP não responder direito. Em todas, o
    // chamador recebe um ViaCepErro de motivo REDE (e não um HttpErrorResponse cru), para a tela
    // tratar tudo pelo mesmo aviso de AC-011.4.
    it.each([
      // Linha 1: 404 do servidor.
      ['HTTP 404', { status: 404, statusText: 'Not Found' }],
      // Linha 2: 400 (o ViaCEP responde 400 para CEP malformado).
      ['HTTP 400', { status: 400, statusText: 'Bad Request' }],
      // Linha 3: 500 do servidor.
      ['HTTP 500', { status: 500, statusText: 'Server Error' }],
      // Linha 4: 503, serviço fora do ar.
      ['HTTP 503', { status: 503, statusText: 'Service Unavailable' }],
    ])('AC-011.4 %s vira ViaCepErro REDE', (_nome, opcoes) => {
      const resultado = consultar(CEP);

      http.expectOne(URL_VIACEP).flush({ mensagem: 'x' }, opcoes);

      esperarErro(resultado, 'REDE');
    });

    // Status 0 é o que o navegador reporta quando não há rede, CORS barrado ou DNS falho.
    it('AC-011.4 falha de rede (status 0) vira ViaCepErro REDE', () => {
      const resultado = consultar(CEP);

      http.expectOne(URL_VIACEP).error(new ProgressEvent('error'));

      esperarErro(resultado, 'REDE');
    });

    // O CEP só pode ser 8 dígitos numéricos (AC-011.2): qualquer outra coisa falha NA HORA, sem
    // ir ao ViaCEP (nada de mandar lixo do usuário para um terceiro, P5).
    it.each([
      // Linha 1: vazio.
      ['vazio', ''],
      // Linha 2: 7 dígitos (incompleto).
      ['7 dígitos', '2204100'],
      // Linha 3: 9 dígitos (longo demais).
      ['9 dígitos', '220410011'],
      // Linha 4: com máscara (o serviço só aceita dígitos).
      ['com máscara', '22041-001'],
      // Linha 5: com letra no meio.
      ['com letra', '2204100a'],
      // Linha 6: só letras.
      ['só letras', 'abcdefgh'],
      // Linha 7: espaço à esquerda.
      ['com espaço', ' 22041001'],
      // Linha 8: caminho malicioso tentando mudar a URL.
      ['com barra', '22041/01'],
    ])('AC-011.2 CEP %s não gera requisição e falha com NAO_ENCONTRADO', async (_nome, cep) => {
      const resultado = consultar(cep);
      await Promise.resolve();

      http.expectNone(() => true);
      esperarErro(resultado, 'NAO_ENCONTRADO');
    });
  });

  describe('timeout de 15 s (relógio simulado)', () => {
    beforeEach(() => {
      // O relógio falso tem de existir ANTES de a consulta ser assinada (o timeout nasce nela).
      vi.useFakeTimers();
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      service = TestBed.inject(ViaCepService);
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      http.verify();
      vi.useRealTimers();
    });

    // Fronteira de baixo: aos 14.999 ms a consulta continua pendente, sem erro e sem valor.
    it('AC-011.4 aos 14.999 ms sem resposta a consulta ainda aguarda', () => {
      const resultado = consultar(CEP);
      const req = http.expectOne(URL_VIACEP);

      vi.advanceTimersByTime(14_999);

      expect(resultado.erros).toEqual([]);
      expect(resultado.valores).toEqual([]);
      expect(resultado.completou).toBe(false);
      expect(req.cancelled).toBe(false);
      resultado.inscricao.unsubscribe();
    });

    // Fronteira de cima: aos 15.000 ms exatos o erro TIMEOUT chega, e a requisição é abandonada.
    it('AC-011.4 aos 15.000 ms sem resposta falha com ViaCepErro TIMEOUT e cancela a requisição', () => {
      const resultado = consultar(CEP);
      const req = http.expectOne(URL_VIACEP);

      // Passo 1: 14.999 ms ainda nada.
      vi.advanceTimersByTime(14_999);
      expect(resultado.erros).toEqual([]);

      // Passo 2: mais 1 ms (total 15.000) e o erro aparece.
      vi.advanceTimersByTime(1);
      esperarErro(resultado, 'TIMEOUT');
      expect(req.cancelled).toBe(true);
    });

    // Depois do timeout não pode haver um segundo erro nem valor tardio, por mais que o tempo passe.
    it('AC-011.4 depois do timeout não há erro duplo nem valor tardio', () => {
      const resultado = consultar(CEP);
      http.expectOne(URL_VIACEP);

      vi.advanceTimersByTime(15_000);
      vi.advanceTimersByTime(120_000);

      esperarErro(resultado, 'TIMEOUT');
    });

    // Responder a tempo (aos 14.999 ms) é sucesso, e o relógio não pode derrubar a consulta depois.
    it('AC-011.4 resposta aos 14.999 ms ainda é aceita e o timeout não dispara depois', () => {
      const resultado = consultar(CEP);
      const req = http.expectOne(URL_VIACEP);

      vi.advanceTimersByTime(14_999);
      req.flush(RESPOSTA_RUA);
      vi.advanceTimersByTime(60_000);

      expect(resultado.valores).toHaveLength(1);
      expect(resultado.erros).toEqual([]);
      expect(resultado.completou).toBe(true);
    });

    // O prazo conta a partir da requisição: duas consultas independentes têm cada uma os seus 15 s.
    it('AC-011.4 cada consulta tem o seu próprio prazo de 15 s', () => {
      // Passo 1: primeira consulta começa em t=0.
      const primeira = consultar(CEP);
      http.expectOne(URL_VIACEP);

      // Passo 2: segunda consulta começa em t=10 s.
      vi.advanceTimersByTime(10_000);
      const segunda = consultar('01001000');
      http.expectOne('https://viacep.com.br/ws/01001000/json/');

      // Passo 3: em t=15 s só a primeira expira.
      vi.advanceTimersByTime(5_000);
      esperarErro(primeira, 'TIMEOUT');
      expect(segunda.erros).toEqual([]);

      // Passo 4: em t=25 s expira a segunda.
      vi.advanceTimersByTime(10_000);
      esperarErro(segunda, 'TIMEOUT');
    });
  });

  describe('autenticação (authInterceptor real)', () => {
    let session: SessionService;
    let client: HttpClient;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([authInterceptor])),
          provideHttpClientTesting(),
          provideApi('/api/v1'),
          provideRouter([]),
        ],
      });
      service = TestBed.inject(ViaCepService);
      http = TestBed.inject(HttpTestingController);
      session = TestBed.inject(SessionService);
      client = TestBed.inject(HttpClient);
    });

    afterEach(() => http.verify());

    // P5/D-12: com a sessão iniciada, o token NUNCA pode ir parar no ViaCEP (terceiro). O
    // controle na mesma sessão prova que o interceptor está ativo e que o teste não é vazio.
    it('P5 com sessão iniciada a chamada ao ViaCEP NÃO leva Authorization (a da API leva)', () => {
      session.start('token-de-teste');

      // Passo 1: controle: a nossa API recebe o token (interceptor funcionando).
      client.get('/api/v1/me').subscribe();
      const reqApi: TestRequest = http.expectOne('/api/v1/me');
      expect(reqApi.request.headers.get('Authorization')).toBe('Bearer token-de-teste');
      reqApi.flush({});

      // Passo 2: a consulta de CEP sai sem o cabeçalho e sem cookies (credentials).
      consultar(CEP);
      const reqCep = http.expectOne(URL_VIACEP);
      expect(reqCep.request.headers.has('Authorization')).toBe(false);
      expect(reqCep.request.withCredentials).toBe(false);
      reqCep.flush(RESPOSTA_RUA);
    });
  });
});
