-- =============================================================================
-- V11 — Forma de pagamento e transferência
-- =============================================================================
-- Nasceu do PRIMEIRO teste de negócio do sistema em alpha (27/07/2026). Duas
-- coisas apareceram no mesmo dia, e elas se entrelaçam:
--
--   1. Um gasto de "gasolina, R$ 10" ficou registrado sem que desse para saber
--      se foi débito, pix ou boleto. O dado não estava errado — nunca tinha
--      sido capturado, e isso não se recupera depois.
--
--   2. Transferir dinheiro entre contas próprias não existia. A mensagem de
--      "encerre a conta transferindo ou ajustando antes" apontava para um
--      caminho que o sistema não oferecia.
--
-- Estão na mesma migração porque a segunda depende do vocabulário da primeira:
-- as duas pernas de uma transferência precisam saber o que gravar (ou não
-- gravar) no campo de forma de pagamento.
--
-- -----------------------------------------------------------------------------
-- ESTE ARQUIVO ROUBOU O NÚMERO QUE ERA DO CARTÃO
-- -----------------------------------------------------------------------------
-- A V10 e docs/decisoes.md (B-D1) reservavam a V11 para cartão, cartão emitido,
-- fatura, parcela e recorrência. Cartão passou a ser a V12. Reserva de número
-- era plano, não fato: o Flyway ordena pelo que existe, e deixar um buraco na
-- sequência para honrar uma anotação seria pior.
--
-- Os comentários DENTRO da V10 continuam dizendo "V11 = cartão" e não foram
-- corrigidos de propósito: migração aplicada é imutável, e editar o arquivo
-- mudaria o checksum que o Flyway confere na subida.
--
-- -----------------------------------------------------------------------------
-- POR QUE FORMA DE PAGAMENTO NÃO É UMA REGRA NOVA DE DINHEIRO
-- -----------------------------------------------------------------------------
-- Todas as formas têm o MESMO efeito patrimonial: o valor se move na data de
-- caixa. Nenhuma muda saldo, situação (B-D9) ou o mapa de gastos (B-D10).
--
-- É uma dimensão de ANÁLISE, irmã de responsavel_id (F32) — serve para explicar
-- como o dinheiro se moveu, não para calcular quanto. Por isso a coluna é
-- anulável, não entra em somatório nenhum e nenhuma consulta de relatório muda.
--
-- CRÉDITO DE CARTÃO NÃO ESTÁ AQUI, e a ausência é decisão. Compra no cartão de
-- crédito nasce como lançamento na conta DO CARTÃO (natureza PASSIVO); quem
-- debita a corrente é o pagamento da fatura, outro lançamento. É a V12.
--
-- CREDITO_EM_CONTA, que ESTÁ aqui, é outra coisa: é como o salário chega. A
-- palavra "crédito" tem os dois sentidos em português, e o nome longo existe
-- justamente para não deixar dúvida sobre qual dos dois.
--
-- -----------------------------------------------------------------------------
-- ROTEIRO
-- -----------------------------------------------------------------------------
--   1. forma_pagamento e forma_pagamento_sentido — vocabulário de referência
--   2. conta_forma_pagamento — o subconjunto de cada conta, com dois padrões
--   3. lancamento.forma_pagamento — anulável, amarrada por DUAS chaves compostas
--   4. lancamento.lancamento_par_id — o par da transferência que F2 exigia
--   5. Row Level Security e auditoria
-- =============================================================================


