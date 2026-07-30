package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.ContaConvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Convites pendentes de conta compartilhada (B-D90).
 *
 * <p>Nao ha metodo de "aceitar" aqui, e a ausencia tem motivo: aceitar grava um
 * vinculo entre uma conta que NAO e de quem aceita e um ambiente que e, e o
 * {@code WITH CHECK} de {@code pol_ca_vincular} exige conta propria dos dois
 * lados — de proposito, para impedir captura de conta alheia por quem tenha o
 * UUID (B-D18). O que separa aceite de captura nao esta na linha inserida; esta
 * no convite que existe antes dela, e por isso o aceite passa pela funcao
 * {@code app_aceitar_convite_de_conta}, que le o convite.</p>
 *
 * <p>Recusar e cancelar, ao contrario, sao {@code delete()} normais:
 * {@code pol_convite_apagar} ja diz a regra — o convidado recusa o que recebeu,
 * o dono cancela o que enviou.</p>
 */
public interface ContaConviteRepositorio extends JpaRepository<ContaConvite, UUID> {

    /** Os convites que esperam a resposta de uma pessoa. */
    List<ContaConvite> findByConvidadoIdOrderByCriadoEm(UUID convidadoId);

    /** Os pendentes de uma conta — a parte "PENDENTE" da lista de §2d. */
    List<ContaConvite> findByContaIdOrderByCriadoEm(UUID contaId);

    /** Uma consulta para a tela inteira, no lugar de uma por conta. */
    List<ContaConvite> findByContaIdIn(Collection<UUID> contaIds);

    Optional<ContaConvite> findByContaIdAndConvidadoId(UUID contaId, UUID convidadoId);

    /** O convite de um PLASTICO especifico (B-D106), do ponto de vista do dono. */
    Optional<ContaConvite> findByCartaoEmitidoIdAndConvidadoId(UUID cartaoEmitidoId,
                                                               UUID convidadoId);
}
