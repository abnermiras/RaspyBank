package com.raspybank.lancamento.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A ancora do modelo: todo lancamento aponta para exatamente uma conta (F4).
 *
 * <h3>Repare no que esta classe NAO tem</h3>
 *
 * <p><b>Nao tem {@code ambienteId}.</b> A conta nao pertence a um ambiente —
 * a visibilidade e N:N via {@link ContaAmbiente}, porque o caso real (conta
 * conjunta do casal, visivel no ambiente da casa E no pessoal de cada um)
 * nao se expressa por igualdade de campo. Foi a revisao R7 que trouxe isso.</p>
 *
 * <p><b>Nao tem saldo.</b> Principio P1, sem excecao: o saldo e a soma dos
 * lancamentos, calculada na hora. Nao ha o que reconciliar quando o dado nao
 * existe em dois lugares (R1). Saldo de abertura e um lancamento na
 * categoria sistemica {@code AJUSTE} (A13), nao um campo magico.</p>
 *
 * <h3>Esta entidade nao se cria por {@code save()}</h3>
 *
 * <p>Criar conta e gravar duas linhas — a conta e o vinculo — e a primeira
 * so se torna visivel depois da segunda. A politica de RLS nao pode enxergar
 * uma linha que ainda nao tem vinculo, entao um {@code save()} direto
 * gravaria uma conta que nem quem a criou consegue ler. A saida e a porta
 * estreita {@code app_criar_conta(...)}, que faz as duas na mesma transacao
 * e confere o vinculo do usuario com o ambiente (B-D19).</p>
 *
 * <p>Depois de criada, a conta se le e se altera normalmente pelo
 * repositorio: a politica ja a enxerga.</p>
 */
@Entity
@Table(name = "conta")
public class Conta {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "natureza", nullable = false)
    private NaturezaConta natureza;

    /**
     * Conta nao se exclui, se encerra (F7). Encerrada, some dos seletores e
     * mantem o historico inteiro — apagar uma conta apagaria o passado dela.
     */
    @Column(name = "encerrada_em")
    private OffsetDateTime encerradaEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    protected Conta() {
    }

    public void renomear(String novoNome) {
        this.nome = novoNome;
    }

    public void encerrar(OffsetDateTime quando) {
        this.encerradaEm = quando;
    }

    public void reabrir() {
        this.encerradaEm = null;
    }

    public boolean estaEncerrada() {
        return encerradaEm != null;
    }

    public UUID getId()                    { return id; }
    public String getNome()                { return nome; }
    public NaturezaConta getNatureza()     { return natureza; }
    public OffsetDateTime getEncerradaEm() { return encerradaEm; }
    public OffsetDateTime getCriadoEm()    { return criadoEm; }
}
