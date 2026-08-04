// =============================================================================
// Dinheiro digitado em português chega à API no formato que ela aceita
// =============================================================================
// Este teste existe por um defeito concreto, encontrado no primeiro dia de uso
// real do sistema (03/08/2026) — não por completude.
//
// -----------------------------------------------------------------------------
// O DEFEITO QUE ELE GUARDA
// -----------------------------------------------------------------------------
// Os cinco formulários de dinheiro mandavam `d.get('valor').trim()` cru para a
// API. O contrato dela é ponto decimal, sem separador de milhar:
//
//     @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?")
//
// Quem digitava "1450,22" — que é como se escreve dinheiro em português — levava
// 400 com a mensagem "valor deve ser positivo, com ate duas casas", que descreve
// a REGRA e não o que de fato falhou. O relato de quem usou foi "diz que não
// aceita número negativo", e não havia negativo nenhum envolvido.
//
// O agravante: o placeholder do formulário de lançamento era `0,00`, com
// vírgula. A tela instruía o usuário a fazer exatamente o que o servidor
// recusava. O de cartão dizia `10000.00`, com ponto — nem entre si concordavam.
//
// -----------------------------------------------------------------------------
// A DECISÃO QUE ESTE TESTE FIXA
// -----------------------------------------------------------------------------
// "1.450" é lido como MILHAR, não como um e quarenta e cinco. É ambíguo entre
// locales e a escolha é deliberada: a interface é em português. Quem quiser um
// e quarenta e cinco escreve "1,45", que não é ambíguo em lugar nenhum.
//
// Se algum dia o sistema for internacionalizado, este é o teste que vai falhar
// primeiro — e é para ele falhar mesmo, porque a decisão terá mudado.
//
// -----------------------------------------------------------------------------
// O QUE NÃO DÁ PARA ADIVINHAR PASSA DIRETO
// -----------------------------------------------------------------------------
// "1,4,5" e "1.4500" voltam como vieram, para o servidor recusar com a mensagem
// dele. Chutar um número aqui seria pior que o erro original: gravaria em silêncio
// um valor que o usuário não quis.
//
// Rodar:  make web-test
// =============================================================================

import assert from 'node:assert/strict'
import { paraDecimal, paraCampo } from '../src/util/formato.js'

let passaram = 0

function confere(entrada, esperado, porque) {
  const obtido = paraDecimal(entrada)
  assert.equal(
    obtido, esperado,
    `paraDecimal(${JSON.stringify(entrada)}) devolveu ${JSON.stringify(obtido)}, ` +
    `esperado ${JSON.stringify(esperado)} — ${porque}`,
  )
  passaram++
}

// -----------------------------------------------------------------------------
// O caso que originou tudo
// -----------------------------------------------------------------------------
confere('1450,22', '1450.22', 'vírgula é o separador decimal em português')
confere('1.450,22', '1450.22', 'com vírgula presente, todo ponto é milhar')
confere('1.234.567,89', '1234567.89', 'vários milhares')

// -----------------------------------------------------------------------------
// Não quebrar o que já funcionava
// -----------------------------------------------------------------------------
confere('1450.22', '1450.22', 'o formato da API continua aceito')
confere('380.00', '380.00', 'idem, com centavos zerados')
confere('10.5', '10.5', 'uma casa decimal só')
confere('1450', '1450', 'inteiro puro')
confere('0', '0', 'zero')

// -----------------------------------------------------------------------------
// A decisão do ponto com três dígitos
// -----------------------------------------------------------------------------
confere('1.450', '1450', 'três dígitos após ponto único = milhar (decisão pt-BR)')
confere('1.000.000', '1000000', 'só pode ser milhar')
confere('10.00', '10.00', 'duas casas = decimal, não milhar')

// -----------------------------------------------------------------------------
// Sujeira que vem de copiar e colar
// -----------------------------------------------------------------------------
confere('R$ 1.450,22', '1450.22', 'símbolo da moeda')
confere('  1450,22  ', '1450.22', 'espaço nas pontas')
confere('R$ 1.450,22', '1450.22', 'espaço não separável, típico de planilha')

// -----------------------------------------------------------------------------
// Sinal — só o saldoInicial da conta aceita, mas a conversão é a mesma
// -----------------------------------------------------------------------------
confere('-500,00', '-500.00', 'conta que começa devendo')
confere('-1.500,50', '-1500.50', 'negativo com milhar')
confere('+500,00', '500.00', 'o mais explícito é redundante e some')

// -----------------------------------------------------------------------------
// Arremates que evitariam um 400 por detalhe
// -----------------------------------------------------------------------------
confere(',50', '0.50', 'sem a parte inteira, o regex do servidor recusaria')
confere('1450,', '1450', 'vírgula sobrando no fim')

// -----------------------------------------------------------------------------
// O que não dá para adivinhar volta como veio
// -----------------------------------------------------------------------------
confere('1,4,5', '1,4,5', 'duas vírgulas não têm leitura possível')
confere('1.4500', '1.4500', 'quatro dígitos após o ponto não é milhar nem decimal')
confere('1.45.000', '1.45.000', 'grupo de milhar malformado')
confere('abc', 'abc', 'texto não é número')
confere('12a', '12a', 'lixo no meio')

// -----------------------------------------------------------------------------
// Vazio e ausente — o campo saldoInicial é opcional
// -----------------------------------------------------------------------------
confere('', '', 'campo vazio continua vazio')
confere('   ', '', 'só espaço é vazio')
confere(null, '', 'ausente não vira "null"')
confere(undefined, '', 'idem')

// -----------------------------------------------------------------------------
// O espelho: da API para o campo de edição
// -----------------------------------------------------------------------------
assert.equal(paraCampo('380.00'), '380,00', 'edição mostra em português')
assert.equal(paraCampo('1450'), '1450', 'sem casas, nada a trocar')
assert.equal(paraCampo(''), '', 'vazio continua vazio')
assert.equal(paraCampo(null), '', 'ausente não vira "null" no campo')
passaram += 4

// -----------------------------------------------------------------------------
// Ida e volta: o que sai do campo e volta para ele tem de sobreviver
// -----------------------------------------------------------------------------
for (const daApi of ['380.00', '1450.22', '0.50', '1450']) {
  assert.equal(
    paraDecimal(paraCampo(daApi)), daApi,
    `ida e volta perdeu informação em ${daApi}`,
  )
  passaram++
}

console.log(`ok — dinheiro-digitado: ${passaram} verificações`)
