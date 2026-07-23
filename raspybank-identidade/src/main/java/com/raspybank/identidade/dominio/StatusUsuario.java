package com.raspybank.identidade.dominio;

/**
 * Status do usuário no ciclo de vida da conta.
 *
 * Valores conferidos contra o CHECK real ck_usuario_status
 * (\d usuario, 23/07/2026): ATIVO, INATIVO. Exatamente estes.
 *
 * Fica DENTRO do módulo identidade (não no shared) por decisão do
 * Bloco A: o ciclo de vida de um usuário e o de um ambiente são
 * conceitos diferentes que hoje coincidem por acaso. Um enum
 * compartilhado forçaria os dois a evoluírem juntos — quando o
 * usuário ganhar um BLOQUEADO, o ambiente não deve ganhar junto.
 *
 * Contrato: name() == valor do CHECK. A entidade Usuario mapeia
 * este enum com @Enumerated(EnumType.STRING), o que garante a
 * gravação correta por construção. NUNCA usar ORDINAL.
 */
public enum StatusUsuario {

    /** Usuário ativo, pode autenticar e operar. */
    ATIVO,

    /** Usuário inativo, autenticação negada. */
    INATIVO
}
