-- =============================================================================
-- V20 — O escopo segue o AMBIENTE ATIVO, e o banco do cartao dividido tem nome
-- =============================================================================
-- Duas correcoes achadas no uso, em 30/07/2026. Decisoes em decisoes.md §4o
-- (B-D111 e B-D112).
--
-- -----------------------------------------------------------------------------
-- O QUE ELE VIU, E O QUE OS DADOS MOSTRARAM
-- -----------------------------------------------------------------------------
-- "compartilhei um cartao e a outra pessoa teve acesso a todos os cartoes."
--
-- O compartilhamento por plastico estava certo: cartao_emitido_ambiente tinha
-- exatamente UMA linha. O que a fazia enxergar os tres era outra coisa — ela e
-- membro do ambiente dele (V15), e por B-D76 quem entra no ambiente ve tudo que
-- e dinheiro. Isso fica como esta: e a decisao dele, e a frase e "dar acesso ao
-- ambiente e a mesma coisa que dar minha senha".
--
-- O DEFEITO de verdade estava junto: a RLS e por USUARIO (R7), entao os tres
-- plasticos apareciam tambem DENTRO DO AMBIENTE DELA — onde so o dividido
-- deveria estar. Nao vaza informacao nova (ela alcanca tudo trocando de
-- ambiente), mas a tela mente sobre o que foi dividido.
--
-- A regra que sai disso, e que vale para o resto do sistema:
--
--   O ESCOPO DO QUE SE VE SEGUE O AMBIENTE ATIVO, e nao a soma dos acessos da
--   pessoa. Quem decide "esta linha aparece nesta tela?" e o ambiente; quem
--   decide "posso ler este texto?" continua sendo a pessoa.
--
-- As duas perguntas sao diferentes, e confundi-las foi o defeito. O texto de um
-- lancamento MEU e meu em qualquer ambiente meu — por isso `meu` continua por
-- usuario. Ja a lista de linhas e do ambiente que esta aberto.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. O banco do cartao dividido tem nome — B-D112
-- -----------------------------------------------------------------------------
-- Ate aqui o cartao recebido respondia "(banco de quem dividiu o cartao)":
-- a conta do banco e dele, e ela nao a enxerga.
--
-- Ele derrubou a preocupacao com uma observacao que eu nao tinha feito: o NOME
-- DO CARTAO ja entrega o banco — "ultravioleta e Nubank, samsung e Itau, porto
-- e Porto Seguro". Esconder o nome era protecao que nao protegia nada, e ainda
-- deixava a tela dela incoerente.
--
-- Devolve SO o nome. Nao devolve saldo, nem formas de pagamento, nem o direito
-- de lancar: a conta continua sendo dele, e `exigirContaNoAmbiente` continua
-- recusando qualquer lancamento que a aponte.
CREATE OR REPLACE FUNCTION app_nome_do_banco_do_cartao(p_cartao_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_nome text;
BEGIN
    -- O porteiro de sempre: ve o nome do banco quem ve o cartao.
    IF p_cartao_id NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Cartao % nao esta no seu ambiente', p_cartao_id;
    END IF;

    SELECT b.nome INTO v_nome
      FROM cartao ct
      JOIN conta b ON b.id = ct.conta_banco_id
     WHERE ct.conta_id = p_cartao_id;

    RETURN v_nome;
END;
$$;

COMMENT ON FUNCTION app_nome_do_banco_do_cartao(uuid) IS
    'O nome do banco do contrato, para quem recebeu um plastico (B-D112). So o nome — a conta continua sendo de quem abriu o cartao.';

GRANT EXECUTE ON FUNCTION app_nome_do_banco_do_cartao(uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 2. O extrato da fatura recorta pelo AMBIENTE ATIVO — B-D111
-- -----------------------------------------------------------------------------
-- A V19 decidia o recorte por app_contas_nao_emprestadas(), que e por USUARIO:
-- para quem tem os dois acessos — membro do ambiente E dono de um plastico —,
-- o extrato vinha inteiro em qualquer ambiente, inclusive no dela.
--
-- Agora o ambiente entra por parametro. Ele nao e uma sugestao da tela: a funcao
-- confere que o ambiente e de quem chama antes de usar. Sem isso, o parametro
-- seria um jeito de pedir o recorte de um ambiente alheio.
--
-- `meu` continua por USUARIO, de proposito, e a diferenca importa: ele decide se
-- a DESCRICAO aparece, e o texto de um lancamento meu e meu em qualquer ambiente
-- meu. Quem mudou foi a lista de LINHAS, que e do ambiente aberto.
DROP FUNCTION app_extrato_da_fatura(uuid);

CREATE FUNCTION app_extrato_da_fatura(
    p_fatura_id   uuid,
    p_ambiente_id uuid
)
RETURNS TABLE (
    id                uuid,
    meu               boolean,
    data_caixa        date,
    data_competencia  date,
    tipo              text,
    situacao          text,
    valor             numeric,
    forma_pagamento   text,
    descricao         text,
    categoria_id      uuid,
    categoria_nome    text,
    subcategoria_id   uuid,
    subcategoria_nome text,
    quem_nome         text,
    cartao_emitido_id uuid,
    titular           text,
    tipo_emitido      text,
    final_do_cartao   text,
    parcela_numero    smallint,
    parcela_total     smallint
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao  uuid;
    v_do_dono boolean;
BEGIN
    SELECT f.cartao_id INTO v_cartao FROM fatura f WHERE f.id = p_fatura_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Fatura % nao encontrada', p_fatura_id;
    END IF;

    IF p_ambiente_id NOT IN (SELECT app_ambientes_do_usuario()) THEN
        RAISE EXCEPTION 'Ambiente % nao e seu', p_ambiente_id;
    END IF;

    IF v_cartao NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Cartao da fatura % nao esta no seu ambiente', p_fatura_id;
    END IF;

    -- O cartao NASCEU neste ambiente? Quem responde e o vinculo deste ambiente,
    -- e nao a soma dos acessos da pessoa.
    SELECT EXISTS (
        SELECT 1 FROM conta_ambiente ca
         WHERE ca.conta_id = v_cartao
           AND ca.ambiente_id = p_ambiente_id
           AND ca.origem
           AND ca.encerrado_em IS NULL
    ) INTO v_do_dono;

    RETURN QUERY
    SELECT l.id,
           v.meu,
           l.data_caixa,
           l.data_competencia,
           l.tipo,
           l.situacao,
           l.valor,
           l.forma_pagamento,
           CASE WHEN v.meu THEN l.descricao       END,
           CASE WHEN v.meu THEN l.categoria_id    END,
           CASE WHEN v.meu THEN c.nome            END,
           CASE WHEN v.meu THEN l.subcategoria_id END,
           CASE WHEN v.meu THEN s.nome            END,
           u.nome,
           l.cartao_emitido_id,
           ce.nome_titular,
           ce.tipo,
           ce.final_do_cartao,
           l.parcela_numero,
           l.parcela_total
      FROM lancamento l
      CROSS JOIN LATERAL (
          SELECT l.ambiente_id IN (SELECT app_ambientes_do_usuario()) AS meu
      ) v
      LEFT JOIN categoria      c  ON c.id  = l.categoria_id
      LEFT JOIN subcategoria   s  ON s.id  = l.subcategoria_id
      LEFT JOIN usuario        u  ON u.id  = l.criado_por
      LEFT JOIN cartao_emitido ce ON ce.id = l.cartao_emitido_id
     WHERE l.fatura_id = p_fatura_id
       AND (
           v_do_dono
           OR l.cartao_emitido_id IN (
               SELECT cea.cartao_emitido_id
                 FROM cartao_emitido_ambiente cea
                WHERE cea.ambiente_id = p_ambiente_id
                  AND cea.encerrado_em IS NULL
           )
       )
     ORDER BY l.data_competencia DESC, l.criado_em DESC;
END;
$$;

COMMENT ON FUNCTION app_extrato_da_fatura(uuid, uuid) IS
    'Extrato da fatura recortado pelo AMBIENTE ATIVO (B-D111). No ambiente de origem, tudo; nos outros, so os plasticos liberados PARA AQUELE ambiente.';

GRANT EXECUTE ON FUNCTION app_extrato_da_fatura(uuid, uuid) TO raspybank_app;


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   -- Com uma pessoa que tem OS DOIS acessos: membro do ambiente dele E dona
--   -- de um plastico dividido, no ambiente dela.
--   SELECT set_config('raspybank.usuario_id', '<uuid-dela>', false);
--
--   -- No ambiente DELE: o extrato inteiro, as mini faturas dos tres plasticos
--   SELECT titular, valor FROM app_extrato_da_fatura('<fatura>', '<ambiente-dele>');
--
--   -- No ambiente DELA: so as linhas do plastico dividido
--   SELECT titular, valor FROM app_extrato_da_fatura('<fatura>', '<ambiente-dela>');
--
--   -- Ambiente de terceiro: recusado, mesmo com o cartao visivel
--   SELECT * FROM app_extrato_da_fatura('<fatura>', '<ambiente-de-outro>');
--   -- ERRO: Ambiente ... nao e seu
--
--   -- B-D112: o banco tem nome
--   SELECT app_nome_do_banco_do_cartao('<cartao>');
-- =============================================================================
