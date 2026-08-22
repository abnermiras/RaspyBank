-- =============================================================================
-- V22 — O extrato COMPLETO, que atravessa ambientes de proposito (T-10)
-- =============================================================================
-- Decisoes em decisoes.md §4s (B-D116 a B-D119). O que ela muda: acrescenta UMA
-- funcao de leitura. Nao cria tabela, nao mexe em politica, nao revoga nada.
--
-- Nao existe tabela `relatorio` aqui, e a ausencia e a decisao: B-D116 recusou a
-- fila. Entrega sincrona nao tem estado a guardar — sem pedido, sem estado, sem
-- expiracao. O teto de 12 meses (validado na borda HTTP, nao aqui) e o que
-- sustenta essa escolha, porque limita o pior caso a milhares de linhas.
--
-- -----------------------------------------------------------------------------
-- POR QUE ESTA FUNCAO E SECURITY DEFINER
-- -----------------------------------------------------------------------------
-- Pelo mesmo motivo de app_extrato_da_conta (V16) e de app_extrato_da_fatura
-- (V17/V19/V20), e este e o quarto membro da familia: pol_lancamento_ambiente e
-- `ambiente_id IN (SELECT app_ambientes_do_usuario())`, entao consulta comum em
-- `lancamento` NUNCA traz a linha da outra pessoa numa conta ou num plastico
-- dividido. A RLS a corta antes.
--
-- Isso esta certo para a tela — a T-08 lista por consulta comum e por isso
-- nunca mostra linha alheia. Mas o arquivo existe para fechar com o extrato do
-- banco: sem as linhas dela, a soma do .xlsx diverge do que a T-05 mostra na
-- mesma conta, e o usuario conclui — com razao — que o arquivo esta errado.
--
-- -----------------------------------------------------------------------------
-- E POR QUE ELA NAO TEM PORTEIRO
-- -----------------------------------------------------------------------------
-- Todas as irmas comecam com um `IF p_alguma_coisa NOT IN (SELECT ...) THEN
-- RAISE`. Aqui nao ha o que conferir, e a ausencia e deliberada:
--
--   os dois parametros sao DATAS. Nao carregam autorizacao nenhuma.
--
-- Nao existe "peca o extrato de fulano" porque nao existe parametro que aponte
-- para fulano. Tudo que a funcao devolve deriva de app_usuario_id() — os
-- ambientes dele, os vinculos de conta dele, os plasticos liberados para ele.
-- Sem identidade na sessao, app_ambientes_do_usuario() e vazio e a funcao
-- devolve zero linha. O guard explicito la embaixo apenas diz isso em voz alta.
--
-- Um porteiro escrito aqui nao teria o que barrar; seria cerimonia que sugere
-- protecao onde a protecao vem da forma da assinatura.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- app_extrato_completo(inicio, fim) — B-D117, B-D119
-- -----------------------------------------------------------------------------
-- A UNICA leitura do sistema que atravessa ambientes de proposito (B-D117).
-- B-D111 — o escopo segue o ambiente ativo — continua valendo para toda TELA; o
-- arquivo e o oposto por natureza, porque e o retrato da pessoa e nao o da tela
-- aberta. A separacao das vidas se mantem DENTRO do arquivo, por `ambiente_da_aba`.
--
-- O que a linha ALHEIA mostra e o mesmo recorte de B-D89/B-D97, sem uma virgula
-- de diferenca: descricao, categoria e subcategoria NAO SAEM DO BANCO. Nao e
-- filtro de exibicao — sao colunas que a funcao devolve nulas. Valor, data,
-- conta, forma, parcela e quem aparecem sempre, porque sao o que faz a soma
-- fechar. O texto livre e onde as pessoas escrevem o que nao pretendiam dividir.
--
-- `quem_nome` e a coluna que torna a linha mascarada legivel: sem ela, uma linha
-- com Descricao e Categoria vazias parece dado corrompido. E a unica coluna do
-- arquivo que nao vem da T-08, e existe por causa da mascara.
--
-- STABLE, como todas as irmas: le e nao escreve. VOLATILE aqui custaria o plano
-- e nao compraria nada.
CREATE OR REPLACE FUNCTION app_extrato_completo(
    p_inicio date,
    p_fim    date
)
RETURNS TABLE (
    -- Colunas de MONTAGEM. Nao viram coluna do .xlsx; o montador precisa delas
    -- para saber em que aba a linha entra e para os testes conferirem o recorte.
    ambiente_da_aba   uuid,
    conta_id          uuid,
    meu               boolean,

    -- Colunas do arquivo, na ordem em que aparecem na aba (B-D119).
    data_caixa        date,
    descricao         text,   -- mascarada
    situacao          text,
    categoria_nome    text,   -- mascarada
    subcategoria_nome text,   -- mascarada
    conta_nome        text,
    -- "Pago com" sai em TRES colunas cruas e nao numa frase pronta: o nome do
    -- contrato, o tipo do plastico e o final. Montar "fisico ····1234" e
    -- formatacao, e formatacao e do Java (P2 no tipo: FISICO/VIRTUAL sai como
    -- name() do enum, em maiusculas, sem de-para).
    cartao_nome       text,
    plastico_tipo     text,
    plastico_final    text,
    forma_pagamento   text,
    parcela_numero    smallint,
    parcela_total     smallint,
    tipo              text,
    -- POSITIVO, como esta no banco (ck_lancamento_valor). O sinal e derivado do
    -- `tipo` no Java, na hora de escrever a celula (B-D118). Devolver negativo
    -- daqui criaria uma segunda representacao para a mesma saida — exatamente o
    -- que a coluna do lancamento evita desde a V10.
    valor             numeric,
    quem_nome         text
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Sem identidade na sessao nao ha extrato. As duas CTEs abaixo ja sairiam
    -- vazias por si — app_ambientes_do_usuario() nao casa com nada quando
    -- app_usuario_id() e nulo —, mas dizer isto na primeira linha e o que faz a
    -- ausencia de porteiro ser leitura e nao esquecimento.
    IF app_usuario_id() IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
    WITH meus AS MATERIALIZED (
        SELECT a AS ambiente_id FROM app_ambientes_do_usuario() a
    ),
    -- Os vinculos ATIVOS de conta em ambiente meu. E daqui que sai a aba da
    -- linha alheia, e o filtro de encerrado_em e o mesmo de
    -- app_contas_do_usuario() (B-D93).
    vinculos AS MATERIALIZED (
        SELECT ca.conta_id, ca.ambiente_id, ca.origem
          FROM conta_ambiente ca
         WHERE ca.ambiente_id IN (SELECT m.ambiente_id FROM meus m)
           AND ca.encerrado_em IS NULL
    ),
    -- ---------------------------------------------------------------------
    -- Duas origens, e por isso DUAS pernas — nao um OR
    -- ---------------------------------------------------------------------
    -- As linhas chegam por caminhos diferentes e, principalmente, por INDICES
    -- diferentes: a perna 1 filtra por ambiente_id e cai em
    -- ix_lancamento_ambiente_caixa (ambiente_id, data_caixa), a perna 2 filtra
    -- por conta_id e cai em ix_lancamento_conta_caixa (conta_id, data_caixa).
    -- Os dois ja existem desde a V10 — nenhum indice novo nesta migracao.
    --
    -- Escrever isto como um unico WHERE com OR juntaria dois predicados que
    -- nenhum indice cobre ao mesmo tempo, e a consulta viraria varredura da
    -- tabela inteira antes do recorte de data. UNION ALL e a forma que mantem o
    -- filtro de periodo dentro do indice, que e a premissa de B-D116.
    --
    -- UNION ALL e nao UNION: as duas pernas sao disjuntas por construcao (a
    -- segunda exige `ambiente_id NOT IN meus`), e um UNION mandaria o banco
    -- deduplicar milhares de linhas para nao achar nenhuma repetida.
    linhas AS (
        -- PERNA 1 — as MINHAS. A aba e o ambiente em que o lancamento NASCEU
        -- (B-D2), que e a resposta do sistema para "de quem e este gasto".
        --
        -- Repare que aqui NAO ha filtro por conta. E deliberado, e mantem o
        -- arquivo coerente com a T-08: pol_lancamento_ambiente e por ambiente,
        -- entao um lancamento meu numa conta que eu depois deixei de enxergar
        -- (vinculo encerrado) continua na tela — e tem de continuar no arquivo.
        SELECT l.ambiente_id AS ambiente_da_aba,
               true          AS meu,
               l.conta_id,
               l.categoria_id,
               l.subcategoria_id,
               l.cartao_emitido_id,
               l.criado_por,
               l.criado_em,
               l.data_caixa,
               l.descricao,
               l.situacao,
               l.forma_pagamento,
               l.parcela_numero,
               l.parcela_total,
               l.tipo,
               l.valor
          FROM lancamento l
         WHERE l.ambiente_id IN (SELECT m.ambiente_id FROM meus m)
           AND l.data_caixa BETWEEN p_inicio AND p_fim

        UNION ALL

        -- PERNA 2 — as ALHEIAS que eu alcanco por conta ou plastico dividido.
        -- Sao exatamente as que a RLS esconde e que o arquivo precisa para
        -- fechar com a T-05.
        SELECT aba.ambiente_id AS ambiente_da_aba,
               false           AS meu,
               l.conta_id,
               l.categoria_id,
               l.subcategoria_id,
               l.cartao_emitido_id,
               l.criado_por,
               l.criado_em,
               l.data_caixa,
               l.descricao,
               l.situacao,
               l.forma_pagamento,
               l.parcela_numero,
               l.parcela_total,
               l.tipo,
               l.valor
          FROM lancamento l
          -- Serve a duas perguntas ao mesmo tempo: "esta conta e um cartao?"
          -- (para o recorte por plastico, logo abaixo) e "qual o nome do
          -- contrato?" (coluna do arquivo). Conta comum nao tem linha aqui.
          --
          -- Ele vem ANTES do LATERAL de proposito: LATERAL so enxerga o que
          -- esta a esquerda dele no FROM, e o recorte precisa de `ct`.
          LEFT JOIN cartao ct ON ct.conta_id = l.conta_id
          -- -----------------------------------------------------------------
          -- A PARTE MAIS DELICADA DA FUNCAO: em que aba cai a linha alheia
          -- -----------------------------------------------------------------
          -- Ela nasceu no ambiente da OUTRA pessoa, e aquele ambiente nao e
          -- aba nenhuma do meu arquivo — nem poderia ser, porque o arquivo e o
          -- retrato da MINHA vida financeira. Entao a aba dela e o ambiente MEU
          -- pelo qual eu enxergo aquela conta, que e o vinculo em
          -- conta_ambiente.
          --
          -- `origem` primeiro porque, quando a conta nasceu num ambiente meu (eu
          -- sou o dono e dividi com ela), e ali que o dinheiro daquela conta
          -- mora para mim; um vinculo secundario meu para a mesma conta seria
          -- uma aba plausivel mas errada.
          --
          -- LIMIT 1 com ORDER BY completo, e nao LIMIT 1 solto: uma conta pode
          -- estar ligada a mais de um ambiente meu, e sem desempate estavel a
          -- mesma linha migraria de aba entre duas geracoes identicas. E o
          -- mesmo motivo do `criado_em DESC` la embaixo.
          CROSS JOIN LATERAL (
              SELECT v.ambiente_id
                FROM vinculos v
               WHERE v.conta_id = l.conta_id
                 -- ---------------------------------------------------------
                 -- O recorte por PLASTICO, herdado da V20 (B-D110/B-D111)
                 -- ---------------------------------------------------------
                 -- Aceitar um plastico vincula a CONTA do contrato ao meu
                 -- ambiente (V19). Sem a condicao abaixo, esse vinculo
                 -- entregaria no meu arquivo as compras de TODOS os plasticos
                 -- daquele cartao — os dez, quando o que foi dividido comigo
                 -- foi um. Mascaradas, mas com valor e data: exatamente o que
                 -- B-D106 tirou da tela e o que B-D110 recusou.
                 --
                 -- A regra e a mesma de app_extrato_da_fatura na V20: no
                 -- ambiente onde o cartao NASCEU, tudo; nos outros, so os
                 -- plasticos liberados PARA AQUELE ambiente. Conta comum nao
                 -- entra nesta conversa — la o vinculo ja diz tudo (B-D89).
                 --
                 -- Consequencia assumida, e a mesma da V20: as duas pernas do
                 -- pagamento da fatura nao tem plastico (B-D59), entao o
                 -- pagamento feito pelo dono nao aparece na aba de quem so
                 -- recebeu um plastico. Esta correto — ela nao paga a fatura
                 -- (B-D107), e o dinheiro dela e o das compras dela.
                 AND (
                     v.origem
                     OR ct.conta_id IS NULL
                     OR EXISTS (
                         SELECT 1
                           FROM cartao_emitido_ambiente cea
                          WHERE cea.cartao_emitido_id = l.cartao_emitido_id
                            AND cea.ambiente_id       = v.ambiente_id
                            AND cea.encerrado_em IS NULL
                     )
                 )
               ORDER BY v.origem DESC, v.ambiente_id
               LIMIT 1
          ) aba
         WHERE l.conta_id IN (SELECT v2.conta_id FROM vinculos v2)
           AND l.ambiente_id NOT IN (SELECT m.ambiente_id FROM meus m)
           AND l.data_caixa BETWEEN p_inicio AND p_fim
    )
    SELECT x.ambiente_da_aba,
           x.conta_id,
           x.meu,
           x.data_caixa,
           -- A mascara de B-D89/B-D97, identica a de app_extrato_da_conta: tres
           -- colunas e mais nenhuma. Mascarar alem disso sumiria com a linha;
           -- mascarar menos vazaria o texto livre.
           CASE WHEN x.meu THEN x.descricao END,
           x.situacao,
           CASE WHEN x.meu THEN c.nome END,
           CASE WHEN x.meu THEN s.nome END,
           co.nome,
           ct.nome,
           ce.tipo,
           ce.final_do_cartao,
           x.forma_pagamento,
           x.parcela_numero,
           x.parcela_total,
           x.tipo,
           x.valor,
           u.nome
      FROM linhas x
      LEFT JOIN conta          co ON co.id       = x.conta_id
      LEFT JOIN cartao         ct ON ct.conta_id = x.conta_id
      LEFT JOIN cartao_emitido ce ON ce.id       = x.cartao_emitido_id
      LEFT JOIN categoria      c  ON c.id        = x.categoria_id
      LEFT JOIN subcategoria   s  ON s.id        = x.subcategoria_id
      LEFT JOIN usuario        u  ON u.id        = x.criado_por
     -- O segundo criterio nao e enfeite. Sem ele, dois lancamentos do mesmo dia
     -- saem em ordem arbitraria e duas geracoes identicas produzem arquivos
     -- diferentes — um arquivo que muda de forma sozinho e um que ninguem
     -- consegue comparar. Mesmo desempate de app_extrato_da_conta (V16).
     ORDER BY x.data_caixa DESC, x.criado_em DESC;
END;
$$;

COMMENT ON FUNCTION app_extrato_completo(date, date) IS
    'Extrato de TODOS os ambientes do usuario no periodo (B-D117), para o .xlsx da T-10. Quarta da familia de app_extrato_da_conta e a unica SEM porteiro: os parametros sao datas e nao carregam autorizacao. Mascara de B-D89 sobre descricao, categoria e subcategoria.';

GRANT EXECUTE ON FUNCTION app_extrato_completo(date, date) TO raspybank_app;


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   SELECT set_config('raspybank.usuario_id', '<uuid-do-dono>', false);
--   SELECT set_config('raspybank.canal', 'WEB', false);
--
--   -- Uma aba por ambiente, e nenhuma linha fora do periodo
--   SELECT ambiente_da_aba, count(*)
--     FROM app_extrato_completo('2026-01-01', '2026-12-31')
--    GROUP BY 1;
--
--   -- A mascara, nas duas direcoes: a linha alheia vem com valor, conta e quem,
--   -- e sem descricao nem categoria
--   SELECT meu, data_caixa, conta_nome, quem_nome, descricao, categoria_nome, valor
--     FROM app_extrato_completo('2026-01-01', '2026-12-31')
--    ORDER BY meu;
--
--   -- Sem identidade, nada. Nao e erro: e vazio.
--   SELECT set_config('raspybank.usuario_id', '', false);
--   SELECT count(*) FROM app_extrato_completo('2000-01-01', '2100-01-01');  -- 0
--
--   -- O periodo cai no indice. Os dois planos devem mostrar Index Scan em
--   -- ix_lancamento_ambiente_caixa e em ix_lancamento_conta_caixa.
--   EXPLAIN SELECT * FROM app_extrato_completo('2026-01-01', '2026-12-31');
-- =============================================================================
