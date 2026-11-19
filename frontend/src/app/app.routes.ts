import { Routes } from '@angular/router';

/**
 * Tabela de rotas do app. Por ora traz as duas telas PÚBLICAS do cadastro do criador do grupo
 * (T-052, AC-001.1, AC-001.6, D-20): `criar-grupo` e `confirmar-email`. Ambas são carregadas
 * sob demanda (`loadComponent`) para o bundle inicial não incluir telas que nem todo visitante
 * usa, e não têm guards nem redirecionamentos: quem ainda não tem conta (ou ainda não confirmou
 * o e-mail) precisa abri-las sem sessão, e as regras de acesso quem manda é o backend (D-20).
 * Os caminhos coincidem com `ROTAS.CRIAR_GRUPO` e `ROTAS.CONFIRMAR_EMAIL`, que guards e
 * interceptor usam para redirecionar.
 */
export const routes: Routes = [
  {
    path: 'criar-grupo',
    loadComponent: () => import('./auth/criar-grupo/criar-grupo.component').then((m) => m.CriarGrupoComponent),
  },
  {
    path: 'confirmar-email',
    loadComponent: () =>
      import('./auth/confirmar-email/confirmar-email.component').then((m) => m.ConfirmarEmailComponent),
  },
];