-- #############################################################################
-- 1. O VOCABULÁRIO — duas tabelas de referência
-- #############################################################################
-- Estas duas tabelas NÃO são cadastro do usuário. São dados de referência, os
-- mesmos para todo mundo, e existem por um motivo bem específico.
--
-- A alternativa era um CHECK com a lista de valores, que é como natureza, tipo
-- e situacao são feitos nesta base. Aqui não serve: além do vocabulário, existe
-- a regra de QUAL SENTIDO cada forma aceita — boleto só paga, salário só
-- credita, pix faz os dois. Com CHECKs essa regra apareceria em três lugares:
--
--   - no CHECK do lancamento (forma × tipo),
--   - no CHECK do padrão da conta (forma × sentido do padrão),
--   - no enum Java.
--
-- Três cópias da mesma regra divergem — e a divergência aparece como "o sistema
-- aceitou salário pago no boleto", meses depois. Com a tabela, a regra vive em
-- UM lugar: as linhas de forma_pagamento_sentido. As chaves compostas do
-- lançamento a consultam, e o Java só precisa saber os nomes.
--
-- Bônus que não é bônus: o frontend passa a LER esta lista do servidor em vez
-- de repeti-la em JavaScript, que seria a quarta cópia.
-- #############################################################################

CREATE TABLE forma_pagamento (

    forma   text        PRIMARY KEY,

    -- O rótulo que a tela mostra. Fica aqui, e não no JavaScript, para não
    -- haver duas listas de nomes que precisam concordar.
    nome    text        NOT NULL,

    -- Ordem de exibição, do mais comum para o menos. Sem ela o seletor sairia
    -- em ordem alfabética, que põe "Boleto" antes de "Débito" sem motivo.
    ordem   smallint    NOT NULL
);

INSERT INTO forma_pagamento (forma, nome, ordem) VALUES
    ('DEBITO',            'Débito',            1),
    ('PIX',               'Pix',               2),
    ('CREDITO_EM_CONTA',  'Crédito em conta',  3),
    ('BOLETO',            'Boleto',            4),
    ('DEBITO_AUTOMATICO', 'Débito automático', 5),
    ('DINHEIRO',          'Dinheiro',          6),
    ('TED',               'TED',               7),
    ('DESCONTO_EM_FOLHA', 'Desconto em folha', 8);


-- -----------------------------------------------------------------------------
-- Quais sentidos cada forma aceita
-- -----------------------------------------------------------------------------
-- ESTA TABELA É A REGRA. Onze linhas que dizem tudo:
--
--   Só SAIDA     — débito, débito automático, boleto, desconto em folha
--   Só ENTRADA   — crédito em conta
--   Os dois      — pix, dinheiro, TED
--
-- "TED" e não "TRANSFERENCIA" de propósito: TRANSFERENCIA já é o código de uma
-- categoria sistêmica, e a mesma palavra significando duas coisas diferentes no
-- mesmo domínio é a receita para alguém ler a errada.
-- -----------------------------------------------------------------------------
CREATE TABLE forma_pagamento_sentido (

    forma   text    NOT NULL,
    tipo    text    NOT NULL,

    PRIMARY KEY (forma, tipo),

    CONSTRAINT fk_fps_forma FOREIGN KEY (forma)
        REFERENCES forma_pagamento (forma) ON DELETE CASCADE,

    CONSTRAINT ck_fps_tipo CHECK (tipo IN ('ENTRADA', 'SAIDA'))
);

INSERT INTO forma_pagamento_sentido (forma, tipo) VALUES
    ('DEBITO',            'SAIDA'),
    ('DEBITO_AUTOMATICO', 'SAIDA'),
    ('BOLETO',            'SAIDA'),
    ('DESCONTO_EM_FOLHA', 'SAIDA'),

    ('CREDITO_EM_CONTA',  'ENTRADA'),

    ('PIX',               'SAIDA'),
    ('PIX',               'ENTRADA'),
    ('DINHEIRO',          'SAIDA'),
    ('DINHEIRO',          'ENTRADA'),
    ('TED',               'SAIDA'),
    ('TED',               'ENTRADA');

COMMENT ON TABLE forma_pagamento_sentido IS
    'A regra de qual forma serve a qual sentido, em UM lugar so. As chaves compostas do lancamento a consultam.';


