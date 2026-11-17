/**
 * Prefixo da API do Cuida+ (D-19). Usado em dois lugares que precisam concordar: o `provideApi`
 * do cliente gerado (em `app.config.ts`) e o `authInterceptor`, que só anexa o access token a
 * URLs que começam por este prefixo, para nunca vazar o token a terceiros como o ViaCEP (D-12).
 */
export const API_BASE_PATH = '/api/v1';
