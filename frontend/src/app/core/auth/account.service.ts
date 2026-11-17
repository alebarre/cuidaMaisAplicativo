import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, of, tap } from 'rxjs';
import { AccountState, MeService } from '../api';

/**
 * Guarda o estado da conta lido do servidor (T-050, D-20, D-33, AC-012.1, AC-015.1).
 *
 * O estado (`ATIVO`, `AGUARDANDO_APROVACAO`...), o papel e o `isAdmin` vêm de
 * `GET /me/account` pelo cliente gerado e ficam num signal, para que guards e a diretiva
 * `*appAdminOnly` leiam sem nova chamada. Cada `refresh()` consulta o servidor de novo, porque
 * o estado muda a qualquer momento e remoção/rebaixamento valem na hora (D-13). Em qualquer
 * falha o estado fica vazio: na dúvida, sem acesso e sem admin. Nenhuma regra de permissão é
 * decidida aqui; só se exibe o que a API devolve (D-20, D-28).
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly me = inject(MeService);

  /** Última conta devolvida pela API; `null` quando desconhecida ou após falha/saída. */
  private readonly estado = signal<AccountState | null>(null);

  /** Leitura pública do estado da conta. */
  readonly account = this.estado.asReadonly();

  /** Verdadeiro só quando a API devolveu exatamente `isAdmin === true` (Responsável, AC-015.1). */
  readonly isAdmin = computed(() => this.estado()?.isAdmin === true);

  /** Papel da conta no grupo (`FAMILIAR`/`CUIDADOR`) ou `null` sem vínculo/conta. */
  readonly role = computed(() => this.estado()?.role ?? null);

  /**
   * Consulta `GET /me/account` (sem cache, uma requisição por chamada), grava o resultado no
   * signal e o devolve. Qualquer erro (401, 500, rede) vira `null` e esquece o estado anterior,
   * para que uma conta removida não continue parecendo admin.
   */
  refresh(): Observable<AccountState | null> {
    return this.me.getMyAccountState().pipe(
      tap((conta) => this.estado.set(conta)),
      catchError(() => {
        this.estado.set(null);
        return of(null);
      }),
    );
  }

  /** Esquece a conta (sair/sessão encerrada, AC-008.5): nenhum resquício de admin na tela. */
  clear(): void {
    this.estado.set(null);
  }
}
