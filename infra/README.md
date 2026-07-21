# Infraestrutura — RaspyBank

Toda a infraestrutura do projeto é código versionado. O teste de sanidade:
**se a máquina for destruída, um `git clone` e um comando devem reconstruir tudo.**
Nada é configurado à mão.

---

## Primeira execução

```bash
cd raspybank
cp .env.example .env
nano .env                 # defina senhas reais
make up
```

O `make up` sobe o Postgres e aguarda até que ele aceite conexões.

Validação:

```bash
make psql
```

Dentro do terminal SQL:

```sql
\du          -- deve listar raspybank_owner e raspybank_app
\dx          -- deve listar pg_trgm e unaccent
\q
```

---

## Comandos

| Comando | O que faz |
|---|---|
| `make` | Lista todos os alvos |
| `make up` | Sobe o ambiente |
| `make down` | Para, **preservando** os dados |
| `make logs` | Acompanha os logs |
| `make psql` | Terminal SQL como proprietário |
| `make psql-app` | Terminal SQL como aplicação (para testar RLS) |
| `make tools` | Sobe o Adminer em `localhost:8081` |
| `make db-dump` | Gera dump compactado em `backups/` |
| `make db-reset` | **Destrói** o banco e recria do zero |

---

## Como os arquivos se combinam

```
compose.yaml            o que é verdade em todo ambiente
  + compose.local.yaml  desenvolvimento: porta exposta, log verboso, Adminer
  + compose.pi.yaml     produção: sem porta, memória limitada, dados no SSD
```

O Compose mescla na ordem informada e o último vence. Correção feita no arquivo
base vale para todos os ambientes automaticamente — é justamente o que se perde
ao manter arquivos independentes por ambiente.

**Perfis são outra coisa.** Servem para ligar e desligar serviços opcionais
*dentro* de um ambiente, como o Adminer. Não para separar ambientes.

---

## Os dois usuários de banco

| Usuário | Papel | Privilégios |
|---|---|---|
| `raspybank_owner` | Proprietário. Executa as migrações do Flyway | Estrutura e dados |
| `raspybank_app` | Aplicação em execução | Apenas dados |

Isso não é preciosismo. **Row Level Security é ignorado para o dono da tabela
e para superusuários.** Se a aplicação conectasse como proprietário, o
isolamento entre tenants — decisão #04 — estaria desligado silenciosamente,
e o vazamento só apareceria quando já houvesse dados de mais de um cliente.

Para verificar em qualquer momento:

```sql
SELECT current_user;      -- deve retornar raspybank_app na aplicação
```

---

## Diferenças entre ambientes

| Item | Desenvolvimento | Raspberry Pi |
|---|---|---|
| Porta do Postgres | `127.0.0.1:5432` | não publicada |
| `log_statement` | `all` | desligado |
| Limite de memória | sem limite | 1 GB |
| `max_connections` | 100 (padrão) | 30 |
| Dados | volume Docker padrão | volume apontado para SSD |
| Adminer | disponível via perfil | ausente |

---

## Avisos para o Raspberry Pi

**Nunca coloque o banco em cartão SD.** Banco grava constantemente e cartão SD
tem ciclos de escrita limitados: ele falha sem aviso, levando os dados. O
`compose.pi.yaml` aponta o volume para `/mnt/ssd/raspybank/postgres`.

Antes do primeiro `up` no Pi:

```bash
sudo mkdir -p /mnt/ssd/raspybank/postgres
```

Com o SSD montado em `/mnt/ssd` via `/etc/fstab`.

**Volume não é backup.** Se o volume corromper, o backup corrompe junto.
O `pg_dump` agendado entra na Fase 9.

---

## Problemas conhecidos

**`make` reclama de "missing separator"**
Makefile exige TAB de indentação. Se o editor converteu para espaços, restaure.

**Porta 5432 já em uso**
Altere `POSTGRES_PORT` no `.env`. Só afeta a porta publicada; dentro da rede
Docker o serviço continua em 5432.

**Alterei um script de `postgres/init/` e nada mudou**
Correto. Esses scripts rodam uma única vez, com o volume vazio. Use `make db-reset`.

**O container sobe e morre em seguida**
Veja `make logs`. A causa mais comum é `.env` ausente ou variável não preenchida.

**Erro relacionado ao diretório de dados na primeira subida**
A imagem do Postgres 18 alterou detalhes do layout interno em relação às
versões anteriores. Definimos `PGDATA` explicitamente para uma subpasta do
ponto de montagem, que é o padrão recomendado e estável. Se ainda assim houver
falha, rode `make db-reset` — na primeira subida não há dado a perder.

---

## Ainda não existe aqui

| Item | Fase |
|---|---|
| `Dockerfile` do backend | 4 |
| Migrações Flyway | 4 |
| Nginx como proxy reverso | 6 |
| GitHub Actions com `buildx` e GHCR | 8 |
| Backup agendado | 9 |