-- Referência é para ler, não para escrever. A aplicação conecta como
-- raspybank_app, e o ALTER DEFAULT PRIVILEGES do script de inicialização deu a
-- ela INSERT/UPDATE/DELETE em toda tabela nova — inclusive nestas duas, onde
-- isso não faz sentido nenhum. Revogar é o controle honesto para dado de
-- referência: RLS aqui seria teatro, já que a lista é a mesma para todo mundo e
-- não contém dado de ninguém.
REVOKE INSERT, UPDATE, DELETE ON forma_pagamento          FROM raspybank_app;
REVOKE INSERT, UPDATE, DELETE ON forma_pagamento_sentido  FROM raspybank_app;


-- #############################################################################
-- 2. conta_forma_pagamento — a lista de cada conta, com DOIS padrões
-- #############################################################################
-- A lista é POR CONTA e não global, porque foi assim que o caso real se
-- apresentou: a carteira só aceita DINHEIRO, uma conta digital só PIX, a conta
-- corrente aceita quase tudo. Uma lista global faria o seletor da T-08 oferecer
-- "desconto em folha" ao lançar um gasto na carteira.
--
-- DOIS PADRÕES, e não um. A regra pedida foi "se a pessoa não indicar, salva
-- débito". Duas correções apareceram ao desenhar:
--
--   a) Débito LITERAL quebraria na carteira, que só aceita DINHEIRO — gravaria
--      nela uma forma que a própria lista dela recusa, em silêncio. Por isso o
--      padrão é por conta.
--   b) Entrada também tem "como o dinheiro se moveu": o salário é CREDITADO. Um
--      padrão só, de saída, deixaria toda entrada em branco para sempre.
-- #############################################################################
CREATE TABLE conta_forma_pagamento (

    conta_id        uuid        NOT NULL,

    forma           text        NOT NULL,

    -- Assumidas quando o lançamento não informa forma. Ver a regra completa em
    -- LancamentoServico.resolverFormaDePagamento: valem só para categoria NÃO
    -- sistêmica, porque saldo de abertura e transferência não foram "pagos" de
    -- forma alguma.
    padrao_saida    boolean     NOT NULL DEFAULT false,
    padrao_entrada  boolean     NOT NULL DEFAULT false,

    criado_em       timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (conta_id, forma),

    -- CASCADE e não RESTRICT: a lista não tem vida própria, ela É um atributo
    -- da conta. Na prática nunca dispara — conta não se exclui, se encerra (F7).
    CONSTRAINT fk_cfp_conta FOREIGN KEY (conta_id)
        REFERENCES conta (id) ON DELETE CASCADE,

    CONSTRAINT fk_cfp_forma FOREIGN KEY (forma)
        REFERENCES forma_pagamento (forma) ON DELETE RESTRICT
);

-- No máximo UMA padrão de cada sentido por conta.
--
-- Índice parcial e não constraint UNIQUE porque a regra é "no máximo uma
-- VERDADEIRA": um UNIQUE sobre (conta_id, padrao_saida) também limitaria a uma
-- FALSA por conta, que é exatamente o oposto do que se quer.
--
-- Nenhuma padrão também é válido: aceitar três formas sem ter preferência é
-- legítimo, e aí o campo simplesmente não se preenche sozinho.
CREATE UNIQUE INDEX ux_cfp_padrao_saida
    ON conta_forma_pagamento (conta_id) WHERE padrao_saida;

CREATE UNIQUE INDEX ux_cfp_padrao_entrada
    ON conta_forma_pagamento (conta_id) WHERE padrao_entrada;

-- Repare no que NÃO existe aqui: um CHECK impedindo padrao_saida numa forma que
-- só aceita entrada. Ele seria a segunda cópia da regra de sentido, e não é
-- necessário — se um CREDITO_EM_CONTA fosse marcado como padrão de saída, o
-- lançamento resultante seria recusado por fk_lancamento_forma_sentido lá
-- embaixo. O dado ruim não consegue produzir lançamento ruim, que é o que
-- importa. A mensagem amigável fica no serviço, onde ela pode ser uma frase.

