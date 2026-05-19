# LifeForge - Backend

Backend da plataforma LifeForge (TCC). Implementa a API REST que o app
Android consome e o motor de simulação de Monte Carlo (a partir da Sprint 2).

## Stack

- **Kotlin 2.0.21** + **Ktor 3.0** (servidor)
- **Exposed 0.55** + **HikariCP** (ORM e pool de conexões)
- **PostgreSQL 16** (banco de dados)
- **JWT (auth0/java-jwt)** + **BCrypt** (autenticação)
- **Docker Compose** (ambiente local)
- **Kotest** (testes)

## Arquitetura (Clean Architecture)

```
src/main/kotlin/com/lifeforge/
├── Application.kt              # entry point
├── config/
│   ├── AppContainer.kt         # DI manual (sem Koin)
│   └── DatabaseFactory.kt      # HikariCP + Exposed init
├── plugins/                    # plugins do Ktor (Serialization, Security, HTTP, Routing)
├── security/                   # JwtService, PasswordHasher
├── data/
│   ├── tables/                 # tabelas Exposed (mapeamento DB <-> Kotlin)
│   └── repository/             # implementações dos repositórios
├── domain/
│   ├── model/                  # entidades de domínio puras
│   └── repository/             # interfaces de repositório
├── dto/                        # DTOs serializáveis (request/response)
└── routes/                     # rotas HTTP por entidade
```

A regra de dependência respeita Clean Architecture: `routes` depende de
`domain.repository` (interface), nunca de `data.repository` (impl). A camada
`domain` não conhece Ktor, Exposed ou qualquer framework.

## Como rodar

### 1. Pré-requisitos

- Docker e Docker Compose
- (Opcional, para desenvolvimento local sem Docker) JDK 17

### 2. Subir tudo via Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

A API estará em `http://localhost:8080` e o PostgreSQL em `localhost:5432`.

### 3. Rodar só o banco e o backend pela IDE

```bash
docker compose up -d postgres
./gradlew run
```

## Endpoints

| Método | Endpoint                              | Auth | Descrição                    |
| ------ | ------------------------------------- | ---- | ---------------------------- |
| GET    | `/health`                             | -    | healthcheck                  |
| POST   | `/api/v1/auth/register`               | -    | cria conta + retorna JWT     |
| POST   | `/api/v1/auth/login`                  | -    | login + retorna JWT          |
| GET    | `/api/v1/users/me`                    | JWT  | dados do usuário atual       |
| GET    | `/api/v1/goals`                       | JWT  | lista metas                  |
| POST   | `/api/v1/goals`                       | JWT  | cria meta                    |
| GET    | `/api/v1/goals/{id}`                  | JWT  | detalhe da meta              |
| PUT    | `/api/v1/goals/{id}`                  | JWT  | atualiza meta                |
| DELETE | `/api/v1/goals/{id}`                  | JWT  | remove meta                  |
| GET    | `/api/v1/incomes`                     | JWT  | lista rendas                 |
| POST   | `/api/v1/incomes`                     | JWT  | registra renda               |
| GET    | `/api/v1/incomes/{id}`                | JWT  | detalhe                      |
| DELETE | `/api/v1/incomes/{id}`                | JWT  | remove                       |
| GET    | `/api/v1/expenses`                    | JWT  | lista despesas               |
| POST   | `/api/v1/expenses`                    | JWT  | registra despesa             |
| GET    | `/api/v1/expenses/{id}`               | JWT  | detalhe                      |
| DELETE | `/api/v1/expenses/{id}`               | JWT  | remove                       |
| GET    | `/api/v1/assets`                      | JWT  | lista ativos                 |
| POST   | `/api/v1/assets`                      | JWT  | cria ativo                   |
| GET    | `/api/v1/assets/{id}`                 | JWT  | detalhe                      |
| PUT    | `/api/v1/assets/{id}`                 | JWT  | atualiza ativo               |
| DELETE | `/api/v1/assets/{id}`                 | JWT  | remove                       |
| POST   | `/api/v1/simulation/run`              | JWT  | executa Monte Carlo (10k+)   |
| GET    | `/api/v1/simulation/{id}`             | JWT  | resultado completo           |
| GET    | `/api/v1/simulation/by-goal/{goalId}` | JWT  | lista simulações da meta     |
| DELETE | `/api/v1/simulation/{id}`             | JWT  |  |

### 

### Exemplo: registrar e criar uma meta

```bash
# 1. Registro
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "gabriel@example.com",
    "name": "Gabriel",
    "password": "senha-segura-123",
    "riskProfile": "MODERATE"
  }' | jq -r .token)

# 2. Criar meta
curl -X POST http://localhost:8080/api/v1/goals \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Aposentadoria",
    "category": "RETIREMENT",
    "targetAmount": "1000000.00",
    "targetDate": "2050-01-01T00:00:00Z",
    "priority": 1
  }'
```

## Testes

```bash
./gradlew test
```

Atualmente cobre o `PasswordHasher`. Testes de integração HTTP (com H2 em
memória) entram na Sprint 2, junto com o motor de Monte Carlo.

## Variáveis de ambiente

Veja `.env.example`. As principais:

- `DATABASE_URL` — string JDBC do PostgreSQL
- `DATABASE_USER` / `DATABASE_PASSWORD`
- `JWT_SECRET` — **obrigatoriamente** trocada em produção. Gere com
  `openssl rand -base64 64`.

## Próximos passos (Sprint 2)

1. Engine de Monte Carlo (`engine/montecarlo/`) — núcleo técnico do TCC
2. Distribuições de probabilidade (Normal, LogNormal, Bernoulli, Poisson)
3. Endpoint `POST /api/v1/simulation/run`
4. Testes unitários do motor estatístico (com seed para reprodutibilidade)
