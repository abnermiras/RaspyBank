package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * O vocabulario de formas de pagamento — contrato em {@code docs/api.md} §4b.
 *
 * <h3>Por que este endpoint existe</h3>
 *
 * <p>Para que a lista de formas e a regra de qual serve a qual sentido NAO
 * sejam reescritas em JavaScript. A regra ja vive em dois lugares por
 * necessidade — as onze linhas de {@code forma_pagamento_sentido}, que o banco
 * impoe, e o enum Java, que da a mensagem de erro. Uma terceira copia no
 * frontend divergiria na primeira forma nova, e o sintoma seria o seletor
 * oferecendo uma opcao que o servidor recusa.</p>
 *
 * <p>Somente leitura, e nao ha endpoint de escrita nem havera: o vocabulario e
 * fixo (B-D30). Acrescentar uma forma e uma migracao, nao um cadastro.</p>
 */
@RestController
@RequestMapping("/api/formas-pagamento")
public class FormaPagamentoControlador {

    /**
     * A lista inteira, na ordem em que a tela deve mostra-la.
     *
     * <p>A ordem e a de declaracao do enum, do mais comum para o menos — e nao
     * alfabetica, que poria "Boleto" antes de "Debito" sem motivo nenhum.</p>
     *
     * <p>Nao ha recorte por ambiente nem por usuario: a lista e a mesma para
     * todo mundo. E por isso que as tabelas de referencia por tras dela nao tem
     * RLS — uma politica ali seria teatro.</p>
     */
    @GetMapping
    public Map<String, Object> listar() {
        List<Item> lista = Arrays.stream(FormaPagamento.values())
            .map(Item::de)
            .toList();

        return Map.of("formasPagamento", lista);
    }

    /**
     * @param sentidos os tipos de lancamento que esta forma aceita. A tela usa
     *                 isto para filtrar o seletor: ao lancar uma entrada, so
     *                 aparecem as que aceitam {@code ENTRADA}
     */
    public record Item(String valor, String nome, List<TipoLancamento> sentidos) {
        static Item de(FormaPagamento f) {
            return new Item(
                f.name(),
                nomeDe(f),
                Arrays.stream(TipoLancamento.values()).filter(f::aceita).toList());
        }

        /**
         * O rotulo de tela.
         *
         * <p>Vive aqui e nao no JavaScript pela mesma razao do resto do
         * endpoint: duas listas de nomes que precisam concordar acabam
         * discordando. A tabela {@code forma_pagamento} guarda os mesmos textos
         * para quem consultar o banco direto.</p>
         *
         * <p><b>Publico pelo mesmo motivo.</b> O extrato em {@code .xlsx} da
         * T-10 precisa do rotulo na coluna "Pago com"
         * ({@code ExtratoCompletoMontador}), e uma copia da lista la seria a
         * terceira — a que envelhece na proxima forma nova.</p>
         */
        public static String nomeDe(FormaPagamento f) {
            return switch (f) {
                case DEBITO            -> "Débito";
                case PIX               -> "Pix";
                case CREDITO_EM_CONTA  -> "Crédito em conta";
                case BOLETO            -> "Boleto";
                case DEBITO_AUTOMATICO -> "Débito automático";
                case DINHEIRO          -> "Dinheiro";
                case TED               -> "TED";
                case DESCONTO_EM_FOLHA -> "Desconto em folha";
            };
        }
    }
}
