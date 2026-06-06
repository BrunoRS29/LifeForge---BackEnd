"""
Schemas Pydantic do microsservico ML.

Todos os payloads de request/response sao validados aqui. Os tipos sao
casados com os DTOs Kotlin do backend (`com.lifeforge.ml.MlClientDtos`).

Convencoes:
- Datas: ISO-8601 (string) - convertidas para pandas.Timestamp internamente.
- Categorias de despesa: strings que casam com o enum
  `com.lifeforge.domain.model.ExpenseCategory`
- Tipos de renda: strings que casam com `IncomeType` do dominio Kotlin.
"""

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


# ============================================================================
# Tipos compartilhados
# ============================================================================

ExpenseCategory = Literal[
    "HOUSING", "FOOD", "TRANSPORT", "HEALTH",
    "EDUCATION", "LEISURE", "OTHER",
]

IncomeType = Literal["SALARY", "BONUS", "DIVIDEND", "RENT", "OTHER"]


class ModelMetrics(BaseModel):
    """Metricas de avaliacao de um modelo, devolvidas pelo /models/metrics."""

    model_config = ConfigDict(protected_namespaces=())  # libera prefixo `model_`

    mae: float = Field(..., description="Mean Absolute Error sobre validacao")
    rmse: float = Field(..., description="Root Mean Squared Error")
    r2: float = Field(..., description="Coeficiente de determinacao (pode ser <0)")
    n_train: int = Field(..., description="Numero de amostras usadas para treino")
    n_test: int = Field(..., description="Numero de amostras usadas para validacao")


# ============================================================================
# /predict/income
# ============================================================================


class IncomeObservation(BaseModel):
    """Uma entrada de renda historica."""

    received_at: date = Field(..., description="Data de recebimento (ISO-8601)")
    amount: float = Field(..., gt=0, description="Valor recebido em moeda corrente")
    income_type: IncomeType = Field(default="SALARY")
    recurring: bool = Field(default=True)


class IncomePredictionRequest(BaseModel):
    """Request do POST /predict/income."""

    history: list[IncomeObservation] = Field(
        ...,
        description="Historico de recebimentos (>= ML_INCOME_MIN_OBSERVATIONS)",
    )
    horizon_months: int = Field(
        ...,
        ge=1,
        description="Numero de meses a projetar",
    )

    @field_validator("history")
    @classmethod
    def history_not_empty(cls, v: list[IncomeObservation]) -> list[IncomeObservation]:
        # A regra de minimo eh aplicada no servico (depende de Settings).
        # Aqui apenas garantimos que veio pelo menos 1 ponto.
        if not v:
            raise ValueError("history nao pode ser vazio")
        return v


class IncomePredictionPoint(BaseModel):
    """Um ponto da projecao mensal."""

    month_index: int = Field(..., description="Offset em meses a partir de agora (1=proximo mes)")
    predicted_amount: float = Field(..., description="Renda mensal projetada")


class IncomePredictionResponse(BaseModel):
    """Response do POST /predict/income."""

    model_name: str = "INCOME_REGRESSION"
    horizon_months: int

    # Serie projetada mes a mes
    projection: list[IncomePredictionPoint]

    # Sumario para calibracao da engine Monte Carlo:
    expected_monthly_income: float = Field(
        ..., description="Renda mensal media projetada (usada como aporte base)"
    )
    annual_growth_rate: float = Field(
        ...,
        description="Crescimento anual estimado a partir da inclinacao da regressao",
    )
    residual_volatility_monthly: float = Field(
        ...,
        ge=0.0,
        description="Desvio padrao dos residuos do modelo (proxy de incerteza mensal)",
    )

    metrics: ModelMetrics


# ============================================================================
# /predict/expenses
# ============================================================================


class ExpenseObservation(BaseModel):
    """Uma despesa historica."""

    spent_at: date
    amount: float = Field(..., gt=0)
    category: ExpenseCategory
    recurring: bool = Field(default=False)


class ExpensePredictionRequest(BaseModel):
    """Request do POST /predict/expenses."""

    history: list[ExpenseObservation]
    horizon_months: int = Field(default=1, ge=1, le=12)

    @field_validator("history")
    @classmethod
    def history_not_empty(cls, v: list[ExpenseObservation]) -> list[ExpenseObservation]:
        if not v:
            raise ValueError("history nao pode ser vazio")
        return v


class CategoryPrediction(BaseModel):
    """Previsao para uma categoria especifica."""

    category: ExpenseCategory
    predicted_amount: float = Field(..., ge=0.0)


class ExpensePredictionResponse(BaseModel):
    """Response do POST /predict/expenses."""

    model_name: str = "EXPENSE_RANDOM_FOREST"
    horizon_months: int

    # Predicao por categoria para o proximo periodo (mes 1)
    by_category: list[CategoryPrediction]

    # Total previsto (soma das categorias).
    # Usado para calibrar `monthlyContribution = income - expenses`.
    expected_monthly_expense: float = Field(..., ge=0.0)

    metrics: ModelMetrics


# ============================================================================
# /predict/wealth (serie temporal de patrimonio)
# ============================================================================


class WealthObservation(BaseModel):
    """Patrimonio (acumulado) ao fim de um mes da serie historica.

    A serie e reconstruida pelo backend a partir do fluxo de caixa
    (receitas - despesas acumuladas). `amount` pode ser negativo.
    """

    month_index: int = Field(..., description="Indice sequencial do mes (0 = primeiro)")
    amount: float = Field(..., description="Patrimonio acumulado no mes")


class WealthPredictionRequest(BaseModel):
    """Request do POST /predict/wealth."""

    history: list[WealthObservation]
    horizon_months: int = Field(default=12, ge=1)

    @field_validator("history")
    @classmethod
    def history_not_empty(cls, v: list[WealthObservation]) -> list[WealthObservation]:
        if not v:
            raise ValueError("history nao pode ser vazio")
        return v


class WealthPredictionPoint(BaseModel):
    """Um ponto da projecao de patrimonio."""

    month_index: int = Field(..., description="Offset em meses (1 = proximo mes)")
    predicted_amount: float = Field(..., description="Patrimonio projetado")


class WealthPredictionResponse(BaseModel):
    """Response do POST /predict/wealth."""

    model_config = ConfigDict(protected_namespaces=())

    model_name: str = "WEALTH_ARIMA"
    horizon_months: int
    projection: list[WealthPredictionPoint]
    expected_final_wealth: float = Field(
        ..., description="Patrimonio projetado ao fim do horizonte"
    )
    monthly_growth_rate: float = Field(
        ..., description="Crescimento mensal medio (inclinacao / nivel medio)"
    )
    metrics: ModelMetrics


# ============================================================================
# /models/metrics
# ============================================================================


class ModelMetricsEntry(BaseModel):
    """Entrada do dicionario retornado por /models/metrics."""

    model_name: str
    fitted_at: str = Field(..., description="Timestamp ISO-8601 do ultimo fit")
    metrics: ModelMetrics


class ModelsMetricsResponse(BaseModel):
    """Response do GET /models/metrics."""

    entries: list[ModelMetricsEntry]


# ============================================================================
# Erros
# ============================================================================


class ErrorResponse(BaseModel):
    """Formato padrao de erro, casado com Kotlin `ErrorResponse`."""

    error: str
    message: str
