import { ApplicationConfig, provideBrowserGlobalErrorListeners, isDevMode } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideServiceWorker } from '@angular/service-worker';
import { provideApi } from './core/api/provide-api';
import { API_BASE_PATH } from './core/api-base-path';
import { authInterceptor } from './core/auth/auth.interceptor';

/**
 * Configuração global do app: erros globais, roteador, HTTP com o interceptor de autenticação
 * (token em memória e renovação no 401, D-11/D-12), base `/api/v1` do cliente gerado (D-19) e o
 * service worker só para o app shell (D-22: nenhuma chamada da API entra em cache).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideApi(API_BASE_PATH),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
