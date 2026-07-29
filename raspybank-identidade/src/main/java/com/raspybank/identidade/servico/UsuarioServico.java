package com.raspybank.identidade.servico;

import com.raspybank.identidade.dominio.Usuario;
import com.raspybank.identidade.repositorio.UsuarioRepositorio;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Leitura e alteracao dos dados cadastrais de quem esta logado.
 *
 * <h3>Por que isto e um servico e nao o controlador chamando o repositorio</h3>
 *
 * <p>Nao e cerimonia de camada: sem ele, <b>nao funciona</b>. O aspecto
 * {@code ConfiguradorSessaoRls} injeta {@code raspybank.usuario_id} na sessao do
 * banco envolvendo metodos anotados com {@code @Transactional}. Um repositorio
 * chamado direto do controlador nao passa por ele, a variavel de sessao fica
 * vazia, {@code app_usuario_id()} devolve nulo — e {@code pol_usuario_proprio},
 * que compara {@code id = app_usuario_id()}, nao casa com linha nenhuma.</p>
 *
 * <p>O sintoma e cruel: {@code findById} devolve vazio para um usuario que
 * existe, e a tela responde 404 sem nada no log. Custou uma rodada de teste
 * vermelho para aparecer, e esta escrito aqui para nao custar de novo.</p>
 */
@Service
public class UsuarioServico {

    private final UsuarioRepositorio usuarios;

    public UsuarioServico(UsuarioRepositorio usuarios) {
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public Usuario buscar(UUID usuarioId) {
        return usuarios.findById(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Usuario nao encontrado"));
    }

    /**
     * Troca o nome de exibicao. So o nome.
     *
     * <p>Nao existe par disto para o e-mail, e a ausencia e decisao: ele e o
     * login. Enquanto nao houver recuperacao de senha, um e-mail digitado errado
     * trancaria a pessoa para fora da propria conta, sem volta.</p>
     */
    @Transactional
    public Usuario renomear(UUID usuarioId, String nome) {
        Usuario u = buscar(usuarioId);
        u.renomear(nome);
        return u;
    }
}
