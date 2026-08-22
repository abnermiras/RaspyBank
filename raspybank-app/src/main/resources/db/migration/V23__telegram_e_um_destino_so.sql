-- =============================================================================
-- V23 — Um Telegram e um destino so, e destino vazio nao e destino
-- =============================================================================
-- Dois defeitos achados pelo qa-adversarial em 18/08/2026 (TelegramNoPerfilTest),
-- logo depois que o PUT /api/perfil/telegram abriu o terceiro caminho de escrita
-- da coluna (B-D120). Os dois tem a mesma raiz: uma invariante de negocio que o
-- projeto escreveu em prosa e em Java, e nunca escreveu no banco.
--
-- 1) O INDICE UNICO NAO IMPEDIA O QUE B-D121 DIZ QUE ELE IMPEDE
--
--    B-D121 recusa verificacao de posse do telegramId, e o argumento inteiro se
--    apoia numa frase: "o indice unico ja impede duas contas apontando para o
--    mesmo destino". docs/api.md repete a promessa no 409, com o motivo:
--    "duas contas apontando para o mesmo destino fariam o bot nao saber para
--    quem lancar".
--
--    So que ux_usuario_telegram (V1:72) compara TEXTO LITERAL, e no Telegram
--    'abner', 'ABNER' e '@abner' sao a MESMA conta — o username nao diferencia
--    caixa, e o '@' e enfeite de exibicao, nao parte do identificador. Tres
--    contas do RaspyBank gravaram as tres grafias, todas com 200. A premissa de
--    B-D121 era falsa; esta migracao a torna verdadeira, em vez de mudar a
--    decisao que dependia dela.
--
--    O padrao ja existia na mesma tabela e no mesmo arquivo: ux_usuario_email
--    resolve o caso irmao com lower(email) desde a V1. Aqui e o mesmo remedio,
--    com um enfeite a mais para tirar.
--
-- 2) STRING VAZIA ERA ACEITA PELO BANCO
--
--    "Vazio vira NULL" nao e higiene, e o indice PARCIAL: ele e
--    WHERE telegram_id IS NOT NULL justamente para varias contas conviverem sem
--    Telegram, e '' e valor REAL para ele — a SEGUNDA pessoa a limpar o campo
--    levaria 409 num campo que ninguem preencheu.
--
--    A regra morava em duas copias de codigo — NULLIF(btrim(...), '') dentro de
--    auth_cadastrar_usuario (V18) e trim/isEmpty no UsuarioServico — e em ZERO
--    lugares do banco. Um UPDATE cru como raspybank_app, na propria linha e com
--    identidade na sessao, gravava '' sem reclamacao. Duas copias e nenhuma
--    guarda: basta um quarto caminho de escrita (o bot, uma importacao, uma
--    correcao manual) para a invariante cair. Agora ela e CHECK.
--
-- O QUE ESTA MIGRACAO NAO FAZ: verificacao de posse. B-D121 continua valendo —
-- quem digitar o Telegram de outra pessoa seguira sem aviso. O que muda e que
-- duas contas nao ficam mais apontando para o mesmo destino, que era o que a
-- decisao afirmava ja acontecer.
--
-- Revoga a parte da V1 que dizia unicidade por texto literal; nao revoga
-- decisao nenhuma. As duas copias em Java continuam onde estao, agora com uma
-- rede embaixo delas.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. A forma normalizada, e por que ela e esta
-- -----------------------------------------------------------------------------
-- A expressao usada daqui em diante, no CHECK e no indice, e:
--
--     lower(regexp_replace(btrim(telegram_id), '^@', ''))
--
-- Ela aparece DUAS vezes neste arquivo, de proposito e nao por descuido. A
-- alternativa era uma funcao fn_telegram_normalizado(text) IMMUTABLE, chamada
-- pelos dois — mais bonita, e recusada por duas razoes concretas:
--
--   * indice sobre funcao propria envelhece em SILENCIO. Um CREATE OR REPLACE
--     futuro na funcao nao reconstroi o indice: as chaves antigas continuam
--     gravadas com a regra velha e o banco para de encontrar linhas que existem.
--     Expressao literal nao tem esse estado escondido — mudar a regra obriga a
--     escrever DROP INDEX + CREATE INDEX numa migracao nova, que e exatamente o
--     que se quer que aconteca.
--   * CHECK que depende de funcao propria acrescenta uma dependencia de ordem no
--     pg_dump/restore, e o backup do Pi e restaurado de verdade.
--
-- E o precedente da casa e esse: ux_usuario_email escreve lower(email) inline, e
-- UsuarioRepositorio.buscarPorEmail repete lower(...) do lado Java para casar com
-- ele. Duas ocorrencias vizinhas, num arquivo so, valem menos risco que uma
-- indirecao que pode dessincronizar sem ninguem ver.
--
-- POR QUE regexp_replace(..., '^@', '') E NAO ltrim(..., '@'):
--
--   ltrim com conjunto de caracteres remove uma SEQUENCIA: '@@abner' viraria
--   'abner'. Isso inventa uma equivalencia que o Telegram nao tem — '@@abner'
--   nao e conta nenhuma. E o efeito pratico e ruim nas duas pontas:
--
--     - um valor lixo ('@@abner') passaria a OCUPAR o destino real de @abner, e
--       uma consulta futura do bot por 'abner' encontraria a linha errada — ou
--       seja, o bot lancando para quem nao deve, que e pior que nao lancar;
--     - a dona legitima de 'abner' levaria 409 por causa do lixo de outra pessoa.
--
--   O '^@' ancorado tira EXATAMENTE um '@' inicial, que e o que o '@' e: prefixo
--   de exibicao, um so. '@@abner' continua sendo '@abner' depois de normalizado,
--   nao colide com ninguem e simplesmente nunca sera reconhecido — que e o dano
--   que B-D121 ja aceitou em voz alta ("o dano de errar e nao funcionar").
--
--   btrim entra na expressao porque ' abner' e 'abner' sao o mesmo destino:
--   espaco em volta nao faz parte de identificador nenhum.
--
--   lower() por ultimo porque username de Telegram ignora caixa. Id numerico
--   passa por tudo isso sem alteracao.
-- -----------------------------------------------------------------------------


