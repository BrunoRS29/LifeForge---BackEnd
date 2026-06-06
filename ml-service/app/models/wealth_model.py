"""
Modelo de projecao de patrimonio - serie temporal (ARIMA).

Justificativa academica:
- O patrimonio acumulado e uma serie temporal tipicamente nao-estacionaria
  (tende a crescer). ARIMA(p, d, q) com d = 1 modela a tendencia via
  diferenciacao e captura autocorrelacao de curto prazo nos residuos -
  exatamente o "ARIMA / Prophet para serie temporal de patrimonio" previsto
  na Secao 7.1 da proposta do TCC.
- Quando o statsmodels nao esta disponivel ou o ARIMA nao converge (serie
  muito curta/degenerada), caimos para uma tendencia linear por minimos
  quadrados. E um modelo de serie temporal degradado, porem SEMPRE disponivel,
  garantindo que o endpoint nunca quebre por questao de ambiente.

Entrada: serie mensal de patrimonio (patrimonio acumulado reconstruido pelo
backend a partir do fluxo de caixa receitas - despesas). Saida: projecao mes a
mes + metricas MAE/RMSE/R2 (transparencia exigida pelo TCC).
"""

from __future__ import annotations

import numpy as np
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

from app.config import get_settings
from app.exceptions import InsufficientDataError, ModelNotFitError
from app.models.base import BaseModel
from app.schemas import (
    ModelMetrics,
    WealthObservation,
    WealthPredictionPoint,
    WealthPredictionResponse,
)

try:
    from statsmodels.tsa.arima.model import ARIMA

    _HAS_STATSMODELS = True
except Exception:  # pragma: no cover - depende do ambiente de execucao
    _HAS_STATSMODELS = False


# Ordem ARIMA padrao: (1,1,1) = um termo AR, uma diferenciacao (tendencia),
# um termo MA. Bom ponto de partida para series financeiras curtas.
ARIMA_ORDER = (1, 1, 1)


class WealthArimaModel(BaseModel):
    """Modelo de serie temporal para projecao de patrimonio."""

    name = "WEALTH_ARIMA"

    def __init__(self) -> None:
        super().__init__()
        self._series: np.ndarray | None = None
        self._monthly_growth_rate: float = 0.0
        self._method: str = "UNFITTED"

    # ------------------------------------------------------------------
    # Fit
    # ------------------------------------------------------------------

    def fit(self, observations: list[WealthObservation]) -> None:
        settings = get_settings()

        ordered = sorted(observations, key=lambda o: o.month_index)
        y = np.array([float(o.amount) for o in ordered], dtype=float)
        n = len(y)
        if n < settings.wealth_min_observations:
            raise InsufficientDataError(
                f"Historico de patrimonio precisa de >= "
                f"{settings.wealth_min_observations} meses (recebeu {n})."
            )

        # Split temporal: as ultimas observacoes viram teste (ordem preservada).
        n_test = min(3, max(1, n // 5))
        n_train = n - n_test
        y_train, y_test = y[:n_train], y[n_train:]

        forecast_test, _ = _fit_and_forecast(y_train, n_test)
        metrics = ModelMetrics(
            mae=float(mean_absolute_error(y_test, forecast_test)),
            rmse=float(np.sqrt(mean_squared_error(y_test, forecast_test))),
            r2=float(r2_score(y_test, forecast_test)) if len(y_test) > 1 else float("nan"),
            n_train=int(n_train),
            n_test=int(n_test),
        )

        # Taxa de crescimento mensal media = inclinacao / nivel medio absoluto.
        t = np.arange(n)
        slope = float(np.polyfit(t, y, 1)[0])
        mean_level = float(np.mean(np.abs(y))) or 1.0
        self._monthly_growth_rate = slope / mean_level

        self._series = y
        self._mark_fitted(metrics)

    # ------------------------------------------------------------------
    # Predict
    # ------------------------------------------------------------------

    def predict(self, horizon_months: int) -> WealthPredictionResponse:
        if not self.is_fitted or self._series is None:
            raise ModelNotFitError("Wealth model nao treinado")

        settings = get_settings()
        if horizon_months < 1:
            raise ValueError("horizon_months deve ser >= 1")
        if horizon_months > settings.wealth_max_horizon_months:
            raise ValueError(
                f"horizon_months > limite ({settings.wealth_max_horizon_months})"
            )

        forecast, method = _fit_and_forecast(self._series, horizon_months)
        self._method = method

        projection = [
            WealthPredictionPoint(
                month_index=k + 1,
                predicted_amount=float(forecast[k]),
            )
            for k in range(horizon_months)
        ]
        expected_final = (
            float(forecast[-1]) if horizon_months > 0 else float(self._series[-1])
        )

        return WealthPredictionResponse(
            horizon_months=horizon_months,
            projection=projection,
            expected_final_wealth=expected_final,
            monthly_growth_rate=self._monthly_growth_rate,
            metrics=self.metrics,
        )


# ======================================================================
# Helpers de forecasting
# ======================================================================


def _fit_and_forecast(y_train: np.ndarray, steps: int) -> tuple[np.ndarray, str]:
    """Tenta ARIMA; cai para tendencia linear se indisponivel ou falhar."""
    if steps < 1:
        return np.array([], dtype=float), "NONE"

    if _HAS_STATSMODELS and len(y_train) >= 4:
        try:
            model = ARIMA(y_train, order=ARIMA_ORDER)
            fitted = model.fit()
            fc = np.asarray(fitted.forecast(steps=steps), dtype=float)
            if fc.shape[0] == steps and np.all(np.isfinite(fc)):
                return fc, f"ARIMA{ARIMA_ORDER}"
        except Exception:
            # Convergencia/algebra linear falhou - usa fallback deterministico.
            pass

    return _linear_forecast(y_train, steps), "LINEAR_TREND"


def _linear_forecast(y: np.ndarray, steps: int) -> np.ndarray:
    """Projecao por tendencia linear (minimos quadrados)."""
    n = len(y)
    if n == 0:
        return np.zeros(steps, dtype=float)
    if n == 1:
        return np.full(steps, float(y[0]), dtype=float)

    t = np.arange(n)
    slope, intercept = np.polyfit(t, y, 1)
    future_t = np.arange(n, n + steps)
    return intercept + slope * future_t
