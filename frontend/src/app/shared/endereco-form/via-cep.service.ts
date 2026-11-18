import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, TimeoutError, catchError, map, throwError, timeout } from 'rxjs';

/** Prazo máximo, em milissegundos, para o ViaCEP responder (AC-011.4, D-27: 15 segundos). */
export const VIACEP_TIMEOUT_MS = 15000;

/** Endereço base do ViaCEP, serviço público externo consultado direto do navegador (D-27 rev. 2). */
const URL_BASE_VIACEP = 'https://viacep.com.br/ws';

/** Os oito dígitos numéricos (ASCII) que formam um CEP; qualquer outra coisa nem sai do aparelho. */
const PADRAO_CEP = /^\d{8}$/;

/**
 * Parte do endereço que o ViaCEP sabe preencher (AC-011.3): logradouro, bairro, cidade e UF, já
 * com os nomes dos campos do `Address` do nosso contrato. Número e complemento ficam com a pessoa.
 */
export interface EnderecoViaCep {
  /** Logradouro (`logradouro` do ViaCEP); vazio em CEP "geral" de cidade pequena. */
  street: string;
  /** Bairro (`bairro` do ViaCEP); vazio em CEP "geral". */
  district: string;
  /** Cidade (`localidade` do ViaCEP). */
  city: string;
  /** Sigla da UF (`uf` do ViaCEP). */
  state: string;
}

/**
 * Falha da consulta de CEP (AC-011.4). Reúne todos os jeitos de a consulta não dar certo num único
 * tipo, para a tela mostrar sempre o mesmo aviso do catálogo sem olhar detalhes do HTTP:
 * `NAO_ENCONTRADO` (o ViaCEP respondeu `{"erro": true}` ou o CEP é malformado), `TIMEOUT` (sem
 * resposta em 15 s) e `REDE` (erro HTTP ou de conexão).
 */
export class ViaCepErro extends Error {
  /** Por que a consulta falhou; a tela não distingue, mas os testes e os logs sim. */
  readonly motivo: 'NAO_ENCONTRADO' | 'TIMEOUT' | 'REDE';

  /**
   * Cria o erro com o motivo informado. A mensagem é só técnica (nunca vai à tela, D-40) e não
   * carrega o CEP, que é dado pessoal (P5).
   */
  constructor(motivo: ViaCepErro['motivo']) {
    super(`Falha na consulta de CEP: ${motivo}`);
    this.name = 'ViaCepErro';
    this.motivo = motivo;
  }
}

/** Forma (parcial) da resposta do ViaCEP; todos os campos são opcionais porque vêm de fora. */
interface RespostaViaCep {
  logradouro?: unknown;
  bairro?: unknown;
  localidade?: unknown;
  uf?: unknown;
  erro?: unknown;
}

/**
 * Consulta de CEP no ViaCEP (T-051, AC-011.3, AC-011.4, D-27 rev. 2).
 *
 * Usado pelo `<app-endereco-form>` para preencher logradouro, bairro, cidade e UF quando a
 * pessoa completa os 8 dígitos. A chamada vai DIRETO do navegador ao ViaCEP, com o `HttpClient`
 * do Angular (e não pelo cliente gerado da API, que é só do nosso backend): sem cabeçalhos extras
 * e sem credenciais, e o `authInterceptor` não anexa o token a URLs de terceiros (P5, D-12).
 * Qualquer falha vira um `ViaCepErro`; o backend nunca depende deste serviço.
 */
@Injectable({ providedIn: 'root' })
export class ViaCepService {
  private readonly http = inject(HttpClient);

  /**
   * Consulta o CEP (somente 8 dígitos, sem máscara) e emite uma vez o endereço encontrado.
   * Falha com `ViaCepErro` `NAO_ENCONTRADO` quando o CEP é malformado (sem sair do aparelho) ou
   * quando o ViaCEP responde `erro` verdadeiro (boolean ou o texto `"true"` de versões antigas);
   * `TIMEOUT` quando nada chega em 15.000 ms (a requisição é abortada); e `REDE` em erro de
   * HTTP ou de conexão. Cancelar a inscrição aborta a requisição em voo.
   */
  consultar(cep: string): Observable<EnderecoViaCep> {
    // 1. CEP fora do formato: falha na hora, sem mandar lixo do usuário a um terceiro.
    if (!PADRAO_CEP.test(cep)) {
      return throwError(() => new ViaCepErro('NAO_ENCONTRADO'));
    }

    return this.http.get<RespostaViaCep | null>(`${URL_BASE_VIACEP}/${cep}/json/`).pipe(
      // 2. O prazo conta a partir da assinatura; estourado, o HttpClient aborta a requisição.
      timeout(VIACEP_TIMEOUT_MS),
      // 3. Traduz o corpo: `erro` verdadeiro ou corpo inesperado é "não encontrado".
      map((resposta) => this.paraEndereco(resposta)),
      // 4. Todo erro que não é nosso vira ViaCepErro (timeout ou rede).
      catchError((erro: unknown) => throwError(() => this.paraViaCepErro(erro))),
    );
  }

  /**
   * Converte o corpo do ViaCEP nos quatro campos do nosso endereço, ignorando o resto (cep,
   * complemento, ibge, ddd...). Lança `ViaCepErro` `NAO_ENCONTRADO` quando o corpo não é um
   * objeto ou traz `erro` como `true` ou `"true"`.
   */
  private paraEndereco(resposta: RespostaViaCep | null): EnderecoViaCep {
    // 1. Corpo que não é objeto não serve para preencher nada.
    if (typeof resposta !== 'object' || resposta === null) {
      throw new ViaCepErro('NAO_ENCONTRADO');
    }
    // 2. O ViaCEP responde 200 com {"erro": true} (ou "true") quando o CEP não existe.
    if (resposta.erro === true || resposta.erro === 'true') {
      throw new ViaCepErro('NAO_ENCONTRADO');
    }
    // 3. Só os quatro campos do nosso endereço; ausência vira texto vazio.
    return {
      street: this.texto(resposta.logradouro),
      district: this.texto(resposta.bairro),
      city: this.texto(resposta.localidade),
      state: this.texto(resposta.uf),
    };
  }

  /** Devolve o valor se for texto e, caso contrário, string vazia (o ViaCEP é fonte externa). */
  private texto(valor: unknown): string {
    return typeof valor === 'string' ? valor : '';
  }

  /**
   * Classifica um erro qualquer do fluxo: um `ViaCepErro` passa como está; o estouro do prazo
   * (`TimeoutError` do RxJS) vira `TIMEOUT`; o resto (HTTP 4xx/5xx, status 0) vira `REDE`.
   */
  private paraViaCepErro(erro: unknown): ViaCepErro {
    if (erro instanceof ViaCepErro) {
      return erro;
    }
    return new ViaCepErro(erro instanceof TimeoutError ? 'TIMEOUT' : 'REDE');
  }
}
