import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { API_BASE_PATH } from '../api-base-path';
import { ROTAS } from '../routes';
import { AccountService } from './account.service';
import { SessionService } from './session.service';

/** Rotas da API que não usam o access token (contrato com `security: []` ou refresh por cookie). */
const ROTAS_PUBLICAS_EXATAS = [`${API_BASE_PATH}/invitations/redeem`, `${API_BASE_PATH}/invitations/register`];

/** Prefixo de autenticação: público, exceto o logout, que exige bearer (AC-008.5). */
const PREFIXO_AUTH = `${API_BASE_PATH}/auth/`;
const URL_LOGOUT = `${API_BASE_PATH}/auth/logout`;

/**
 * Devolve o caminho da URL sem a query string, para comparar com os caminhos da API.
 */
function caminhoDe(url: string): string {
  return url.split(/[?#]/)[0];
}

/**
 * Diz se a URL é da nossa API: só caminhos relativos que começam em `/api/v1`. URLs absolutas
 * (ViaCEP, outro host com caminho parecido) nunca são nossas, então nunca recebem o token (D-12).
 */
function ehDaApi(url: string): boolean {
  const caminho = caminhoDe(url);
  return caminho === API_BASE_PATH || caminho.startsWith(`${API_BASE_PATH}/`);
}

/**
 * Diz se a rota é pública: tudo sob `/auth/` menos o logout, mais resgate e cadastro por convite.
 * Nelas não se anexa token e um 401 tem significado próprio (ex.: login inválido, AC-008.1).
 */
function ehPublica(url: string): boolean {
  const caminho = caminhoDe(url);
  if (caminho === URL_LOGOUT) {
    return false;
  }
  return caminho.startsWith(PREFIXO_AUTH) || ROTAS_PUBLICAS_EXATAS.includes(caminho);
}

/**
 * Clona a requisição com `Authorization: Bearer <token>`; sem token devolve a original intacta
 * (nada de "Bearer null").
 */
function comToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/**
 * Interceptor de autenticação da API (T-050, D-11, D-12, AC-008.3, AC-008.5).
 *
 * Anexa o access token (só em memória) às chamadas para `/api/v1` e, quando uma rota protegida
 * responde 401 por token expirado, renova a sessão por `/auth/refresh` e reenvia a requisição
 * original UMA vez com o token novo. A renovação é compartilhada entre requisições simultâneas
 * (single-flight no `SessionService`), porque o refresh token rotaciona e o reuso revoga a
 * família (D-11). Se a renovação falha, encerra a sessão, leva ao login e propaga o erro original.
 *
 * Não mexe em rotas públicas, em `POST /auth/logout` (anexa o token mas nunca renova num 401),
 * em URLs de terceiros nem em erros que não sejam 401. O backend continua revalidando tudo (D-20).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Passo 1: fora da nossa API não se anexa token nem se tenta renovar nada.
  if (!ehDaApi(req.url)) {
    return next(req);
  }

  const session = inject(SessionService);

  // Passo 2: rotas públicas seguem como vieram, e o 401 delas chega intacto ao chamador.
  if (ehPublica(req.url)) {
    return next(req);
  }

  const ehLogout = caminhoDe(req.url) === URL_LOGOUT;
  const account = inject(AccountService);
  const router = inject(Router);

  // Passo 3: rota protegida: envia com o token atual.
  return next(comToken(req, session.accessToken())).pipe(
    catchError((erro: unknown) => {
      // Passo 4: só um 401 de rota protegida (e que não seja o logout) pede renovação.
      if (!(erro instanceof HttpErrorResponse) || erro.status !== 401 || ehLogout) {
        return throwError(() => erro);
      }

      // Passo 5: renova (uma renovação só, mesmo com vários 401 juntos). Se falhar, a sessão
      // acaba: limpa o estado em memória, vai ao login e o chamador recebe o erro ORIGINAL.
      return session.refresh().pipe(
        catchError(() => {
          session.clear();
          account.clear();
          void router.navigateByUrl(ROTAS.LOGIN);
          return throwError(() => erro);
        }),
        // Passo 6: reenvia a original com o token novo; um 2º 401 aqui não renova de novo.
        switchMap((novoToken) => next(comToken(req, novoToken))),
      );
    }),
  );
};
