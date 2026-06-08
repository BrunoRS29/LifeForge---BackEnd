# LifeForge — Backend

Backend da plataforma **LifeForge** (TCC — Engenharia de Computação): planejamento
de vida com **modelagem probabilística**, **Simulação de Monte Carlo**, **otimização**
e **IA preditiva**. Expõe a API REST que o app Android consome, o motor de simulação
(núcleo técnico do TCC) e orquestra o microsserviço de Machine Learning.

Este repositório contém **dois serviços** (orquestrados via Docker Compose junto ao
PostgreSQL):

- **`backend`** — API Ktor + motor de Monte Carlo + otimização (Kotlin/JVM)
- **`ml-service`** — microsserviço de IA preditiva (Python/FastAPI) — ver `ml-service/README.md`

## Stack

- **Kotlin 2.x** + **Ktor 3.x** (servidor assíncrono)
- **Exposed** + **HikariCP** (ORM e pool de conexões)
- **PostgreSQL 16** (persistência)
- **JWT (auth0/java-jwt)** + **BCrypt** (autenticação)
- **kotlinx.serialization** (JSON, incl. colunas `jsonb`)
- **Python/FastAPI + scikit-learn + statsmodels** (microsserviço de ML)
- **Docker Compose** (ambiente local) · **Kotest** + **JaCoCo** (testes e cobertura)

## Arquitetura (Clean Architecture)

```
src/main/kotlin/com/lifeforge/
├── Application.kt              # entry point
├── config/                    # AppContainer (DI manual), DatabaseFactory
├── plugins/                   # Serialization, Security, HTTP, Routing
├── security/                  # JwtService, PasswordHasher
├── data/{tables,repository}/  # Exposed tables + implementações de repositório
├── domain/{model,repository}/ # entidades puras + interfaces (sem framework)
├── dto/                       # DTOs serializáveis (request/response)
├── engine/
│   ├── montecarlo/            # MonteCarloEngine, parâmetros, resultado, fan chart
│   ├── optimization/          # aporte ideal, prazo, rebalanceamento (busca binária)
│   └── statistics/            # geradores aleatórios, estatística, ReferenceData
├── ml/                        # cliente HTTP + serviço de predição (consome ml-service)
└── routes/                    # rotas HTTP por agregado
```

A regra de dependência respeita Clean Architecture: `routes` dependem de
`domain.repository` (interface), nunca da implementação; `domain` não conhece
Ktor, Exposed ou qualquer framework.

## Como rodar

```bash
cp .env.example .env
docker compose up --build
```

Sobe `postgres` (5432), `ml-service` (interno) e `backend` em
`http://localhost:8080`. Healthcheck: `GET /health` (reporta também o status do ML).
Documentação interativa: `GET /docs` (Swagger UI) e `GET /openapi.yaml`.

Para desenvolver o backend pela IDE com o banco no Docker:

```bash
docker compose up -d postgres ml-service
./gradlew run
```

## Endpoints

Base: `/api/v1`. Salvo indicação, exigem **JWT** (`Authorization: Bearer <token>`).

