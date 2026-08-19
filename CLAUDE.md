# RaspyBank — contexto para agentes

Sistema de controle financeiro pessoal. Monólito modular Java 21 / Spring Boot 3.5,
PostgreSQL 18 com Row Level Security, frontend React 19 + Vite servido pelo próprio backend.

## A regra que vem antes de todas

`docs/decisoes.md` é **fonte de verdade**. Quando qualquer documento, conversa ou trecho de
código contradisser aquele registro, o registro prevalece — ou é formalmente revisado, com o
motivo escrito. Código sem decisão correspondente é invenção ou buraco de documentação, e os
dois merecem parada.

Corolário operacional: **chat decide, repositório registra**. Decisão nova tomada em conversa
entra em `docs/decisoes.md` no mesmo commit que a implementa. Decisão nunca é apagada — quando
superada, vai para a seção de Revisões com o motivo.

## Princípios

| # | Princípio |
|---|---|
| P1 | Nenhuma entidade guarda saldo, total ou agregado. O lançamento é a fonte única de verdade; todo saldo é calculado. |
| P2 | Valor no banco == `name()` do enum Java. Sem campo paralelo, sem conversor. |
| P3 | Migração primeiro, código depois. O banco aprende o valor novo antes do Java enviá-lo. |
| P4 | O ambiente é a fronteira de dados, e o filtro dela não depende de ninguém lembrar. Isolamento no banco (RLS + identidade de sessão por aspecto), nunca num `where` escrito à mão. |

## Arquitetura

Maven multi-módulo **por contexto de negócio**, não por camada técnica:

```
raspybank-shared        base da pirâmide — erro, contexto de requisição, RLS. Não conhece ninguém.
raspybank-identidade    usuário, autenticação
raspybank-ambiente      ambiente financeiro, vínculos
raspybank-auditoria     registro de auditoria, outbox
raspybank-lancamento    conta, categoria, lançamento, cartão, fatura, transferência  (o maior)
raspybank-app           montagem: controladores, segurança, config, migrações Flyway, testes
raspybank-web           React 19 + Vite (fora do Maven)
```

**Contextos não se conhecem.** Nenhum importa classe de outro. Quando um precisa referenciar
outro, guarda o **identificador** (`UUID ambienteId`), nunca o objeto. Comunicação entre
contextos é por **eventos na tabela outbox**, escritos na mesma transação da operação. É isso
que permite extrair um contexto para outro processo na Fase 8.

Essa fronteira não é convenção: `ArquiteturaTest` (ArchUnit) quebra o build quando alguém a
atravessa. Ao criar um contexto novo, declare-o lá e adicione o teste de isolamento nos dois
sentidos.

## Multi-tenancy

RLS no PostgreSQL, com o **usuário** como tenant (não o ambiente). Identidade da sessão via
`set_config('raspybank.usuario_id', ..., true)`, aplicada por `ConfiguradorSessaoRls`.

**Nenhuma linha de código Java filtra por usuário.** Se você se pegar escrevendo
`where usuario_id = ?` em repositório, pare: ou a política RLS está faltando, ou você está
resolvendo no lugar errado.

Dois usuários de banco: `raspybank_owner` (migrações) e `raspybank_app` (aplicação, sem DDL).
Operações que precisam atravessar a política passam por funções **SECURITY DEFINER**,
inventariadas em `docs/security-definer.md` — toda função nova entra lá.

## Convenções de API

- **Dinheiro é string** (`"450.00"`), nunca número JSON. `double` para dinheiro é proibido.
- **Datas são datas**: `"2026-07-26"`, sem hora e sem fuso. Só timestamp de auditoria é instante.
- **O ambiente é implícito**: vem do token de acesso. Nenhum endpoint recebe `ambienteId`.
- **Erro**: `{"erro": "<frase exibível>"}`, validação acrescenta `{"campos": {...}}`.
  400 validação · 401 sem sessão · 403 sem vínculo · 404 inexistente · 409 conflito · 500 nosso.
- **Nada é excluído fisicamente**, exceto lançamento. Categoria arquiva, conta encerra —
  daí os verbos `POST /{id}/arquivar` em vez de `DELETE`.

O contrato vive em `docs/api.md` e é atualizado junto do endpoint, nunca depois.

## Nomenclatura

Português no domínio inteiro — pacotes, classes, tabelas, colunas, rotas de tela.
`LancamentoServico`, `conta_ambiente`, `app_saldo_da_conta`. Identificadores Java e comentários
de código sem acento; documentação em Markdown com acento normal.

## Comandos

```
make up        sobe o banco             make build     compila + testa tudo
make app       backend com recarga      make test      só os testes
make web       frontend em dev          make arch      só as fronteiras (ArchUnit)
make psql-app  SQL como app (testa RLS) make gate      imagem real + sobe. Passou aqui, passa no Pi.
```

`make gate` é o portão antes de qualquer entrega. Migração nova exige `make db-reset` limpo
mais o teste de integração correspondente.

## Documentos

