"""Endpoint que expoe metricas dos modelos atualmente treinados."""

from fastapi import APIRouter, Depends

from app.schemas import ModelsMetricsResponse
from app.services.registry import ModelRegistry, get_registry

router = APIRouter(prefix="/models", tags=["metrics"])


@router.get(
    "/metrics",
    response_model=ModelsMetricsResponse,
    summary="Lista metricas de avaliacao dos modelos treinados",
)
def get_models_metrics(
    registry: ModelRegistry = Depends(get_registry),
) -> ModelsMetricsResponse:
    """
    Retorna metricas (MAE, RMSE, R2) dos modelos que foram treinados
    desde o startup do servico. Modelos nunca treinados sao omitidos.

    Esta route eh consumida pelo dashboard do app para exibir um indicador
    de "qualidade da predicao" ao usuario - boa pratica de transparencia
    em sistemas de ML aplicados a financas pessoais.
    """
    return registry.metrics_response()
