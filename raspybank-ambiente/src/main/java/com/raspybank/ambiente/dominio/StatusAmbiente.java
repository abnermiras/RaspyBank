package com.raspybank.ambiente.dominio;

/**
 * Status do ambiente financeiro.
 *
 * Valores conferidos contra o CHECK real ck_ambiente_status
 * (\d ambiente, 23/07/2026): ATIVO, INATIVO. Exatamente estes.
 *
 * Enum próprio do módulo ambiente — não compartilhado com o de
 * usuário. Justificativa completa em StatusUsuario.
 *
 * PENDÊNCIA REGISTRADA (Bloco B, inconsistências conhecidas):
 * a tabela ambiente tem status E excluido_em, dois mecanismos de
 * ciclo de vida sem regra clara sobre o que INATIVO significa que
 * excluido_em não significa. Decisão adiada — não bloqueia o Bloco A.
 *
 * Contrato: name() == valor do CHECK, via @Enumerated(EnumType.STRING).
 */
public enum StatusAmbiente {

    /** Ambiente ativo, visível e operável. */
    ATIVO,

    /** Ambiente inativo. Semântica exata pendente de decisão
     *  (vide pendência acima). */
    INATIVO
}