| Método | Endpoint | Auth | Descrição |
| --- | --- | --- | --- |
| GET | `/health` | — | healthcheck (inclui status do ML) |
| GET | `/docs`, `/openapi.yaml` | — | documentação OpenAPI/Swagger |
| GET | `/api/v1/reference-data` | — | base de estatísticas de referência (calibração) |
| POST | `/api/v1/auth/register` | — | cria conta + retorna JWT |
| POST | `/api/v1/auth/login` | — | login + retorna JWT |
| GET | `/api/v1/users/me` | JWT | usuário atual |
| GET·PUT | `/api/v1/profile` | JWT | perfil estendido (parâmetros opcionais p/ projeções) |
| GET·POST | `/api/v1/goals` | JWT | lista / cria metas |
| GET·PUT·DELETE | `/api/v1/goals/{id}` | JWT | detalhe / atualiza / remove |
| GET·POST | `/api/v1/incomes` | JWT | lista / cria rendas |
| GET·PUT·DELETE | `/api/v1/incomes/{id}` | JWT | detalhe / atualiza / remove |
| `/api/v1/incomes/schedules` | JWT | rendas recorrentes (escopo `FUTURE_ONLY`/`ALL`) |
| GET·POST | `/api/v1/expenses` | JWT | lista / cria despesas |
| GET·PUT·DELETE | `/api/v1/expenses/{id}` | JWT | detalhe / atualiza / remove |
| `/api/v1/expenses/schedules` | JWT | despesas recorrentes (escopo `FUTURE_ONLY`/`ALL`) |
| GET·POST | `/api/v1/assets` | JWT | lista / cria ativos |
| GET·PUT·DELETE | `/api/v1/assets/{id}` | JWT | detalhe / atualiza / remove |
| POST | `/api/v1/finance/import` | JWT | importação em lote (extratos/faturas) |
| POST | `/api/v1/simulation/run` | JWT | Monte Carlo (≥ 10.000 simulações) |
| POST | `/api/v1/simulation/run-calibrated` | JWT | Monte Carlo calibrado por IA + perfil |
| GET | `/api/v1/simulation/{id}` | JWT | resultado completo |
| GET | `/api/v1/simulation/by-goal/{goalId}` | JWT | simulações de uma meta |
| DELETE | `/api/v1/simulation/{id}` | JWT | remove |
| POST | `/api/v1/optimize/contribution` | JWT | aporte ideal (busca binária + Monte Carlo) |
| POST | `/api/v1/optimize/horizon` | JWT | prazo mínimo para o aporte atual |
| POST | `/api/v1/optimize/rebalance` | JWT | alocação sugerida por perfil de risco |
| POST | `/api/v1/predictions/{income,expenses,wealth}` | JWT | predições de IA (regressão / RF / ARIMA) |

## Motor de Simulação de Monte Carlo

`engine/montecarlo/MonteCarloEngine` executa ≥ 10.000 trajetórias estocásticas
(Proposta, Seção 6.2), variando mês a mês:

- **retorno** da carteira ~ Normal(média, desvio);
- **evento de desemprego** ~ Bernoulli(prob. mensal), com duração configurável;
- **despesa inesperada** ~ nº de eventos/mês Poisson(λ/12) × magnitude Exponencial(média);
- deflação por **inflação** para resultado em valor real.

Saídas: probabilidade de sucesso, média/mediana, percentis (P5…P95), pior/melhor
caso, histograma e **bandas de trajetória** (P10–P90) para o *fan chart*. É
determinístico por *seed* (reprodutível).

## Base de estatísticas de referência (calibração)

`engine/statistics/ReferenceData` centraliza as premissas de longo prazo (inflação,
retorno/volatilidade por perfil de risco, risco de desemprego por vínculo,
depreciação de veículos, valorização imobiliária, regra dos 4%, custo de filhos por
faixa etária), derivadas de dados públicos brasileiros e da literatura. Expostas em
`GET /api/v1/reference-data` e usadas para **democratizar** as simulações: o usuário
não precisa estimar esses parâmetros — valores informados no perfil **sobrepõem** os
defaults. Detalhes e fontes em `docs/estatisticas-referencia.md`.

## IA preditiva

`ml/MlPredictionService` consome o microsserviço Python (`ml-service`) para projetar
renda (regressão), gastos (Random Forest) e patrimônio (ARIMA), e **calibra** os
parâmetros do Monte Carlo a partir das predições (rota `run-calibrated`). Métricas de
erro (MAE/RMSE/R²) são propagadas para transparência.

## Testes e cobertura

```bash
./gradlew test                 # Kotest (motor, otimização, estatística, rotas, ML)
./gradlew jacocoTestReport     # relatório de cobertura
```

Cobre o motor de Monte Carlo (determinismo, reprodutibilidade, conformidade
distribucional, desempenho com 10k), otimização (convergência da busca binária,
casos infactíveis, monotonicidade), rebalanceamento, geradores aleatórios, funções
estatísticas, hash de senha e rotas (test client do Ktor). Relatórios analíticos:

- `docs/cobertura-testes.md` — cobertura (JaCoCo, > 70% no motor)
- `docs/analise-sensibilidade.md` — análise de sensibilidade dos parâmetros
- `docs/comparacao-montecarlo-deterministico.md` — Monte Carlo × determinístico
- `docs/estatisticas-referencia.md` — base de referência e mapeamento da §6.2

## Variáveis de ambiente

Veja `.env.example`. Principais: `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `JWT_SECRET` (troque em produção — `openssl rand -base64 64`),
e a URL do microsserviço de ML.
