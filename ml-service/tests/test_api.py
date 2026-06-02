"""
Testes de integracao da API FastAPI usando TestClient.

Valida o ciclo completo de request/response, incluindo:
- Status codes corretos (200, 400, 422)
- Estrutura do payload (campos obrigatorios)
- Exception handlers (InsufficientDataError -> 422)
- Healthcheck
- /models/metrics depois de treinar pelo /predict/income

Estrategia: usar o TestClient sincrono do FastAPI, que executa a app
in-process sem subir um servidor real. Suficiente para validar o
contrato HTTP que o backend Ktor vai consumir.
"""

from __future__ import annotations

from datetime import date

import pytest
from fastapi.testclient import TestClient

from app.main import app
from tests.conftest import make_expense_history, make_income_history


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


# ============================================================================
# /health
# ============================================================================


class TestHealth:

    def test_returns_ok(self, client: TestClient) -> None:
        response = client.get("/health")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ok"
        assert body["service"] == "lifeforge-ml-service"


# ============================================================================
# /predict/income
# ============================================================================


class TestPredictIncome:

    def _payload(self, months: int = 24, horizon: int = 6) -> dict:
        history = make_income_history(months=months)
        return {
            "history": [
                {
                    "received_at": h.received_at.isoformat(),
                    "amount": h.amount,
                    "income_type": h.income_type,
                    "recurring": h.recurring,
                }
                for h in history
            ],
            "horizon_months": horizon,
        }

    def test_happy_path(self, client: TestClient) -> None:
        response = client.post("/predict/income", json=self._payload())
        assert response.status_code == 200

        body = response.json()
        assert body["model_name"] == "INCOME_REGRESSION"
        assert body["horizon_months"] == 6
        assert len(body["projection"]) == 6
        for point in body["projection"]:
            assert point["predicted_amount"] >= 0.0

        # Sumarios para calibracao do Monte Carlo
        assert body["expected_monthly_income"] > 0.0
        assert body["residual_volatility_monthly"] >= 0.0
        # Crescimento esperado positivo no dataset sintetico
        assert body["annual_growth_rate"] > 0.0

        # Metricas presentes
        metrics = body["metrics"]
        assert metrics["mae"] >= 0.0
        assert metrics["rmse"] >= metrics["mae"]
        assert metrics["n_train"] > 0

    def test_insufficient_data_returns_422(self, client: TestClient) -> None:
        # Apenas 3 meses - abaixo do minimo (6)
        payload = self._payload(months=3, horizon=2)
        response = client.post("/predict/income", json=payload)
        assert response.status_code == 422
        body = response.json()
        assert body["error"] == "INSUFFICIENT_DATA"
        assert "6" in body["message"]  # menciona o limiar

    def test_empty_history_returns_422(self, client: TestClient) -> None:
        # Validacao do Pydantic - lista vazia
        response = client.post(
            "/predict/income",
            json={"history": [], "horizon_months": 3},
        )
        assert response.status_code == 422
        body = response.json()
        assert body["error"] == "VALIDATION"

    def test_negative_amount_returns_422(self, client: TestClient) -> None:
        payload = {
            "history": [
                {
                    "received_at": date(2024, m, 5).isoformat(),
                    "amount": -100.0,  # invalido (gt=0)
                    "income_type": "SALARY",
                    "recurring": True,
                }
                for m in range(1, 8)
            ],
            "horizon_months": 3,
        }
        response = client.post("/predict/income", json=payload)
        assert response.status_code == 422
        assert response.json()["error"] == "VALIDATION"

    def test_horizon_zero_returns_422(self, client: TestClient) -> None:
        payload = self._payload(horizon=0)
        response = client.post("/predict/income", json=payload)
        assert response.status_code == 422


# ============================================================================
# /predict/expenses
# ============================================================================


