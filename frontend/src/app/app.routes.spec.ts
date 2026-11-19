import { Route } from '@angular/router';
import { routes } from './app.routes';
import { ROTAS } from './core/routes';
import { ConfirmarEmailComponent } from './auth/confirmar-email/confirmar-email.component';
import { CriarGrupoComponent } from './auth/criar-grupo/criar-grupo.component';

/**
 * Cobre a tabela de rotas do app (T-052, D-20, D-33) no que a tarefa acrescenta: as duas telas
 * PÚBLICAS do cadastro do criador do grupo, `criar-grupo` (AC-001.1) e `confirmar-email`
 * (AC-001.2, AC-001.6, AC-005.x).
 *
 * O que se prova aqui:
 *  - `ROTAS.CRIAR_GRUPO` existe e vale `/criar-grupo`; `ROTAS.CONFIRMAR_EMAIL` continua
 *    `/confirmar-email` e corresponde ao `path` registrado (os guards e o interceptor redirecionam
 *    por esta tabela, então path e constante não podem divergir); os demais valores de `ROTAS`
 *    não mudaram;
 *  - as duas rotas são LAZY (`loadComponent` definido, `component` ausente), para o bundle
 *    inicial não carregar telas que nem todo visitante usa, e carregam o componente certo;
 *  - as duas rotas são PÚBLICAS: sem `canActivate`, `canActivateChild`, `canMatch` nem
 *    `canDeactivate`. Quem ainda não tem conta (ou ainda não confirmou o e-mail) precisa abri-las
 *    sem sessão, e um guard aqui criaria laço de redirecionamento (D-20: o backend é quem manda).
 */
describe('Rotas do app (T-052)', () => {
  /** Procura a rota pelo path (sem barra inicial); falha com mensagem clara se não existir. */
  function rotaDe(path: string): Route {
    const rota = routes.find((r) => r.path === path);
    expect(rota, `a rota "${path}" deveria existir em app.routes.ts`).toBeDefined();
    return rota as Route;
  }

  // A constante nova vale exatamente o que a decisão de desenho fixou.
  it('AC-001.1 ROTAS.CRIAR_GRUPO vale "/criar-grupo"', () => {
    expect(ROTAS.CRIAR_GRUPO).toBe('/criar-grupo');
  });

  // Nenhum outro caminho muda: guards e interceptor já dependem destes valores (T-050).
  it('D-20 os demais valores de ROTAS ficam intactos', () => {
    expect(ROTAS).toEqual({
      LOGIN: '/entrar',
      CONFIRMAR_EMAIL: '/confirmar-email',
      CADASTRO_IDOSO: '/idoso/novo',
      AGUARDANDO_APROVACAO: '/aguardando-aprovacao',
      HOME: '/inicio',
      CRIAR_GRUPO: '/criar-grupo',
    });
  });

  // Cada constante corresponde ao path registrado (a barra inicial é só da constante).
  it.each([
    // Linha 1: tela de cadastro do criador.
    ['criar-grupo', ROTAS.CRIAR_GRUPO],
    // Linha 2: tela de confirmação do e-mail (destino dos guards, AC-008.2).
    ['confirmar-email', ROTAS.CONFIRMAR_EMAIL],
  ])('AC-001.1 a rota "%s" existe e corresponde à constante %s', (path, constante) => {
    expect(rotaDe(path).path).toBeDefined();
    expect(constante).toBe(`/${path}`);
  });

  // Lazy: `loadComponent` é função e o componente não é importado de forma ansiosa na rota.
  it.each(['criar-grupo', 'confirmar-email'])('AC-001.1 a rota "%s" é carregada sob demanda (loadComponent)', (path) => {
    const rota = rotaDe(path);

    expect(typeof rota.loadComponent).toBe('function');
    expect(rota.component).toBeUndefined();
    expect(rota.loadChildren).toBeUndefined();
  });

  // O loadComponent entrega o componente esperado (e não, por engano, o da outra tela).
  it('AC-001.1 loadComponent de "criar-grupo" entrega o CriarGrupoComponent', async () => {
    const carregado = await (rotaDe('criar-grupo').loadComponent as () => Promise<unknown>)();

    expect(carregado).toBe(CriarGrupoComponent);
  });

  // Idem para a tela de confirmação: o loadComponent entrega o ConfirmarEmailComponent (AC-001.2, AC-005.x).
  it('AC-001.2 loadComponent de "confirmar-email" entrega o ConfirmarEmailComponent', async () => {
    const carregado = await (rotaDe('confirmar-email').loadComponent as () => Promise<unknown>)();

    expect(carregado).toBe(ConfirmarEmailComponent);
  });

  // Públicas: nenhum guard de nenhum tipo (quem não tem conta precisa chegar a estas telas).
  it.each(['criar-grupo', 'confirmar-email'])('D-20 a rota "%s" é pública: sem nenhum guard', (path) => {
    const rota = rotaDe(path);

    expect(rota.canActivate).toBeUndefined();
    expect(rota.canActivateChild).toBeUndefined();
    expect(rota.canMatch).toBeUndefined();
    expect(rota.canDeactivate).toBeUndefined();
  });

  // Nada de redirecionamento nas duas rotas e os paths não se repetem na tabela.
  it('D-20 as duas rotas não redirecionam e não aparecem duas vezes na tabela', () => {
    for (const path of ['criar-grupo', 'confirmar-email']) {
      expect(rotaDe(path).redirectTo, path).toBeUndefined();
      expect(routes.filter((r) => r.path === path), path).toHaveLength(1);
    }
  });
});
