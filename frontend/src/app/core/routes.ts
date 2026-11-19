/**
 * Caminhos das telas que os guards e o interceptor precisam conhecer (T-050, D-20, AC-012.1).
 *
 * Centralizar aqui evita strings soltas espalhadas: o interceptor leva ao login quando a sessão
 * não pode ser renovada e os guards escolhem o destino conforme o estado da conta
 * (D-33). As telas em si são criadas por outras tarefas; esta tabela só fixa os caminhos
 * que elas devem ocupar. `CRIAR_GRUPO` (T-052) é a tela pública de cadastro do criador do grupo.
 */
export const ROTAS = {
  LOGIN: '/entrar',
  CONFIRMAR_EMAIL: '/confirmar-email',
  CADASTRO_IDOSO: '/idoso/novo',
  AGUARDANDO_APROVACAO: '/aguardando-aprovacao',
  HOME: '/inicio',
  CRIAR_GRUPO: '/criar-grupo',
} as const;
