/// <reference types="node" />
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { MENSAGENS, ChaveMensagem } from './mensagens';

/**
 * Cobre o catálogo de mensagens pt-BR `MENSAGENS` (T-061, D-40): todo texto exato que a spec
 * (`specs/000-fundacao/spec.md`) manda mostrar na tela — ACs 001.1, 003.5, 003.7, 004.4, 004.5,
 * 004.7, 006.8, 008.2, 011.4, 012.2 (linha "exclusão do grupo"), 013.1 e 015.2 — precisa existir
 * no catálogo CARACTERE POR CARACTERE, numa chave nomeada pelo AC (`AC_<nnn>_<m>_<SUFIXO>`).
 *
 * Três camadas de prova, para que uma falha de transcrição não passe despercebida:
 *  1. VARREDURA DA SPEC: o teste lê o `spec.md` do disco, isola o bloco de cada AC, extrai os
 *     trechos entre aspas duplas retas e exige que cada um seja o valor de alguma chave do
 *     catálogo (com o prefixo do AC de origem). É o "Pronto quando" da T-061.
 *  2. TESTES DIRETOS: o valor literal de cada texto copiado da spec, um por texto, caso o
 *     extrator falhe de forma coordenada com o catálogo.
 *  3. PROVAS DE NÃO-VACUIDADE: a contagem de textos por AC é fixada (regex quebrada que extrai
 *     zero textos falha) e o mesmo comparador, aplicado a catálogos adulterados em memória, tem
 *     que acusar divergência (o comparador não é frouxo).
 *
 * Marcadores de valor variável: em AC-004.7, `N` vira `{restantes}` e `MM:SS` vira `{tempo}`.
 * Além dos textos da spec existe UMA chave extra, `FALLBACK_ERRO_GENERICO` (texto genérico para
 * código de erro desconhecido, NÃO vem da spec).
 *
 * T-052 (textos APROVADOS pelo dono da spec, fora do `spec.md`): o catálogo ganhou 12 chaves
 * (mensagens de validação dos formulários, `VAL_*`, e as de senha/código de AC-001.3 e AC-005.x).
 * Elas NÃO entram na varredura da spec (não estão lá): têm a tabela própria
 * `TEXTOS_APROVADOS_FORA_DA_SPEC`, com o texto literal exato, e o catálogo precisa ser EXATAMENTE
 * (chaves da spec ∪ essas 12 ∪ `FALLBACK_ERRO_GENERICO`), com cada texto aprovado igual
 * caractere por caractere. A varredura da spec continua exatamente como era.
 * Marcadores: além dos de AC-004.7, `{restantes}` também em `AC_005_2_CODIGO_INCORRETO`.
 *
 * Leitura do arquivo: o Vitest roda em Node; `@types/node` não está em `tsconfig.spec.json`
 * (`types: ["vitest/globals"]`), por isso a diretiva `/// <reference types="node" />` no topo
 * deste arquivo, sem alterar nenhuma configuração do projeto.
 */

// ---------------------------------------------------------------------------------------------
// Leitura e extração (vivem neste arquivo de propósito; só provam o catálogo)
// ---------------------------------------------------------------------------------------------

/** Sobe a partir do diretório atual até achar `specs/000-fundacao/spec.md` (cwd = `frontend/`). */
function localizarSpec(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i++) {
    const candidato = join(dir, 'specs', '000-fundacao', 'spec.md');
    if (existsSync(candidato)) {
      return candidato;
    }
    dir = dirname(dir);
  }
  throw new Error(`spec.md não encontrado subindo a partir de ${process.cwd()}`);
}

/** Texto extraído da spec, com o AC de onde veio (ex.: `004.7`). */
export interface TextoDaSpec {
  ac: string;
  texto: string;
}

/** Motivo pelo qual um texto da spec não está corretamente no catálogo. */
export interface Divergencia {
  ac: string;
  texto: string;
  motivo: 'AUSENTE_NO_CATALOGO' | 'CHAVE_COM_PREFIXO_ERRADO';
}