-- -----------------------------------------------------------------------------
-- 2. Dado existente: o branco vira nulo (ausencia nao e valor)
-- -----------------------------------------------------------------------------
-- Antes de qualquer constraint, o que ja esta gravado. Esta migracao roda como
-- raspybank_owner e usuario nao tem FORCE ROW LEVEL SECURITY, entao o UPDATE
-- abaixo alcanca todas as linhas — e precisa alcancar.
--
-- Converter '' e '   ' em NULL nao apaga dado de ninguem: os dois JA significam
-- "nao informei", em toda parte do sistema que trata do assunto (V18 e o
-- UsuarioServico). Ficar sem valor e o estado que a pessoa escolheu; o que muda
-- e a representacao dele. O efeito colateral e o gatilho tg_usuario_atualizado
-- mexer em atualizado_em das linhas afetadas, e isso e aceitavel — a linha mudou
-- mesmo.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_linhas int;
BEGIN
    UPDATE usuario
       SET telegram_id = NULL
     WHERE telegram_id IS NOT NULL
       AND btrim(telegram_id) = '';

    GET DIAGNOSTICS v_linhas = ROW_COUNT;

    IF v_linhas > 0 THEN
        RAISE NOTICE 'V23: % linha(s) com telegram_id em branco viraram NULL.', v_linhas;
    END IF;
END;
$$;


-- -----------------------------------------------------------------------------
-- 3. Dado existente: o que normaliza para nada, mas nao e branco, PARA a migracao
-- -----------------------------------------------------------------------------
-- Sobrou um caso: '@' sozinho (ou '  @  '). Nao e ausencia — alguem digitou
-- alguma coisa —, e tambem nao identifica ninguem. Aqui a migracao FALHA ALTO,
-- com os ids na mensagem, em vez de decidir sozinha o que fazer com o que a
-- pessoa escreveu. Corrigir a mao e barato; descobrir meses depois que a
-- migracao apagou o campo de alguem em silencio, nao.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_relato text;
BEGIN
    SELECT string_agg(format('  %s -> %L', id, telegram_id), E'\n' ORDER BY id)
      INTO v_relato
      FROM usuario
     WHERE telegram_id IS NOT NULL
       AND lower(regexp_replace(btrim(telegram_id), '^@', '')) = '';

    IF v_relato IS NOT NULL THEN
        RAISE EXCEPTION
            E'V23 nao pode continuar: ha telegram_id que nao identifica conta '
            'nenhuma depois de normalizado (um "@" sem nome, por exemplo).\n%\n'
            'Decida caso a caso e limpe (UPDATE usuario SET telegram_id = NULL '
            'WHERE id = ...) antes de aplicar a migracao de novo.', v_relato;
    END IF;
