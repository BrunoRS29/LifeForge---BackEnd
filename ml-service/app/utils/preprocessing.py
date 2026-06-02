"""
Funcoes de pre-processamento.

Os modelos preditivos esperam features ja agregadas por (ano, mes). As
funcoes deste modulo cuidam dessa agregacao e da geracao de features
ciclicas para sazonalidade.

Documentacao tecnica importante:
- Para sazonalidade usamos codificacao senoidal/cossenoidal (Fourier)
  porque modelos arvore-baseados ainda capturam interacao, e linear
  consegue modelar fase. E padrao em time-series forecasting.

  month_sin = sin(2*pi*m/12)
  month_cos = cos(2*pi*m/12)
"""

from __future__ import annotations

import math

import numpy as np
import pandas as pd

from app.schemas import ExpenseObservation, IncomeObservation


# ============================================================================
# Codificacao ciclica de sazonalidade
# ============================================================================

def cyclic_month_features(month: int) -> tuple[float, float]:
    """
    Codifica o mes (1..12) como par (seno, cosseno).

    Justificativa: o numero ordinal 12 nao deve estar adjacente a 1 num
    espaco linear (dezembro e janeiro sao "vizinhos" no calendario).
    A codificacao senoidal preserva essa adjacencia.

    Formula:
      sin = sin(2 * pi * m / 12)
      cos = cos(2 * pi * m / 12)
    """
    if month < 1 or month > 12:
        raise ValueError(f"month deve estar em [1,12], recebeu {month}")
    angle = 2 * math.pi * month / 12
    return math.sin(angle), math.cos(angle)


# ============================================================================
# Agregacao de renda por (ano, mes)
# ============================================================================

def income_history_to_monthly(observations: list[IncomeObservation]) -> pd.DataFrame:
    """
    Agrega uma lista de IncomeObservation em um DataFrame mensal.

    Output columns:
      - period         pd.Period  (mensal)
      - month_idx      int        (0 = primeiro mes do historico)
      - month_sin      float
      - month_cos      float
      - amount         float      (soma dentro do mes)

    Meses faltantes no historico SAO PREENCHIDOS COM ZERO. Em renda essa
    e uma decisao com tradeoff: zerar e conservador (forca o modelo a
    captar o fato de "ficou sem receber"), mas pode introduzir outliers.
    Para a versao do TCC priorizamos simplicidade e reprodutibilidade.
    """
    if not observations:
        raise ValueError("Lista vazia de observacoes")

    df = pd.DataFrame(
        {
            "date": [pd.Timestamp(o.received_at) for o in observations],
            "amount": [o.amount for o in observations],
        }
    )

    # Agrega por mes calendarico
    df["period"] = df["date"].dt.to_period("M")
    monthly = df.groupby("period", as_index=False)["amount"].sum()

    # Garante todos os meses entre min e max (sem buracos)
    full_range = pd.period_range(
        start=monthly["period"].min(),
        end=monthly["period"].max(),
        freq="M",
    )
    monthly = (
        monthly.set_index("period")
        .reindex(full_range, fill_value=0.0)
        .rename_axis("period")
        .reset_index()
    )

    # Features
    monthly["month_idx"] = np.arange(len(monthly), dtype=int)
    monthly["month_sin"] = monthly["period"].apply(
        lambda p: cyclic_month_features(p.month)[0]
    )
    monthly["month_cos"] = monthly["period"].apply(
        lambda p: cyclic_month_features(p.month)[1]
    )

    return monthly


# ============================================================================
# Agregacao de despesas por (ano, mes, categoria)
# ============================================================================

def expense_history_to_monthly(
    observations: list[ExpenseObservation],
) -> pd.DataFrame:
    """
    Agrega despesas para o formato esperado pelo Random Forest.

    Output columns:
      - period         pd.Period
      - category       str
      - month_sin      float
      - month_cos      float
      - lag1           float  - gasto na categoria no mes anterior
      - lag3_avg       float  - media dos 3 meses anteriores
      - recurring_share float - fracao do gasto recorrente no mes
      - amount         float  - alvo (gasto total na categoria/mes)

    Linhas com lag indefinido (inicio do historico) sao DESCARTADAS,
    nao preenchidas com zero - mistura-las falsearia o aprendizado de
    "comportamento recente prediz proximo mes".
    """
    if not observations:
        raise ValueError("Lista vazia de observacoes")

    rows = []
    for o in observations:
        ts = pd.Timestamp(o.spent_at)
        rows.append({
            "period": ts.to_period("M"),
            "category": o.category,
            "amount": o.amount,
            "recurring_amount": o.amount if o.recurring else 0.0,
        })

    raw = pd.DataFrame(rows)

    # Agrega por (period, category)
    agg = (
        raw.groupby(["period", "category"], as_index=False)
        .agg(
            amount=("amount", "sum"),
            recurring_amount=("recurring_amount", "sum"),
        )
    )

    # Garante grid (period x category) completo para calcular lags corretos
    all_periods = pd.period_range(
        start=agg["period"].min(),
        end=agg["period"].max(),
        freq="M",
    )
    all_categories = sorted(agg["category"].unique())
    grid = pd.MultiIndex.from_product(
        [all_periods, all_categories],
        names=["period", "category"],
    ).to_frame(index=False)

    agg = grid.merge(agg, on=["period", "category"], how="left").fillna(
        {"amount": 0.0, "recurring_amount": 0.0}
    )

    # Features temporais
    agg["month_sin"] = agg["period"].apply(lambda p: cyclic_month_features(p.month)[0])
    agg["month_cos"] = agg["period"].apply(lambda p: cyclic_month_features(p.month)[1])

    # Lags por categoria (shift dentro de cada grupo)
    agg = agg.sort_values(["category", "period"]).reset_index(drop=True)
    agg["lag1"] = agg.groupby("category")["amount"].shift(1)
    agg["lag3_avg"] = (
        agg.groupby("category")["amount"]
        .shift(1)
        .rolling(window=3, min_periods=1)
        .mean()
        .reset_index(level=0, drop=True)
    )

    # Recurring share - protege divisao por zero
    agg["recurring_share"] = np.where(
        agg["amount"] > 0,
        agg["recurring_amount"] / agg["amount"],
        0.0,
    )

    # Drop primeiras linhas onde lag1 e NaN (nao temos historico anterior)
    agg = agg.dropna(subset=["lag1", "lag3_avg"]).reset_index(drop=True)

    return agg[
        [
            "period",
            "category",
            "month_sin",
            "month_cos",
            "lag1",
            "lag3_avg",
            "recurring_share",
            "amount",
        ]
    ]


# ============================================================================
# Utilidades de avaliacao
# ============================================================================

def safe_train_test_split(
    n: int,
    test_size: float = 0.2,
    min_train: int = 4,
    min_test: int = 1,
) -> tuple[int, int]:
    """
    Calcula tamanhos de treino e teste respeitando minimos.

    Retorna (n_train, n_test). Em datasets minusculos pode reduzir
    test_size, mas nunca abaixo de `min_test`.
    """
    if n < min_train + min_test:
        raise ValueError(
            f"Amostra de {n} insuficiente para split (min {min_train + min_test})"
        )

    n_test = max(int(round(n * test_size)), min_test)
    n_train = n - n_test
    if n_train < min_train:
        # Reduz test ate caber
        n_train = min_train
        n_test = n - n_train

    return n_train, n_test