/** Prefixo de chave de um AC: `004.7` -> `AC_004_7_`. */
export function prefixoDoAc(ac: string): string {
  return `AC_${ac.replace('.', '_')}_`;
}

/**
 * Isola o bloco de um AC: da linha `- **AC-xxx.y**` até o próximo item `- **AC-` ou título.
 * Sub-itens indentados (`  - `) e a tabela de AC-012.2 pertencem ao bloco.
 */
export function blocoDoAc(markdown: string, ac: string): string {
  const linhas = markdown.replace(/\r\n/g, '\n').split('\n');
  const inicio = linhas.findIndex((l) => l.startsWith(`- **AC-${ac}**`));
  if (inicio < 0) {
    throw new Error(`AC-${ac} não encontrado na spec`);
  }
  let fim = inicio + 1;
  while (fim < linhas.length && !/^- \*\*AC-\d/.test(linhas[fim]) && !/^#{1,6} /.test(linhas[fim])) {
    fim++;
  }
  return linhas.slice(inicio, fim).join('\n');
}

/**
 * Extrai os trechos entre aspas duplas retas de um trecho de Markdown: remove `**`, junta
 * qualquer sequência de espaços/quebras de linha em um único espaço (a spec quebra linha no
 * meio de frases) e, em AC-004.7, troca `N` por `{restantes}` e `MM:SS` por `{tempo}`.
 */
export function extrairTextos(trechoMarkdown: string, ac: string): TextoDaSpec[] {
  const semNegrito = trechoMarkdown.replace(/\*\*/g, '');
  const textos = [...semNegrito.matchAll(/"([^"]+)"/g)].map((m) => m[1].replace(/\s+/g, ' '));
  return textos.map((t) => ({
    ac,
    texto:
      ac === '004.7' ? t.replace(/\bN\b/g, '{restantes}').replace('MM:SS', '{tempo}') : t,
  }));
}

/** Para AC-012.2 só interessa a linha "exclusão do grupo" da tabela (as demais linhas não têm texto exato). */
export function linhaExclusaoDoGrupo(markdown: string): string {
  const linha = blocoDoAc(markdown, '012.2')
    .split('\n')
    .find((l) => l.trimStart().startsWith('|') && l.includes('exclusão do grupo'));
  if (linha === undefined) {
    throw new Error('linha "exclusão do grupo" não encontrada na tabela de AC-012.2');
  }
  return linha;
}

/** Textos exatos de um AC (AC-012.2: só a linha da tabela). */
export function textosDoAc(markdown: string, ac: string): TextoDaSpec[] {
  const trecho = ac === '012.2' ? linhaExclusaoDoGrupo(markdown) : blocoDoAc(markdown, ac);
  return extrairTextos(trecho, ac);
}

/**
 * Comparador: cada texto da spec precisa ser IGUAL (===, sem normalizar) ao valor de alguma
 * chave do catálogo, e essa chave precisa ter o prefixo do AC de origem. Devolve as divergências
 * (lista vazia = catálogo fiel à spec).
 */
export function compararComCatalogo(
  textos: readonly TextoDaSpec[],
  catalogo: Readonly<Record<string, string>>,
): Divergencia[] {
  const divergencias: Divergencia[] = [];
  for (const { ac, texto } of textos) {
    const chaves = Object.keys(catalogo).filter((k) => catalogo[k] === texto);
    if (chaves.length === 0) {
      divergencias.push({ ac, texto, motivo: 'AUSENTE_NO_CATALOGO' });
    } else if (!chaves.some((k) => k.startsWith(prefixoDoAc(ac)))) {
      divergencias.push({ ac, texto, motivo: 'CHAVE_COM_PREFIXO_ERRADO' });
    }
  }
  return divergencias;
}

// ---------------------------------------------------------------------------------------------
// Dados fixos dos testes
// ---------------------------------------------------------------------------------------------

