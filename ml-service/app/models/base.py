"""
Interface comum dos modelos preditivos.

A abstracao mantem o registry (services/registry.py) generico: ele
guarda dicionarios `name -> BaseModel` sem precisar conhecer detalhes
de cada implementacao.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime, timezone

from app.exceptions import ModelNotFitError
from app.schemas import ModelMetrics


class BaseModel(ABC):
    """Contrato comum dos modelos preditivos."""

    # Nome canonico - deve casar com `Prediction.model_name` no banco
    name: str = "BASE"

    def __init__(self) -> None:
        self._fitted: bool = False
        self._fitted_at: datetime | None = None
        self._metrics: ModelMetrics | None = None

    # ------------------------------------------------------------------
    # Estado
    # ------------------------------------------------------------------

    @property
    def is_fitted(self) -> bool:
        return self._fitted

    @property
    def fitted_at(self) -> datetime | None:
        return self._fitted_at

    @property
    def metrics(self) -> ModelMetrics:
        if self._metrics is None:
            raise ModelNotFitError(f"Modelo {self.name} ainda nao foi treinado")
        return self._metrics

    # ------------------------------------------------------------------
    # Marcadores comuns
    # ------------------------------------------------------------------

    def _mark_fitted(self, metrics: ModelMetrics) -> None:
        """Hook chamado pelas subclasses ao final do fit."""
        self._fitted = True
        self._fitted_at = datetime.now(timezone.utc)
        self._metrics = metrics

    # ------------------------------------------------------------------
    # Contratos a serem implementados
    # ------------------------------------------------------------------

    @abstractmethod
    def fit(self, *args, **kwargs) -> None:  # pragma: no cover - abstrato
        """Treina o modelo. Subclasses definem a assinatura concreta."""

    @abstractmethod
    def predict(self, *args, **kwargs):  # pragma: no cover - abstrato
        """Gera predicoes. Subclasses definem o retorno."""
