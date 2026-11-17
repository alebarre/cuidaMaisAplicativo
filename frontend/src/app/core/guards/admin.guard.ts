import { CanActivateFn } from '@angular/router';
import { ROTAS } from '../routes';
import { guardarPorConta } from './account-redirect';

/**
 * Guard das rotas só do Responsável: convites, pedidos, aprovação, exclusão do grupo
 * (T-050, D-20, D-33, AC-015.1).
 *
 * Libera `ATIVO` com grupo e `isAdmin=true`; quem é `ATIVO` com grupo mas não é admin (Cuidador
 * ou Familiar comum) vai à home; `ATIVO` sem grupo continua preso no cadastro do idoso
 * (AC-001.7, que tem precedência). Os demais estados seguem a tabela comum em
 * `account-redirect.ts`. É só experiência de uso: o backend responde 403 a quem não pode
 * (AC-015.2) e sempre revalida (D-20).
 */
export const adminGuard: CanActivateFn = () =>
  guardarPorConta((conta) => {
    if (!conta.hasGroup) {
      return ROTAS.CADASTRO_IDOSO;
    }
    return conta.isAdmin ? true : ROTAS.HOME;
  });
