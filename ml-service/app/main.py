"""
Entrypoint do microsservico ML do LifeForge.

Monta a aplicacao FastAPI, registra as rotas e os exception handlers
que convertem erros de dominio em respostas HTTP padronizadas.

Como rodar localmente:
    uvicorn app.main:app --reload

Como rodar no container:
    docker compose up ml-service
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.exceptions import MlServiceError
from app.routes import health, metrics, predictions
from app.schemas import ErrorResponse


# ----------------------------------------------------------------------------
# Configuracao basica de logs
# ----------------------------------------------------------------------------

settings = get_settings()
logging.basicConfig(
    level=settings.log_level,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
log = logging.getLogger(settings.app_name)


# ----------------------------------------------------------------------------
# Instanciacao da app
# ----------------------------------------------------------------------------

app = FastAPI(
    title="LifeForge ML Service",
    version=settings.app_version,
    description=(
        "Microsservico de IA preditiva do LifeForge. "
        "Treina modelos personalizados sobre o historico de renda e despesa "
        "do usuario e devolve projecoes calibradas para alimentar a engine "
        "de Monte Carlo do backend Ktor."
    ),
)


# ----------------------------------------------------------------------------
# Roteamento
# ----------------------------------------------------------------------------

app.include_router(health.router)
app.include_router(predictions.router)
app.include_router(metrics.router)


# ----------------------------------------------------------------------------
# Exception handlers
# ----------------------------------------------------------------------------

@app.exception_handler(MlServiceError)
async def ml_service_error_handler(_: Request, exc: MlServiceError) -> JSONResponse:
    """Converte excecoes de dominio em respostas estruturadas."""
    log.warning("MlServiceError: code=%s message=%s", exc.code, exc.message)
    return JSONResponse(
        status_code=exc.http_status,
        content=ErrorResponse(error=exc.code, message=exc.message).model_dump(),
    )


@app.exception_handler(RequestValidationError)
async def validation_error_handler(
    _: Request, exc: RequestValidationError
) -> JSONResponse:
    """Padroniza erros de validacao Pydantic em formato consumido pelo Kotlin."""
    # Pega o primeiro erro para mensagem curta; o detalhe completo vai no body
    first = exc.errors()[0] if exc.errors() else {"msg": "validation error"}
    message = f"{first.get('loc', ['?'])[-1]}: {first.get('msg', 'invalid')}"
    return JSONResponse(
        status_code=422,
        content=ErrorResponse(error="VALIDATION", message=message).model_dump(),
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    """Pega tudo que nao foi tratado para nao vazar stacktrace."""
    log.exception("Unhandled exception: %s", exc)
    return JSONResponse(
        status_code=500,
        content=ErrorResponse(
            error="INTERNAL_ERROR",
            message="Erro interno no servico de ML",
        ).model_dump(),
    )
