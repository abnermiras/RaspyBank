-- ============================================================================
-- V9: A operação ACESSO passa a existir no vocabulário da auditoria.
-- ============================================================================
--
-- Motivo (Bloco A, 23/07/2026): login estava sendo registrado como CRIACAO,
-- o que polui semanticamente a trilha — um filtro "tudo que foi criado"
-- devolvia logins no meio. Login não cria nada; ACESSO é operação de
-- primeira classe.
--
-- Nota de método: nomes de constraint e valores abaixo foram CONFERIDOS
-- contra o schema real (\d registro_auditoria) e contra o corpo de
-- auth_registrar_evento (\sf) antes da escrita. O rascunho original errava
-- o nome da constraint e usava ATUALIZACAO em vez de ALTERACAO.
--
-- Contrato vigente desde a V8: valor no banco == name() do enum Java.
-- Este arquivo muda o banco ANTES do código passar a enviar ACESSO —
-- na ordem inversa, o código gravaria valor que o CHECK rejeita.
-- ============================================================================

-- Passo 1: derruba o CHECK atual (só conhece CRIACAO, ALTERACAO, EXCLUSAO).
ALTER TABLE registro_auditoria
    DROP CONSTRAINT ck_auditoria_operacao;

-- Passo 2: recria com ACESSO. Os valores são EXATAMENTE os names() do
-- enum Operacao em raspybank-shared.
ALTER TABLE registro_auditoria
    ADD CONSTRAINT ck_auditoria_operacao
    CHECK (operacao = ANY (ARRAY['CRIACAO'::text, 'ALTERACAO'::text,
                                 'EXCLUSAO'::text, 'ACESSO'::text]));

-- Passo 3: corrige o passado — logins gravados como CRIACAO viram ACESSO.
-- Filtro triplo, conferido contra o corpo real de auth_registrar_evento:
--   - operacao:    só o valor antigo errado
--   - entidade:    a função grava 'Autenticacao' fixo — protege eventos
--                  de outros módulos que um dia tenham JSON parecido
--   - estado_novo: só o evento de login (o cadastro de usuário, que é
--                  CRIACAO legítima da mesma entidade, não é tocado)
UPDATE registro_auditoria
   SET operacao = 'ACESSO'
 WHERE operacao = 'CRIACAO'
   AND entidade = 'Autenticacao'
   AND estado_novo ->> 'evento' = 'login';
