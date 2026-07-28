-- =============================================================================
-- V13 — O lançamento passa a saber QUAL cartão fez a compra
-- =============================================================================
-- Veio dos testes de negócio da V12, em 28/07/2026. O pedido, nas palavras
-- dele: <i>"quando eu clicar em Ver fatura, vai mostrar os gastos de cada cartão
-- virtual, de cada cartão físico, no mesmo mês. Porque no final das contas é uma
-- fatura que está sendo paga."</i>
--
-- A V12 amarrou o lançamento à FATURA, e isso bastava para cobrar. Não bastava
-- para explicar: uma fatura com o físico, dois virtuais e o adicional da Luciana
-- é uma pilha de gastos sem dono. A coluna aqui é o dono.
--
-- -----------------------------------------------------------------------------
-- O QUE MUDA JUNTO, E NÃO É NESTE ARQUIVO
-- -----------------------------------------------------------------------------
-- Esta migração acompanha uma inversão de TELA que não toca no schema, e que
-- vale registrar aqui porque quem ler a coluna vai se perguntar:
--
--   O cartão deixou de ser uma "conta" aos olhos de quem usa. Você lança em
--   "Nubank" e diz que pagou com "Black físico ****4352". Por baixo, o
--   lançamento continua morando na conta do cartão (B-D47) — porque é o saldo
--   dela que é a dívida, e é a dívida que a fatura cobra.
--
-- Manter o armazenamento não foi conservadorismo: pagamento PARCIAL e pagar a
-- fatura do Nubank com a conta do C6 — os dois pedidos dele — exigem que a
-- dívida exista como saldo próprio. Se a compra debitasse o banco direto, a
-- fatura não teria o que pagar.
--
-- Decisões em docs/decisoes.md §4g (B-D61 a B-D64).
-- =============================================================================

ALTER TABLE lancamento ADD COLUMN cartao_emitido_id uuid;

-- RESTRICT e não CASCADE: apagar um emitido apagaria as compras feitas com ele.
-- Na prática nunca dispara — cartão emitido não se apaga, se cancela, pela mesma
-- razão de F7 para conta: um virtual descartado depois de uma compra precisa
-- continuar explicando aquela compra.
ALTER TABLE lancamento ADD CONSTRAINT fk_lancamento_cartao_emitido
    FOREIGN KEY (cartao_emitido_id)
    REFERENCES cartao_emitido (id) ON DELETE RESTRICT;

-- Cartão sem fatura não existe: se o lançamento sabe com qual plástico foi
-- pago, ele é uma compra de cartão, e compra de cartão é cobrada num ciclo.
--
-- A recíproca NÃO é verdadeira, e é por isso que a condição é de mão única: as
-- duas pernas de um PAGAMENTO de fatura têm fatura_id e não têm cartão nenhum —
-- ninguém paga a fatura "com o cartão dela".
ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_cartao_exige_fatura
    CHECK (cartao_emitido_id IS NULL OR fatura_id IS NOT NULL);

-- O "Ver fatura" agrupa por cartão dentro de um ciclo — este índice é
-- exatamente a consulta dele.
CREATE INDEX ix_lancamento_fatura_cartao
    ON lancamento (fatura_id, cartao_emitido_id)
    WHERE cartao_emitido_id IS NOT NULL;

COMMENT ON COLUMN lancamento.cartao_emitido_id IS
    'Qual plastico ou virtual fez a compra (V13). Nulo em lancamento comum e nas pernas do pagamento de fatura.';
