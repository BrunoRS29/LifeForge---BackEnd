"""
Registry de modelos.

Mantem instancias treinadas em memoria para que o GET /models/metrics
consiga reportar metricas mesmo que entre requests. Cada fit chamado
pelo /predict/* atualiza o registry.

Em producao real:
- substituir por persistencia em disco/object store
- carregar modelos no startup do servico
- versionar (model_name + version) para A/B testing
"""

from __future__ import annotations

from threading import RLock

from app.models.base import BaseModel
from app.models.expense_model import ExpenseRandomForestModel
from app.models.income_model import IncomeRegressionModel
from app.models.wealth_model import WealthArimaModel
from app.schemas import ModelMetricsEntry, ModelsMetricsResponse


class ModelRegistry:
    """Container thread-safe de modelos por nome."""

    def __init__(self) -> None:
        self._lock = RLock()
        # Pre-instanciamos os modelos vazios. Eles ficam "not fitted" ate
        # receberem o primeiro fit em /predict/*.
        self._models: dict[str, BaseModel] = {
            IncomeRegressionModel.name: IncomeRegressionModel(),
            ExpenseRandomForestModel.name: ExpenseRandomForestModel(),
            WealthArimaModel.name: WealthArimaModel(),
        }

    def get(self, name: str) -> BaseModel:
        """Recupera modelo por nome canonico."""
        with self._lock:
            if name not in self._models:
                raise KeyError(f"Modelo desconhecido: {name}")
            return self._models[name]

    def all(self) -> list[BaseModel]:
        with self._lock:
            return list(self._models.values())

    def metrics_response(self) -> ModelsMetricsResponse:
        """Monta o payload do GET /models/metrics."""
        entries: list[ModelMetricsEntry] = []
        for m in self.all():
            if not m.is_fitted or m.fitted_at is None:
                continue  # nao reporta modelo nao treinado
            entries.append(
                ModelMetricsEntry(
                    model_name=m.name,
                    fitted_at=m.fitted_at.isoformat(),
                    metrics=m.metrics,
                )
            )
        return ModelsMetricsResponse(entries=entries)


# Instancia unica para a aplicacao (singleton de modulo)
_registry: ModelRegistry | None = None
_registry_lock = RLock()


def get_registry() -> ModelRegistry:
    """Acesso global ao registry. Lazy-init thread-safe."""
    global _registry
    if _registry is None:
        with _registry_lock:
            if _registry is None:  # double-checked locking
                _registry = ModelRegistry()
    return _registry


def reset_registry() -> None:
    """Helper para testes - reseta o estado global."""
    global _registry
    with _registry_lock:
        _registry = None