/** ACs da T-061 e a quantidade EXATA de textos entre aspas que a spec traz em cada um. */
const ACS_E_CONTAGEM: ReadonlyArray<readonly [string, number]> = [
  ['001.1', 2], // aviso de 24 h + rótulo "Criar grupo de cuidado"
  ['003.5', 1],
  ['003.7', 1],
  ['004.4', 1],
  ['004.5', 3], // VENCIDO, CANCELADO, USADO
  ['004.7', 2], // código errado + bloqueio
  ['006.8', 1],
  ['008.2', 3], // aguardando aprovação, não aprovado (RECUSADO/EXPIRADO), removido
  ['011.4', 1],
  ['012.2', 1], // só a linha "exclusão do grupo"
  ['013.1', 2], // aviso + "Excluir definitivamente"
  ['015.2', 1],
];

/** As 19 chaves dos textos exatos da spec (convenção `AC_<nnn>_<m>_<SUFIXO>`). */
const CHAVES_DA_SPEC = [
  'AC_001_1_AVISO_CONFIRMACAO',
  'AC_001_1_ROTULO_CRIAR_GRUPO',
  'AC_003_5_JA_NO_GRUPO',
  'AC_003_7_EMAIL_VINCULADO',
  'AC_004_4_PEDIDO_EM_ANALISE',
  'AC_004_5_VENCIDO',
  'AC_004_5_CANCELADO',
  'AC_004_5_USADO',
  'AC_004_7_INVALIDO',
  'AC_004_7_BLOQUEADO',
  'AC_006_8_PEDIDO_EXPIRADO',
  'AC_008_2_AGUARDANDO_APROVACAO',
  'AC_008_2_NAO_APROVADO',
  'AC_008_2_REMOVIDO',
  'AC_011_4_CEP_FALHA',
  'AC_012_2_GRUPO_EXCLUIDO',
  'AC_013_1_AVISO_EXCLUSAO',
  'AC_013_1_BOTAO_EXCLUIR',
  'AC_015_2_SEM_PERMISSAO',
];

/**
 * T-052: textos aprovados pelo dono da spec que NÃO estão no `spec.md` (mensagens de validação dos
 * formulários e de senha/código). Chave -> texto LITERAL exato, digitado à parte de qualquer
 * extrator: o catálogo precisa ter cada um caractere por caractere.
 */
const TEXTOS_APROVADOS_FORA_DA_SPEC: Readonly<Record<string, string>> = {
  VAL_OBRIGATORIO: 'Preencha este campo.',
  VAL_NOME_CURTO: 'O nome precisa ter pelo menos 2 letras.',
  VAL_EMAIL_INVALIDO: 'Informe um e-mail válido.',
  VAL_TELEFONE_INVALIDO: 'Informe o telefone com DDD, só números (10 ou 11 dígitos).',
  AC_001_3_SENHA_REGRA:
    'A senha deve ter no mínimo 8 caracteres, com pelo menos uma letra e um número.',
  VAL_CEP_INVALIDO: 'Informe o CEP com 8 números.',
  VAL_NUMERO_INVALIDO: 'Informe o número (ex.: 120 ou 12B) ou S/N.',
  VAL_UF_OBRIGATORIA: 'Escolha o estado.',
  AC_005_2_CODIGO_INCORRETO: 'Código incorreto. Tentativas restantes: {restantes}.',
  AC_005_1_CODIGO_EXPIRADO: 'Este código expirou ou foi bloqueado. Peça um novo código.',
  AC_001_6_CODIGO_REENVIADO: 'Se o e-mail estiver cadastrado, enviaremos um novo código.',
  AC_005_3_AGUARDE_REENVIO: 'Aguarde um minuto para pedir outro código.',
};

/** Chaves aprovadas fora da spec (as 12 da tabela acima). */
const CHAVES_APROVADAS = Object.keys(TEXTOS_APROVADOS_FORA_DA_SPEC);

/** Todas as chaves esperadas do catálogo: spec ∪ aprovadas fora da spec ∪ o fallback. */
const CHAVES_ESPERADAS = [...CHAVES_DA_SPEC, ...CHAVES_APROVADAS, 'FALLBACK_ERRO_GENERICO'].sort();

/**
 * Compara os textos aprovados com um catálogo: devolve as chaves cujo texto está ausente ou
 * difere (comparação estrita `===`, sem normalizar). Lista vazia = catálogo fiel aos aprovados.
 */