END;
$$;


-- -----------------------------------------------------------------------------
-- 4. Dado existente: colisoes sob a regra nova PARAM a migracao
-- -----------------------------------------------------------------------------
-- Pode haver, em producao, duas contas com 'abner' e '@Abner'. Sob a regra nova
-- elas sao a mesma, e o CREATE UNIQUE INDEX abaixo falharia — mas falharia
-- mostrando UM par e o valor da chave, sem dizer o que fazer.
--
-- Este bloco antecipa a falha com o relatorio inteiro: qual destino, quais
-- contas, qual texto cada uma gravou. Quem decide qual das duas fica com o
-- destino e uma pessoa, nao a migracao — escolher pela mais antiga seria
-- inventar regra de negocio dentro de um arquivo de schema, e apagar as duas
-- seria destruir dado para nao ter que conversar.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_relato text;
BEGIN
    SELECT string_agg(format('  %L: %s', c.destino, c.contas), E'\n' ORDER BY c.destino)
      INTO v_relato
      FROM (
          SELECT lower(regexp_replace(btrim(telegram_id), '^@', '')) AS destino,
                 string_agg(format('%s (%L)', id, telegram_id), ', ' ORDER BY criado_em)
                     AS contas
            FROM usuario
           WHERE telegram_id IS NOT NULL
           GROUP BY 1
          HAVING count(*) > 1
      ) c;

    IF v_relato IS NOT NULL THEN
        RAISE EXCEPTION
            E'V23 nao pode continuar: ha contas diferentes apontando para o MESMO '
            'destino de Telegram (o "@" e a caixa nao os distinguem).\n%\n'
            'Escolha qual conta fica com cada destino e limpe as outras '
            '(UPDATE usuario SET telegram_id = NULL WHERE id = ...) antes de '
            'aplicar a migracao de novo.', v_relato;
    END IF;
END;
$$;


-- -----------------------------------------------------------------------------
-- 5. O CHECK — defeito 2
-- -----------------------------------------------------------------------------
-- NULL continua permitido: o campo e opcional, e nulo e o unico jeito de ficar
-- fora do indice parcial.
--
-- O criterio nao e "diferente de vazio", e "sobra alguma coisa depois de
-- normalizar". Isso cobre '', '   ' e '@' com uma regra so, e e literalmente a
-- mesma expressao da chave do indice — se um dia ela mudar, muda nos dois, na
-- mesma migracao.
--
-- O nome da constraint nomeia a coluna de proposito: a mensagem de erro do
-- Postgres carrega o nome, e quem receber a excecao precisa saber do que se
-- trata sem abrir o schema.
-- -----------------------------------------------------------------------------
ALTER TABLE usuario
    ADD CONSTRAINT ck_usuario_telegram_identificavel CHECK (
        telegram_id IS NULL
        OR lower(regexp_replace(btrim(telegram_id), '^@', '')) <> ''
    );


-- -----------------------------------------------------------------------------
-- 6. O indice — defeito 1
-- -----------------------------------------------------------------------------
-- DROP + CREATE explicitos, e o nome VOLTA a ser ux_usuario_telegram. Nao e
-- apego: TratadorGlobalDeErros procura essa string na mensagem do SQLException
-- para transformar a violacao em 409 com frase exibivel, e docs/api.md promete
-- esse 409. Indice com nome novo faria a mesma violacao virar 500 "Erro
-- interno" — o defeito trocaria de forma em vez de sumir.
--
-- Mesmo motivo de sempre para nao deixar os dois vivos (V18): duas portas para
-- a mesma garantia, sendo que a antiga deixa passar o que a nova barra.
--
-- A PARCIALIDADE E PRESERVADA. O WHERE telegram_id IS NOT NULL e o que permite
-- varias contas sem Telegram conviverem; sem ele, o segundo cadastro sem
-- Telegram quebra — o defeito que a V1 documentou e a V18 teve que contornar.
-- Com a expressao no lugar da coluna crua o WHERE continua sobre a COLUNA, e nao
-- sobre a expressao, porque o que fica de fora do indice e a ausencia de valor,
-- nao o resultado da normalizacao (esse, o CHECK acima ja garante que nunca e
-- vazio).
-- -----------------------------------------------------------------------------
DROP INDEX ux_usuario_telegram;