COMMENT ON TABLE conta_forma_pagamento IS
    'Formas que cada conta aceita, com a padrao de cada sentido (V11).';


-- #############################################################################
-- 3. lancamento.forma_pagamento — DUAS chaves compostas
-- #############################################################################
-- ANULÁVEL, por três razões distintas:
--
--   a) Nem toda movimentação tem forma conhecida, e "não sei" é resposta melhor
--      que um palpite gravado.
--   b) Saldo de abertura é um lançamento em AJUSTE (A13) e transferência é um
--      par em TRANSFERENCIA: nenhum foi pago de forma alguma.
--   c) Registrar depois é legítimo — o campo aceita ser preenchido na edição.
-- #############################################################################
ALTER TABLE lancamento ADD COLUMN forma_pagamento text;

-- PRIMEIRA chave: a forma precisa estar na lista DAQUELA conta.
--
-- Um CHECK com os oito valores aceitaria BOLETO num lançamento da carteira. A
-- chave composta transforma "só as formas que esta conta aceita" em
-- impossibilidade estrutural, no mesmo espírito das três que a V10 já usa.
--
-- MATCH SIMPLE é o padrão do Postgres e é o que se quer: com qualquer coluna da
-- chave nula, a restrição não é verificada. Como conta_id é NOT NULL e
-- forma_pagamento é anulável, o efeito é "nulo passa, preenchido é conferido".
--
-- RESTRICT ao remover uma forma da lista de uma conta que já a usou: a remoção
-- é recusada em vez de apagar a informação dos lançamentos antigos. Recusar dá
-- a chance de reclassificar; SET NULL apagaria em silêncio exatamente o dado
-- que esta migração veio registrar.
ALTER TABLE lancamento ADD CONSTRAINT fk_lancamento_forma_da_conta
    FOREIGN KEY (conta_id, forma_pagamento)
    REFERENCES conta_forma_pagamento (conta_id, forma) ON DELETE RESTRICT;

-- SEGUNDA chave: a forma precisa aceitar o SENTIDO do lançamento.
--
-- É esta que impede "salário pago no boleto" e "gasolina paga com crédito em
-- conta". Sem ela, a lista da conta bastaria — e uma conta corrente que aceita
-- boleto E crédito em conta permitiria as duas trocas.
--
-- A regra não está repetida aqui: ela mora nas onze linhas de
-- forma_pagamento_sentido, e esta chave apenas as consulta.
ALTER TABLE lancamento ADD CONSTRAINT fk_lancamento_forma_sentido
    FOREIGN KEY (forma_pagamento, tipo)
    REFERENCES forma_pagamento_sentido (forma, tipo) ON DELETE RESTRICT;

-- Sem este índice, cada remoção de uma forma da lista de uma conta varre a
-- tabela de lançamentos inteira para avaliar o RESTRICT. Parcial porque só as
-- linhas com forma preenchida interessam à varredura.
CREATE INDEX ix_lancamento_forma_pagamento
    ON lancamento (conta_id, forma_pagamento)
    WHERE forma_pagamento IS NOT NULL;

COMMENT ON COLUMN lancamento.forma_pagamento IS
    'Como o dinheiro se moveu (V11). Dimensao de analise: nao entra em soma, saldo nem mapa. Nulo e legitimo.';


