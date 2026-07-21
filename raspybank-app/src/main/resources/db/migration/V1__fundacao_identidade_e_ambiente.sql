-- =============================================================================
-- V1 — Fundação: Identidade e Ambiente
-- =============================================================================
-- Cria as tabelas que sustentam todo o resto do sistema.
--
-- REGRA DO FLYWAY: este arquivo é IMUTÁVEL depois de aplicado.
-- O Flyway guarda uma soma de verificação de cada migração; alterar um arquivo
-- já aplicado faz a aplicação recusar-se a subir. Correções vêm sempre em uma
-- nova migração (V4, V5...), nunca editando uma antiga.
--
-- Referências de requisito: RF-M1-01, RF-M2-01 a RF-M2-06
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Função utilitária: manutenção automática de "atualizado_em"
-- -----------------------------------------------------------------------------
-- Sem isso, cada UPDATE precisaria lembrar de atualizar a coluna manualmente,
-- e uma hora alguém esquece.
CREATE OR REPLACE FUNCTION fn_atualizar_timestamp()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.atualizado_em = now();
    RETURN NEW;
END;
$$;


-- -----------------------------------------------------------------------------
-- usuario — Módulo M1
-- -----------------------------------------------------------------------------
CREATE TABLE usuario (

    -- UUIDv7: ordenado por tempo, gerado pelo próprio banco.
    -- Ordenado significa que inserções consecutivas caem em posições próximas
    -- do índice B-tree, sem a fragmentação típica do UUID aleatório.
    -- Ver decisão de arquitetura sobre chave primária.
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    nome            text        NOT NULL,

    -- Guardado como digitado, para exibir do jeito que a pessoa escreveu.
    -- A unicidade sem diferenciar maiúsculas é garantida pelo índice abaixo.
    email           text        NOT NULL,

    -- Hash da senha, nunca a senha. O algoritmo (BCrypt, Argon2) é decisão da
    -- camada de aplicação; o banco só guarda o resultado.
    senha_hash      text        NOT NULL,

    -- Identificador do Telegram. Opcional — RF-M1-01.
    telegram_id     text,

    status          text        NOT NULL DEFAULT 'ativo',

    criado_em       timestamptz NOT NULL DEFAULT now(),
    atualizado_em   timestamptz NOT NULL DEFAULT now(),

    -- Restringe os valores possíveis no próprio banco. Mesmo que a aplicação
    -- tenha um defeito, o dado inválido não entra.
    CONSTRAINT ck_usuario_status CHECK (status IN ('ativo', 'inativo'))
);

-- Unicidade de e-mail ignorando maiúsculas e minúsculas:
-- "Abner@x.com" e "abner@x.com" são a mesma pessoa.
CREATE UNIQUE INDEX ux_usuario_email ON usuario (lower(email));

-- Unicidade do Telegram apenas entre quem informou.
-- A cláusula WHERE cria um índice PARCIAL: valores nulos ficam de fora, então
-- vários usuários podem não ter Telegram sem colidir entre si.
CREATE UNIQUE INDEX ux_usuario_telegram ON usuario (telegram_id)
    WHERE telegram_id IS NOT NULL;

CREATE TRIGGER tg_usuario_atualizado
    BEFORE UPDATE ON usuario
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE  usuario IS 'Pessoa autenticada. Pertence a um ou mais ambientes (RF-M2-02).';
COMMENT ON COLUMN usuario.senha_hash IS 'Hash da senha. Nunca a senha em texto.';


-- -----------------------------------------------------------------------------
-- ambiente — Módulo M2
-- -----------------------------------------------------------------------------
-- O Ambiente é a FRONTEIRA DE DADOS do sistema (princípio P4).
-- Todo dado financeiro pertence a exatamente um ambiente, e nenhum usuário
-- enxerga dado de ambiente ao qual não pertence.
--
-- Ele atende três cenários com a mesma estrutura:
--   - uso individual                 (1 usuário, 1 ambiente)
--   - casal com finanças comuns      (2 usuários, 1 ambiente)
--   - várias famílias na instalação  (N ambientes independentes)
-- -----------------------------------------------------------------------------
CREATE TABLE ambiente (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    nome            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ativo',

    criado_em       timestamptz NOT NULL DEFAULT now(),
    atualizado_em   timestamptz NOT NULL DEFAULT now(),

    -- Exclusão lógica: o histórico financeiro é preservado.
    -- Nulo significa ativo; preenchido significa excluído naquela data.
    excluido_em     timestamptz,

    CONSTRAINT ck_ambiente_status CHECK (status IN ('ativo', 'inativo'))
);

CREATE TRIGGER tg_ambiente_atualizado
    BEFORE UPDATE ON ambiente
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE ambiente IS
    'Fronteira de dados. Todo dado financeiro pertence a exatamente um ambiente (P4, RF-M2-01).';


-- -----------------------------------------------------------------------------
-- usuario_ambiente — vínculo
-- -----------------------------------------------------------------------------
-- Relacionamento muitos-para-muitos: RF-M2-02 e RF-M2-03.
--
-- Não existe coluna de papel ou permissão, e isso é intencional: RF-M2-04
-- estabelece acesso total para quem está dentro. Quem compartilha finanças
-- não tem o que esconder da outra parte; a separação de responsabilidade é
-- feita pelo Responsável, que é dimensão de análise, não de acesso.
-- -----------------------------------------------------------------------------
CREATE TABLE usuario_ambiente (

    usuario_id      uuid        NOT NULL,
    ambiente_id     uuid        NOT NULL,
    criado_em       timestamptz NOT NULL DEFAULT now(),

    -- Chave primária composta: um usuário não pode ser vinculado duas vezes
    -- ao mesmo ambiente. A regra é estrutural, não depende de validação.
    CONSTRAINT pk_usuario_ambiente PRIMARY KEY (usuario_id, ambiente_id),

    -- ON DELETE CASCADE: se o usuário for removido fisicamente, os vínculos
    -- somem junto. Não faz sentido vínculo órfão.
    CONSTRAINT fk_ua_usuario  FOREIGN KEY (usuario_id)
        REFERENCES usuario (id)  ON DELETE CASCADE,

    -- RESTRICT no ambiente: impede apagar um ambiente que ainda tem gente
    -- dentro. Exclusão de ambiente é lógica (excluido_em), não física.
    CONSTRAINT fk_ua_ambiente FOREIGN KEY (ambiente_id)
        REFERENCES ambiente (id) ON DELETE RESTRICT
);

-- A chave primária já indexa (usuario_id, ambiente_id) nessa ordem.
-- Este índice cobre o caminho inverso: "quem são os usuários deste ambiente".
CREATE INDEX ix_usuario_ambiente_ambiente ON usuario_ambiente (ambiente_id);

COMMENT ON TABLE usuario_ambiente IS
    'Vínculo N:N. Sem coluna de permissão: acesso é total por decisão (RF-M2-04).';
