"""
Excecoes de dominio do microsservico ML.

Capturadas no main.py por exception handlers, que as convertem em
respostas HTTP estruturadas. Isso evita expor stacktraces e padroniza
o formato de erro consumido pelo backend Ktor.
"""


class MlServiceError(Exception):
    """Excecao base. Todos os erros do servico herdam dela."""

    http_status: int = 500
    code: str = "ML_INTERNAL_ERROR"

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class InsufficientDataError(MlServiceError):
    """Historico fornecido eh muito curto para treinar um modelo confiavel."""

    http_status = 422
    code = "INSUFFICIENT_DATA"


class InvalidInputError(MlServiceError):
    """Payload sintaticamente valido mas semanticamente inconsistente."""

    http_status = 400
    code = "INVALID_INPUT"


class ModelNotFitError(MlServiceError):
    """Tentativa de prever antes de chamar fit/train."""

    http_status = 500
    code = "MODEL_NOT_FIT"
