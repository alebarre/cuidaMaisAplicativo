import { AbstractControl, FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { Address, UF } from '../../core/api';

/** As 27 unidades da federação, tiradas do próprio contrato (AC-011.2); nunca uma lista à mão. */
export const UFS: readonly UF[] = Object.values(UF);

/** Número do imóvel: até 6 dígitos com uma letra opcional (casa 12B) ou "S/N" (AC-011.1). */
const PADRAO_NUMERO = /^(\d{1,6}[A-Za-z]?|s\/n)$/i;

/** Exatamente 8 dígitos numéricos ASCII (AC-011.2). */
const PADRAO_CEP = /^\d{8}$/;

/** Limites de tamanho do contrato (`Address`): logradouro 150; complemento, bairro e cidade 80. */
const LIMITE_LOGRADOURO = 150;
const LIMITE_CURTO = 80;

/**
 * Formulário tipado de endereço (AC-011.1, D-27): um `FormGroup` com os sete campos do `Address`,
 * todos como texto não nulo (o `state` é texto porque nasce vazio antes de a pessoa escolher a UF).
 * O controle `zipCode` guarda só os 8 dígitos; a máscara `00000-000` é apenas visual.
 */
export type EnderecoForm = FormGroup<{
  zipCode: FormControl<string>;
  street: FormControl<string>;
  number: FormControl<string>;
  complement: FormControl<string>;
  district: FormControl<string>;
  city: FormControl<string>;
  state: FormControl<string>;
}>;

/**
 * Validador de obrigatoriedade que trata texto só de espaços como vazio (AC-011.1). Devolve o
 * erro `required`, igual ao do Angular, para a tela reagir da mesma forma.
 */
const obrigatorio: ValidatorFn = (controle: AbstractControl): ValidationErrors | null =>
  String(controle.value ?? '').trim() === '' ? { required: true } : null;

/**
 * Cria um validador de tamanho máximo que mede o texto SEM os espaços das pontas, porque é assim
 * que ele segue para a API (`enderecoDoForm` aplica `trim`). O erro tem a forma `maxlength` do
 * Angular.
 */
function tamanhoMaximo(limite: number): ValidatorFn {
  return (controle: AbstractControl): ValidationErrors | null => {
    const tamanho = String(controle.value ?? '').trim().length;
    return tamanho > limite ? { maxlength: { requiredLength: limite, actualLength: tamanho } } : null;
  };
}

/**
 * Valida o número do imóvel contra `PADRAO_NUMERO`, ignorando espaços nas pontas (o teclado do
 * celular costuma acrescentá-los). Vazio fica por conta do `obrigatorio`. Erro: `pattern`.
 */
const formatoNumero: ValidatorFn = (controle: AbstractControl): ValidationErrors | null => {
  const texto = String(controle.value ?? '').trim();
  return texto === '' || PADRAO_NUMERO.test(texto) ? null : { pattern: true };
};

/**
 * Valida que o CEP tem exatamente 8 dígitos. O controle só guarda dígitos, então um valor com
 * máscara ou letras é inválido (AC-011.2). Vazio fica por conta do `obrigatorio`. Erro: `pattern`.
 */
const formatoCep: ValidatorFn = (controle: AbstractControl): ValidationErrors | null => {
  const texto = String(controle.value ?? '');
  return texto.trim() === '' || PADRAO_CEP.test(texto) ? null : { pattern: true };
};

/**
 * Valida que a UF é uma das 27 siglas do contrato (AC-011.2). Vazio fica por conta do
 * `obrigatorio`. Erro: `uf`.
 */
const ufValida: ValidatorFn = (controle: AbstractControl): ValidationErrors | null => {
  const texto = String(controle.value ?? '');
  return texto.trim() === '' || (UFS as readonly string[]).includes(texto) ? null : { uf: true };
};

/**
 * Cria o `FormGroup` tipado de endereço (AC-011.1, AC-011.2, D-25, D-27), vazio ou com o valor de
 * um endereço salvo. Todos os campos são obrigatórios, exceto o complemento (texto só de espaços
 * conta como vazio). Cada chamada devolve um grupo independente, e `complement` nulo ou ausente
 * vira texto vazio (os controles nunca guardam `null`).
 */
export function criarEnderecoForm(valorInicial?: Address): EnderecoForm {
  const texto = (valor: string | null | undefined, validadores: ValidatorFn[]) =>
    new FormControl<string>(valor ?? '', { nonNullable: true, validators: validadores });

  return new FormGroup({
    zipCode: texto(valorInicial?.zipCode, [obrigatorio, formatoCep]),
    street: texto(valorInicial?.street, [obrigatorio, tamanhoMaximo(LIMITE_LOGRADOURO)]),
    number: texto(valorInicial?.number, [obrigatorio, formatoNumero]),
    complement: texto(valorInicial?.complement, [tamanhoMaximo(LIMITE_CURTO)]),
    district: texto(valorInicial?.district, [obrigatorio, tamanhoMaximo(LIMITE_CURTO)]),
    city: texto(valorInicial?.city, [obrigatorio, tamanhoMaximo(LIMITE_CURTO)]),
    state: texto(valorInicial?.state, [obrigatorio, ufValida]),
  });
}

/**
 * Converte o formulário no `Address` do contrato, SEM alterar os controles: o CEP sai só com os 8
 * dígitos; textos saem aparados; `s/n` e a UF saem em maiúsculas; complemento vazio ou só de
 * espaços vira `null`. Deve ser chamado com o formulário válido (a validade é do chamador).
 */
export function enderecoDoForm(form: EnderecoForm): Address {
  const bruto = form.getRawValue();
  const complemento = bruto.complement.trim();

  return {
    zipCode: bruto.zipCode.replace(/\D/g, ''),
    street: bruto.street.trim(),
    number: bruto.number.trim().toUpperCase() === 'S/N' ? 'S/N' : bruto.number.trim(),
    complement: complemento === '' ? null : complemento,
    district: bruto.district.trim(),
    city: bruto.city.trim(),
    state: bruto.state.trim().toUpperCase() as UF,
  };
}
