import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Subject, catchError, map, merge, of, switchMap } from 'rxjs';
import { MensagemService } from '../../core/messages/mensagem.service';
import { ChaveMensagem } from '../../core/messages/mensagens';
import { EnderecoForm, UFS } from './endereco-form';
import { EnderecoViaCep, ViaCepService } from './via-cep.service';

/** Campos que a consulta de CEP preenche (AC-011.3); número e complemento nunca são tocados. */
type CampoDoCep = keyof EnderecoViaCep;

/** Os quatro campos preenchidos pelo ViaCEP, na ordem em que aparecem na tela. */
const CAMPOS_DO_CEP: readonly CampoDoCep[] = ['street', 'district', 'city', 'state'];

/** Resultado de uma consulta já tratado: o endereço achado ou a falha (sem lançar erro). */
type ResultadoConsulta = { ok: true; endereco: EnderecoViaCep } | { ok: false };

/** Contador que dá a cada instância um prefixo de `id` próprio (vários formulários na mesma tela). */
let proximaInstancia = 0;

/**
 * Formulário único de endereço do Cuida+ (T-051, AC-011.1 a AC-011.4, D-27 rev. 2, D-40, P4, P5).
 *
 * Mostra CEP, logradouro, número, complemento, bairro, cidade e UF para qualquer tela de cadastro
 * (idoso, contatos de emergência, membros). O pai cria o grupo com `criarEnderecoForm()` e o passa
 * em `[form]`; a validade e o `Address` final são lidos por ele (`enderecoDoForm`).
 *
 * Comportamento: o CEP aparece com a máscara `00000-000`, mas o controle guarda só os 8 dígitos.
 * Quando a pessoa completa os 8 dígitos, o componente consulta o ViaCEP (sem esperar, a cada
 * evento de digitação), cancela a consulta anterior se o CEP mudar, não repete o mesmo CEP seguido
 * e preenche logradouro, bairro, cidade e UF, que continuam editáveis. Um campo que a pessoa edita
 * DEPOIS de a consulta começar não é sobrescrito pela resposta dela. Se a consulta falhar (CEP não
 * encontrado, erro de rede ou 15 s sem resposta), aparece NO ATO o aviso de AC-011.4, lido do
 * catálogo pelo `MensagemService`, sem bloquear nem apagar nada: o cadastro segue à mão.
 *
 * Acessibilidade (P4): cada campo tem `<label for>` com `id` único por instância, `aria-invalid`
 * só em campo tocado e inválido, `aria-busy` durante a consulta e o aviso do CEP em `role="alert"`.
 * T-052: abaixo de cada campo tocado e inválido aparece a mensagem de validação do catálogo
 * (`VAL_CEP_INVALIDO`, `VAL_NUMERO_INVALIDO`, `VAL_UF_OBRIGATORIA`, `VAL_OBRIGATORIO`, D-40),
 * ligada ao campo por `aria-describedby` e SEM `role="alert"` (só o aviso do CEP, AC-011.4, é
 * alerta). O complemento nunca mostra mensagem de obrigatório; excesso de tamanho mantém só o
 * `aria-invalid`, pois não há texto aprovado para isso. Quando o pai chama `markAllAsTouched`
 * (envio), todas as mensagens pendentes aparecem de uma vez. Nada vai para storage ou cookie (P5)
 * e o backend não é chamado.
 */
