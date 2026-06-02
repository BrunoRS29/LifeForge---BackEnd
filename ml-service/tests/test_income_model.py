"""Testes unitarios do IncomeRegressionModel."""

from __future__ import annotations

from datetime import date

import pytest

from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.income_model import IncomeRegressionModel
from app.schemas import IncomeObservation
from tests.conftest import make_income_history


class TestIncomeRegressionModel:
    """Cobertura de fit + predict + edge cases."""

    def test_predict_before_fit_raises(self) -> None:
        model = IncomeRegressionModel()
        with pytest.raises(ModelNotFitError):
            model.predict(horizon_months=3)

    def test_insufficient_data_raises(self) -> None:
        model = IncomeRegressionModel()
        # 3 meses < ML_INCOME_MIN_OBSERVATIONS (6)
        small_history = [
            IncomeObservation(received_at=date(2024, m, 5), amount=5000)
            for m in (1, 2, 3)
        ]
        with pytest.raises(InsufficientDataError):
            model.fit(small_history)

    def test_fit_then_predict_produces_correct_horizon(self, income_history_24m) -> None:
        model = IncomeRegressionModel()
        model.fit(income_history_24m)

        response = model.predict(horizon_months=6)
        assert response.horizon_months == 6
        assert len(response.projection) == 6
        assert all(p.predicted_amount > 0 for p in response.projection)

    def test_predicted_amounts_follow_trend(self) -> None:
        """Dataset sem ruido: a regressao deve recuperar a tendencia."""
        # Tendencia pura sem sazonalidade ou bonus, sem ruido
        history = make_income_history(
            months=24,
            base=5_000.0,
            monthly_growth=100.0,
            bonus_in_december=0.0,
            noise=0.0,
        )
        model = IncomeRegressionModel()
        model.fit(history)

        response = model.predict(horizon_months=12)

        # Renda mensal projetada deve estar crescendo
        amounts = [p.predicted_amount for p in response.projection]
        # Permite pequenas oscilacoes por causa do termo sazonal aprendido
        # (mesmo zero sazonal pode dar coeficientes pequenos != 0).
        # Verificamos que o ultimo ponto > primeiro com folga clara.
        assert amounts[-1] > amounts[0] * 1.05

    def test_annual_growth_rate_is_positive_for_growing_income(
        self, income_history_24m
    ) -> None:
        model = IncomeRegressionModel()
        model.fit(income_history_24m)
        response = model.predict(horizon_months=3)
        assert response.annual_growth_rate > 0

    def test_residual_volatility_is_higher_with_noise(self) -> None:
        """Mais ruido -> maior `residual_volatility_monthly`."""
        history_calm = make_income_history(months=24, noise=10.0)
        history_noisy = make_income_history(months=24, noise=500.0)

        m1, m2 = IncomeRegressionModel(), IncomeRegressionModel()
        m1.fit(history_calm)
        m2.fit(history_noisy)

        r1 = m1.predict(horizon_months=3)
        r2 = m2.predict(horizon_months=3)

        assert r2.residual_volatility_monthly > r1.residual_volatility_monthly

    def test_metrics_are_populated_after_fit(self, income_history_24m) -> None:
        model = IncomeRegressionModel()
        model.fit(income_history_24m)
        m = model.metrics
        assert m.n_train >= 4
        assert m.n_test >= 1
        assert m.mae >= 0.0
        assert m.rmse >= m.mae  # RMSE >= MAE sempre

    def test_predictions_never_negative(self) -> None:
        """Mesmo com input ruidoso o clip impede valores negativos."""
        # Cria historico que cai a quase zero no final
        history = []
        for m in range(12):
            history.append(
                IncomeObservation(
                    received_at=date(2024, m + 1, 5),
                    amount=max(5000 - 400 * m, 50),
                )
            )
        model = IncomeRegressionModel()
        model.fit(history)
        response = model.predict(horizon_months=12)
        assert all(p.predicted_amount >= 0.0 for p in response.projection)
