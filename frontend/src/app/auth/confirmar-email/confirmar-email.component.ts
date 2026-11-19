import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService, ResendCodeRequest } from '../../core/api';
import { SessionService } from '../../core/auth/session.service';
import { MensagemService } from '../../core/messages/mensagem.service';
import { ROTAS } from '../../core/routes';
import { FluxoCadastroService } from '../fluxo-cadastro.service';
import { emailEhValido, normalizarEmail } from '../validadores';

/** Quantidade de dígitos do código de confirmação (AC-005.1). */
const DIGITOS_DO_CODIGO = 6;

/** Qual chamada está em voo: serve para travar os dois botões e marcar o ocupado certo. */
type Acao = 'confirmando' | 'reenviando';

/**
 * Tela pública "Confirmar e-mail" (T-052, AC-001.1, AC-001.2, AC-001.6, AC-005.1 a AC-005.3,
 * D-04, D-05, D-11, D-12, D-33, D-40, P4, P5).
 *
 * Depois do cadastro (202) a pessoa chega aqui com o e-mail já preenchido (vindo do
 * `FluxoCadastroService`) e, quando veio do cadastro, o aviso de 24 horas do catálogo no topo
 * (AC-001.1). Ela digita o código de 6 dígitos (teclado numérico, preenchimento automático do
 * código e só dígitos) e confirma: com sucesso (200) a sessão começa só em memória (D-12), o
 * estado temporário do cadastro é apagado e a pessoa segue ao cadastro do idoso (AC-001.2).
 *
 * Erros pelo que a API devolve, nunca pelo texto do servidor (D-40): `400` com tentativas
 * restantes mostra "Código incorreto. Tentativas restantes: N", limpa o código e devolve o foco
 * a ele (AC-005.2); `410` (código expirado ou bloqueado) mostra o aviso e leva o foco a "Reenviar
 * código" (AC-005.1); qualquer outro erro vira o texto genérico. "Reenviar código" não exige o
 * código: `202` mostra SEMPRE o mesmo texto neutro, exista a conta ou não (AC-001.6, P5), e `429`
 * mostra "Aguarde um minuto" (AC-005.3).
 *
 * Enquanto uma chamada está em voo os dois botões ficam desabilitados (sem envio duplo). E-mail,
 * código e token nunca vão para URL, storage, estado do roteador nem console (P5).
 */
