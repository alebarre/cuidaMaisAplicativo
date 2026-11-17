import { CanActivateFn } from '@angular/router';
import { ROTAS } from '../routes';
import { guardarPorConta } from './account-redirect';

/**
 * Guard da área logada (T-050, D-20, D-33, AC-012.1, AC-001.7).
 *
 * Só `ATIVO` com grupo entra. `ATIVO` sem grupo fica PRESO no cadastro do idoso (a sessão é
 * mantida para ele cadastrar e para o "Sair"); os demais estados vão para a tela do seu
 * momento ou ao login, conforme a tabela comum em `account-redirect.ts`. É só experiência de
 * uso: o backend sempre revalida (D-20).
 */
export const memberAreaGuard: CanActivateFn = () =>
  guardarPorConta((conta) => (conta.hasGroup ? true : ROTAS.CADASTRO_IDOSO));
