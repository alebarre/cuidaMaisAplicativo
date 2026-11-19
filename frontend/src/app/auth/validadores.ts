import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Tamanho máximo do e-mail no contrato (`Email`, 254 caracteres). */
const LIMITE_EMAIL = 254;

/**
 * Formato mínimo de e-mail aceito no cliente: uma parte local sem espaço nem arroba, um arroba e
 * um domínio com rótulos não vazios separados por ponto. É de propósito simples: a validação de
 * verdade é a do backend (`@Email`), aqui só se evita enviar o que obviamente está errado (AC-001.1).
 */
const PADRAO_EMAIL = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)*$/;

/** Telefone aceito: DDD + 8 ou 9 dígitos (AC-001.1), ou seja, 10 ou 11 dígitos. */
const PADRAO_TELEFONE = /^\d{10,11}$/;

/** Limites da senha (AC-001.3): de 8 a 128 caracteres, como no contrato. */
const SENHA_MINIMO = 8;
const SENHA_MAXIMO = 128;

/**
 * Normaliza o e-mail como o backend o guarda: sem espaços nas pontas e em minúsculas. É usado
 * tanto no que vai para a API quanto no que o `FluxoCadastroService` guarda em memória, para o
 * e-mail digitado no cadastro bater com o da confirmação (AC-001.2).
 */
export function normalizarEmail(valor: string): string {
  return valor.trim().toLowerCase();
}

/** Mantém só os dígitos ASCII de um texto (telefone com máscara, "+55" etc. viram só números). */
export function soDigitos(valor: string): string {
  return valor.replace(/\D/g, '');
}

/**
 * Diz se o texto é um e-mail aceitável para enviar: depois de aparado tem formato de e-mail e
 * no máximo 254 caracteres. Vazio, sem arroba, sem domínio, com espaço no meio ou longo demais
 * são rejeitados.
 */
export function emailEhValido(valor: string): boolean {
  const texto = valor.trim();
  return texto.length > 0 && texto.length <= LIMITE_EMAIL && PADRAO_EMAIL.test(texto);
}

/**
 * Diz se a senha cumpre AC-001.3: de 8 a 128 caracteres, com pelo menos uma letra e um número.
 * A senha é medida exatamente como digitada (sem aparar), igual ao que o backend recebe, e a
 * regra de letra/número é a mesma do backend (`[A-Za-z]` e `\d`).
 */
export function senhaEhValida(valor: string): boolean {
  return (
    valor.length >= SENHA_MINIMO &&
    valor.length <= SENHA_MAXIMO &&
    /[A-Za-z]/.test(valor) &&
    /\d/.test(valor)
  );
}

/**
 * Validador do campo de e-mail (AC-001.1): devolve o erro `email` quando o texto não passa em
 * `emailEhValido` (inclui vazio). A tela mostra sempre a mesma mensagem do catálogo.
 */
export const validarEmail: ValidatorFn = (controle: AbstractControl): ValidationErrors | null =>
  emailEhValido(String(controle.value ?? '')) ? null : { email: true };

/**
 * Validador do telefone (AC-001.1): descarta o que não é dígito e exige 10 ou 11 dígitos. Vazio
 * também é inválido. Erro: `telefone`.
 */
export const validarTelefone: ValidatorFn = (controle: AbstractControl): ValidationErrors | null =>
  PADRAO_TELEFONE.test(soDigitos(String(controle.value ?? ''))) ? null : { telefone: true };

/**
 * Validador da senha (AC-001.3): devolve o erro `senha` quando a regra de `senhaEhValida` não é
 * cumprida. Não apara nem altera o valor.
 */
export const validarSenha: ValidatorFn = (controle: AbstractControl): ValidationErrors | null =>
  senhaEhValida(String(controle.value ?? '')) ? null : { senha: true };

/**
 * Validador do nome (AC-001.1): sem os espaços das pontas, vazio é `required`, com menos de 2
 * letras é `curto` e com mais de 120 caracteres é `longo` (limite do contrato).
 */
export const validarNome: ValidatorFn = (controle: AbstractControl): ValidationErrors | null => {
  const tamanho = String(controle.value ?? '').trim().length;
  if (tamanho === 0) {
    return { required: true };
  }
  if (tamanho < 2) {
    return { curto: true };
  }
  return tamanho > 120 ? { longo: true } : null;
};
