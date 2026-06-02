"""
Modelo de projecao de renda - Regressao Linear.

Justificativa academica:
- Renda salarial tem componente tendencial (aumentos, promocoes) que
  e bem capturado por uma reta no espaco "renda x tempo".
- Componente sazonal (13o salario, bonus anual) e captado adicionando
  features ciclicas (sin/cos do mes calendarico).
- A residual sigma estimada nos da uma medida de incerteza que
  alimenta a engine Monte Carlo - eh exatamente isso que o spec do
  TCC chama de "input calibrado".

Formula da regressao linear multipla:

    y = beta0 + beta1*t + beta2*sin(2*pi*m/12) + beta3*cos(2*pi*m/12) + eps

Onde:
    y   = renda mensal observada
    t   = indice temporal (0, 1, 2, ...)
    m   = mes calendarico (1..12)
    eps ~ N(0, sigma^2)

O coeficiente beta1 multiplicado por 12 da a taxa de crescimento anual
aproximada em valor absoluto. Convertida em fracao do nivel medio gera
a `annual_growth_rate` retornada na response.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

from app.config import get_settings
from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.base import BaseModel
from app.schemas import (
    IncomeObservation,
    IncomePredictionPoint,
    IncomePredictionResponse,
    ModelMetrics,
)
from app.utils.preprocessing import (
    cyclic_month_features,
    income_history_to_monthly,
    safe_train_test_split,
)


# Ordem das features no vetor X. Documentada para auditoria.
FEATURE_NAMES: tuple[str, ...] = (
    "month_idx",
    "month_sin",
    "month_cos",
)


class IncomeRegressionModel(BaseModel):
    """Regressao linear para projecao de renda mensal."""

    name = "INCOME_REGRESSION"

    def __init__(self) -> None:
        super().__init__()
        self._regressor: LinearRegression | None = None
        self._last_month_idx: int | None = None
        # Estatisticas auxiliares
        self._residual_std: float = 0.0
        self._mean_observed: float = 0.0

    # ------------------------------------------------------------------
    # Fit
    # ------------------------------------------------------------------

    def fit(self, observations: list[IncomeObservation]) -> None:
        """
        Treina a regressao linear no historico de renda.

        Args:
            observations: lista de IncomeObservation (>= ML_INCOME_MIN_OBSERVATIONS).

        Raises:
            InsufficientDataError: amostra menor que o minimo.
        """
        settings = get_settings()

        monthly = income_history_to_monthly(observations)
        n = len(monthly)
        if n < settings.income_min_observations:
            raise InsufficientDataError(
                f"Historico precisa de >= {settings.income_min_observations} meses "
                f"(recebeu {n}). Adicione mais registros antes de prever."
            )

        X = monthly[list(FEATURE_NAMES)].to_numpy(dtype=float)
        y = monthly["amount"].to_numpy(dtype=float)

        # Split temporal: as ultimas observacoes viram teste, preservando
        # a ordem natural do tempo. Nao podemos randomizar shuffle em
        # series temporais sem viesar a metrica.
        n_train, n_test = safe_train_test_split(
            n, test_size=0.2, min_train=settings.income_min_observations - 1, min_test=1,
        )
        X_train, y_train = X[:n_train], y[:n_train]
        X_test, y_test = X[n_train:], y[n_train:]

        model = LinearRegression()
        model.fit(X_train, y_train)

        # Metricas no conjunto de teste
        y_pred = model.predict(X_test)
        metrics = ModelMetrics(
            mae=float(mean_absolute_error(y_test, y_pred)),
            rmse=float(np.sqrt(mean_squared_error(y_test, y_pred))),
            r2=float(r2_score(y_test, y_pred)) if len(y_test) > 1 else float("nan"),
            n_train=int(n_train),
            n_test=int(n_test),
        )

        # Re-fit no dataset completo para projecao final (pratica padrao:
        # apos selecionar o modelo, treina com todos os dados disponiveis).
        full_model = LinearRegression()
        full_model.fit(X, y)

        # Estatisticas auxiliares para calibracao do Monte Carlo
        y_full_pred = full_model.predict(X)
        residuals = y - y_full_pred
        self._residual_std = float(np.std(residuals, ddof=1)) if n > 1 else 0.0
        self._mean_observed = float(np.mean(y))

        self._regressor = full_model
        self._last_month_idx = int(monthly["month_idx"].iloc[-1])
        self._last_period = monthly["period"].iloc[-1]  # type: pd.Period

        self._mark_fitted(metrics)

    # ------------------------------------------------------------------
    # Predict
    # ------------------------------------------------------------------

    def predict(self, horizon_months: int) -> IncomePredictionResponse:
        """Gera projecao para os proximos `horizon_months` meses."""
        if not self.is_fitted or self._regressor is None:
            raise ModelNotFitError("Income model nao treinado")

        settings = get_settings()
        if horizon_months < 1:
            raise ValueError("horizon_months deve ser >= 1")
        if horizon_months > settings.income_max_horizon_months:
            raise ValueError(
                f"horizon_months > limite ({settings.income_max_horizon_months})"
            )

        # Monta matriz de features para os meses futuros
        feature_rows: list[list[float]] = []
        for k in range(1, horizon_months + 1):
            future_idx = self._last_month_idx + k  # type: ignore[operator]
            future_period = self._last_period + k  # pd.Period suporta + int
            sin_v, cos_v = cyclic_month_features(future_period.month)
            feature_rows.append([float(future_idx), sin_v, cos_v])

        X_future = np.array(feature_rows, dtype=float)
        y_future = self._regressor.predict(X_future)

        # Renda nao deve ser negativa (clipping)
        y_future = np.clip(y_future, a_min=0.0, a_max=None)

        projection = [
            IncomePredictionPoint(
                month_index=k + 1,
                predicted_amount=float(y_future[k]),
            )
            for k in range(horizon_months)
        ]

        # ---- Sumarios para o Monte Carlo ----
        expected_monthly = float(np.mean(y_future))

        # Taxa de crescimento anual derivada da inclinacao temporal:
        # beta_t * 12 = variacao em 12 meses, dividida pela media observada.
        slope_monthly = float(self._regressor.coef_[0])  # coeficiente de month_idx
        annual_growth_rate = (
            (slope_monthly * 12.0) / self._mean_observed
            if self._mean_observed > 0
            else 0.0
        )

        return IncomePredictionResponse(
            horizon_months=horizon_months,
            projection=projection,
            expected_monthly_income=expected_monthly,
            annual_growth_rate=annual_growth_rate,
            residual_volatility_monthly=self._residual_std,
            metrics=self.metrics,
        )
