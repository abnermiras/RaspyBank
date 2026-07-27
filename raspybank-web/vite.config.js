import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// =============================================================================
// RaspyBank — configuração do Vite
// =============================================================================
// Duas decisões moram aqui, e as duas têm motivo.
//
// 1. O build escreve DENTRO de raspybank-app/src/main/resources/static/, que
//    é de onde o Spring serve arquivo estático. Assim `make app` entrega a
//    SPA em :8080 sem etapa de cópia — e sem uma segunda cópia dos mesmos
//    arquivos em outro lugar, que é como versões diferentes passam a conviver.
//
//    A pasta é ESVAZIADA a cada build (emptyOutDir). É o que garante que um
//    asset com hash antigo não fique para trás acumulando; o conteúdo dela é
//    saída de build, não fonte, e por isso está no .gitignore.
//
// 2. O proxy existe para que o navegador enxergue front e back na MESMA
//    origem durante o desenvolvimento. Sem ele o cookie de sessão viraria
//    cross-site e o fluxo de login não funcionaria em dev — um problema que
//    não existe em produção, onde o Spring serve os dois.
// =============================================================================
export default defineConfig({
  plugins: [react()],
  server: {
    // O Vite nasce escutando só em 127.0.0.1, e aí o navegador de fora da VM
    // não o alcança — foi exatamente o que aconteceu em 27/07/2026, com o
    // :8080 do Spring abrindo e o :5173 não. "host: true" o coloca nas mesmas
    // interfaces que o Spring já usa.
    //
    // Isto vale para a REDE LOCAL de casa e nada além: servidor de
    // desenvolvimento não vai para a internet. Em produção quem serve o
    // frontend é o Spring, e este bloco inteiro deixa de existir.
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: '../raspybank-app/src/main/resources/static',
    emptyOutDir: true,
    sourcemap: true,
  },
})
