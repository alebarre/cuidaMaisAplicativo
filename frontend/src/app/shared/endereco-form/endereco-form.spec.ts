import { Address, UF } from '../../core/api';
import { criarEnderecoForm, enderecoDoForm, type EnderecoForm } from './endereco-form';

/**
 * Cobre os helpers puros do formulário de endereço (T-051, AC-011.1, AC-011.2, D-25, D-27):
 * `criarEnderecoForm` (o FormGroup tipado, vazio ou com valor inicial), a validação campo a campo
 * e `enderecoDoForm` (conversão para o `Address` do contrato).
 *
 * Regras provadas: todos os campos são obrigatórios, EXCETO complemento (AC-011.1); número aceita
 * "S/N" (AC-011.1); CEP com exatamente 8 dígitos numéricos (AC-011.2); UF é uma das 27 unidades
 * da federação (AC-011.2); textos só de espaços contam como vazios; limites de tamanho do
 * contrato (logradouro 150; complemento, bairro e cidade 80).
 *
 * Decisão registrada: `s/n` em minúsculo É aceito pela validação e `enderecoDoForm` o normaliza
 * para `S/N`. O controle `zipCode` guarda só dígitos (a máscara é só visual, D-27).
 */
describe('endereco-form (helpers puros)', () => {
  type Campo = keyof EnderecoForm['controls'];

  /** Endereço completo e válido, usado como base: cada teste estraga UM campo por vez. */
  const BASE: Address = {
    zipCode: '22041001',
    street: 'Avenida Atlântica',
    number: '370',
    complement: null,
    district: 'Copacabana',
    city: 'Rio de Janeiro',
    state: UF.Rj,
  };

  /** Monta um formulário a partir da base, sobrescrevendo só os campos informados (texto puro). */
  function formCom(parcial: Partial<Record<Campo, string>> = {}): EnderecoForm {
    const form = criarEnderecoForm();
    form.setValue({
      zipCode: BASE.zipCode,
      street: BASE.street,
      number: BASE.number,
      complement: '',
      district: BASE.district,
      city: BASE.city,
      state: BASE.state,
      ...parcial,
    });
    return form;
  }

  const repetir = (caractere: string, vezes: number) => caractere.repeat(vezes);

  describe('criarEnderecoForm', () => {
    // Sem valor inicial, o grupo nasce com os sete controles de texto vazios (state inclusive).
    it('AC-011.1 sem valor inicial cria os 7 controles vazios e o grupo inválido', () => {
      const form = criarEnderecoForm();

      expect(Object.keys(form.controls).sort()).toEqual(
        ['city', 'complement', 'district', 'number', 'state', 'street', 'zipCode'].sort(),
      );
      expect(form.getRawValue()).toEqual({
        zipCode: '',
        street: '',
        number: '',
        complement: '',
        district: '',
        city: '',
        state: '',
      });
      expect(form.valid).toBe(false);
    });

    // Edição de um endereço salvo: o valor inicial preenche os controles e o grupo já é válido.
    it('AC-011.1 com valor inicial preenche os controles e o grupo nasce válido', () => {
      const form = criarEnderecoForm({ ...BASE, complement: 'apto 502' });

      expect(form.getRawValue()).toEqual({
        zipCode: '22041001',
        street: 'Avenida Atlântica',
        number: '370',
        complement: 'apto 502',
        district: 'Copacabana',
        city: 'Rio de Janeiro',
        state: 'RJ',
      });
      expect(form.valid).toBe(true);
    });

    // O contrato permite complemento null ou ausente: o controle de texto vira '' (nunca null).
    it('AC-011.1 complemento null ou ausente no valor inicial vira string vazia', () => {
      // Passo 1: null.
      expect(criarEnderecoForm({ ...BASE, complement: null }).controls.complement.value).toBe('');
      // Passo 2: propriedade ausente.
      const { complement: _semComplemento, ...semComplemento } = BASE;
      expect(criarEnderecoForm(semComplemento).controls.complement.value).toBe('');
    });

    // Controles não nulos: `reset()` volta para '' e nunca para null (o tipo é string).
    it('AC-011.1 os controles são não nulos: reset() devolve string vazia', () => {
      const form = criarEnderecoForm();
      form.patchValue({ street: 'Rua X', state: 'SP' });

      form.reset();

      for (const controle of Object.values(form.controls)) {
        expect(controle.value).toBe('');
      }
    });

    // Cada chamada devolve um grupo independente (três formulários na mesma tela não se misturam).
    it('D-27 cada chamada cria um grupo independente', () => {
      const a = criarEnderecoForm();
      const b = criarEnderecoForm();

      a.controls.street.setValue('Rua A');

      expect(b.controls.street.value).toBe('');
    });
  });

  describe('validação (AC-011.1 e AC-011.2)', () => {
    // A base inteira é válida, com o complemento vazio: complemento é o único opcional.
    it('AC-011.1 endereço completo com complemento vazio é válido', () => {
      expect(formCom().valid).toBe(true);
    });

    // Preencher o complemento também é válido.
    it('AC-011.1 endereço completo com complemento preenchido é válido', () => {
      expect(formCom({ complement: 'Bloco B, apto 502' }).valid).toBe(true);
    });

    // Cada campo obrigatório vazio derruba o grupo inteiro e marca o erro `required` no controle.
    it.each<[Campo]>([
      // Linha 1: CEP.
      ['zipCode'],
      // Linha 2: logradouro.
      ['street'],
      // Linha 3: número.
      ['number'],
      // Linha 4: bairro.
      ['district'],
      // Linha 5: cidade.
      ['city'],
      // Linha 6: UF.
      ['state'],
    ])('AC-011.1 %s é obrigatório (vazio invalida o grupo com erro required)', (campo) => {
      const form = formCom({ [campo]: '' });

      expect(form.valid).toBe(false);
      expect(form.controls[campo].errors?.['required']).toBeTruthy();
    });

    // Espaços não contam como preenchimento: texto só de espaços é tratado como vazio.
    it.each<[Campo]>([
      // Linha 1: logradouro só com espaços.
      ['street'],
      // Linha 2: número só com espaços.
      ['number'],
      // Linha 3: bairro só com espaços.
      ['district'],
      // Linha 4: cidade só com espaços.
      ['city'],
      // Linha 5: UF só com espaços.
      ['state'],
      // Linha 6: CEP só com espaços.
      ['zipCode'],
    ])('AC-011.1 %s só com espaços conta como vazio (required)', (campo) => {
      const form = formCom({ [campo]: '     ' });

      expect(form.valid).toBe(false);
      expect(form.controls[campo].errors?.['required']).toBeTruthy();
    });

    // O complemento é opcional: vazio e só espaços não invalidam.
    it.each([
      // Linha 1: vazio.
      [''],
      // Linha 2: só espaços.
      ['   '],
    ])('AC-011.1 complemento %j é aceito (campo opcional)', (valor) => {
      const form = formCom({ complement: valor });

      expect(form.controls.complement.valid).toBe(true);
      expect(form.valid).toBe(true);
    });

    // CEP: exatamente 8 dígitos numéricos. Cada linha é um CEP inválido de AC-011.2.
    it.each([
      // Linha 1: 7 dígitos.
      ['2204100'],
      // Linha 2: 9 dígitos.
      ['220410011'],
      // Linha 3: 1 dígito.
      ['2'],
      // Linha 4: valor com a máscara (o controle guarda só dígitos).
      ['22041-001'],
      // Linha 5: com pontuação.
      ['22.041-001'],
      // Linha 6: letra no meio de 8 caracteres.
      ['2204100a'],
      // Linha 7: 8 letras.
      ['abcdefgh'],
      // Linha 8: espaço no meio.
      ['22 41001'],
      // Linha 9: dígitos fora do alfabeto ASCII (árabe-índicos), que não são "0-9".
      ['٢٢٠٤١٠٠١'],
    ])('AC-011.2 CEP %j é inválido', (cep) => {
      const form = formCom({ zipCode: cep });

      expect(form.controls.zipCode.invalid).toBe(true);
      expect(form.controls.zipCode.errors).not.toBeNull();
      expect(form.valid).toBe(false);
    });

    // CEP válido: 8 dígitos, inclusive com zeros à esquerda (é texto, nunca número).
    it.each([
      // Linha 1: CEP comum.
      ['22041001'],
      // Linha 2: só zeros.
      ['00000000'],
      // Linha 3: zero à esquerda (Praça da Sé, SP).
      ['01001000'],
    ])('AC-011.2 CEP %j é válido', (cep) => {
      const form = formCom({ zipCode: cep });

      expect(form.controls.zipCode.valid).toBe(true);
      expect(form.valid).toBe(true);
    });

    // Número: o AC aceita número comum e "S/N". Regra do design: ^(\d{1,6}[A-Za-z]?|S/N)$.
    it.each([
      // Linha 1: um dígito.
      ['1'],
      // Linha 2: número comum.
      ['370'],
      // Linha 3: número com letra (casa 12B).
      ['12B'],
      // Linha 4: letra minúscula é aceita.
      ['12b'],
      // Linha 5: limite de 6 dígitos.
      ['999999'],
      // Linha 6: 6 dígitos com letra.
      ['123456Z'],
      // Linha 7: sem número, em maiúsculas.
      ['S/N'],
      // Linha 8: sem número, em minúsculas (decisão: aceito, normalizado depois).
      ['s/n'],
    ])('AC-011.1 número %j é válido', (numero) => {
      const form = formCom({ number: numero });

      expect(form.controls.number.valid).toBe(true);
      expect(form.valid).toBe(true);
    });

    // Número inválido: cada linha foge do padrão.
    it.each([
      // Linha 1: duas letras depois do número.
      ['12AB'],
      // Linha 2: 7 dígitos (passa do limite).
      ['1234567'],
      // Linha 3: "SN" sem a barra.
      ['SN'],
      // Linha 4: barra incompleta.
      ['S/'],
      // Linha 5: S/N com sobra.
      ['S/NN'],
      // Linha 6: letra antes do número.
      ['A12'],
      // Linha 7: sinal negativo.
      ['-5'],
      // Linha 8: espaço entre número e letra.
      ['12 B'],
      // Linha 9: decimal.
      ['1.5'],
      // Linha 10: só uma letra.
      ['B'],
    ])('AC-011.1 número %j é inválido', (numero) => {
      const form = formCom({ number: numero });

      expect(form.controls.number.invalid).toBe(true);
      expect(form.controls.number.errors).not.toBeNull();
      expect(form.valid).toBe(false);
    });

    // Limites de tamanho do contrato, na fronteira: o máximo passa, um a mais não passa.
    it.each<[Campo, number]>([
      // Linha 1: logradouro máx. 150.
      ['street', 150],
      // Linha 2: complemento máx. 80.
      ['complement', 80],
      // Linha 3: bairro máx. 80.
      ['district', 80],
      // Linha 4: cidade máx. 80.
      ['city', 80],
    ])('AC-011.1 %s aceita exatamente %i caracteres', (campo, limite) => {
      const form = formCom({ [campo]: repetir('a', limite) });

      expect(form.controls[campo].valid).toBe(true);
      expect(form.valid).toBe(true);
    });

    // Um caractere acima do limite derruba o campo e o grupo.
    it.each<[Campo, number]>([
      // Linha 1: logradouro com 151.
      ['street', 150],
      // Linha 2: complemento com 81.
      ['complement', 80],
      // Linha 3: bairro com 81.
      ['district', 80],
      // Linha 4: cidade com 81.
      ['city', 80],
    ])('AC-011.1 %s rejeita %i + 1 caracteres', (campo, limite) => {
      const form = formCom({ [campo]: repetir('a', limite + 1) });

      expect(form.controls[campo].invalid).toBe(true);
      expect(form.controls[campo].errors).not.toBeNull();
      expect(form.valid).toBe(false);
    });

    // O contrato tem exatamente 27 UFs e o formulário usa a mesma lista (Object.values(UF)).
    it('AC-011.2 o cliente gerado tem exatamente as 27 unidades da federação', () => {
      const ufs = Object.values(UF);

      expect(ufs).toHaveLength(27);
      expect(new Set(ufs).size).toBe(27);
    });

    // Cada uma das 27 UFs é aceita (uma linha por sigla, geradas do próprio contrato).
    it.each(Object.values(UF))('AC-011.2 UF %s é válida', (sigla) => {
      const form = formCom({ state: sigla });

      expect(form.controls.state.valid).toBe(true);
      expect(form.valid).toBe(true);
    });

    // Siglas que não são unidades da federação são rejeitadas.
    it.each([
      // Linha 1: sigla inexistente.
      ['XX'],
      // Linha 2: outra inexistente.
      ['ZZ'],
      // Linha 3: uma letra só.
      ['R'],
      // Linha 4: três letras.
      ['RJJ'],
      // Linha 5: nome por extenso.
      ['Rio de Janeiro'],
      // Linha 6: dígitos.
      ['12'],
    ])('AC-011.2 UF %j é inválida', (sigla) => {
      const form = formCom({ state: sigla });

      expect(form.controls.state.invalid).toBe(true);
      expect(form.controls.state.errors).not.toBeNull();
      expect(form.valid).toBe(false);
    });
  });

  describe('enderecoDoForm', () => {
    // Caso feliz: devolve exatamente o Address do contrato, sem campos a mais, complemento null.
    it('AC-011.2 converte o formulário para o Address do contrato (complemento vazio vira null)', () => {
      const endereco = enderecoDoForm(formCom());

      expect(endereco).toStrictEqual({
        zipCode: '22041001',
        street: 'Avenida Atlântica',
        number: '370',
        complement: null,
        district: 'Copacabana',
        city: 'Rio de Janeiro',
        state: 'RJ',
      });
    });

    // Complemento opcional: vazio e só espaços viram null; texto sai aparado.
    it.each([
      // Linha 1: vazio vira null.
      ['', null],
      // Linha 2: só espaços vira null.
      ['    ', null],
      // Linha 3: texto com espaços nas pontas é aparado.
      ['  apto 502  ', 'apto 502'],
      // Linha 4: texto sem espaços sobra intacto.
      ['Bloco B', 'Bloco B'],
    ])('AC-011.1 complemento %j vira %j', (digitado, esperado) => {
      expect(enderecoDoForm(formCom({ complement: digitado })).complement).toBe(esperado);
    });

    // "S/N": a sigla sai sempre em maiúsculas, mesmo digitada em minúsculas.
    it('AC-011.1 número s/n digitado em minúsculas sai como S/N', () => {
      expect(enderecoDoForm(formCom({ number: 's/n' })).number).toBe('S/N');
    });

    // Número comum sai como foi digitado (nada de converter para número nem zerar).
    it.each([
      // Linha 1: só dígitos.
      ['370'],
      // Linha 2: dígitos e letra maiúscula.
      ['12B'],
      // Linha 3: S/N já maiúsculo.
      ['S/N'],
    ])('AC-011.1 número %j sai como digitado', (numero) => {
      expect(enderecoDoForm(formCom({ number: numero })).number).toBe(numero);
    });

    // Os textos obrigatórios saem aparados (trim) nas pontas.
    it('AC-011.1 logradouro, bairro e cidade saem sem espaços nas pontas', () => {
      const endereco = enderecoDoForm(
        formCom({ street: '  Avenida Atlântica ', district: ' Copacabana  ', city: '  Rio de Janeiro ' }),
      );

      expect(endereco.street).toBe('Avenida Atlântica');
      expect(endereco.district).toBe('Copacabana');
      expect(endereco.city).toBe('Rio de Janeiro');
    });

    // A UF sai sempre em maiúsculas.
    it('AC-011.2 UF em minúsculas sai em maiúsculas', () => {
      expect(enderecoDoForm(formCom({ state: 'rj' })).state).toBe('RJ');
    });

    // O CEP sai só com 8 dígitos, sem máscara nem pontuação (AC-011.2, contrato do Address).
    it.each([
      // Linha 1: já só dígitos.
      ['22041001', '22041001'],
      // Linha 2: com a máscara.
      ['22041-001', '22041001'],
      // Linha 3: com ponto e hífen.
      ['22.041-001', '22041001'],
    ])('AC-011.2 CEP %j sai como %j (8 dígitos, sem máscara)', (digitado, esperado) => {
      const endereco = enderecoDoForm(formCom({ zipCode: digitado }));

      expect(endereco.zipCode).toBe(esperado);
      expect(endereco.zipCode).toMatch(/^\d{8}$/);
    });

    // O helper só lê: nenhuma normalização muda os valores que a pessoa vê no formulário.
    it('D-27 não altera os valores dos controles do formulário', () => {
      const form = formCom({ number: 's/n', complement: '  x  ', street: ' Rua ' });

      enderecoDoForm(form);

      expect(form.controls.number.value).toBe('s/n');
      expect(form.controls.complement.value).toBe('  x  ');
      expect(form.controls.street.value).toBe(' Rua ');
    });
  });
});