@Component({
  selector: 'app-endereco-form',
  imports: [ReactiveFormsModule],
  templateUrl: './endereco-form.component.html',
  styleUrl: './endereco-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnderecoFormComponent implements OnInit {
  /** Grupo de controles do endereço, criado pelo pai com `criarEnderecoForm()`. */
  @Input({ required: true }) form!: EnderecoForm;

  private readonly viaCep = inject(ViaCepService);
  private readonly mensagens = inject(MensagemService);
  private readonly destroyRef = inject(DestroyRef);

  /** Prefixo único dos `id` desta instância, para o `<label for>` achar o campo certo. */
  protected readonly prefixo = `endereco-${++proximaInstancia}`;

  /** As 27 UFs do contrato, na ordem do cliente gerado, para o `<select>`. */
  protected readonly ufs = UFS;

  /** Verdadeiro enquanto há consulta ao ViaCEP em voo (vira `aria-busy`); nenhum campo é bloqueado. */
  protected readonly ocupado = signal(false);

  /** Texto do aviso de AC-011.4 enquanto a última consulta falhou; `null` quando não há aviso. */
  protected readonly avisoCep = signal<string | null>(null);

  /**
   * Contador que sobe a cada mudança em qualquer controle (valor, estado, "tocado"). Lido pelo
   * template, faz o `aria-invalid` e o CEP mascarado se atualizarem mesmo sem evento de DOM
   * (ex.: o pai ou a resposta do ViaCEP alterou um controle).
   */
  private readonly versao = signal(0);

  /** Pedidos de consulta: um CEP de 8 dígitos inicia uma; `null` cancela a em andamento. */
  private readonly pedidos = new Subject<string | null>();

  /** Último CEP consultado seguido, para não repetir a mesma consulta; `null` depois de apagar dígito. */
  private ultimoCepConsultado: string | null = null;

  /** Há uma consulta em voo cujas edições manuais precisam ser protegidas da resposta. */
  private consultaEmVoo = false;

  /** Campos editados pela pessoa depois que a consulta em voo começou (a resposta não os sobrescreve). */
  private readonly editadosEmVoo = new Set<CampoDoCep>();

  /** Verdadeiro enquanto a resposta do ViaCEP está sendo gravada nos controles (não é edição). */
  private aplicandoResposta = false;

  /**
   * Liga o componente ao formulário recebido: guarda o CEP do valor inicial como "já consultado"
   * (um endereço salvo não dispara consulta ao abrir), atualiza a tela a cada mudança nos
   * controles, observa as edições dos campos que o ViaCEP preenche e inicia o fluxo de consultas.
   */
  ngOnInit(): void {
    // 1. Endereço salvo: o CEP inicial não deve ser reconsultado ao abrir nem colado de novo.
    const cepInicial = this.form.controls.zipCode.value;
    this.ultimoCepConsultado = /^\d{8}$/.test(cepInicial) ? cepInicial : null;

    // 2. Qualquer mudança em qualquer controle reavalia o que o template deriva deles.
    merge(...Object.values(this.form.controls).map((controle) => controle.events))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.versao.update((v) => v + 1));

    // 3. Edição manual de um campo do CEP durante a consulta protege o valor digitado.
    for (const campo of CAMPOS_DO_CEP) {
      this.form.controls[campo].valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
        if (this.consultaEmVoo && !this.aplicandoResposta) {
          this.editadosEmVoo.add(campo);
        }
      });
    }

    // 4. Fluxo das consultas: o switchMap cancela a anterior (e aborta o HTTP) a cada novo pedido.
    this.pedidos
      .pipe(
        switchMap((cep) => this.executarPedido(cep)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((resultado) => this.concluirConsulta(resultado));
  }

  /**
   * Trata um pedido do fluxo: `null` só encerra o estado de consulta (o CEP ficou incompleto);
   * um CEP completo começa a consulta, limpa o aviso antigo e as edições protegidas, e devolve o
   * resultado já sem erro lançado (falha vira `{ ok: false }`, para o fluxo nunca morrer).
   */
  private executarPedido(cep: string | null) {
    // 1. Pedido de cancelamento: sem consulta, sem ocupado, sem proteção de edição.
    if (cep === null) {
      this.consultaEmVoo = false;
      this.editadosEmVoo.clear();
      this.ocupado.set(false);
      return EMPTY;
    }

    // 2. Consulta nova: o aviso da anterior sai e só edições a partir de agora são protegidas.
    this.avisoCep.set(null);
    this.editadosEmVoo.clear();
    this.consultaEmVoo = true;
    this.ocupado.set(true);

    // 3. Consulta ao ViaCEP; qualquer `ViaCepErro` vira resultado de falha.
    return this.viaCep.consultar(cep).pipe(
      map((endereco): ResultadoConsulta => ({ ok: true, endereco })),
      catchError(() => of<ResultadoConsulta>({ ok: false })),
    );
  }

  /**
   * Encerra uma consulta: no sucesso preenche os quatro campos que a pessoa NÃO editou durante a
   * consulta (AC-011.3); na falha mostra o aviso de AC-011.4 pelo catálogo e libera o mesmo CEP
   * para uma nova tentativa. Nada é apagado nem desabilitado em nenhum dos dois casos.
   */
  private concluirConsulta(resultado: ResultadoConsulta): void {
    // 1. A consulta terminou: sai do estado "ocupado" e deixa de proteger edições.
    this.consultaEmVoo = false;
    this.ocupado.set(false);

    if (!resultado.ok) {
      // 2a. Falha: aviso no ato, texto do catálogo (D-40); o mesmo CEP pode ser tentado de novo.
      this.ultimoCepConsultado = null;
      this.avisoCep.set(this.mensagens.texto('AC_011_4_CEP_FALHA'));
      this.editadosEmVoo.clear();
      return;
    }

    // 2b. Sucesso: grava só o que a pessoa não editou em voo, sem contar como edição manual.
    this.aplicandoResposta = true;
    try {
      for (const campo of CAMPOS_DO_CEP) {
        if (!this.editadosEmVoo.has(campo)) {
          this.form.controls[campo].setValue(resultado.endereco[campo]);
        }
      }
    } finally {
      this.aplicandoResposta = false;
    }
    this.editadosEmVoo.clear();
  }

  /**
   * Handler do evento `input` do CEP (digitação ou colagem): descarta o que não é dígito, corta em
   * 8 dígitos, reescreve o campo com a máscara, grava só os dígitos no controle e decide a
   * consulta. O aviso de falha some a cada edição do CEP.
   */
  protected aoDigitarCep(evento: Event): void {
    const entrada = evento.target as HTMLInputElement;

    // 1. Só dígitos, no máximo 8 (o excedente de uma colagem é cortado).
    const digitos = entrada.value.replace(/\D/g, '').slice(0, 8);

    // 2. A máscara é só visual; reescreve o campo apenas se mudou, para não mover o cursor à toa.
    const mascarado = this.mascararCep(digitos);
    if (entrada.value !== mascarado) {
      entrada.value = mascarado;
    }

    // 3. O controle guarda só os dígitos; editar o CEP faz o aviso de falha sumir.
    this.avisoCep.set(null);
    const controle = this.form.controls.zipCode;
    controle.setValue(digitos);
    controle.markAsDirty();

    // 4. CEP incompleto cancela a consulta e libera o mesmo CEP para ser digitado de novo.
    if (digitos.length < 8) {
      this.ultimoCepConsultado = null;
      this.pedidos.next(null);
      return;
    }

    // 5. CEP completo e diferente do último consultado: consulta, sem espera (sem debounce).
    if (digitos !== this.ultimoCepConsultado) {
      this.ultimoCepConsultado = digitos;
      this.pedidos.next(digitos);
    }
  }

  /** Marca o CEP como "tocado" ao sair do campo (ele não usa o `formControl` direto por causa da máscara). */
  protected aoSairDoCep(): void {
    this.form.controls.zipCode.markAsTouched();
  }

  /**
   * Devolve o CEP como o campo mostra: `00000-000`, com o hífen só a partir do 6º dígito. Entrada
   * é qualquer texto; só os dígitos (no máximo 8) entram.
   */
  protected mascararCep(valor: string): string {
    const digitos = valor.replace(/\D/g, '').slice(0, 8);
    return digitos.length > 5 ? `${digitos.slice(0, 5)}-${digitos.slice(5)}` : digitos;
  }

  /** CEP do controle já mascarado, para o `[value]` do campo (acompanha mudanças feitas por fora). */
  protected cepExibido(): string {
    this.versao();
    return this.mascararCep(this.form.controls.zipCode.value);
  }

  /**
   * Diz se o campo deve aparecer como inválido para leitores de tela (`aria-invalid`): só quando
   * a pessoa já passou por ele (tocado) e o valor não é válido. Ao abrir, nada "grita" (P4).
   */
  protected invalido(campo: keyof EnderecoForm['controls']): boolean {
    this.versao();
    const controle = this.form.controls[campo];
    return controle.touched && controle.invalid;
  }

  /**
   * Devolve a mensagem de validação (texto do catálogo, D-40) do campo, ou `null` quando não há
   * o que mostrar. Só aparece se o campo está tocado e inválido (`invalido`); o texto depende do
   * campo e do erro: CEP e número usam a orientação própria (inclusive quando vazios), UF pede
   * para escolher o estado, e logradouro/bairro/cidade vazios pedem para preencher. Excesso de
   * tamanho e o complemento não têm texto aprovado e ficam sem mensagem.
   */
  protected mensagemDe(campo: keyof EnderecoForm['controls']): string | null {
    // 1. Sem campo tocado e inválido não há nada a mostrar (a tela não "grita" ao abrir).
    if (!this.invalido(campo)) {
      return null;
    }

    // 2. Escolhe a chave do catálogo conforme o campo e o tipo de erro.
    const erros = this.form.controls[campo].errors ?? {};
    let chave: ChaveMensagem | null = null;
    switch (campo) {
      case 'zipCode':
        chave = 'VAL_CEP_INVALIDO';
        break;
      case 'number':
        chave = 'VAL_NUMERO_INVALIDO';
        break;
      case 'state':
        chave = 'VAL_UF_OBRIGATORIA';
        break;
      case 'street':
      case 'district':
      case 'city':
        // Só o "vazio" tem texto aprovado; "longo demais" fica apenas com aria-invalid.
        chave = erros['required'] ? 'VAL_OBRIGATORIO' : null;
        break;
      default:
        // `complement` é opcional: nunca mostra mensagem.
        chave = null;
    }

    // 3. O texto sempre vem do catálogo, pelo serviço (nada digitado aqui).
    return chave === null ? null : this.mensagens.texto(chave);
  }

  /**
   * Monta o valor do `aria-describedby` do campo: o id da mensagem de validação (quando visível)
   * e, no CEP, também o id do aviso de falha da consulta (AC-011.4). Devolve `null` quando não há
   * nenhum descritor, para o atributo nem existir.
   */
  protected descritoresDe(campo: keyof EnderecoForm['controls'], sufixo: string): string | null {
    const ids: string[] = [];
    // 1. A mensagem de validação, quando existe, é o primeiro descritor.
    if (this.mensagemDe(campo) !== null) {
      ids.push(`${this.prefixo}-${sufixo}-erro`);
    }
    // 2. O aviso da consulta de CEP só pertence ao campo do CEP.
    if (campo === 'zipCode' && this.avisoCep()) {
      ids.push(`${this.prefixo}-aviso-cep`);
    }
    return ids.length > 0 ? ids.join(' ') : null;
  }
}
