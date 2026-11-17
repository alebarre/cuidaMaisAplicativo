import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, ReplaySubject, catchError, map, of } from 'rxjs';
import { AuthService } from '../api';

/**
 * Dono da sessão do navegador (T-050, D-11, D-12, AC-008.3, AC-008.5).
 *
 * Guarda o access token SOMENTE em memória (um signal): nunca em localStorage, sessionStorage
 * nem cookie escrito pelo JavaScript, para que um XSS não consiga roubá-lo (D-12). O refresh
 * token vive num cookie HttpOnly que o navegador envia sozinho; este serviço apenas pede a
 * renovação em `POST /auth/refresh` pelo cliente gerado.
 *
 * Como o refresh token rotaciona e a reapresentação de um token já usado revoga a família
 * inteira (D-11), a renovação é "single-flight": chamadas simultâneas (várias requisições com
 * 401 ao mesmo tempo, ou dois guards) compartilham UMA requisição HTTP. `restore()` reabre a
 * sessão depois de recarregar a página e tenta no máximo uma vez por carga.
 *
 * É a única peça que conhece o token; o interceptor e os guards só dependem dela.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly auth = inject(AuthService);

  /** Access token atual; `null` quando ninguém está logado. Nunca persistido (D-12). */
  private readonly token = signal<string | null>(null);

  /** Leitura pública do token para o interceptor anexar ao cabeçalho Authorization. */
  readonly accessToken = this.token.asReadonly();

  /** Verdadeiro enquanto existe um access token em memória. */
  readonly isAuthenticated = computed(() => this.token() !== null);

  /** Renovação em andamento, compartilhada por todos os chamadores (D-11); `null` se não há. */
  private renovando: Observable<string> | null = null;

  /** Tentativa de restauração em andamento, compartilhada entre `restore()` simultâneos. */
  private restaurando: Observable<boolean> | null = null;

  /** Verdadeiro depois que a restauração desta carga já foi tentada (ou a sessão foi encerrada). */
  private restauracaoTentada = false;

  /**
   * Inicia a sessão com o access token recebido no login (ou numa renovação). Substitui o token
   * anterior e fica só em memória.
   */
  start(accessToken: string): void {
    this.token.set(accessToken);
  }

  /**
   * Encerra a sessão local ("Sair" ou sessão inválida, AC-008.5): esquece o token e marca a
   * restauração como tentada, para que nenhum guard tente reabrir a sessão por conta própria
   * logo depois de a pessoa sair.
   */
  clear(): void {
    this.token.set(null);
    this.restauracaoTentada = true;
  }

  /**
   * Renova o access token pelo cookie de refresh (`POST /auth/refresh`, AC-008.3).
   *
   * É single-flight (D-11): se já há uma renovação em voo, devolve a mesma; senão dispara uma
   * só requisição e compartilha o resultado com todos os assinantes. Em sucesso grava o token
   * novo na sessão; em falha propaga o erro HTTP e libera a próxima tentativa.
   */
  refresh(): Observable<string> {
    // Passo 1: reaproveita a renovação em andamento, se houver.
    if (this.renovando) {
      return this.renovando;
    }

    // Passo 2: dispara a requisição já agora (eager) e a compartilha por um ReplaySubject, de
    // modo que quem assinar depois, ainda em voo, receba o mesmo resultado.
    const resultado = new ReplaySubject<string>(1);
    const compartilhado = resultado.asObservable();
    this.renovando = compartilhado;

    this.auth
      .refreshSession()
      .pipe(map((resposta) => resposta.accessToken))
      .subscribe({
        next: (novoToken) => {
          // Passo 3a: sucesso, o token novo passa a ser o da sessão.
          this.start(novoToken);
          this.renovando = null;
          resultado.next(novoToken);
          resultado.complete();
        },
        error: (erro) => {
          // Passo 3b: falha, nenhuma renovação fica "presa" e o erro vai a todos os assinantes.
          this.renovando = null;
          resultado.error(erro);
        },
      });

    return compartilhado;
  }

  /**
   * Garante uma sessão de pé ao ativar uma rota (D-12). Nunca lança erro; devolve `true` se há
   * (ou foi recuperada) uma sessão e `false` se a pessoa precisa entrar de novo.
   *
   * Com token em memória responde `true` sem rede. Sem token tenta renovar UMA vez por carga:
   * `restore()` simultâneos compartilham a mesma tentativa em voo e o mesmo resultado, e depois
   * de uma tentativa que falhou as chamadas seguintes respondem `false` sem repetir o HTTP.
   */
  restore(): Observable<boolean> {
    // Passo 1: já há sessão em memória, não precisa de rede.
    if (this.token() !== null) {
      return of(true);
    }
    // Passo 2: há uma tentativa em voo, todos compartilham o resultado dela.
    if (this.restaurando) {
      return this.restaurando;
    }
    // Passo 3: a tentativa desta carga já terminou sem sucesso (ou a pessoa saiu).
    if (this.restauracaoTentada) {
      return of(false);
    }

    // Passo 4: primeira tentativa, via renovação (que já é single-flight).
    const resultado = new ReplaySubject<boolean>(1);
    const compartilhado = resultado.asObservable();
    this.restaurando = compartilhado;

    this.refresh()
      .pipe(
        map(() => true),
        catchError(() => of(false)),
      )
      .subscribe((ok) => {
        this.restauracaoTentada = true;
        this.restaurando = null;
        resultado.next(ok);
        resultado.complete();
      });

    return compartilhado;
  }
}
