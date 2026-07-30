-- =============================================================================
-- V17 — Cartão compartilhado
-- =============================================================================
-- Implementa o desenho de decisoes.md §4l (B-D98 a B-D103) e api.md §2e.
--
-- Pedido dele na mesma conversa da V16: "compartilhamento de conta e depois de
-- cartao". Desenhado junto, entregue depois.
--
-- -----------------------------------------------------------------------------
-- O QUE ESTA MIGRACAO NAO PRECISOU FAZER (B-D98)
-- -----------------------------------------------------------------------------
-- Nenhuma tabela nova, nenhum convite proprio, nenhum mecanismo de acesso.
--
-- O cartao E uma conta desde a V12 (B-D47: cartao.conta_id e PK e FK), entao
-- compartilhar cartao e compartilhar a conta do contrato — e isso a V16 ja
-- resolveu inteiro. Um segundo mecanismo significaria duas portas para a mesma
-- pergunta ("quem ve esta conta?") e duas chances de divergirem. E o mesmo
-- raciocinio de B-D74, que recusou criar mecanismo novo para o ambiente.
--
-- O pagamento tambem ja estava pronto e vale registrar por que: ele tem duas
-- pernas (B-D59) — saida na conta bancaria e entrada na conta do cartao. Se ela
-- paga, a perna bancaria e conta DELA, no ambiente dela, e a perna do cartao cai
-- no ambiente dela tambem, porque e la que o cartao compartilhado aparece.
-- Estruturalmente pronto; o que faltava era decidir o produto.
--
-- O que esta migracao faz, entao, e apertar quem pode O QUE — e fazer as somas
-- da fatura atravessarem, que e a parte em que errar sai caro: alguem paga menos
-- do que deve e descobre com juros.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. O total da fatura ATRAVESSA — a terceira funcao de B-D96
-- -----------------------------------------------------------------------------
-- A V16 anunciou esta funcao e nao a criou, para nao deixar codigo morto
-- esperando chamador. Agora ela tem chamador.
--
-- O impasse e o mesmo de app_saldo_da_conta e igualmente inevitavel: por
-- construcao uma pessoa nao pode ver as compras da outra pela politica, e mesmo
-- assim precisa soma-las. Aqui o sintoma de nao somar e pior que um saldo
-- diferente — a fatura pareceria menor do que e, e o pagamento sairia curto.
--
-- O filtro por conta do cartao NAO e redundante: o pagamento marca a fatura nas
-- DUAS pernas (B-D59), entao sem ele a saida da conta pagadora entraria no total
-- de compras. E a conta do cartao vem da propria fatura, nao de parametro: com
-- ela por fora, dois argumentos incoerentes produziriam um total silenciosamente
-- errado.
CREATE OR REPLACE FUNCTION app_total_da_fatura(p_fatura_id uuid)
RETURNS TABLE (compras numeric, pagamentos numeric)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao uuid;
BEGIN
    SELECT f.cartao_id INTO v_cartao FROM fatura f WHERE f.id = p_fatura_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Fatura % nao encontrada', p_fatura_id;
    END IF;

    -- O porteiro, na mesma forma das irmas da V16. Sem ele, DEFINER significaria
    -- "leia a fatura de qualquer cartao do sistema, basta ter o UUID".
    IF v_cartao NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Cartao da fatura % nao esta no seu ambiente', p_fatura_id;
    END IF;

    RETURN QUERY
    SELECT coalesce(SUM(CASE WHEN l.conta_id = v_cartao AND l.tipo = 'SAIDA'
                             THEN l.valor ELSE 0 END), 0),
           coalesce(SUM(CASE WHEN l.conta_id = v_cartao AND l.tipo = 'ENTRADA'
                             THEN l.valor ELSE 0 END), 0)
      FROM lancamento l
     WHERE l.fatura_id = p_fatura_id;
END;
$$;

COMMENT ON FUNCTION app_total_da_fatura(uuid) IS
    'Compras e pagamentos da fatura, ATRAVESSANDO ambientes (B-D87/B-D96). Sem isto a fatura pareceria menor e o pagamento sairia curto.';

-- O extrato da fatura, com o mesmo recorte de privacidade do extrato da conta
-- (B-D89 via B-D97) e uma coluna que aqui pesa mais: a PARCELA.
--
-- B-D102 decidiu mostra-la, e o motivo e dinheiro do dono preso no limite dele —
-- as proximas parcelas ja nascem comprometidas em faturas de meses que ainda nao
-- chegaram, e sem isso o limite informativo (B-D48) para de servir para planejar
-- exatamente no caso em que planejar importa mais. O custo foi dito em voz alta:
-- "3/10 de R$ 200" revela que a compra foi de R$ 2.000.
CREATE OR REPLACE FUNCTION app_extrato_da_fatura(p_fatura_id uuid)
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
    v_cartao uuid;
BEGIN
    SELECT f.cartao_id INTO v_cartao FROM fatura f WHERE f.id = p_fatura_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Fatura % nao encontrada', p_fatura_id;
    END IF;

    IF v_cartao NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Cartao da fatura % nao esta no seu ambiente', p_fatura_id;
    END IF;

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
           -- O plastico NAO e recortado, e a escolha e coerente com B-D103: quem
           -- comprou vem de criado_por, e o plastico usado pertence ao contrato
           -- do dono — ele conhece os proprios cartoes emitidos. Esconde-lo
           -- quebraria o extrato por titular, que e o ponto dos dezessete cartoes.
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
     ORDER BY l.data_competencia DESC, l.criado_em DESC;
END;
$$;

COMMENT ON FUNCTION app_extrato_da_fatura(uuid) IS
    'Extrato da fatura ATRAVESSANDO ambientes. Recorte de B-D89; parcela visivel por B-D102 (limite preso e dinheiro do dono).';

