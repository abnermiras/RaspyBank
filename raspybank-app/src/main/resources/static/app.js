/* ==========================================================================
   RaspyBank — prototipo das telas T-01/T-02/T-03
   ==========================================================================
   JavaScript puro, sem framework e sem etapa de build: o framework da SPA
   (P-T6 do mapa de telas) ainda NAO foi escolhido, e este arquivo existe para
   ver o circuito funcionando hoje — nao para virar a fundacao do frontend.
   Quando a SPA de verdade nascer, isto aqui morre.

   O que ele demonstra de real:
     - o contrato de erro (I-12): 401 vago no login, 409 no e-mail duplicado,
       400 com o mapa "campos" marcando o campo problematico;
     - a sessao com ambiente (I-15): troca de ambiente e renovacao que
       PRESERVA o ambiente em uso;
     - o logout por dispositivo (I-14).
   ========================================================================== */

(function () {
    'use strict';

    // ----------------------------------------------------------------------
    // Guarda dos tokens
    // ----------------------------------------------------------------------
    // localStorage e a escolha simples, nao a segura: qualquer script injetado
    // na pagina consegue ler. Aceitavel num prototipo de sistema familiar e
    // auto-hospedado; a decisao definitiva (cookie httpOnly + SameSite, por
    // exemplo) fica para o desenho da SPA. Registrar isso em decisoes.md.
    var GUARDA = {
        acesso: 'raspybank.tokenAcesso',
        renovacao: 'raspybank.tokenRenovacao',
        ambiente: 'raspybank.ambienteId'
    };

    function guardarSessao(dados) {
        localStorage.setItem(GUARDA.acesso, dados.tokenAcesso);
        localStorage.setItem(GUARDA.renovacao, dados.tokenRenovacao);
        if (dados.ambienteId) {
            localStorage.setItem(GUARDA.ambiente, dados.ambienteId);
        }
    }

    function limparSessao() {
        Object.keys(GUARDA).forEach(function (k) { localStorage.removeItem(GUARDA[k]); });
    }

    function tokenAcesso()    { return localStorage.getItem(GUARDA.acesso); }
    function tokenRenovacao() { return localStorage.getItem(GUARDA.renovacao); }
    function ambienteAtual()  { return localStorage.getItem(GUARDA.ambiente); }

    // ----------------------------------------------------------------------
    // Conversa com a API
    // ----------------------------------------------------------------------

    function pedir(caminho, opcoes) {
        opcoes = opcoes || {};
        var cabecalhos = { 'Content-Type': 'application/json' };
        if (opcoes.autenticado) {
            cabecalhos['Authorization'] = 'Bearer ' + tokenAcesso();
        }
        return fetch(caminho, {
            method: opcoes.metodo || 'GET',
            headers: cabecalhos,
            body: opcoes.corpo ? JSON.stringify(opcoes.corpo) : undefined
        }).then(function (resposta) {
            return resposta.text().then(function (texto) {
                return {
                    ok: resposta.ok,
                    status: resposta.status,
                    corpo: texto ? JSON.parse(texto) : {}
                };
            });
        });
    }

    /**
     * Chamada autenticada com renovacao transparente.
     *
     * O 401 por token expirado nao deve devolver a pessoa ao login: tenta-se
     * uma renovacao e repete-se a chamada. E aqui que o contrato do I-15
     * aparece — o ambienteId vai junto, senao a sessao renovada voltaria ao
     * primeiro ambiente e o seletor "pularia" sozinho.
     */
    function pedirComRenovacao(caminho, opcoes) {
        opcoes = opcoes || {};
        opcoes.autenticado = true;

        return pedir(caminho, opcoes).then(function (resposta) {
            if (resposta.status !== 401) {
                return resposta;
            }
            return pedir('/api/auth/renovar', {
                metodo: 'POST',
                corpo: { tokenRenovacao: tokenRenovacao(), ambienteId: ambienteAtual() }
            }).then(function (renovacao) {
                if (!renovacao.ok) {
                    return resposta; // a sessao acabou de verdade
                }
                guardarSessao(renovacao.corpo);
                return pedir(caminho, opcoes);
            });
        });
    }

    // ----------------------------------------------------------------------
    // Utilidades de tela
    // ----------------------------------------------------------------------

    function elemento(id) { return document.getElementById(id); }

    function mostrarAviso(id, mensagem, sucesso) {
        var alvo = elemento(id);
        alvo.textContent = mensagem;
        alvo.classList.toggle('sucesso', !!sucesso);
        alvo.hidden = false;
    }

    function esconderAviso(id) { elemento(id).hidden = true; }

    function limparMarcasDeErro(formulario) {
        formulario.querySelectorAll('.com-erro').forEach(function (campo) {
            campo.classList.remove('com-erro');
        });
    }

    /**
     * Traduz o corpo de erro da API para a tela — o contrato do I-12:
     * `erro` sempre existe; `campos` (campo -> mensagem) so na validacao.
     */
    function aplicarErro(resposta, idAviso, formulario, prefixo) {
        var corpo = resposta.corpo || {};
        if (corpo.campos) {
            Object.keys(corpo.campos).forEach(function (campo) {
                var entrada = elemento(prefixo + '-' + campo);
                if (entrada) { entrada.classList.add('com-erro'); }
            });
            var primeiro = Object.keys(corpo.campos)[0];
            mostrarAviso(idAviso, corpo.campos[primeiro]);
            return;
        }
        mostrarAviso(idAviso, corpo.erro || 'Nao foi possivel completar a operacao.');
    }

    function trocarPara(tela) {
        elemento('tela-login').hidden = (tela !== 'login');
        elemento('tela-principal').hidden = (tela !== 'principal');
    }

    // ----------------------------------------------------------------------
    // T-01 — Login
    // ----------------------------------------------------------------------

    elemento('form-login').addEventListener('submit', function (evento) {
        evento.preventDefault();
        var formulario = evento.target;
        var botao = formulario.querySelector('button[type=submit]');

        esconderAviso('erro-login');
        limparMarcasDeErro(formulario);
        botao.disabled = true;

        pedir('/api/auth/login', {
            metodo: 'POST',
            corpo: {
                email: elemento('login-email').value.trim(),
                senha: elemento('login-senha').value
            }
        }).then(function (resposta) {
            if (!resposta.ok) {
                // O 401 e deliberadamente vago: e-mail inexistente e senha
                // errada respondem igual, para o login nao virar verificador
                // de cadastro (B-A8/B-T2). A tela nao tenta adivinhar mais.
                aplicarErro(resposta, 'erro-login', formulario, 'login');
                return;
            }
            guardarSessao(resposta.corpo);
            elemento('login-senha').value = '';
            return abrirTelaPrincipal();
        }).catch(function () {
            mostrarAviso('erro-login', 'Servidor indisponivel. Tente de novo.');
        }).finally(function () {
            botao.disabled = false;
        });
    });

    // ----------------------------------------------------------------------
    // T-02 — Cadastro
    // ----------------------------------------------------------------------

    elemento('form-cadastro').addEventListener('submit', function (evento) {
        evento.preventDefault();
        var formulario = evento.target;
        var botao = formulario.querySelector('button[type=submit]');

        esconderAviso('erro-cadastro');
        limparMarcasDeErro(formulario);
        botao.disabled = true;

        var email = elemento('cadastro-email').value.trim();

        pedir('/api/auth/cadastro', {
            metodo: 'POST',
            corpo: {
                nome: elemento('cadastro-nome').value.trim(),
                email: email,
                senha: elemento('cadastro-senha').value
            }
        }).then(function (resposta) {
            if (!resposta.ok) {
                // 409 = e-mail ja cadastrado; 400 = validacao, com "campos".
                aplicarErro(resposta, 'erro-cadastro', formulario, 'cadastro');
                return;
            }
            // A API exige login explicito apos o cadastro, de proposito.
            alternarFormulario('login');
            elemento('login-email').value = email;
            elemento('login-senha').focus();
            mostrarAviso('erro-login', 'Conta criada. Faca login para continuar.', true);
        }).catch(function () {
            mostrarAviso('erro-cadastro', 'Servidor indisponivel. Tente de novo.');
        }).finally(function () {
            botao.disabled = false;
        });
    });

    function alternarFormulario(qual) {
        var login = (qual === 'login');
        elemento('form-login').hidden = !login;
        elemento('form-cadastro').hidden = login;
        elemento('texto-alternar').textContent = login ? 'Ainda não tem conta?' : 'Já tem conta?';
        elemento('botao-alternar').textContent = login ? 'Cadastre-se' : 'Entrar';
        esconderAviso('erro-login');
        esconderAviso('erro-cadastro');
    }

    elemento('botao-alternar').addEventListener('click', function () {
        alternarFormulario(elemento('form-login').hidden ? 'login' : 'cadastro');
    });

    // ----------------------------------------------------------------------
    // T-03 — Casca autenticada
    // ----------------------------------------------------------------------

    function abrirTelaPrincipal() {
        return pedirComRenovacao('/api/perfil').then(function (resposta) {
            if (!resposta.ok) {
                limparSessao();
                trocarPara('login');
                return;
            }
            desenharPerfil(resposta.corpo);
            trocarPara('principal');
        });
    }

    function desenharPerfil(perfil) {
        var ambientes = perfil.ambientes || [];

        // O ambiente do token manda; o guardado e so uma lembranca do cliente.
        if (perfil.ambienteAtual) {
            localStorage.setItem(GUARDA.ambiente, perfil.ambienteAtual);
        }

        var seletor = elemento('ambiente');
        seletor.innerHTML = '';
        ambientes.forEach(function (ambiente) {
            var opcao = document.createElement('option');
            opcao.value = ambiente.id;
            opcao.textContent = ambiente.nome;
            opcao.selected = (ambiente.id === perfil.ambienteAtual);
            seletor.appendChild(opcao);
        });
        // Com um ambiente so, o seletor nao tem o que oferecer.
        seletor.disabled = ambientes.length < 2;

        var nomeDoAtual = (ambientes.find(function (a) {
            return a.id === perfil.ambienteAtual;
        }) || {}).nome;

        elemento('saudacao').textContent = nomeDoAtual ? 'Você está em ' + nomeDoAtual : '';
        elemento('dado-usuario').textContent = perfil.usuarioId || '—';
        elemento('dado-ambiente').textContent = perfil.ambienteAtual || '—';
        elemento('dado-canal').textContent = perfil.canal || '—';
        elemento('dado-quantidade').textContent = ambientes.length;
    }

    // Troca de ambiente (I-15): novo token de acesso com o outro recorte.
    elemento('ambiente').addEventListener('change', function (evento) {
        var escolhido = evento.target.value;
        pedirComRenovacao('/api/sessao/ambiente', {
            metodo: 'POST',
            corpo: { ambienteId: escolhido }
        }).then(function (resposta) {
            if (!resposta.ok) {
                return abrirTelaPrincipal(); // volta ao estado real do servidor
            }
            localStorage.setItem(GUARDA.acesso, resposta.corpo.tokenAcesso);
            localStorage.setItem(GUARDA.ambiente, resposta.corpo.ambienteId);
            return abrirTelaPrincipal();
        });
    });

    // Sair (I-14): encerra SO este dispositivo. As outras sessoes seguem.
    elemento('botao-sair').addEventListener('click', function () {
        pedirComRenovacao('/api/auth/logout', { metodo: 'POST' })
            .catch(function () { /* sem servidor, a saida local basta */ })
            .finally(function () {
                limparSessao();
                elemento('form-login').reset();
                alternarFormulario('login');
                trocarPara('login');
            });
    });

    // ----------------------------------------------------------------------
    // Entrada
    // ----------------------------------------------------------------------

    if (tokenAcesso()) {
        abrirTelaPrincipal();   // sessao guardada: tenta entrar direto
    } else {
        trocarPara('login');
    }
})();
