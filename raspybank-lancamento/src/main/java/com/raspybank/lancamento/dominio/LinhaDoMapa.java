package com.raspybank.lancamento.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma celula crua do mapa de gastos, como o banco a devolve: um mes, uma
 * classificacao, dois numeros.
 *
 * <p>O relatorio inteiro sai de <b>uma</b> varredura que produz estas linhas.
 * O agrupamento em blocos, o aninhamento das subcategorias e o preenchimento
 * dos meses vazios acontecem depois, em memoria — sobre um punhado de linhas,
 * nao sobre a tabela.</p>
 *
 * <p><b>Os dois numeros nunca viram um</b> (B-D10). Se a soma chegasse pronta,
 * a tela nao teria como cumprir a parte do "deixa claro que ainda nao
 * realizou". Quem separa e quem calcula.</p>
 *
 * <p>Aqui os dois sao sempre positivos: o bloco (saidas ou entradas) ja diz o
 * sentido, e {@code valor} e positivo por construcao (F1). Sinal so aparece na
 * linha de saldo, que e entradas menos saidas.</p>
 *
 * @param subcategoriaId nulo e legitimo — F11 torna a subcategoria opcional, e
 *                       essa linha vira "(sem subcategoria)" na tela
 */
public record LinhaDoMapa(
    TipoLancamento tipo,
    UUID categoriaId,
    String categoriaNome,
    UUID subcategoriaId,
    String subcategoriaNome,
    Integer mes,
    BigDecimal realizado,
    BigDecimal previsto
) {}
