/**
 * Catálogo de mensagens pt-BR do Cuida+ (T-061, D-40).
 *
 * É a ÚNICA fonte dos textos exatos que a spec (`specs/000-fundacao/spec.md`) manda mostrar na
 * tela: nenhum componente digita esses textos à mão, todos os buscam aqui (diretamente ou pelo
 * `MensagemService`). Cada chave é nomeada pelo AC de origem no formato `AC_<nnn>_<m>_<SUFIXO>`
 * e cada valor é copiado caractere por caractere da spec; o teste `mensagens.spec.ts` lê o
 * `spec.md` e falha se qualquer texto divergir.
 *
 * Marcadores de valor variável (apenas em AC-004.7): `{restantes}` (tentativas que restam) e
 * `{tempo}` (contador MM:SS do bloqueio). Quem preenche é o `MensagemService.texto`.
 *
 * A chave `FALLBACK_ERRO_GENERICO` NÃO vem da spec: é o texto neutro usado quando o `code` do
 * erro é desconhecido ou inexistente (D-40). O catálogo é público, por isso nunca carrega dado
 * pessoal (P5).
 */
export const MENSAGENS = {
  /** AC-001.1: aviso exibido depois do cadastro do criador do grupo. */
  AC_001_1_AVISO_CONFIRMACAO:
    'Confirme seu e-mail em até 24 horas. Depois desse prazo o cadastro é descartado e será preciso começar de novo.',
  /** AC-001.1: rótulo da opção que inicia o cadastro do criador. */
  AC_001_1_ROTULO_CRIAR_GRUPO: 'Criar grupo de cuidado',
  /** AC-003.5: tentativa de convidar quem já é membro ativo ou tem pedido em análise. */
  AC_003_5_JA_NO_GRUPO: 'Esta pessoa já faz parte do grupo ou tem um pedido em análise.',
  /** AC-003.7: tentativa de convidar e-mail vinculado a outro grupo ou conta sem grupo. */
  AC_003_7_EMAIL_VINCULADO:
    'Este e-mail já está vinculado a um grupo de cuidado e não pode ser convidado.',
  /** AC-004.4: tela de conclusão do cadastro por convite. */
  AC_004_4_PEDIDO_EM_ANALISE:
    'Seu pedido de acesso será analisado pelo responsável em até 24 horas. Depois desse prazo ele expira e será preciso pedir um novo convite.',
  /** AC-004.5: convite com status VENCIDO. */
  AC_004_5_VENCIDO: 'Este código venceu. Peça um novo convite ao responsável.',
  /** AC-004.5: convite com status CANCELADO. */
  AC_004_5_CANCELADO: 'Este convite foi cancelado. Peça um novo convite ao responsável.',
  /** AC-004.5: convite com status USADO. */
  AC_004_5_USADO: 'Este código já foi usado. Se você já se cadastrou, entre com e-mail e senha.',
  /** AC-004.7: e-mail ou código errado; `{restantes}` = tentativas que restam. */
  AC_004_7_INVALIDO: 'E-mail ou código inválidos. Restam {restantes} tentativas.',
  /** AC-004.7: bloqueio por tentativas; `{tempo}` = contador regressivo MM:SS. */
  AC_004_7_BLOQUEADO: 'Bloqueado por tentativas excessivas. Tente novamente em {tempo}.',
  /** AC-006.8: aprovar ou recusar pedido cujo prazo de 24 horas já venceu. */
  AC_006_8_PEDIDO_EXPIRADO: 'Este pedido expirou. Envie um novo convite.',
  /** AC-008.2: conta com status AGUARDANDO_APROVACAO ao entrar. */
  AC_008_2_AGUARDANDO_APROVACAO: 'Seu cadastro está aguardando aprovação do responsável.',
  /** AC-008.2: conta com status RECUSADO ou EXPIRADO ao entrar (um único texto para os dois). */
  AC_008_2_NAO_APROVADO:
    'Seu pedido de acesso não foi aprovado. Peça um novo convite ao responsável.',
  /** AC-008.2: conta com status REMOVIDO ao entrar. */
  AC_008_2_REMOVIDO: 'Seu acesso foi removido pelo responsável. Peça um novo convite.',
  /** AC-011.4: consulta de CEP falhou ou passou de 15 segundos; o endereço é preenchido à mão. */
  AC_011_4_CEP_FALHA: 'Não conseguimos consultar o CEP. Preencha o endereço manualmente.',
  /** AC-012.2: aviso ao Responsável após a exclusão do grupo (volta ao login). */
  AC_012_2_GRUPO_EXCLUIDO: 'O grupo de cuidado foi excluído.',
  /** AC-013.1: aviso antes de concluir a exclusão do grupo. */
  AC_013_1_AVISO_EXCLUSAO:
    'Excluir o grupo apaga o idoso, todos os registros e todas as contas, inclusive a sua. Isso não pode ser desfeito.',
  /** AC-013.1: rótulo do botão que confirma a exclusão do grupo. */
  AC_013_1_BOTAO_EXCLUIR: 'Excluir definitivamente',
  /** AC-015.2: ação rejeitada por falta de permissão do papel. */
  AC_015_2_SEM_PERMISSAO: 'Você não tem permissão para esta ação.',
  /** NÃO vem da spec (D-40): texto neutro para `code` desconhecido ou ausente. */
  FALLBACK_ERRO_GENERICO: 'Não foi possível concluir a ação. Tente novamente.',
} as const;

/** Chave válida do catálogo: só compila quem usa uma chave que existe em `MENSAGENS`. */
export type ChaveMensagem = keyof typeof MENSAGENS;
