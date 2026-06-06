"""Testes unitarios do ExpenseRandomForestModel."""

from __future__ import annotations

from datetime import date

import pytest

from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.expense_model import ExpenseRandomForestModel
from app.schemas import ExpenseObservation


class TestExpenseRandomForestModel:

    def test_predict_before_fit_raises(self) -> None:
        model = ExpenseRandomForestModel()
        with pytest.raises(ModelNotFitError):
            model.predict(horizon_months=1)

    def test_insufficient_data_raises(self) -> None:
        model = ExpenseRandomForestModel()
        # Apenas 2 observacoes - bem abaixo do minimo (12 linhas agregadas)
        tiny = [
            ExpenseObservation(spent_at=date(2024, 1, 5), amount=100, category="FOOD"),
            ExpenseObservation(spent_at=date(2024, 2, 5), amount=110, category="FOOD"),
        ]
        with pytest.raises(InsufficientDataError):
            model.fit(tiny)

    def test_fit_then_predict_returns_only_categories_with_history(
        self, expense_history_18m
    ) -> None:
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)

        response = model.predict(horizon_months=1)
        returned = {p.category for p in response.by_category}
        # So retorna categorias com historico real (a fixture usa 4 das 7).
        assert returned == {"FOOD", "HOUSING", "TRANSPORT", "LEISURE"}
        # Categorias sem historico NAO entram - senao inflariam o total.
        assert "HEALTH" not in returned
        assert "EDUCATION" not in returned

    def test_single_category_user_total_not_inflated(self) -> None:
        """Regressao do bug do 'Simular com IA': usuario com UMA categoria nao
        pode ter o total inflado por categorias sem historico."""
        history = []
        for m in range(14):  # 14 meses distintos -> >= 12 apos descartar lag inicial
            year = 2023 + m // 12
            month = (m % 12) + 1
            history.append(
                ExpenseObservation(
                    spent_at=date(year, month, 10),
                    amount=1_500.0,
                    category="HOUSING",
                )
            )

        model = ExpenseRandomForestModel()
        model.fit(history)
        response = model.predict(horizon_months=1)

        cats = {p.category for p in response.by_category}
        assert cats == {"HOUSING"}
        # Total ~ uma categoria (~1500), nao 7x isso.
        assert response.expected_monthly_expense < 3_000.0

    def test_predictions_are_non_negative(self, expense_history_18m) -> None:
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)
        response = model.predict(horizon_months=3)
        assert all(p.predicted_amount >= 0.0 for p in response.by_category)
        assert response.expected_monthly_expense >= 0.0

    def test_expected_monthly_expense_matches_sum(self, expense_history_18m) -> None:
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)
        response = model.predict(horizon_months=1)
        total = sum(p.predicted_amount for p in response.by_category)
        assert abs(response.expected_monthly_expense - total) < 1e-6

    def test_categories_with_history_have_positive_predictions(
        self, expense_history_18m
    ) -> None:
        """Categorias que aparecem no historico devem ter predicao > 0."""
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)
        response = model.predict(horizon_months=1)
        by_cat = {p.category: p.predicted_amount for p in response.by_category}
        # As fixtures geram FOOD, HOUSING, TRANSPORT, LEISURE
        for cat in ("FOOD", "HOUSING", "TRANSPORT", "LEISURE"):
            assert by_cat[cat] > 0.0

    def test_metrics_populated_after_fit(self, expense_history_18m) -> None:
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)
        m = model.metrics
        assert m.n_train > 0
        assert m.n_test > 0
        assert m.mae >= 0.0
        assert m.rmse >= 0.0

    def test_horizon_out_of_range_raises(self, expense_history_18m) -> None:
        model = ExpenseRandomForestModel()
        model.fit(expense_history_18m)
        with pytest.raises(ValueError):
            model.predict(horizon_months=0)
        with pytest.raises(ValueError):
            model.predict(horizon_months=13)
