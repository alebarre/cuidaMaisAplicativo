import { Injectable, signal } from '@angular/core';
import { normalizarEmail } from './validadores';

/**
 * "Memória curta" do cadastro do criador do grupo (T-052, AC-001.1, AC-001.6, P5).
 *
 * Leva o e-mail digitado em "Criar grupo de cuidado" até a tela de confirmação do e-mail, para a
 * pessoa não precisar digitá-lo de novo, e liga o aviso de 24 horas (AC-001.1) somente nesse
 * caminho. O login (T-053) usa `definirEmail` para abrir a confirmação com o e-mail já
 * preenchido, mas SEM o aviso: quem só quer confirmar o e-mail não deve ver "Confirme em até 24
 * horas" de novo.
 *
 * Tudo vive apenas em memória (signals): nada vai para storage, cookie, URL ou estado do
 * roteador, e o serviço não faz nenhuma chamada HTTP (P5). A senha NUNCA passa por aqui.
 */
@Injectable({ providedIn: 'root' })
export class FluxoCadastroService {
  private readonly emailAtual = signal('');
  private readonly avisoLigado = signal(false);

  /** E-mail em andamento, já normalizado; vazio quando não há cadastro ou confirmação em curso. */
  readonly email = this.emailAtual.asReadonly();

  /** Verdadeiro quando a tela de confirmação deve mostrar o aviso de 24 horas (AC-001.1). */
  readonly avisoPendente = this.avisoLigado.asReadonly();

  /**
   * Registra um cadastro recém-aceito (202): guarda o e-mail normalizado (aparado e em
   * minúsculas, como o backend) e liga o aviso de 24 horas. Um novo cadastro substitui o anterior.
   */
  iniciar(email: string): void {
    this.emailAtual.set(normalizarEmail(email));
    this.avisoLigado.set(true);
  }

  /**
   * Guarda só o e-mail normalizado, sem ligar o aviso de 24 horas. Usado pelo login (T-053) para
   * abrir a confirmação de quem já tem cadastro pendente (AC-001.6).
   */
  definirEmail(email: string): void {
    this.emailAtual.set(normalizarEmail(email));
    this.avisoLigado.set(false);
  }

  /** Esquece tudo (e-mail e aviso): chamado depois que a confirmação dá certo (AC-001.2). */
  limpar(): void {
    this.emailAtual.set('');
    this.avisoLigado.set(false);
  }
}
