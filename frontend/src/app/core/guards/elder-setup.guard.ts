import { CanActivateFn } from '@angular/router';
import { ROTAS } from '../routes';
import { guardarPorConta } from './account-redirect';

/**
 * Guard da rota de cadastro do idoso (T-050, D-20, D-33, AC-001.7).
 *
 * Só o criador `ATIVO` SEM grupo permanece aqui; `ATIVO` com grupo já tem idoso (um por grupo,
 * AC-002.1) e vai para a home. Os demais estados seguem a tabela comum em `account-redirect.ts`.
 * É só experiência de uso: o backend sempre revalida (D-20).
 */
export const elderSetupGuard: CanActivateFn = () =>
  guardarPorConta((conta) => (conta.hasGroup ? ROTAS.HOME : true));
