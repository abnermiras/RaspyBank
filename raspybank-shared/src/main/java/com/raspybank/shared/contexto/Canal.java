package com.raspybank.shared.contexto;

/**
 * Canal de origem de uma operação: por onde a ação chegou ao sistema.
 *
 * REGRA CENTRAL deste enum (e de todos os enums de domínio do projeto):
 * o name() do enum É o valor gravado no banco. Não existe campo paralelo.
 *
 * Por que removemos o valorBanco que existia aqui?
 * Porque ele divergiu: a migração V8 subiu o banco para maiúsculo
 * e o campo continuou "web" minúsculo. Um campo paralelo é uma
 * segunda fonte de verdade — e duas fontes de verdade eventualmente
 * discordam. Com name(), a divergência é impossível por construção.
 *
 * Os quatro valores espelham o CHECK ck_auditoria_canal. Adicionar
 * valor aqui sem migração correspondente = violação de CHECK em
 * runtime. Migração primeiro, enum depois. Sempre.
 */
public enum Canal {

    /** Ação originada pela interface web. */
    WEB,

    /** Ação originada pelo bot Telegram (futuro). */
    TELEGRAM,

    /**
     * Ação originada pelo próprio sistema, sem usuário no gatilho
     * (ex.: fechamento automático de fatura, jobs agendados).
     */
    SISTEMA,

    /**
     * Rede de segurança: o contexto da requisição não pôde determinar
     * o canal. Se aparecer na trilha, é sinal de bug a investigar.
     */
    DESCONHECIDO;

    /**
     * Converte o texto vindo do banco (ou de fora) no enum.
     *
     * Por que não usar Canal.valueOf() direto pelo código afora?
     * valueOf lança IllegalArgumentException com mensagem genérica.
     * Centralizar aqui dá uma mensagem que aponta o dado podre —
     * e um único lugar para colocar tolerância (trim, caixa) se
     * um dia precisarmos ler dado legado.
     */
    public static Canal de(String valor) {
        if (valor == null) {
            throw new IllegalArgumentException(
                "Canal nulo — a auditoria exige canal de origem.");
        }
        try {
            return Canal.valueOf(valor.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Canal desconhecido: '" + valor
                + "'. Válidos: WEB, TELEGRAM, SISTEMA, DESCONHECIDO.");
        }
    }
}
