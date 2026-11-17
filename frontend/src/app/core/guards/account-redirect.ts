import { inject } from '@angular/core';
import { GuardResult, Router } from '@angular/router';
import { Observable, map, of, switchMap } from 'rxjs';
import { AccountState, AccountStateValue } from '../api';
import { AccountService } from '../auth/account.service';
import { SessionService } from '../auth/session.service';
import { ROTAS } from '../routes';

/**
 * Regra de um guard para uma conta `ATIVO`: devolve `true` para liberar a rota ou o caminho
 * para onde mandar a pessoa.
 */
export type RegraContaAtiva = (conta: AccountState) => true | string;

/**
 * Tabela de AC-012.1 / D-33 para os estados que NÃO são `ATIVO`, comum a todos os guards:
 * cada estado tem a tela do seu momento; recusado, expirado e removido encerram a sessão.
 */
function destinoFora(estado: AccountStateValue): { rota: string; encerrarSessao: boolean } {
  switch (estado) {
    case AccountStateValue.AguardandoConfirmacaoEmail:
      return { rota: ROTAS.CONFIRMAR_EMAIL, encerrarSessao: false };
    case AccountStateValue.AguardandoAprovacao:
      return { rota: ROTAS.AGUARDANDO_APROVACAO, encerrarSessao: false };
    case AccountStateValue.Recusado:
    case AccountStateValue.Expirado:
    case AccountStateValue.Removido:
      return { rota: ROTAS.LOGIN, encerrarSessao: true };
    default:
      // Estado que o app não conhece (contrato mais novo): nunca libera, manda ao login.
      return { rota: ROTAS.LOGIN, encerrarSessao: false };
  }
}

/**
 * Núcleo comum dos guards da área logada (T-050, D-20, D-33, AC-012.1, AC-001.7).
 *
 * A cada ativação: (1) garante a sessão (restaura pelo cookie de refresh se a página foi
 * recarregada, D-12); sem sessão, login. (2) Consulta `GET /me/account` de novo, porque a
 * remoção vale na hora (D-13); falha na consulta, login. (3) Decide pelo estado da conta: fora
 * de `ATIVO` aplica a tabela comum (encerrando a sessão quando recusado/expirado/removido);
 * `ATIVO` delega à `regra` de cada guard (preso no cadastro do idoso, home, admin).
 *
 * Só dá experiência de uso: o backend revalida tudo (D-20, D-28). Deve ser chamada dentro de
 * um contexto de injeção (corpo de um `CanActivateFn`).
 */
export function guardarPorConta(regra: RegraContaAtiva): Observable<GuardResult> {
  const session = inject(SessionService);
  const account = inject(AccountService);
  const router = inject(Router);

  /** Converte um caminho em UrlTree, o formato de redirecionamento dos guards. */
  const ir = (rota: string): GuardResult => router.parseUrl(rota);

  return session.restore().pipe(
    switchMap((temSessao) => {
      // Passo 1: sem sessão não há o que consultar, vai ao login.
      if (!temSessao) {
        return of(ir(ROTAS.LOGIN));
      }
      // Passo 2: lê o estado atual da conta no servidor.
      return account.refresh().pipe(
        map((conta): GuardResult => {
          // Passo 3: falha ao consultar a conta, na dúvida login.
          if (!conta) {
            return ir(ROTAS.LOGIN);
          }
          // Passo 4: ATIVO segue a regra do guard específico.
          if (conta.state === AccountStateValue.Ativo) {
            const decisao = regra(conta);
            return decisao === true ? true : ir(decisao);
          }
          // Passo 5: demais estados seguem a tabela comum; alguns encerram a sessão.
          const { rota, encerrarSessao } = destinoFora(conta.state);
          if (encerrarSessao) {
            session.clear();
            account.clear();
          }
          return ir(rota);
        }),
      );
    }),
  );
}