| Arquivo | O que é |
|---|---|
| `docs/decisoes.md` | Fonte de verdade. Princípios, decisões A/F/B-D, estado das migrações. |
| `docs/api.md` | Contrato da API, endpoint a endpoint. |
| `docs/inconsistencias.md` | Pendências e achados conhecidos (I-01…), com estado. |
| `docs/security-definer.md` | Inventário das funções SECURITY DEFINER e por que cada uma existe. |
| `docs/mapa-telas.md` | Telas e o que cada uma consome. |
| `docs/desenho-*.md` | Desenho ainda **não decidido** e não implementado. Não é fonte de verdade; vira decisão só ao entrar em `decisoes.md`, no commit que o implementa. |

## Agentes

| Agente | Escopo |
|---|---|
| `banco-e-migracoes` | migrações Flyway, RLS, funções SECURITY DEFINER, índices, constraints |
| `dominio-lancamento` | lançamento, conta, categoria, cartão, plástico, fatura, transferência, mapa |
| `identidade-e-sessao` | identidade, ambiente, auditoria, JWT, rotação de token, compartilhamento |
| `api-e-contrato` | controladores da borda HTTP e `docs/api.md` |
| `frontend-web` | telas, componentes, ganchos e o cliente da API em `raspybank-web` |
| `infra-e-implantacao` | Docker, Compose, Makefile, rede, o Pi de produção |
| `qa-adversarial` | testes que tentam quebrar o que foi entregue. Não conserta o que acha |
| `revisor-de-fronteiras` | revisa contra fronteiras, princípios e decisões. Não escreve produção |
| `escriba` | mantém `docs/` em dia. Só edita `docs/` |

Invoque pelo nome (`@agent-banco-e-migracoes ...`) ou descreva a tarefa e deixe a delegação
acontecer.

Regra de ouro: **um agente por fronteira**. Quem escreve migração não escreve tela, quem revisa
não é quem escreveu, e quem procura defeito não é quem entregou a funcionalidade. O ganho não é
velocidade — é isolamento de contexto e ausência de viés de autor.

Ordem típica de uma entrega: decisão registrada → migração → regra de domínio → borda HTTP →
tela → `qa-adversarial` → `revisor-de-fronteiras` → `make gate`.

## Fluxo de um defeito

### O relato é sintoma, não diagnóstico

Quem reporta descreve **o que fez, o que esperava e o que aconteceu** — não a causa que
imagina. O I-24 é a prova: o relato foi *"diz que não aceita número negativo"*, e não havia
negativo nenhum envolvido; o defeito era o `"1450,22"` com vírgula. Teoria no relato faz o
conserto começar no lugar errado.

Agente que receber relato já diagnosticado **verifica o diagnóstico antes de aceitá-lo**, e diz
quando ele não se sustenta.

### Os cinco passos

1. **Reproduzir.** `qa-adversarial` escreve o teste que expõe o comportamento atual e o deixa
   vermelho. Sem teste vermelho, o diagnóstico é chute. Ele aponta de quem é o conserto e não
   conserta nada.
2. **Corrigir.** O agente dono da área conserta — **sem tocar no teste**. Não é desconfiança,
   é higiene: quem faz passar não pode afrouxar a prova. Se a correção exigir coluna, CHECK ou
   função, `banco-e-migracoes` vem antes (P3).
3. **Registrar.** `escriba` abre a entrada em `docs/inconsistencias.md` no formato do I-24:
   sintoma, causa, **o que o banco não pegou**, correção, pendência, lição. A terceira pergunta
   é a mais valiosa — quando um defeito passa, ou faltou constraint ou faltou política, e a
   resposta previne a próxima família inteira.
4. **Revisar.** `revisor-de-fronteiras` dá o parecer contra fronteiras, princípios e decisões.
5. **Fechar.** `make gate` verde, commit, push.

### Roteamento por sintoma

| O que se vê | Dono |
|---|---|
| Número, situação, fatura, parcela ou saldo errados | `dominio-lancamento` |
| Vejo dado que não é meu, ou não vejo o que é meu | `banco-e-migracoes` (é RLS) |
| Deslogou sozinho, token, troca de ambiente, convite | `identidade-e-sessao` |
| Mensagem de erro errada, código HTTP estranho, campo faltando na resposta | `api-e-contrato` |
| Tela não atualiza, formulário rejeita o que foi digitado | `frontend-web` |
| Container, deploy no Pi, rede, build | `infra-e-implantacao` |

Na dúvida, não escolha por palpite: descreva o sintoma e deixe o roteamento acontecer. Errar de
agente custa uma rodada; errar de diagnóstico custa mais.

### Proporção

Cerimônia proporcional ao risco. Texto de botão, cor, alinhamento: conserta e commita. O fluxo
completo é para defeito de **comportamento** — dinheiro, visibilidade, sessão, dado gravado.

### A separação que não pode relaxar

Corrigir e testar não são do mesmo agente, na mesma conversa. Quem está fazendo passar tem
incentivo silencioso a escrever o teste que passa. Por isso `qa-adversarial` não toca em
produção e quem corrige não toca no teste. É a única parte deste arranjo que, se afrouxar,
some sem fazer barulho.
