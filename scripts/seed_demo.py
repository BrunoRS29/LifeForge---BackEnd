#!/usr/bin/env python3
"""
Popula uma conta de DEMONSTRACAO do LifeForge com dados realistas, para
apresentacoes e capturas de tela: 18 meses de receitas e despesas (historico
suficiente para os modelos de IA), ativos, perfil, tres metas e simulacoes
(inclusive duas estrategias para a mesma meta e uma calibrada pela IA).

Uso:
    python scripts/seed_demo.py --email demo@lifeforge.test --password <senha>
    python scripts/seed_demo.py --base-url https://xyz.ngrok-free.app --email ... --password ...

Se a conta ja existir, entra nela e acrescenta os dados. Os valores sao
ficticios (gerados com semente fixa); nao use dados pessoais reais.
Somente biblioteca padrao (Python 3.9+).
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import urllib.error
import urllib.request
from datetime import date


def call(base: str, method: str, path: str, body=None, token: str | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("ngrok-skip-browser-warning", "true")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            return e.code, json.loads(text)
        except json.JSONDecodeError:
            return e.code, text


def months_back(n: int) -> list[date]:
    today = date.today()
    result = []
    year, month = today.year, today.month
    for _ in range(n):
        result.append(date(year, month, 1))
        month -= 1
        if month == 0:
            year, month = year - 1, 12
    return list(reversed(result))


def main() -> int:
    # Console do Windows usa cp1252 por padrao: forca UTF-8 para os acentos.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Dados de demonstracao do LifeForge")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--email", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--name", default="Conta Demonstração")
    args = parser.parse_args()
    base, v1 = args.base_url, "/api/v1"
    rng = random.Random(2026)

    status, auth = call(base, "POST", f"{v1}/auth/register",
                        {"email": args.email, "name": args.name, "password": args.password, "riskProfile": "MODERATE"})
    if status == 409:
        status, auth = call(base, "POST", f"{v1}/auth/login", {"email": args.email, "password": args.password})
    if status not in (200, 201):
        print(f"Falha na autenticacao ({status}): {auth}")
        return 1
    token = auth["token"]
    print(f"Conta pronta: {auth['user']['email']}")

    call(base, "PUT", f"{v1}/profile", {
        "age": 32, "monthlySalary": "9.500,00", "employmentType": "CLT", "retirementAge": 60,
        "monthlyContribution": "2.000,00", "dependents": 1, "childrenAges": "4",
        "housingStatus": "RENTED", "housingMonthlyCost": "2.800,00", "expectedSalaryGrowth": "5",
        "maritalStatus": "MARRIED", "state": "SP",
    }, token)

    # ---- 18 meses de historico (o mes atual entra so ate o dia de hoje) ----
    incomes, expenses = [], []
    for i, first_day in enumerate(months_back(18)):
        salary = 9500 * (1.05 ** (i // 12))
        day = lambda d: f"{first_day.replace(day=min(d, 28)).isoformat()}T12:00:00Z"
        if first_day.replace(day=5) <= date.today():
            incomes.append({"source": "Salário", "amount": f"{salary:.2f}", "incomeType": "SALARY",
                            "recurring": True, "receivedAt": day(5)})
        if first_day.month == 12:
            incomes.append({"source": "13º salário", "amount": f"{salary:.2f}", "incomeType": "BONUS",
                            "recurring": False, "receivedAt": day(20)})
        if first_day.month in (3, 9):
            incomes.append({"source": "Dividendos FII", "amount": f"{rng.uniform(380, 520):.2f}",
                            "incomeType": "DIVIDEND", "recurring": False, "receivedAt": day(15)})
        leisure_boost = 1.7 if first_day.month in (1, 7, 12) else 1.0
        planned = [
            ("Aluguel", 2800, "HOUSING", True, 10),
            ("Supermercado", rng.uniform(1250, 1600), "FOOD", False, 12),
            ("Combustível e transporte", rng.uniform(450, 700), "TRANSPORT", False, 14),
            ("Plano de saúde", 380, "HEALTH", True, 8),
            ("Escola infantil", 950, "EDUCATION", True, 6),
            ("Lazer", rng.uniform(300, 520) * leisure_boost, "LEISURE", False, 22),
            ("Assinaturas e outros", rng.uniform(150, 260), "OTHER", False, 18),
        ]
        for description, amount, category, recurring, d in planned:
            if first_day.replace(day=min(d, 28)) <= date.today():
                expenses.append({"description": description, "amount": f"{amount:.2f}", "category": category,
                                 "recurring": recurring, "spentAt": day(d)})
    status, imported = call(base, "POST", f"{v1}/finance/import", {"incomes": incomes, "expenses": expenses}, token)
    print(f"Lançamentos importados: {imported}")

    for asset in (
        {"name": "Tesouro Selic", "assetType": "FIXED_INCOME", "currentValue": "45000.00",
         "expectedReturn": "0.10", "volatility": "0.005"},
        {"name": "ETF de ações (BOVA11)", "assetType": "STOCKS", "currentValue": "25000.00",
         "expectedReturn": "0.12", "volatility": "0.22"},
        {"name": "Fundos imobiliários", "assetType": "REAL_ESTATE_FUND", "currentValue": "15000.00",
         "expectedReturn": "0.09", "volatility": "0.12"},
    ):
        call(base, "POST", f"{v1}/assets", asset, token)

    goals = {}
    for key, body in {
        "retirement": {"name": "Aposentadoria tranquila", "category": "RETIREMENT", "targetAmount": "2500000.00",
                       "targetDate": "2054-01-01T15:00:00Z", "priority": 1},
        "apartment": {"name": "Entrada do apartamento", "category": "REAL_ESTATE", "targetAmount": "150000.00",
                      "targetDate": "2030-06-01T15:00:00Z", "priority": 2},
        "travel": {"name": "Viagem ao Japão", "category": "TRAVEL", "targetAmount": "35000.00",
                   "targetDate": "2027-10-01T15:00:00Z", "priority": 3},
    }.items():
        status, goal = call(base, "POST", f"{v1}/goals", body, token)
        goals[key] = goal["id"]

    def simulate(goal_key: str, contribution: float, target: float, months: int, ret: float, vol: float, seed: int):
        return call(base, "POST", f"{v1}/simulation/run", {
            "goalId": str(goals[goal_key]), "initialCapital": 85000, "monthlyContribution": contribution,
            "expectedReturnAnnual": ret, "volatilityAnnual": vol, "horizonMonths": months,
            "targetAmount": target, "inflationAnnual": 0.045, "unemploymentProbAnnual": 0.08,
            "numSimulations": 10000, "seed": seed,
        }, token)

    # Duas estrategias para a mesma meta (comparacao lado a lado) e as demais metas.
    simulate("apartment", 900, 150000, 44, 0.10, 0.03, 11)
    simulate("apartment", 1300, 150000, 44, 0.11, 0.10, 12)
    simulate("travel", 600, 35000, 12, 0.10, 0.005, 13)
    simulate("retirement", 1800, 2500000, 327, 0.11, 0.10, 14)
    status, calibrated = call(base, "POST", f"{v1}/simulation/run-calibrated", {
        "goalId": str(goals["retirement"]), "initialCapital": 85000, "horizonMonths": 327,
        "targetAmount": 2500000, "seed": 15,
    }, token)
    print(f"Simulação calibrada pela IA: HTTP {status}")
    print("Pronto. Entre no app com a conta de demonstração.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
