import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, SignupRequest } from '../../core/api';
import { MensagemService } from '../../core/messages/mensagem.service';
import { ROTAS } from '../../core/routes';
import { EnderecoFormComponent } from '../../shared/endereco-form/endereco-form.component';
import { criarEnderecoForm, enderecoDoForm } from '../../shared/endereco-form/endereco-form';
import { FluxoCadastroService } from '../fluxo-cadastro.service';
import { normalizarEmail, soDigitos, validarEmail, validarNome, validarSenha, validarTelefone } from '../validadores';

/** Campos de texto simples do formulário (o endereço tem a validação própria do `<app-endereco-form>`). */
type CampoSimples = 'name' | 'email' | 'phone' | 'password';

/**
 * Tela pública "Criar grupo de cuidado" (T-052, AC-001.1, AC-001.3, AC-001.4, AC-011.1, D-15,
 * D-40, P4, P5).
 *
 * É por onde o futuro Responsável começa: informa nome, e-mail, telefone, endereço (o formulário
 * único `<app-endereco-form>` da T-051) e senha, tudo numa coluna só pensada para o celular. A
 * validação no cliente é só ajuda de uso (o backend valida de novo, D-20): cada campo mostra,
 * depois de tocado, a mensagem do catálogo (D-40) abaixo dele, ligada por `aria-describedby`; ao
 * tentar enviar com algo inválido nada é enviado, todos os campos viram "tocados" e o foco vai ao
 * primeiro campo inválido.
 *
 * O envio chama `POST /auth/signup` pelo cliente gerado. Há proteção contra envio duplo (guarda
 * no handler e botão desabilitado com `aria-busy`). A resposta `202` é a mesma para e-mail novo
 * ou já cadastrado (AC-001.4, P5): a tela guarda o e-mail no `FluxoCadastroService` (que liga o
 * aviso de 24 horas) e segue para a confirmação do e-mail. Qualquer erro mostra um único alerta
 * com o texto genérico do catálogo, mantendo tudo o que foi digitado.
 *
 * Privacidade (P5): a senha vive só no campo e no corpo da requisição; nunca vai para URL, storage,
 * estado do roteador, console nem para o `FluxoCadastroService`.
 */