-- #############################################################################
-- 4. lancamento.lancamento_par_id — o par que F2 exigia e nunca existiu
-- #############################################################################
-- F2 diz "transferência são dois lançamentos LIGADOS" e F16 diz "transferência
-- PROPAGA PARA O PAR". A V10 criou a categoria sistêmica TRANSFERENCIA para
-- isso — e não criou a coluna que expressa o vínculo. A promessa estava no
-- documento e não no schema.
--
-- Não é detalhe de organização. Sem o vínculo:
--
--   - apagar uma perna deixa a outra órfã, e R$ 100 APARECEM do nada no
--     patrimônio (ou somem dele);
--   - editar o valor de uma perna e não da outra faz a mesma coisa, em silêncio.
--
-- O par é MÚTUO: A aponta para B e B aponta para A. Poderia ser um único
-- ponteiro do "filho" para o "pai", e não é de propósito — numa transferência
-- não existe perna principal. Escolher uma criaria uma assimetria que o domínio
-- não tem, e todo código que lê o par precisaria saber de que lado está.
--
-- ON DELETE CASCADE faz o banco cumprir a metade de F16 que mais dói. Apagar
-- uma perna apaga a outra: o Postgres detecta o ciclo da cascata mútua e
-- termina sozinho. E o gatilho de auditoria dispara para as DUAS linhas, então
-- nada some sem registro.
-- #############################################################################
ALTER TABLE lancamento ADD COLUMN lancamento_par_id uuid;

ALTER TABLE lancamento ADD CONSTRAINT fk_lancamento_par
    FOREIGN KEY (lancamento_par_id)
    REFERENCES lancamento (id) ON DELETE CASCADE;

-- Um lançamento não é par de si mesmo. Sem isto, um UPDATE distraído criaria
-- uma "transferência" de uma conta para ela mesma, com saldo intacto e extrato
-- mentindo.
ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_par_nao_e_ele_mesmo
    CHECK (lancamento_par_id IS NULL OR lancamento_par_id <> id);

-- E ninguém é par de dois. Sem este índice, três lançamentos poderiam apontar
-- para a mesma perna, e a cascata apagaria mais do que a transferência.
CREATE UNIQUE INDEX ux_lancamento_par
    ON lancamento (lancamento_par_id)
    WHERE lancamento_par_id IS NOT NULL;

COMMENT ON COLUMN lancamento.lancamento_par_id IS
    'A outra perna da transferencia (F2). Mutuo: A aponta B e B aponta A. CASCADE cumpre F16 no banco.';


-- #############################################################################
-- 5. ROW LEVEL SECURITY E AUDITORIA
-- #############################################################################
-- A regra da casa da V10 continua: tabela DE DOMÍNIO nova nasce com RLS ligado.
-- Vale para conta_forma_pagamento e não para as duas de referência — aquelas
-- não têm dado de ninguém, e uma política `USING (true)` seria uma política que
-- não significa nada. O controle delas é o REVOKE da seção 1.
--
-- Aqui a pergunta é feita à CONTA, e não a um ambiente_id que esta tabela não
-- tem, pela mesma razão de conta (R7): a lista pertence à conta, e a conta é
-- visível por vínculo, não por igualdade de campo.
--
-- Diferente de conta_ambiente, o WITH CHECK precisa de UMA condição só. Aquela
-- tabela é perigosa porque tem dois lados e o INSERT poderia capturar conta
-- alheia; esta tem um lado só, e ele é justamente o que a condição confere.
--
-- E a conta recém-criada, que ainda não é visível? Não é problema: a lista é
-- gravada DEPOIS de app_criar_conta() ter criado conta e vínculo, e nesse ponto
-- app_contas_do_usuario() já a devolve. Nenhuma porta estreita nova.
-- #############################################################################
ALTER TABLE conta_forma_pagamento ENABLE ROW LEVEL SECURITY;

CREATE POLICY pol_cfp_conta ON conta_forma_pagamento
    FOR ALL
    USING      (conta_id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (conta_id IN (SELECT app_contas_do_usuario()));

-- Mesmo gatilho genérico de F26 / B-D6. Ele extrai ambiente_id e id do JSON da
-- linha, então serve a esta tabela sem alteração — do mesmo jeito que já serve
-- a conta_ambiente, que também não tem nenhuma das duas colunas.
CREATE TRIGGER tg_auditar_conta_forma_pagamento
    AFTER INSERT OR UPDATE OR DELETE ON conta_forma_pagamento
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('ContaFormaPagamento');