GRANT EXECUTE ON FUNCTION app_total_da_fatura(uuid)   TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_extrato_da_fatura(uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 2. cartao e cartao_emitido — a porta do contrato (B-D101)
-- -----------------------------------------------------------------------------
-- As politicas da V12 eram FOR ALL sobre app_contas_do_usuario: quem enxergava o
-- cartao emitia adicional e encerrava o contrato. Valia enquanto enxergar
-- significava estar no ambiente de quem o abriu.
--
-- O predicado novo e app_contas_nao_emprestadas() — "nasceu em algum ambiente
-- meu" — e nao app_contas_proprias(). A distincao e a mesma da V16 e importa
-- pelo mesmo motivo: no compartilhamento de AMBIENTE, criar cartao, emitir,
-- cancelar e encerrar sao DINHEIRO (§2c), e quem entrou por convite faz tudo
-- isso. No de CONTA nao faz nada disso, porque nao esta no ambiente do dono.
DROP POLICY pol_cartao_conta ON cartao;

CREATE POLICY pol_cartao_leitura ON cartao
    FOR SELECT
    USING (conta_id IN (SELECT app_contas_do_usuario()));

CREATE POLICY pol_cartao_escrita ON cartao
    FOR ALL
    USING      (conta_id IN (SELECT app_contas_nao_emprestadas()))
    WITH CHECK (conta_id IN (SELECT app_contas_nao_emprestadas()));

-- Emitir adicional cria plastico sob o limite do contrato DELE (B-D101).
DROP POLICY pol_cartao_emitido_cartao ON cartao_emitido;

CREATE POLICY pol_cartao_emitido_leitura ON cartao_emitido
    FOR SELECT
    USING (cartao_id IN (SELECT app_contas_do_usuario()));

CREATE POLICY pol_cartao_emitido_escrita ON cartao_emitido
    FOR ALL
    USING      (cartao_id IN (SELECT app_contas_nao_emprestadas()))
    WITH CHECK (cartao_id IN (SELECT app_contas_nao_emprestadas()));


-- -----------------------------------------------------------------------------
-- 3. fatura — FECHAR e dos dois, REABRIR e do dono (B-D100)
-- -----------------------------------------------------------------------------
-- A regra mais fina desta migracao, e ela sai de politica e nao de codigo.
--
-- Fechar e rotina de mes: quem esta dentro faz. Reabrir DESFAZ o que o outro
-- fez, num flag unico que os dois veem, e e ai que duas maos no mesmo ciclo
-- viram briga.
--
-- As duas regras se separam pelo valor de fechada_em na linha NOVA. Politicas
-- permissivas se somam com OR, entao:
--
--   pol_fatura_fechar  — qualquer membro, desde que o resultado seja FECHADA;
--   pol_fatura_porta   — quem nasceu com o cartao, para qualquer resultado.
--
-- Reabrir (fechada_em -> NULL) so passa pela segunda. E como a regra vive no
-- banco, ela vale para qualquer caminho que um dia atualize a fatura — inclusive
-- o fechamento automatico da leitura, que continua funcionando para os dois
-- porque tambem resulta em FECHADA.
DROP POLICY pol_fatura_cartao ON fatura;

CREATE POLICY pol_fatura_leitura ON fatura
    FOR SELECT
    USING (cartao_id IN (SELECT app_contas_do_usuario()));

-- Gerar os ciclos e derivacao, nao decisao: quem ve o cartao pode materializar
-- as faturas dele. Sem isto, a compra parcelada dela pararia no meio — ela
-- precisa das faturas dos meses a frente, e elas podem nao existir ainda.
CREATE POLICY pol_fatura_gerar ON fatura
    FOR INSERT
    WITH CHECK (cartao_id IN (SELECT app_contas_do_usuario()));

CREATE POLICY pol_fatura_fechar ON fatura
    FOR UPDATE
    USING      (cartao_id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (cartao_id IN (SELECT app_contas_do_usuario())
                AND fechada_em IS NOT NULL);

CREATE POLICY pol_fatura_porta ON fatura
    FOR UPDATE
    USING      (cartao_id IN (SELECT app_contas_nao_emprestadas()))
    WITH CHECK (cartao_id IN (SELECT app_contas_nao_emprestadas()));

-- Sem politica de DELETE, e e um aperto deliberado: a V12 permitia por ser
-- FOR ALL, e ninguem apaga fatura no codigo. Ciclo que existiu nao se desfaz —
-- se um dia precisar, ganha desenho.


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   -- Com o cartao ja compartilhado pela V16 (convite + aceite):
--   SELECT set_config('raspybank.usuario_id', '<uuid-dela>', false);
--   SELECT set_config('raspybank.canal', 'WEB', false);
--
--   -- Ela ve o cartao e as faturas, e o total ja soma as compras dos dois
--   SELECT * FROM app_total_da_fatura('<fatura>');
--
--   -- B-D100: ela FECHA
--   UPDATE fatura SET fechada_em = now() WHERE id = '<fatura>';   -- 1 linha
--
--   -- ... e nao REABRE
--   UPDATE fatura SET fechada_em = NULL WHERE id = '<fatura>';    -- 0 linhas
--
--   -- B-D101: ela nao emite adicional no contrato dele
--   INSERT INTO cartao_emitido (cartao_id, nome_titular, tipo, final_do_cartao)
--   VALUES ('<cartao>', 'Ela', 'FISICO', '9999');
--   -- ERRO: new row violates row-level security policy
--
--   -- B-D102: a parcela alheia aparece, a descricao nao
--   SELECT meu, valor, descricao, parcela_numero, parcela_total, quem_nome
--     FROM app_extrato_da_fatura('<fatura>');
-- =============================================================================