CREATE UNIQUE INDEX ux_usuario_telegram
    ON usuario (lower(regexp_replace(btrim(telegram_id), '^@', '')))
    WHERE telegram_id IS NOT NULL;


COMMENT ON COLUMN usuario.telegram_id IS
    'Identificador do Telegram, como a pessoa digitou. A unicidade e pelo DESTINO, nao pelo texto: ux_usuario_telegram indexa lower(regexp_replace(btrim(...), ''^@'', '''')), porque @Fulano, fulano e FULANO sao a mesma conta. Nulo = nao informado; string vazia e proibida por ck_usuario_telegram_identificavel.';


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   -- (a) O caso do defeito 1: as tres grafias sao o MESMO destino.
--   --     O cadastro passa por auth_cadastrar_usuario (SECURITY DEFINER), que e
--   --     o unico caminho de INSERT que a aplicacao tem.
--   SELECT auth_cadastrar_usuario('A', 'a@x.local', 'hash', 'alias_teste');
--   SELECT auth_cadastrar_usuario('B', 'b@x.local', 'hash', 'ALIAS_TESTE');
--   -- ERRO: duplicate key value violates unique constraint "ux_usuario_telegram"
--   SELECT auth_cadastrar_usuario('C', 'c@x.local', 'hash', '@alias_teste');
--   -- ERRO: o mesmo. Antes da V23 os tres entravam, e o bot ficava sem saber
--   -- para quem lancar — que e o motivo do 409 escrito em docs/api.md.
--
--   -- (b) A parcialidade continua de pe: varios sem Telegram convivem.
--   SELECT auth_cadastrar_usuario('D', 'd@x.local', 'hash', NULL);
--   SELECT auth_cadastrar_usuario('E', 'e@x.local', 'hash', '');
--   SELECT auth_cadastrar_usuario('F', 'f@x.local', 'hash', '   ');
--   -- os tres passam; o NULLIF(btrim(...)) da V18 grava NULL nos dois ultimos.
--
--   -- (c) O caso do defeito 2: o UPDATE cru, que e o que um caminho de escrita
--   --     novo faria. Precisa de identidade na sessao (pol_usuario_escrita).
--   SELECT id FROM usuario WHERE email = 'a@x.local';   -- copie o id
--   SELECT set_config('raspybank.usuario_id', '<id-de-A>', false);
--
--   UPDATE usuario SET telegram_id = '' WHERE id = '<id-de-A>';
--   -- ERRO: new row for relation "usuario" violates check constraint
--   --       "ck_usuario_telegram_identificavel"
--   UPDATE usuario SET telegram_id = '@' WHERE id = '<id-de-A>';
--   -- ERRO: o mesmo — '@' sozinho nao identifica conta nenhuma.
--
--   -- (d) E o indice NAO dispara contra a propria linha, nem quando a grafia
--   --     muda mas o destino e o mesmo:
--   UPDATE usuario SET telegram_id = '@Alias_Teste' WHERE id = '<id-de-A>';
--   -- UPDATE 1. Mesmo destino, mesma linha: nao ha conflito consigo mesma.
--
--   -- (e) Conferindo do lado do dono (make psql), a chave que o indice guarda:
--   SELECT telegram_id,
--          lower(regexp_replace(btrim(telegram_id), '^@', '')) AS destino
--     FROM usuario WHERE telegram_id IS NOT NULL;
--
--   -- Limpeza:
--   -- (make psql) DELETE FROM usuario WHERE email LIKE '%@x.local';
-- =============================================================================