class TestPredictExpenses:

    def _payload(self, months: int = 18, horizon: int = 1) -> dict:
        history = make_expense_history(months=months)
        return {
            "history": [
                {
                    "spent_at": h.spent_at.isoformat(),
                    "amount": h.amount,
                    "category": h.category,
                    "recurring": h.recurring,
                }
                for h in history
            ],
            "horizon_months": horizon,
        }

    def test_happy_path(self, client: TestClient) -> None:
        response = client.post("/predict/expenses", json=self._payload())
        assert response.status_code == 200

        body = response.json()
        assert body["model_name"] == "EXPENSE_RANDOM_FOREST"
        assert body["horizon_months"] == 1

        # Sempre retorna as 7 categorias canonicas
        cats = {p["category"] for p in body["by_category"]}
        assert cats == {
            "HOUSING", "FOOD", "TRANSPORT", "HEALTH",
            "EDUCATION", "LEISURE", "OTHER",
        }

        total = sum(p["predicted_amount"] for p in body["by_category"])
        assert abs(body["expected_monthly_expense"] - total) < 1e-6
        assert body["expected_monthly_expense"] > 0.0

    def test_insufficient_data_returns_422(self, client: TestClient) -> None:
        payload = {
            "history": [
                {
                    "spent_at": date(2024, 1, 5).isoformat(),
                    "amount": 100.0,
                    "category": "FOOD",
                    "recurring": False,
                }
            ],
            "horizon_months": 1,
        }
        response = client.post("/predict/expenses", json=payload)
        assert response.status_code == 422
        assert response.json()["error"] == "INSUFFICIENT_DATA"

    def test_invalid_category_returns_422(self, client: TestClient) -> None:
        payload = {
            "history": [
                {
                    "spent_at": date(2024, 1, 5).isoformat(),
                    "amount": 100.0,
                    "category": "INVALID_CATEGORY",
                    "recurring": False,
                }
            ],
            "horizon_months": 1,
        }
        response = client.post("/predict/expenses", json=payload)
        assert response.status_code == 422


# ============================================================================
# /models/metrics
# ============================================================================


class TestModelsMetrics:

    def test_empty_when_no_model_was_fit(self, client: TestClient) -> None:
        response = client.get("/models/metrics")
        assert response.status_code == 200
        body = response.json()
        assert body == {"entries": []}

    def test_returns_metrics_after_income_fit(self, client: TestClient) -> None:
        # Treina via /predict/income
        income_payload = {
            "history": [
                {
                    "received_at": h.received_at.isoformat(),
                    "amount": h.amount,
                    "income_type": h.income_type,
                    "recurring": h.recurring,
                }
                for h in make_income_history(months=24)
            ],
            "horizon_months": 3,
        }
        client.post("/predict/income", json=income_payload).raise_for_status()

        response = client.get("/models/metrics")
        assert response.status_code == 200
        entries = response.json()["entries"]
        assert len(entries) == 1
        entry = entries[0]
        assert entry["model_name"] == "INCOME_REGRESSION"
        assert "fitted_at" in entry
        assert entry["metrics"]["n_train"] > 0

    def test_returns_metrics_for_both_models_after_fit(
        self, client: TestClient
    ) -> None:
        # Treina os dois
        income_payload = {
            "history": [
                {
                    "received_at": h.received_at.isoformat(),
                    "amount": h.amount,
                    "income_type": h.income_type,
                    "recurring": h.recurring,
                }
                for h in make_income_history(months=24)
            ],
            "horizon_months": 3,
        }
        expense_payload = {
            "history": [
                {
                    "spent_at": h.spent_at.isoformat(),
                    "amount": h.amount,
                    "category": h.category,
                    "recurring": h.recurring,
                }
                for h in make_expense_history(months=18)
            ],
            "horizon_months": 1,
        }
        client.post("/predict/income", json=income_payload).raise_for_status()
        client.post("/predict/expenses", json=expense_payload).raise_for_status()

        response = client.get("/models/metrics")
        entries = response.json()["entries"]
        model_names = {e["model_name"] for e in entries}
        assert model_names == {"INCOME_REGRESSION", "EXPENSE_RANDOM_FOREST"}
