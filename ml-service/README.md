# LifeForge ML Service

Microsserviço de IA preditiva do LifeForge. Treina modelos personalizados sobre o histórico de renda e despesa do usuário e devolve projeções **calibradas** que alimentam a engine Monte Carlo do backend Ktor.

## Stack

- **FastAPI 0.115** — framework HTTP assíncrono
- **scikit-learn 1.5** — Linear Regression + Random Forest
- **pandas / numpy** — pré-processamento e feature engineering
- **Pydantic 2** — validação de payloads e settings
- **pytest** — testes unitários e de integração

## Endpoints

| Método | Path | Descrição |
|--------|------|-----------|
| `GET`  | `/health` | Healthcheck (consumido pelo Docker) |
| `POST` | `/predict/income` | Regressão linear sobre histórico de renda |
| `POST` | `/predict/expenses` | Random Forest sobre despesas categorizadas |
| `GET`  | `/models/metrics` | MAE / RMSE / R² dos modelos treinados |

A documentação interativa (Swagger UI) fica em `/docs` quando o serviço está rodando.

## Como rodar

### Local (desenvolvimento)

```bash
cd ml-service
python -m venv .venv
source .venv/bin/activate   # ou .venv\Scripts\activate no Windows
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Servidor sobe em `http://localhost:8000`.

### Container

Roda automaticamente junto com `postgres` e `backend` via `docker compose up` (ver `docker-compose.yml` na raiz do projeto).

### Testes

```bash
pytest -v
```

Cobre testes unitários dos dois modelos preditivos + testes de integração da API (HTTP status, validação, exception handlers).

## Decisões arquiteturais

**Por que treinar a cada request?** O escopo do TCC é planejamento financeiro pessoal — cada usuário tem seu próprio histórico e datasets pequenos. Treinar com os dados mais frescos a cada chamada é simples e adequado. Em produção, faria sentido cachear modelos por `user_id` com TTL.

**Por que registry singleton?** Permite que `GET /models/metrics` reporte métricas mesmo entre requests. Implementado com `RLock` (thread-safe) e double-checked locking. Em produção real, substituir por persistência em object store.

**Por que features cíclicas (sin/cos do mês)?** Dezembro e janeiro são "vizinhos" no calendário, mas o número ordinal 12 não está adjacente a 1 num espaço linear. A codificação senoidal preserva essa adjacência — padrão em time-series forecasting.

**Como o output calibra o Monte Carlo?** A response do `/predict/income` traz três campos pensados para isso:

- `expected_monthly_income` → base do aporte mensal
- `annual_growth_rate` → reajuste anual
- `residual_volatility_monthly` → incerteza da renda (vira input de volatilidade da engine)

O backend Ktor combina esses valores com a predição de despesas (`expected_monthly_expense`) para derivar `monthlyContribution = income − expenses` no endpoint `/api/v1/simulation/run-calibrated`.

## Estrutura

```
ml-service/
├── app/
│   ├── main.py              # FastAPI + exception handlers
│   ├── config.py            # Settings via pydantic-settings
│   ├── schemas.py           # Request/Response models
│   ├── exceptions.py        # Erros de domínio
│   ├── models/
│   │   ├── base.py          # Interface abstrata
│   │   ├── income_model.py  # Regressão Linear
│   │   └── expense_model.py # Random Forest
│   ├── services/
│   │   └── registry.py      # Singleton thread-safe de modelos
│   ├── routes/
│   │   ├── health.py
│   │   ├── predictions.py
│   │   └── metrics.py
│   └── utils/
│       └── preprocessing.py # Agregação + features cíclicas
├── tests/
│   ├── conftest.py          # Fixtures (geradores sintéticos)
│   ├── test_income_model.py
│   ├── test_expense_model.py
│   └── test_api.py          # Integração via TestClient
├── requirements.txt
├── Dockerfile
└── README.md
```

## Variáveis de ambiente

Todas opcionais — defaults razoáveis em `app/config.py`. Prefixo `ML_`.

| Variável | Default | Descrição |
|----------|---------|-----------|
| `ML_LOG_LEVEL` | `INFO` | Nível de log |
| `ML_INCOME_MIN_OBSERVATIONS` | `6` | Mínimo de meses para treinar a regressão |
| `ML_INCOME_MAX_HORIZON_MONTHS` | `60` | Máximo de meses projetáveis |
| `ML_EXPENSE_MIN_OBSERVATIONS` | `12` | Mínimo de linhas agregadas |
| `ML_EXPENSE_RF_N_ESTIMATORS` | `100` | Árvores na Random Forest |
| `ML_EXPENSE_RF_MAX_DEPTH` | `10` | Profundidade máxima |
| `ML_EXPENSE_RF_RANDOM_STATE` | `42` | Semente p/ reprodutibilidade |
