package com.raspybank.shared.contexto;

/**
 * Operação registrada na trilha de auditoria: o QUE aconteceu.
 * (O Canal diz POR ONDE; a Operacao diz O QUÊ.)
 *
 * Vive em raspybank-shared porque auditoria é transversal:
 * todo módulo de negócio registra eventos, então todo módulo
 * precisa enxergar este vocabulário.
 *
 * Contrato: name() == valor do CHECK ck_auditoria_operacao (V9).
 * Adicionar um valor aqui SEM migração correspondente = violação
 * de CHECK em runtime. Migração primeiro, enum depois. Sempre.
 *
 * Nota de arqueologia: o nome é ALTERACAO (não ATUALIZACAO) porque
 * o CHECK da V2 já dizia ALTERACAO. O enum se curva ao banco —
 * renomear no banco seria migração + UPDATE de dados sem ganho.
 */
public enum Operacao {

    /** Um registro passou a existir (cadastro de usuário, novo lançamento...). */
    CRIACAO,

    /** Um registro existente mudou de estado ou conteúdo. */
    ALTERACAO,

    /** Um registro deixou de existir (física ou logicamente). */
    EXCLUSAO,

    /**
     * O usuário entrou no sistema. Login não cria nada —
     * registrá-lo como CRIACAO poluía a trilha (Bloco A, V9).
     */
    ACESSO;

    /**
     * Parsing centralizado com mensagem que aponta o dado podre.
     * Preferir este método a valueOf() espalhado pelo código.
     */
    public static Operacao de(String valor) {
        if (valor == null) {
            throw new IllegalArgumentException(
                "Operação nula — a auditoria exige a operação.");
        }
        try {
            return Operacao.valueOf(valor.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Operação desconhecida: '" + valor
                + "'. Válidas: CRIACAO, ALTERACAO, EXCLUSAO, ACESSO.");
        }
    }
}