function divergenciasDosAprovados(
  aprovados: Readonly<Record<string, string>>,
  catalogoAvaliado: Readonly<Record<string, string>>,
): string[] {
  return Object.keys(aprovados).filter((chave) => catalogoAvaliado[chave] !== aprovados[chave]);
}

/**
 * Valores LITERAIS copiados da spec (um por texto), digitados à parte do extrator. Se o extrator
 * e o catálogo errarem juntos, estes literais ainda acusam.
 */
const LITERAIS: ReadonlyArray<readonly [string, ChaveMensagem, string]> = [
  [
    'AC-001.1',
    'AC_001_1_AVISO_CONFIRMACAO',
    'Confirme seu e-mail em até 24 horas. Depois desse prazo o cadastro é descartado e será preciso começar de novo.',
  ],
  ['AC-001.1', 'AC_001_1_ROTULO_CRIAR_GRUPO', 'Criar grupo de cuidado'],
  [
    'AC-003.5',
    'AC_003_5_JA_NO_GRUPO',
    'Esta pessoa já faz parte do grupo ou tem um pedido em análise.',
  ],
  [
    'AC-003.7',
    'AC_003_7_EMAIL_VINCULADO',
    'Este e-mail já está vinculado a um grupo de cuidado e não pode ser convidado.',
  ],
  [
    'AC-004.4',
    'AC_004_4_PEDIDO_EM_ANALISE',
    'Seu pedido de acesso será analisado pelo responsável em até 24 horas. Depois desse prazo ele expira e será preciso pedir um novo convite.',
  ],
  [
    'AC-004.5 (VENCIDO)',
    'AC_004_5_VENCIDO',
    'Este código venceu. Peça um novo convite ao responsável.',
  ],
  [
    'AC-004.5 (CANCELADO)',
    'AC_004_5_CANCELADO',
    'Este convite foi cancelado. Peça um novo convite ao responsável.',
  ],
  [
    'AC-004.5 (USADO)',
    'AC_004_5_USADO',
    'Este código já foi usado. Se você já se cadastrou, entre com e-mail e senha.',
  ],
  [
    'AC-004.7 (código errado)',
    'AC_004_7_INVALIDO',
    'E-mail ou código inválidos. Restam {restantes} tentativas.',
  ],
  [
    'AC-004.7 (bloqueio)',
    'AC_004_7_BLOQUEADO',
    'Bloqueado por tentativas excessivas. Tente novamente em {tempo}.',
  ],
  ['AC-006.8', 'AC_006_8_PEDIDO_EXPIRADO', 'Este pedido expirou. Envie um novo convite.'],
  [
    'AC-008.2 (AGUARDANDO_APROVACAO)',
    'AC_008_2_AGUARDANDO_APROVACAO',
    'Seu cadastro está aguardando aprovação do responsável.',
  ],
  [
    'AC-008.2 (RECUSADO ou EXPIRADO)',
    'AC_008_2_NAO_APROVADO',
    'Seu pedido de acesso não foi aprovado. Peça um novo convite ao responsável.',
  ],
  [
    'AC-008.2 (REMOVIDO)',
    'AC_008_2_REMOVIDO',
    'Seu acesso foi removido pelo responsável. Peça um novo convite.',
  ],
  [
    'AC-011.4',
    'AC_011_4_CEP_FALHA',
    'Não conseguimos consultar o CEP. Preencha o endereço manualmente.',
  ],
  ['AC-012.2 (exclusão do grupo)', 'AC_012_2_GRUPO_EXCLUIDO', 'O grupo de cuidado foi excluído.'],
  [
    'AC-013.1 (aviso)',
    'AC_013_1_AVISO_EXCLUSAO',
    'Excluir o grupo apaga o idoso, todos os registros e todas as contas, inclusive a sua. Isso não pode ser desfeito.',
  ],
  ['AC-013.1 (botão)', 'AC_013_1_BOTAO_EXCLUIR', 'Excluir definitivamente'],
  ['AC-015.2', 'AC_015_2_SEM_PERMISSAO', 'Você não tem permissão para esta ação.'],
];

