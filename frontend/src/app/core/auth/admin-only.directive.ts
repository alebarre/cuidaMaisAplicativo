import { Directive, TemplateRef, ViewContainerRef, effect, inject } from '@angular/core';
import { AccountService } from './account.service';

/**
 * Diretiva estrutural `*appAdminOnly` (T-050, AC-015.1, D-20, D-13).
 *
 * Renderiza o conteúdo SOMENTE quando `AccountService.isAdmin()` é verdadeiro (o Responsável).
 * Para os demais as ações vedadas (convidar, aprovar, recusar, alterar papel, remover, editar o
 * idoso, excluir o grupo) simplesmente não existem no DOM; não são só escondidas por CSS. Como
 * lê um signal, reage na hora quando a conta muda (rebaixada, removida, saída) sem recarregar.
 *
 * É só experiência de uso: o backend revalida toda requisição e responde 403 a quem não pode
 * (AC-015.2), então esta diretiva não implementa regra de permissão.
 */
@Directive({ selector: '[appAdminOnly]' })
export class AdminOnlyDirective {
  private readonly modelo = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  private readonly account = inject(AccountService);

  /** Evita recriar a view a cada reavaliação do efeito quando nada mudou. */
  private visivel = false;

  /**
   * Registra o efeito que acompanha `isAdmin()`: cria a view quando passa a ser admin e a
   * destrói quando deixa de ser, mantendo o DOM sempre igual ao que a API autoriza.
   */
  constructor() {
    effect(() => {
      const admin = this.account.isAdmin();
      if (admin && !this.visivel) {
        this.container.createEmbeddedView(this.modelo);
        this.visivel = true;
      } else if (!admin && this.visivel) {
        this.container.clear();
        this.visivel = false;
      }
    });
  }
}