@Component({
  selector: 'app-criar-grupo',
  imports: [ReactiveFormsModule, EnderecoFormComponent],
  templateUrl: './criar-grupo.component.html',
  styleUrl: './criar-grupo.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CriarGrupoComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fluxo = inject(FluxoCadastroService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  /** Catálogo de mensagens (D-40): todo texto da tela, exceto rótulos de campos, vem daqui. */
  protected readonly mensagens = inject(MensagemService);

  /** Formulário da tela, na ordem do documento: nome, e-mail, telefone, endereço e senha. */
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [validarNome] }),
    email: new FormControl('', { nonNullable: true, validators: [validarEmail] }),
    phone: new FormControl('', { nonNullable: true, validators: [validarTelefone] }),
    address: criarEnderecoForm(),
    password: new FormControl('', { nonNullable: true, validators: [validarSenha] }),
  });

  /** Verdadeiro enquanto o `signup` está em voo: desabilita o botão (aria-busy) e barra novo envio. */
  protected readonly enviando = signal(false);

  /** Verdadeiro depois de uma falha do envio: mostra o único alerta com o texto genérico. */
  protected readonly falhou = signal(false);

  /** Verdadeiro quando a senha está visível no campo (botão mostrar/ocultar, `aria-pressed`). */
  protected readonly senhaVisivel = signal(false);

  /**
   * Contador que sobe a cada mudança nos controles (valor, "tocado", validade). Lido pelo
   * template, faz mensagens e `aria-invalid` se atualizarem mesmo quando a mudança vem de código
   * (ex.: `markAllAsTouched` no envio), já que o estado dos controles não é um signal.
   */
  private readonly versao = signal(0);

  /**
   * Liga a tela ao formulário: a cada evento de qualquer controle (inclusive os do endereço,
   * que sobem para o grupo pai) incrementa a versão para a tela reavaliar mensagens e estados.
   */
  constructor() {
    this.form.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.versao.update((v) => v + 1));
  }

  /**
   * Diz se o campo deve aparecer como inválido (`aria-invalid`): só quando a pessoa já passou por
   * ele (tocado) e o valor não é válido. Ao abrir a tela nada "grita" (P4).
   */
  protected invalido(campo: CampoSimples): boolean {
    this.versao();
    const controle = this.form.controls[campo];
    return controle.touched && controle.invalid;
  }

  /**
   * Devolve a mensagem de validação do campo (texto do catálogo, D-40) ou `null` quando não há o
   * que mostrar. Nome vazio pede para preencher e nome curto tem texto próprio; e-mail, telefone
   * e senha têm uma mensagem cada. Excesso de tamanho (nome acima de 120) deixa o campo inválido
   * sem texto, pois não há mensagem aprovada para isso.
   */
  protected mensagemDe(campo: CampoSimples): string | null {
    // 1. Só mostra para campo tocado e inválido.
    if (!this.invalido(campo)) {
      return null;
    }

    // 2. Escolhe a chave conforme o campo (e, no nome, conforme o erro).
    const erros = this.form.controls[campo].errors ?? {};
    switch (campo) {
      case 'name':
        if (erros['required']) {
          return this.mensagens.texto('VAL_OBRIGATORIO');
        }
        return erros['curto'] ? this.mensagens.texto('VAL_NOME_CURTO') : null;
      case 'email':
        return this.mensagens.texto('VAL_EMAIL_INVALIDO');
      case 'phone':
        return this.mensagens.texto('VAL_TELEFONE_INVALIDO');
      case 'password':
        return this.mensagens.texto('AC_001_3_SENHA_REGRA');
    }
  }

  /** Alterna entre mostrar e ocultar a senha digitada; não valida nem envia nada. */
  protected alternarSenha(): void {
    this.senhaVisivel.update((visivel) => !visivel);
  }

  /** Marca o campo como "tocado" ao sair dele, para a mensagem de validação aparecer. */
  protected aoSair(campo: CampoSimples): void {
    this.form.controls[campo].markAsTouched();
  }

  /**
   * Trata o envio do formulário (botão ou Enter): barra envio duplo, valida no cliente (todos
   * os campos viram "tocados" e o foco vai ao primeiro inválido), chama `POST /auth/signup` e
   * trata o resultado. Sucesso (202) guarda o e-mail em memória e segue para a confirmação (se a
   * navegação falhar, a tela é liberada com o alerta genérico); qualquer erro mostra o texto
   * genérico do catálogo e mantém os dados digitados.
   */
  protected aoEnviar(): void {
    // 1. Envio duplo: enquanto há requisição em voo, nenhum novo envio sai.
    if (this.enviando()) {
      return;
    }

    // 2. Valida no cliente: com algo inválido nada é enviado, tudo aparece de uma vez e o foco
    //    vai ao primeiro campo inválido (na ordem do documento).
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focarPrimeiroInvalido();
      return;
    }

    // 3. Monta o corpo do contrato: nome aparado, e-mail normalizado, telefone só com dígitos,
    //    endereço pelo conversor único e senha exatamente como digitada.
    const valores = this.form.getRawValue();
    const corpo: SignupRequest = {
      name: valores.name.trim(),
      email: normalizarEmail(valores.email),
      phone: soDigitos(valores.phone),
      address: enderecoDoForm(this.form.controls.address),
      password: valores.password,
    };

    // 4. Marca o envio em voo e some com o alerta de uma tentativa anterior.
    this.enviando.set(true);
    this.falhou.set(false);

    // 5. Chama o cliente gerado e trata os dois desfechos.
    this.auth.signup(corpo).subscribe({
      next: () => {
        // 5a. 202 neutro (AC-001.4): só o e-mail vai para a memória; a senha não passa por aqui.
        this.fluxo.iniciar(corpo.email);
        //     Se a navegação REJEITAR (rota inexistente, chunk lazy que não carrega offline) ou
        //     resolver `false`, a pessoa não pode ficar presa com o botão travado: a tela volta a
        //     ser utilizável, com os dados mantidos e o alerta genérico (`falharNavegacao`).
        this.router.navigateByUrl(ROTAS.CONFIRMAR_EMAIL).then(
          (navegou) => {
            if (!navegou) {
              this.falharNavegacao();
            }
          },
          () => this.falharNavegacao(),
        );
      },
      error: () => {
        // 5b. Qualquer falha vira o mesmo alerta; os dados ficam e o botão volta a funcionar.
        this.falhou.set(true);
        this.enviando.set(false);
      },
    });
  }

  /**
   * Libera a tela quando a navegação para a confirmação não aconteceu (a promise rejeitou ou
   * resolveu `false`): reabilita o botão (some o `aria-busy`) e mostra o único alerta genérico do
   * catálogo. Os dados digitados ficam nos campos e um novo envio refaz o `signup`.
   */
  private falharNavegacao(): void {
    this.falhou.set(true);
    this.enviando.set(false);
  }

  /**
   * Leva o foco ao primeiro campo marcado como inválido na ordem do documento. Espera o próximo
   * ciclo de renderização, porque `aria-invalid` (inclusive o dos campos do endereço, que ficam
   * no componente filho) só aparece no DOM depois que a tela é redesenhada.
   */
  private focarPrimeiroInvalido(): void {
    afterNextRender(
      () => {
        this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
      },
      { injector: this.injector },
    );
  }
}
