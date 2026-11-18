import { Injectable } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { CODIGO_PARA_CHAVE } from './codigos';
import { ChaveMensagem, MENSAGENS } from './mensagens';

/** Valores que preenchem os marcadores `{nome}` de um texto (ex.: `{ restantes: 3 }`). */
export type ParametrosMensagem = Readonly<Record<string, string | number>>;

/**
 * Porta única de acesso das telas ao catálogo de mensagens pt-BR (T-061, D-40).
 *
 * Faz três coisas: devolve o texto de uma chave preenchendo os marcadores `{restantes}` e
 * `{tempo}` (AC-004.7); traduz o `code` do Problem Details do backend para uma chave do
 * catálogo; e transforma um erro HTTP qualquer em texto de tela. O texto sempre vem do
 * catálogo: o `detail`/`title` do servidor NUNCA é mostrado (D-40, P5), e `textoDoErro` nunca
 * lança, para que a tela de erro jamais quebre por causa do próprio erro.
 */
@Injectable({ providedIn: 'root' })
export class MensagemService {
  /**
   * Devolve o texto da chave com os marcadores `{nome}` trocados pelos parâmetros. O valor entra
   * literalmente (função de substituição evita que `$&` e similares sejam interpretados). Falta
   * de parâmetro para um marcador lança `Error` citando-o, para nunca exibir `{restantes}` ao
   * usuário; parâmetro sem marcador correspondente é ignorado.
   */
  texto(chave: ChaveMensagem, params?: ParametrosMensagem): string {
    return MENSAGENS[chave].replace(/\{([^}]+)\}/g, (_marcador, nome: string) => {
      // 1. O parâmetro precisa existir como propriedade própria (nada herdado do protótipo).
      if (params === undefined || !Object.hasOwn(params, nome)) {
        throw new Error(`Falta o parâmetro "${nome}" para a mensagem ${chave}`);
      }
      // 2. Números viram texto; o valor é inserido sem interpretação de padrões especiais.
      return String(params[nome]);
    });
  }

  /**
   * Traduz o `code` do Problem Details para a chave do catálogo. Code desconhecido, vazio,
   * ausente ou com nome herdado do protótipo (`constructor`...) cai em `FALLBACK_ERRO_GENERICO`.
   */
  chaveDoCodigo(code: string | null | undefined): ChaveMensagem {
    if (typeof code === 'string' && Object.hasOwn(CODIGO_PARA_CHAVE, code)) {
      return CODIGO_PARA_CHAVE[code];
    }
    return 'FALLBACK_ERRO_GENERICO';
  }

  /**
   * Converte qualquer erro em texto de tela, sem nunca lançar e sem nunca expor o texto do
   * servidor. Só um `HttpErrorResponse` com corpo `{ code: string }` é aproveitado; o resto
   * (rede, corpo vazio, texto puro, outro tipo de erro) vira o fallback. Se o texto da chave
   * exigir um marcador não informado em `params`, devolve o fallback em vez de lançar.
   */
  textoDoErro(erro: unknown, params?: ParametrosMensagem): string {
    // 1. Extrai o `code` apenas de um HttpErrorResponse com corpo objeto e `code` string.
    const code = this.extrairCodigo(erro);
    const chave = this.chaveDoCodigo(code);
    // 2. Monta o texto do catálogo; marcador faltando não pode quebrar a tela de erro.
    try {
      return this.texto(chave, params);
    } catch {
      return MENSAGENS.FALLBACK_ERRO_GENERICO;
    }
  }

  /**
   * Lê `error.code` de um `HttpErrorResponse` (Problem Details do contrato). Devolve `undefined`
   * para qualquer outra forma de erro ou quando `code` não for string.
   */
  private extrairCodigo(erro: unknown): string | undefined {
    if (!(erro instanceof HttpErrorResponse)) {
      return undefined;
    }
    const corpo: unknown = erro.error;
    if (typeof corpo !== 'object' || corpo === null || !('code' in corpo)) {
      return undefined;
    }
    const code = (corpo as { code: unknown }).code;
    return typeof code === 'string' ? code : undefined;
  }
}
