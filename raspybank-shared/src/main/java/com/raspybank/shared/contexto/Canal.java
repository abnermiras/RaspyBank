package com.raspybank.shared.contexto;

/**
 * Canal pelo qual uma operacao chegou ao sistema.
 *
 * <p>Existe porque a auditoria precisa registrar a origem: saber que um
 * lancamento foi alterado pelo Telegram, e nao pela web, e a informacao que
 * de fato explica alguma coisa. O banco de dados nao tem como saber isso —
 * para ele, toda alteracao chega pela mesma conexao.</p>
 *
 * <p>Os valores precisam bater com a restricao CHECK da tabela
 * {@code registro_auditoria}.</p>
 */
public enum Canal {

    /** Interface web. */
    WEB("web"),

    /** Bot do Telegram. */
    TELEGRAM("telegram"),

    /** Rotinas automaticas: fechamento de fatura, tarefas agendadas. */
    SISTEMA("sistema");

    private final String valorBanco;

    Canal(String valorBanco) {
        this.valorBanco = valorBanco;
    }

    public String valorBanco() {
        return valorBanco;
    }
}