/** O catálogo visto como dicionário genérico de strings (para o comparador). */
const catalogo: Readonly<Record<string, string>> = MENSAGENS;

describe('Catálogo de mensagens pt-BR (T-061, D-40)', () => {
  const spec = readFileSync(localizarSpec(), 'utf-8');

  describe('varredura da spec: cada texto exato está no catálogo, caractere por caractere', () => {
    // Uma linha por AC. Cada execução prova: (1) a spec tem a contagem esperada de textos
    // (extrator não é vacuoso) e (2) todos eles existem no catálogo com o prefixo do AC.
    it.each(ACS_E_CONTAGEM)(
      'AC-%s todos os textos exatos da spec constam no catálogo (esperados: %i)',
      (ac, quantidade) => {
        const textos = textosDoAc(spec, ac);

        // Proteção contra vacuidade: se a regex quebrar e extrair 0 textos, falha aqui.
        expect(textos).toHaveLength(quantidade);
        // Igualdade exata (acentos, pontuação, espaços) e prefixo de chave do AC de origem.
        expect(compararComCatalogo(textos, catalogo)).toEqual([]);
      },
    );

    // Fecha a conta: 19 textos no total = 19 chaves da spec (+ as 12 aprovadas + o fallback), nenhuma sobrando.
    it('D-40 o total de textos extraídos da spec bate com o das chaves do catálogo (menos o fallback e as 12 aprovadas fora da spec)', () => {
      const todos = ACS_E_CONTAGEM.flatMap(([ac]) => textosDoAc(spec, ac));
      expect(todos).toHaveLength(19);

      // Cada texto da spec corresponde a UMA chave e vice-versa (fora o fallback).
      const valoresDaSpec = todos.map((t) => t.texto).sort();
      const valoresDoCatalogo = Object.entries(catalogo)
        .filter(([chave]) => chave !== 'FALLBACK_ERRO_GENERICO' && !CHAVES_APROVADAS.includes(chave))
        .map(([, valor]) => valor)
        .sort();
      expect(valoresDoCatalogo).toEqual(valoresDaSpec);
    });
  });

  describe('o extrator e o comparador funcionam (sanidade, não vacuidade)', () => {
    // Markdown sintético com as armadilhas da spec: frase quebrada em duas linhas, texto em
    // negrito, aspas no AC vizinho (não pode vazar) e marcadores N / MM:SS de AC-004.7.
    const sintetico = [
      '### AC-009 Algo',
      '- **AC-009.1** Antes **"Texto numa',
      '  frase quebrada."** depois.',
      '- **AC-009.2** Outro AC com "vizinho" que não pode vazar.',
      '- **AC-004.7** Erro **"Restam N tentativas."** e **"Tente em MM:SS."**',
    ].join('\n');

    // O bloco para no próximo AC; quebra de linha vira um único espaço; `**` é removido.
    it('o extrator isola o bloco do AC, junta linhas quebradas e remove o negrito', () => {
      expect(textosDoAc(sintetico, '009.1')).toEqual([
        { ac: '009.1', texto: 'Texto numa frase quebrada.' },
      ]);
    });

    // Só em AC-004.7 os valores variáveis viram marcadores.
    it('AC-004.7 o extrator troca N por {restantes} e MM:SS por {tempo}', () => {
      expect(textosDoAc(sintetico, '004.7').map((t) => t.texto)).toEqual([
        'Restam {restantes} tentativas.',
        'Tente em {tempo}.',
      ]);
    });

    // Catálogo sintético idêntico à spec: zero divergências (linha de base do comparador).
    it('o comparador não acusa nada quando o catálogo é fiel à spec', () => {
      const textos = ACS_E_CONTAGEM.flatMap(([ac]) => textosDoAc(spec, ac));
      const fiel = Object.fromEntries(
        textos.map((t, i) => [`${prefixoDoAc(t.ac)}T${i}`, t.texto]),
      );
      expect(compararComCatalogo(textos, fiel)).toEqual([]);
    });

    // Mutação 1: uma vírgula trocada por ponto e vírgula num texto de AC-013.1.
    it('o comparador acusa divergência quando uma vírgula do texto é trocada (mutação)', () => {
      const textos = ACS_E_CONTAGEM.flatMap(([ac]) => textosDoAc(spec, ac));
      const adulterado = Object.fromEntries(
        textos.map((t, i) => [
          `${prefixoDoAc(t.ac)}T${i}`,
          t.ac === '013.1' && t.texto.includes(',') ? t.texto.replace(',', ';') : t.texto,
        ]),
      );
      const divergencias = compararComCatalogo(textos, adulterado);
      expect(divergencias).toHaveLength(1);
      expect(divergencias[0].ac).toBe('013.1');
      expect(divergencias[0].motivo).toBe('AUSENTE_NO_CATALOGO');
    });

    // Mutação 2: ponto final removido, espaço sobrando e acento perdido também precisam ser acusados.
    it('o comparador acusa ponto final removido, espaço extra e acento perdido (mutações)', () => {
      const alvo: TextoDaSpec = { ac: '006.8', texto: 'Este pedido expirou. Envie um novo convite.' };
      const mutacoes = [
        'Este pedido expirou. Envie um novo convite', // sem ponto final
        'Este pedido expirou. Envie um novo convite. ', // espaço no fim
        'Este pedido expirou.  Envie um novo convite.', // espaço duplo
        'Este pedido expirou. Envie um novo convite!', // pontuação trocada
        'Este  pedido expirou. Envie um novo convite.',
      ];
      for (const mutada of mutacoes) {
        expect(compararComCatalogo([alvo], { AC_006_8_X: mutada })).toHaveLength(1);
      }
      const comAcento: TextoDaSpec = { ac: '003.5', texto: 'Esta pessoa já faz parte do grupo.' };
      expect(
        compararComCatalogo([comAcento], { AC_003_5_X: 'Esta pessoa ja faz parte do grupo.' }),
      ).toHaveLength(1);
    });

    // Mutação 3: texto certo, mas numa chave de outro AC (catálogo desorganizado).
    it('o comparador acusa texto correto registrado sob o prefixo de outro AC (mutação)', () => {
      const alvo: TextoDaSpec = { ac: '006.8', texto: 'Este pedido expirou. Envie um novo convite.' };
      const divergencias = compararComCatalogo([alvo], {
        AC_004_5_X: 'Este pedido expirou. Envie um novo convite.',
      });
      expect(divergencias).toEqual([{ ...alvo, motivo: 'CHAVE_COM_PREFIXO_ERRADO' }]);
    });

    // Mutação 4: chave removida do catálogo.
    it('o comparador acusa texto ausente do catálogo (mutação)', () => {
      const alvo: TextoDaSpec = { ac: '006.8', texto: 'Este pedido expirou. Envie um novo convite.' };
      expect(compararComCatalogo([alvo], {})).toEqual([{ ...alvo, motivo: 'AUSENTE_NO_CATALOGO' }]);
    });
  });

  describe('textos literais da spec (um teste por texto)', () => {
    // Cada linha: AC, chave definida e o texto exato digitado a partir da spec.
    it.each(LITERAIS)('%s o texto da chave %s é exatamente o da spec', (_ac, chave, texto) => {
      expect(MENSAGENS[chave]).toBe(texto);
    });
  });

  describe('textos aprovados fora da spec (T-052): um teste por texto, caractere por caractere', () => {
    // Cada linha: chave aprovada e o texto literal exato da tabela (digitado à parte do catálogo).
    it.each(Object.entries(TEXTOS_APROVADOS_FORA_DA_SPEC))(
      'T-052 o texto da chave %s é exatamente o aprovado: %s',
      (chave, texto) => {
        // A chave existe (senão `toBe` compararia com undefined e a mensagem seria confusa).
        expect(Object.keys(catalogo), `a chave ${chave} precisa existir no catálogo`).toContain(chave);
        expect(catalogo[chave]).toBe(texto);
      },
    );

    // Nenhuma divergência no conjunto todo (a mesma verificação, como lista, para o relatório de falha).
    it('T-052 o catálogo não diverge em nenhum dos 12 textos aprovados', () => {
      expect(divergenciasDosAprovados(TEXTOS_APROVADOS_FORA_DA_SPEC, catalogo)).toEqual([]);
    });

    // Os 12 textos aprovados não repetem nenhum texto da spec (cada texto tem um dono só).
    it('T-052 nenhum texto aprovado coincide com um texto da spec', () => {
      const daSpec = ACS_E_CONTAGEM.flatMap(([ac]) => textosDoAc(spec, ac)).map((t) => t.texto);
      for (const [chave, texto] of Object.entries(TEXTOS_APROVADOS_FORA_DA_SPEC)) {
        expect(daSpec, chave).not.toContain(texto);
      }
    });

    // Não-vacuidade do comparador: um catálogo fiel não acusa nada e cada mutação é acusada.
    it('o comparador dos aprovados não acusa nada num catálogo fiel (linha de base)', () => {
      expect(divergenciasDosAprovados(TEXTOS_APROVADOS_FORA_DA_SPEC, { ...TEXTOS_APROVADOS_FORA_DA_SPEC })).toEqual([]);
    });

    it('o comparador dos aprovados acusa ponto final removido, espaço extra, acento perdido e chave ausente (mutações)', () => {
      const fiel = { ...TEXTOS_APROVADOS_FORA_DA_SPEC };

      // Mutação 1: ponto final removido.
      expect(divergenciasDosAprovados(fiel, { ...fiel, VAL_NOME_CURTO: 'O nome precisa ter pelo menos 2 letras' })).toEqual([
        'VAL_NOME_CURTO',
      ]);
      // Mutação 2: espaço duplo no meio do texto.
      expect(divergenciasDosAprovados(fiel, { ...fiel, VAL_OBRIGATORIO: 'Preencha  este campo.' })).toEqual([
        'VAL_OBRIGATORIO',
      ]);
      // Mutação 3: acento perdido ("válido" -> "valido").
      expect(divergenciasDosAprovados(fiel, { ...fiel, VAL_EMAIL_INVALIDO: 'Informe um e-mail valido.' })).toEqual([
        'VAL_EMAIL_INVALIDO',
      ]);
      // Mutação 4: marcador renomeado ({restantes} -> {n}).
      expect(
        divergenciasDosAprovados(fiel, { ...fiel, AC_005_2_CODIGO_INCORRETO: 'Código incorreto. Tentativas restantes: {n}.' }),
      ).toEqual(['AC_005_2_CODIGO_INCORRETO']);
      // Mutação 5: chave removida do catálogo.
      const semUf = Object.fromEntries(Object.entries(fiel).filter(([chave]) => chave !== 'VAL_UF_OBRIGATORIA'));
      expect(divergenciasDosAprovados(fiel, semUf)).toEqual(['VAL_UF_OBRIGATORIA']);
    });
  });

  describe('forma do catálogo', () => {
    // Conjunto exato de chaves: nenhuma faltando, nenhuma extra.
    it('D-40 o catálogo tem exatamente as chaves combinadas (19 da spec + 12 aprovadas fora da spec + o fallback)', () => {
      expect(Object.keys(MENSAGENS).sort()).toEqual(CHAVES_ESPERADAS);
      // 19 + 12 + 1: o conjunto esperado não pode ter chave repetida nem tabela vazia (não-vacuidade).
      expect(CHAVES_DA_SPEC).toHaveLength(19);
      expect(CHAVES_APROVADAS).toHaveLength(12);
      expect(new Set(CHAVES_ESPERADAS).size).toBe(32);
    });

    // Toda chave, exceto o fallback, segue o padrão nomeado pelo AC; as 12 aprovadas fora da
    // spec também podem ser `VAL_<SUFIXO>` (validação de formulário, sem AC próprio).
    it('D-40 toda chave (exceto o fallback) segue o padrão AC_<nnn>_<m>_<SUFIXO> ou, nas aprovadas, VAL_<SUFIXO>', () => {
      for (const chave of Object.keys(MENSAGENS)) {
        if (chave === 'FALLBACK_ERRO_GENERICO') {
          continue;
        }
        if (CHAVES_APROVADAS.includes(chave)) {
          // Aprovadas fora da spec: nomeadas pelo AC de origem ou prefixadas por VAL_.
          expect(chave).toMatch(/^(AC_\d{3}_\d{1,2}|VAL)_[A-Z][A-Z0-9_]*$/);
        } else {
          // Textos da spec: SEMPRE nomeados pelo AC, sem exceção.
          expect(chave).toMatch(/^AC_\d{3}_\d{1,2}_[A-Z][A-Z0-9_]*$/);
        }
      }
    });

    // Valores: string, não vazia, sem espaço sobrando nas pontas.
    it('todos os valores são strings não vazias, sem espaços sobrando nas pontas', () => {
      for (const [chave, valor] of Object.entries(catalogo)) {
        expect(typeof valor, chave).toBe('string');
        expect(valor.length, chave).toBeGreaterThan(0);
        expect(valor, chave).toBe(valor.trim());
      }
    });

    // Um texto por chave e nenhuma duplicata (a spec não repete texto entre ACs).
    it('nenhum texto aparece em duas chaves diferentes', () => {
      const valores = Object.values(catalogo);
      expect(new Set(valores).size).toBe(valores.length);
    });

    // Marcadores `{...}`: exatamente três chaves os têm, cada uma com o seu (AC-004.7 e AC-005.2).
    it('AC-004.7 e AC-005.2 os únicos marcadores do catálogo são {restantes} e {tempo}, nas três chaves esperadas', () => {
      const marcadoresPorChave = Object.entries(catalogo)
        .map(([chave, valor]) => [chave, [...valor.matchAll(/\{[^}]*\}/g)].map((m) => m[0])] as const)
        .filter(([, marcadores]) => marcadores.length > 0)
        .sort(([a], [b]) => a.localeCompare(b));

      expect(marcadoresPorChave).toEqual([
        // AC-004.7: tentativas que restam no código de convite.
        ['AC_004_7_BLOQUEADO', ['{tempo}']],
        ['AC_004_7_INVALIDO', ['{restantes}']],
        // AC-005.2: tentativas que restam no código de confirmação de e-mail.
        ['AC_005_2_CODIGO_INCORRETO', ['{restantes}']],
      ]);
    });

    // Chaves de AC-004.5 e AC-008.2: textos distintos por situação, mesmo no mesmo AC.
    it('AC-004.5 e AC-008.2 usam chaves distintas para cada situação com texto distinto', () => {
      expect(
        new Set([MENSAGENS.AC_004_5_VENCIDO, MENSAGENS.AC_004_5_CANCELADO, MENSAGENS.AC_004_5_USADO])
          .size,
      ).toBe(3);
      expect(
        new Set([
          MENSAGENS.AC_008_2_AGUARDANDO_APROVACAO,
          MENSAGENS.AC_008_2_NAO_APROVADO,
          MENSAGENS.AC_008_2_REMOVIDO,
        ]).size,
      ).toBe(3);
    });

    // P5: o catálogo é público e nunca carrega dado pessoal; o mínimo verificável é não ter "@".
    it('P5 nenhum texto do catálogo contém "@" (nada de e-mail ou dado pessoal)', () => {
      for (const [chave, valor] of Object.entries(catalogo)) {
        expect(valor, chave).not.toContain('@');
      }
    });

    // O fallback NÃO vem da spec: só garantimos que é um texto simples, sem marcador e que não
    // repete nenhum texto de AC (senão confundiria o tratamento de código desconhecido).
    it('D-40 FALLBACK_ERRO_GENERICO (texto que não vem da spec) é simples e sem marcadores', () => {
      const fallback = MENSAGENS.FALLBACK_ERRO_GENERICO;
      expect(fallback).not.toMatch(/\{[^}]*\}/);
      expect(Object.entries(catalogo).filter(([, v]) => v === fallback)).toHaveLength(1);
    });
  });
});
