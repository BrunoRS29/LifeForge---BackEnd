"""
Modelo de previsao de despesas - Random Forest Regressor.

Justificativa academica:
- Despesas tipicamente nao seguem padrao linear: ha interacoes entre
  categoria, sazonalidade e historico recente (ex: "se na ultima ferias
  gastou muito em LEISURE, e dezembro, tende a repetir").
- Random Forest captura essas interacoes nao-lineares sem feature
  engineering manual pesado e e robusto a outliers (votacao por arvore).
- Limitacao: nao extrapola alem do range observado nos dados de treino.
  Isso e adequado para despesas (categorias sao limitadas) mas seria
  problema em renda (que cresce monotonicamente).

Features:
    month_sin, month_cos  -> sazonalidade ciclica
    category              -> one-hot encoded
    lag1                  -> gasto na mesma categoria no mes anterior
    lag3_avg              -> media dos 3 meses anteriores
    recurring_share       -> fracao recorrente no mes anterior
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

from app.config import get_settings
from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.base import BaseModel
from app.schemas import (
    CategoryPrediction,
    ExpenseObservation,
    ExpensePredictionResponse,
    ModelMetrics,
)
from app.utils.preprocessing import (
    cyclic_month_features,
    expense_history_to_monthly,
    safe_train_test_split,
)


# Categorias canonicas - precisa casar com `ExpenseCategory` do dominio Kotlin.
KNOWN_CATEGORIES: tuple[str, ...] = (
    "HOUSING", "FOOD", "TRANSPORT", "HEALTH",
    "EDUCATION", "LEISURE", "OTHER",
)


def _one_hot(category: str) -> list[float]:
    """One-hot deterministico sobre KNOWN_CATEGORIES."""
    return [1.0 if category == c else 0.0 for c in KNOWN_CATEGORIES]


class ExpenseRandomForestModel(BaseModel):
    """Random Forest para previsao de despesa mensal por categoria."""

    name = "EXPENSE_RANDOM_FOREST"

    def __init__(self) -> None:
        super().__init__()
        self._regressor: RandomForestRegressor | None = None
        # Snapshot do ultimo periodo conhecido (para gerar features futuras)
        self._last_period: pd.Period | None = None
        # Lag state por categoria: ultimo valor e media dos 3 ultimos
        self._lag_state: dict[str, dict[str, float]] = {}

    # ------------------------------------------------------------------
    # Fit
    # ------------------------------------------------------------------

    def fit(self, observations: list[ExpenseObservation]) -> None:
        """Treina o Random Forest no historico de despesas."""
        settings = get_settings()

        monthly = expense_history_to_monthly(observations)
        n = len(monthly)
        if n < settings.expense_min_observations:
            raise InsufficientDataError(
                f"Historico precisa de >= {settings.expense_min_observations} linhas "
                f"agregadas (recebeu {n} apos descartar lags iniciais)."
            )

        X_full = self._build_feature_matrix(monthly)
        y_full = monthly["amount"].to_numpy(dtype=float)

        # Split temporal preservando ordem
        n_train, n_test = safe_train_test_split(
            n, test_size=0.2, min_train=settings.expense_min_observations - 1, min_test=1,
        )

        # Ordena por periodo antes de cortar (importante: monthly ja vem por
        # categoria depois periodo, entao reordenamos por periodo p/ split)
        order = monthly["period"].argsort(kind="stable").to_numpy()
        X_ord = X_full[order]
        y_ord = y_full[order]

        X_train, y_train = X_ord[:n_train], y_ord[:n_train]
        X_test, y_test = X_ord[n_train:], y_ord[n_train:]

        model = RandomForestRegressor(
            n_estimators=settings.expense_rf_n_estimators,
            max_depth=settings.expense_rf_max_depth,
            random_state=settings.expense_rf_random_state,
            n_jobs=-1,
        )
        model.fit(X_train, y_train)

        y_pred = model.predict(X_test)
        metrics = ModelMetrics(
            mae=float(mean_absolute_error(y_test, y_pred)),
            rmse=float(np.sqrt(mean_squared_error(y_test, y_pred))),
            r2=float(r2_score(y_test, y_pred)) if len(y_test) > 1 else float("nan"),
            n_train=int(n_train),
            n_test=int(n_test),
        )

        # Re-fit no dataset completo para predicao em producao
        full_model = RandomForestRegressor(
            n_estimators=settings.expense_rf_n_estimators,
            max_depth=settings.expense_rf_max_depth,
            random_state=settings.expense_rf_random_state,
            n_jobs=-1,
        )
        full_model.fit(X_full, y_full)
        self._regressor = full_model

        # Captura estado para predicao futura
        self._last_period = monthly["period"].max()
        self._lag_state = self._compute_lag_state(monthly)

        self._mark_fitted(metrics)

    # ------------------------------------------------------------------
    # Predict
    # ------------------------------------------------------------------

    def predict(self, horizon_months: int) -> ExpensePredictionResponse:
        """
        Preve gasto por categoria nos proximos meses.

        Iterativo: usa a propria predicao do mes k como lag1 para o mes k+1
        (multi-step recursive forecasting). Suficiente para horizonte curto.
        """
        if not self.is_fitted or self._regressor is None or self._last_period is None:
            raise ModelNotFitError("Expense model nao treinado")

        if horizon_months < 1 or horizon_months > 12:
            raise ValueError("horizon_months deve estar em [1, 12]")

        # Copia mutavel do estado de lags para nao corromper o original
        lag_state = {c: dict(s) for c, s in self._lag_state.items()}

        last_period = self._last_period
        last_by_category: dict[str, float] = {}

        for step in range(1, horizon_months + 1):
            future_period = last_period + step
            sin_v, cos_v = cyclic_month_features(future_period.month)

            rows = []
            cats = list(KNOWN_CATEGORIES)
            for cat in cats:
                state = lag_state.get(cat, {"lag1": 0.0, "lag3_avg": 0.0, "recurring_share": 0.0})
                feats = [sin_v, cos_v] + _one_hot(cat) + [
                    state["lag1"],
                    state["lag3_avg"],
                    state["recurring_share"],
                ]
                rows.append(feats)

            X_step = np.array(rows, dtype=float)
            y_step = self._regressor.predict(X_step)
            y_step = np.clip(y_step, a_min=0.0, a_max=None)

            # Atualiza estado para o proximo passo
            for i, cat in enumerate(cats):
                prev_lag1 = lag_state[cat]["lag1"]
                prev_lag3 = lag_state[cat]["lag3_avg"]
                # lag3_avg movel: rolling de 3, descartando o mais antigo
                new_lag3 = (prev_lag1 + prev_lag3 * 2.0 + y_step[i]) / 3.0 \
                    if step == 1 else (prev_lag3 * 2.0 + y_step[i]) / 3.0
                lag_state[cat] = {
                    "lag1": float(y_step[i]),
                    "lag3_avg": float(new_lag3),
                    # recurring_share: assumimos persistir o ultimo conhecido
                    "recurring_share": lag_state[cat]["recurring_share"],
                }

            # Salva o ultimo step (so o final eh exposto na response, mas
            # poderiamos expor todos no futuro)
            if step == horizon_months:
                last_by_category = {cat: float(y_step[i]) for i, cat in enumerate(cats)}

        by_category = [
            CategoryPrediction(category=cat, predicted_amount=amount)  # type: ignore[arg-type]
            for cat, amount in last_by_category.items()
        ]
        total = float(sum(last_by_category.values()))

        return ExpensePredictionResponse(
            horizon_months=horizon_months,
            by_category=by_category,
            expected_monthly_expense=total,
            metrics=self.metrics,
        )

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _build_feature_matrix(monthly: pd.DataFrame) -> np.ndarray:
        """Monta a matriz de features a partir do dataframe agregado."""
        feature_rows: list[list[float]] = []
        for _, row in monthly.iterrows():
            cat = str(row["category"])
            feats = [
                float(row["month_sin"]),
                float(row["month_cos"]),
            ] + _one_hot(cat) + [
                float(row["lag1"]),
                float(row["lag3_avg"]),
                float(row["recurring_share"]),
            ]
            feature_rows.append(feats)
        return np.array(feature_rows, dtype=float)

    @staticmethod
    def _compute_lag_state(monthly: pd.DataFrame) -> dict[str, dict[str, float]]:
        """Para cada categoria, captura o ultimo lag1, lag3_avg e recurring_share."""
        latest = monthly.sort_values("period").groupby("category").tail(1)
        state: dict[str, dict[str, float]] = {}
        for _, row in latest.iterrows():
            cat = str(row["category"])
            # O lag1 do "proximo mes" = amount do mes mais recente
            # O lag3_avg do "proximo mes" = (amount + lag1 + lag2) / 3 — usamos
            # a propria coluna do dataframe (que e o lag3_avg do mes presente)
            state[cat] = {
                "lag1": float(row["amount"]),
                "lag3_avg": float((row["amount"] + row["lag1"] + row["lag3_avg"]) / 3.0),
                "recurring_share": float(row["recurring_share"]),
            }
        # Categorias nunca vistas ficam zeradas
        for cat in KNOWN_CATEGORIES:
            state.setdefault(cat, {"lag1": 0.0, "lag3_avg": 0.0, "recurring_share": 0.0})
        return state
