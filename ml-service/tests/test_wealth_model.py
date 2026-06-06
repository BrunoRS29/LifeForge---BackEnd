"""Testes unitarios do WealthArimaModel (serie temporal de patrimonio)."""

from __future__ import annotations

import pytest

from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.wealth_model import WealthArimaModel
from app.schemas import WealthObservation


def make_wealth_history(
    months: int,
    start: float = 1_000.0,
    step: float = 500.0,
) -> list[WealthObservation]:
    """Serie de patrimonio acumulado linearmente crescente."""
    return [
        WealthObservation(month_index=i, amount=start + step * i)
        for i in range(months)
    ]


class TestWealthArimaModel:
    def test_predict_before_fit_raises(self) -> None:
        model = WealthArimaModel()
        with pytest.raises(ModelNotFitError):
            model.predict(horizon_months=3)

    def test_insufficient_data_raises(self) -> None:
        model = WealthArimaModel()
        # 4 meses < wealth_min_observations (6)
        with pytest.raises(InsufficientDataError):
            model.fit(make_wealth_history(months=4))

    def test_fit_then_predict_produces_correct_horizon(self) -> None:
        model = WealthArimaModel()
        model.fit(make_wealth_history(months=24))

        response = model.predict(horizon_months=12)
        assert response.horizon_months == 12
        assert len(response.projection) == 12
        assert [p.month_index for p in response.projection] == list(range(1, 13))

    def test_projection_continues_upward_trend(self) -> None:
        """Serie crescente: a projecao deve seguir subindo (ARIMA ou fallback)."""
        model = WealthArimaModel()
        model.fit(make_wealth_history(months=24, start=1_000.0, step=500.0))

        response = model.predict(horizon_months=6)
        amounts = [p.predicted_amount for p in response.projection]

        assert amounts[-1] > amounts[0]
        assert response.expected_final_wealth == amounts[-1]
        assert response.monthly_growth_rate > 0

    def test_metrics_populated_after_fit(self) -> None:
        model = WealthArimaModel()
        model.fit(make_wealth_history(months=24))

        m = model.metrics
        assert m.n_train >= 1
        assert m.n_test >= 1
        assert m.mae >= 0.0
        assert m.rmse >= 0.0
