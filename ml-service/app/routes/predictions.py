"""
Endpoints de predicao.

POST /predict/income     - regressao linear sobre historico de renda
POST /predict/expenses   - random forest sobre historico de despesas

Convencao de orquestracao: ambos endpoints chamam fit() seguido de
predict() na mesma request. Isso e adequado para o cenario do TCC
(modelos personalizados por usuario, datasets pequenos) - cada chamada
treina com os dados mais frescos do usuario. Em producao seria
desejavel cache de modelos por user_id com TTL.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from app.models.expense_model import ExpenseRandomForestModel
from app.models.income_model import IncomeRegressionModel
from app.schemas import (
    ExpensePredictionRequest,
    ExpensePredictionResponse,
    IncomePredictionRequest,
    IncomePredictionResponse,
)
from app.services.registry import ModelRegistry, get_registry


router = APIRouter(prefix="/predict", tags=["predictions"])


@router.post(
    "/income",
    response_model=IncomePredictionResponse,
    summary="Projeta renda mensal usando regressao linear",
)
def predict_income(
    request: IncomePredictionRequest,
    registry: ModelRegistry = Depends(get_registry),
) -> IncomePredictionResponse:
    """
    Treina um modelo de regressao linear no historico fornecido e
    devolve a projecao para os proximos `horizon_months` meses.

    Calibracao para o Monte Carlo:
    - `expected_monthly_income` -> base de aporte mensal
    - `annual_growth_rate`      -> reajuste anual da renda
    - `residual_volatility_monthly` -> volatilidade da renda
    """
    model = registry.get(IncomeRegressionModel.name)
    assert isinstance(model, IncomeRegressionModel)  # type narrow
    model.fit(request.history)
    return model.predict(request.horizon_months)


@router.post(
    "/expenses",
    response_model=ExpensePredictionResponse,
    summary="Preve gasto mensal por categoria usando Random Forest",
)
def predict_expenses(
    request: ExpensePredictionRequest,
    registry: ModelRegistry = Depends(get_registry),
) -> ExpensePredictionResponse:
    """
    Treina um Random Forest no historico de despesas (agregadas por
    categoria/mes) e devolve a previsao para o proximo periodo.
    """
    model = registry.get(ExpenseRandomForestModel.name)
    assert isinstance(model, ExpenseRandomForestModel)
    model.fit(request.history)
    return model.predict(request.horizon_months)
