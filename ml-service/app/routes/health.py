"""Endpoint de healthcheck."""

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    """Indica disponibilidade do servico - usado pelo Docker healthcheck."""
    return {"status": "ok", "service": "lifeforge-ml-service"}