@Component({
  selector: 'app-confirmar-email',
  templateUrl: './confirmar-email.component.html',
  styleUrl: './confirmar-email.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmarEmailComponent {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  /** Memória curta do cadastro: e-mail inicial e se o aviso de 24 horas deve aparecer. */
  protected readonly fluxo = inject(FluxoCadastroService);

  /** Catálogo de mensagens (D-40): todo texto da tela, exceto rótulos de campos e botões, vem daqui. */
  protected readonly mensagens = inject(MensagemService);

  /** Campo do código, para devolver o foco a ele depois de um código incorreto. */
  private readonly campoCodigo = viewChild<ElementRef<HTMLInputElement>>('campoCodigo');

  /** Ação "Reenviar código", para levar o foco a ela quando o código expirou ou foi bloqueado. */
  private readonly botaoReenviar = viewChild<ElementRef<HTMLButtonElement>>('botaoReenviar');

  /** Campo do e-mail, para focar quando é o primeiro inválido. */
  private readonly campoEmail = viewChild<ElementRef<HTMLInputElement>>('campoEmail');

  /** E-mail digitado (começa com o do fluxo e segue editável). */
  protected readonly email = signal(this.fluxo.email());

  /** Código digitado: só dígitos, no máximo 6 (texto, para preservar zero à esquerda). */
  protected readonly codigo = signal('');

  /** O e-mail já foi tocado (saiu do campo ou tentou confirmar/reenviar): libera a mensagem. */
  private readonly emailTocado = signal(false);

  /** O código já foi tocado (saiu do campo ou tentou confirmar): libera a mensagem. */
  private readonly codigoTocado = signal(false);

  /** Chamada em voo (`null` quando nenhuma); trava os dois botões e barra envio duplo. */
  protected readonly acaoEmVoo = signal<Acao | null>(null);

  /** Texto do alerta de erro atual (`null` quando não há); sempre vem do catálogo. */
  protected readonly erro = signal<string | null>(null);

  /** Verdadeiro depois de um reenvio aceito (202): mostra o texto neutro em região de status. */
  protected readonly reenviado = signal(false);

  /** Mensagem do e-mail (tocado e inválido) ou `null`; ligada ao campo por `aria-describedby`. */
  protected readonly mensagemEmail = computed(() =>
    this.emailTocado() && !emailEhValido(this.email()) ? this.mensagens.texto('VAL_EMAIL_INVALIDO') : null,
  );

  /** Mensagem do código (tocado e com menos de 6 dígitos) ou `null`. */
  protected readonly mensagemCodigo = computed(() =>
    this.codigoTocado() && this.codigo().length !== DIGITOS_DO_CODIGO ? this.mensagens.texto('VAL_OBRIGATORIO') : null,
  );

  /** Marca o e-mail como "tocado" ao sair do campo, para a mensagem de validação poder aparecer. */
  protected aoSairDoEmail(): void {
    this.emailTocado.set(true);
  }

  /** Marca o código como "tocado" ao sair do campo, para a mensagem de validação poder aparecer. */
  protected aoSairDoCodigo(): void {
    this.codigoTocado.set(true);
  }

  /** Guarda o e-mail enquanto a pessoa digita (a validação e a normalização ficam para o envio). */
  protected aoDigitarEmail(evento: Event): void {
    this.email.set((evento.target as HTMLInputElement).value);
  }

  /**
   * Trata a digitação (ou colagem) do código: descarta o que não é dígito ASCII, corta em 6 e
   * reescreve o campo se precisou mudar. O código é texto, então "012345" não perde o zero.
   */
  protected aoDigitarCodigo(evento: Event): void {
    const entrada = evento.target as HTMLInputElement;
    // 1. Só dígitos de 0 a 9, no máximo 6 (o excedente de uma colagem é cortado).
    const digitos = entrada.value.replace(/[^0-9]/g, '').slice(0, DIGITOS_DO_CODIGO);
    // 2. Reescreve o campo só quando mudou, para não mexer no cursor à toa.
    if (entrada.value !== digitos) {
      entrada.value = digitos;
    }
    this.codigo.set(digitos);
  }

  /**
   * Trata o envio do formulário ("Confirmar e-mail" ou Enter): barra envio duplo, valida e-mail
   * e código no cliente (sem requisição se algo estiver inválido) e chama
   * `POST /auth/verify-email`. Sucesso inicia a sessão em memória, apaga o estado temporário do
   * cadastro e vai ao cadastro do idoso (se a navegação falhar, a tela é liberada com o alerta
   * genérico e a sessão é mantida); erros mostram o texto do catálogo conforme o caso.
   */
  protected aoConfirmar(): void {
    // 1. Envio duplo: com qualquer chamada em voo nada novo sai.
    if (this.acaoEmVoo() !== null) {
      return;
    }

    // 2. Valida no cliente: mostra as duas mensagens de uma vez e foca o primeiro inválido.
    this.emailTocado.set(true);
    this.codigoTocado.set(true);
    this.erro.set(null);
    this.reenviado.set(false);
    const emailOk = emailEhValido(this.email());
    const codigoOk = this.codigo().length === DIGITOS_DO_CODIGO;
    if (!emailOk || !codigoOk) {
      this.focar(emailOk ? this.campoCodigo : this.campoEmail);
      return;
    }

    // 3. Chama o cliente gerado com o e-mail normalizado e o código como texto.
    this.acaoEmVoo.set('confirmando');
    this.auth.verifyEmail({ email: normalizarEmail(this.email()), code: this.codigo() }).subscribe({
      next: (resposta) => {
        // 3a. 200: a sessão fica só em memória, o cadastro temporário é esquecido e a pessoa
        //     segue ao cadastro do idoso (AC-001.2).
        this.session.start(resposta.accessToken);
        this.fluxo.limpar();
        //     Se a navegação REJEITAR (rota inexistente, chunk lazy que não carrega offline) ou
        //     resolver `false`, a pessoa não pode ficar presa com os botões travados: a tela é
        //     liberada com o alerta genérico (`falharNavegacao`) e a sessão NÃO é desfeita.
        this.router.navigateByUrl(ROTAS.CADASTRO_IDOSO).then(
          (navegou) => {
            if (!navegou) {
              this.falharNavegacao();
            }
          },
          () => this.falharNavegacao(),
        );
      },
      error: (falha: unknown) => {
        // 3b. Erros: o texto vem do catálogo conforme o que a API devolveu, nunca do servidor.
        this.acaoEmVoo.set(null);
        this.tratarErroDaConfirmacao(falha);
      },
    });
  }

  /**
   * Trata o clique em "Reenviar código": barra envio duplo, valida só o e-mail (o código não é
   * exigido) e chama `POST /auth/resend-code` com a finalidade de confirmação de e-mail. `202`
   * mostra sempre o mesmo texto neutro e limpa o código antigo (AC-001.6, P5); `429` mostra o
   * aviso de espera (AC-005.3); qualquer outro erro vira o texto genérico.
   */
  protected aoReenviar(): void {
    // 1. Envio duplo: com qualquer chamada em voo nada novo sai.
    if (this.acaoEmVoo() !== null) {
      return;
    }

    // 2. Só o e-mail precisa estar válido para pedir um novo código.
    this.emailTocado.set(true);
    this.erro.set(null);
    this.reenviado.set(false);
    if (!emailEhValido(this.email())) {
      this.focar(this.campoEmail);
      return;
    }

    // 3. Chama o cliente gerado; o corpo é só { email, purpose }.
    this.acaoEmVoo.set('reenviando');
    this.auth
      .resendCode({ email: normalizarEmail(this.email()), purpose: ResendCodeRequest.PurposeEnum.EmailVerification })
      .subscribe({
        next: () => {
          // 3a. 202: texto neutro igual para qualquer e-mail; o código antigo não vale mais.
          this.acaoEmVoo.set(null);
          this.codigo.set('');
          this.codigoTocado.set(false);
          this.reenviado.set(true);
        },
        error: (falha: unknown) => {
          // 3b. 429 tem texto próprio (AC-005.3); o resto cai no genérico.
          this.acaoEmVoo.set(null);
          const limite = falha instanceof HttpErrorResponse && falha.status === 429;
          this.erro.set(this.mensagens.texto(limite ? 'AC_005_3_AGUARDE_REENVIO' : 'FALLBACK_ERRO_GENERICO'));
        },
      });
  }

  /**
   * Libera a tela quando a navegação para o cadastro do idoso não aconteceu (a promise rejeitou ou
   * resolveu `false`): reabilita os dois botões (some o `aria-busy`) e mostra o único alerta
   * genérico do catálogo. E-mail e código ficam nos campos e a sessão já iniciada pelo 200 não é
   * desfeita, pois o servidor já ativou a conta.
   */
  private falharNavegacao(): void {
    this.acaoEmVoo.set(null);
    this.erro.set(this.mensagens.texto('FALLBACK_ERRO_GENERICO'));
  }

  /**
   * Escolhe o texto e o foco para um erro da confirmação: `400` com `remainingAttempts` é código
   * incorreto (limpa o código e foca o campo, AC-005.2); `410` é código expirado ou bloqueado
   * (foca "Reenviar código", AC-005.1); o resto (inclusive `400` de validação e falha de rede)
   * mostra o texto genérico do catálogo e mantém tudo o que foi digitado.
   */
  private tratarErroDaConfirmacao(falha: unknown): void {
    // 1. Só um HttpErrorResponse tem status e corpo para consultar; o resto é erro genérico.
    if (falha instanceof HttpErrorResponse) {
      // 2a. Código incorreto: a API informa quantas tentativas restam (zero é um valor válido).
      const corpo: unknown = falha.error;
      const restantes =
        typeof corpo === 'object' && corpo !== null && 'remainingAttempts' in corpo
          ? (corpo as { remainingAttempts: unknown }).remainingAttempts
          : undefined;
      if (falha.status === 400 && typeof restantes === 'number') {
        this.erro.set(this.mensagens.texto('AC_005_2_CODIGO_INCORRETO', { restantes }));
        this.codigo.set('');
        this.codigoTocado.set(false);
        this.focar(this.campoCodigo);
        return;
      }

      // 2b. Código expirado ou invalidado: o caminho é pedir outro, então o foco vai à ação.
      if (falha.status === 410) {
        this.erro.set(this.mensagens.texto('AC_005_1_CODIGO_EXPIRADO'));
        this.focar(this.botaoReenviar);
        return;
      }
    }

    // 3. Qualquer outro caso: texto genérico do catálogo (D-40).
    this.erro.set(this.mensagens.texto('FALLBACK_ERRO_GENERICO'));
  }

  /**
   * Leva o foco ao elemento depois do próximo ciclo de renderização: só então o botão deixa de
   * estar desabilitado e o campo já foi redesenhado (um elemento desabilitado não recebe foco).
   */
  private focar(alvo: () => ElementRef<HTMLElement> | undefined): void {
    afterNextRender(() => alvo()?.nativeElement.focus(), { injector: this.injector });
  }
}
