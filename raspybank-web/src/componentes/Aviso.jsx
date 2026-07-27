/**
 * Mensagem de erro ou de sucesso. `role="alert"` faz o leitor de tela anunciar
 * o texto quando ele aparece — sem isso, quem não está olhando para o ponto
 * exato da tela simplesmente não fica sabendo que a operação falhou.
 */
export default function Aviso({ aviso }) {
  if (!aviso) return null
  return (
    <p className={aviso.sucesso ? 'aviso sucesso' : 'aviso'} role="alert">
      {aviso.texto}
    </p>
  )
}
