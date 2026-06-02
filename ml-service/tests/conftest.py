"""
Fixtures compartilhadas dos testes.

A estrategia para datasets sinteticos e gerar series com tendencia +
sazonalidade + ruido controlado, de forma que os modelos consigam
recuperar os parametros conhecidos com erro pequeno.
"""

from __future__ import annotations

import math
import random
from datetime import date

import pytest

from app.schemas import ExpenseObservation, IncomeObservation
from app.services.registry import reset_registry


@pytest.fixture(autouse=True)
def _reset_registry_between_tests():
    """Garante isolamento - cada teste comeca com registry limpo."""
    reset_registry()
    yield
    reset_registry()


# ============================================================================
# Helpers de geracao
# ============================================================================

def make_income_history(
    months: int = 24,
    base: float = 5_000.0,
    monthly_growth: float = 50.0,
    bonus_in_december: float = 5_000.0,
    noise: float = 100.0,
    seed: int = 42,
) -> list[IncomeObservation]:
    """
    Gera historico sintetico de renda com tendencia + sazonalidade + ruido.

    A formula determinstica e:
        renda(m) = base + monthly_growth * m
                 + (bonus_in_december if calendar_month == 12 else 0)
                 + N(0, noise)
    """
    rng = random.Random(seed)
    out: list[IncomeObservation] = []
    start_year = 2023
    for m in range(months):
        year = start_year + m // 12
        month = (m % 12) + 1
        amount = base + monthly_growth * m
        if month == 12:
            amount += bonus_in_december
        amount += rng.gauss(0.0, noise)
        out.append(
            IncomeObservation(
                received_at=date(year, month, 5),
                amount=max(amount, 100.0),  # evita zero
                income_type="SALARY",
                recurring=True,
            )
        )
    return out


def make_expense_history(
    months: int = 18,
    categories: tuple[str, ...] = ("FOOD", "HOUSING", "TRANSPORT", "LEISURE"),
    seed: int = 42,
) -> list[ExpenseObservation]:
    """
    Gera historico sintetico de despesas com perfil mensal por categoria.

    Cada categoria tem uma "linha de base" e oscila com sazonalidade
    diferente (LEISURE sobe em dezembro/janeiro - ferias).
    """
    rng = random.Random(seed)
    base_by_cat = {
        "FOOD": 1_500.0,
        "HOUSING": 2_500.0,
        "TRANSPORT": 700.0,
        "LEISURE": 400.0,
        "HEALTH": 300.0,
        "EDUCATION": 200.0,
        "OTHER": 250.0,
    }

    out: list[ExpenseObservation] = []
    start_year = 2024
    for m in range(months):
        year = start_year + m // 12
        month = (m % 12) + 1
        for cat in categories:
            base = base_by_cat.get(cat, 300.0)
            # Sazonalidade: LEISURE/FOOD sobem em dezembro
            seasonal = 1.0
            if cat == "LEISURE" and month in (12, 1, 7):
                seasonal = 1.6
            elif cat == "FOOD" and month == 12:
                seasonal = 1.2
            amount = base * seasonal * (1.0 + 0.08 * rng.gauss(0.0, 1.0))
            # Multiplas transacoes por mes para forcar o agrupamento
            for _ in range(rng.randint(1, 4)):
                slice_amount = max(amount / 4.0, 20.0)
                day = rng.randint(1, 28)
                out.append(
                    ExpenseObservation(
                        spent_at=date(year, month, day),
                        amount=slice_amount,
                        category=cat,  # type: ignore[arg-type]
                        recurring=(cat in {"HOUSING", "TRANSPORT"}),
                    )
                )
    return out


# ============================================================================
# Fixtures expostas
# ============================================================================

@pytest.fixture
def income_history_24m() -> list[IncomeObservation]:
    return make_income_history(months=24)


@pytest.fixture
def expense_history_18m() -> list[ExpenseObservation]:
    return make_expense_history(months=18)
