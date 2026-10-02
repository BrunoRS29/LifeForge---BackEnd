#!/usr/bin/env python3
"""
Smoke test de ponta a ponta da API do LifeForge (backend + microsservico de IA).

Exercita, contra o ambiente em execucao (Docker Compose), os fluxos principais:
saude, base de referencia, registro/login, perfil, metas (com idempotencia),
receitas, despesas, ativos, painel, simulacao classica e calibrada (com o recuo
de partida a frio), historico, otimizacao, predicao e o contrato de erros.

Cada execucao cria um usuario descartavel (e-mail aleatorio @lifeforge.test) e
apaga, ao final, os lancamentos que criou.

Uso:
    python scripts/smoke_test.py                       # http://localhost:8080
    python scripts/smoke_test.py --base-url https://xyz.ngrok-free.app
    python scripts/smoke_test.py --bench 30            # + latencia HTTP de /simulation/run

Somente biblioteca padrao (Python 3.9+).
"""
from __future__ import annotations

import argparse
import json
import secrets
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass
class Report:
    passed: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)

    def check(self, condition: bool, label: str, detail: str = "") -> bool:
        if condition:
            self.passed.append(label)
            print(f"  [ok]    {label}")
        else:
            self.failed.append(label)
            print(f"  [FALHA] {label}" + (f" -> {detail}" if detail else ""))
        return condition


class Api:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")
        self.token: str | None = None

    def call(self, method: str, path: str, body=None, headers: dict | None = None, raw: str | None = None):
        data = raw.encode() if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        # Pula o aviso HTML do ngrok gratuito (inofensivo em outros servidores).
        req.add_header("ngrok-skip-browser-warning", "true")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                text = resp.read().decode("utf-8")
                status = resp.status
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8")
            status = e.code
        elapsed_ms = (time.perf_counter() - started) * 1000
        try:
            payload = json.loads(text) if text else None
        except json.JSONDecodeError:
            payload = text
        return status, payload, elapsed_ms


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke test da API do LifeForge")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--bench", type=int, default=0, help="rodadas para medir a latencia de /simulation/run")
    args = parser.parse_args()

    api = Api(args.base_url)
    r = Report()
    v1 = "/api/v1"

    print("== Saude e base de referencia")
    status, health, _ = api.call("GET", "/health")
    r.check(status == 200 and health.get("status") == "ok", "GET /health", str(health))
    r.check(health.get("ml") == "ok", "microsservico de IA respondendo", str(health))
    status, ref, _ = api.call("GET", f"{v1}/reference-data")
    r.check(status == 200 and "byRiskProfile" in ref, "GET /reference-data (publico)")

    print("== Conta")
    email = f"smoke-{secrets.token_hex(6)}@lifeforge.test"
    password = secrets.token_urlsafe(12)
    status, auth, _ = api.call("POST", f"{v1}/auth/register",
                               {"email": email, "name": "Smoke Test", "password": password, "riskProfile": "MODERATE"})
    if not r.check(status == 201 and "token" in auth, "POST /auth/register", str(auth)):
        return finish(r)
    api.token = auth["token"]
    status, me, _ = api.call("GET", f"{v1}/users/me")
    r.check(status == 200 and me["email"] == email, "GET /users/me")
    status, _, _ = api.call("PUT", f"{v1}/profile", {
        "age": 30, "monthlySalary": "8.000,00", "employmentType": "CLT",
        "retirementAge": 60, "monthlyContribution": "1.500,00",
    })
    r.check(status == 200, "PUT /profile (perfil estendido JSONB)")

    print("== Metas (com idempotencia)")
    goal_body = {"name": "Reserva de emergencia", "category": "CUSTOM", "targetAmount": "300000.00",
                 "targetDate": "2036-01-01T00:00:00Z", "priority": 1}
    key = {"Idempotency-Key": f"smoke-{secrets.token_hex(8)}"}
    s1, g1, _ = api.call("POST", f"{v1}/goals", goal_body, key)
    s2, g2, _ = api.call("POST", f"{v1}/goals", goal_body, key)
    r.check(s1 == 201 and s2 == 201 and g1["id"] == g2["id"], "reenvio com a mesma Idempotency-Key nao duplica")
    goal_id = g1["id"]

    print("== Lancamentos")
    for month in (7, 8, 9):
        api.call("POST", f"{v1}/incomes", {"source": "Salario", "amount": "8000.00", "incomeType": "SALARY",
                                            "recurring": True, "receivedAt": f"2026-0{month}-05T12:00:00Z"})
        api.call("POST", f"{v1}/expenses", {"description": "Aluguel", "amount": "2500.00", "category": "HOUSING",
                                             "recurring": True, "spentAt": f"2026-0{month}-10T12:00:00Z"})
    status, asset, _ = api.call("POST", f"{v1}/assets", {"name": "Tesouro Selic", "assetType": "FIXED_INCOME",
                                                          "currentValue": "40000.00", "expectedReturn": "0.10",
                                                          "volatility": "0.005"})
    r.check(status == 201, "POST /assets")
    status, incomes, _ = api.call("GET", f"{v1}/incomes?limit=2")
    r.check(status == 200 and len(incomes) == 2, "paginacao ?limit= em /incomes")
    status, dash, _ = api.call("GET", f"{v1}/dashboard")
    r.check(status == 200 and float(dash["totalAssets"]) == 40000.0, "GET /dashboard", str(dash))

    print("== Simulacao de Monte Carlo")
    sim_body = {"goalId": str(goal_id), "initialCapital": 40000, "monthlyContribution": 2500,
                "expectedReturnAnnual": 0.10, "volatilityAnnual": 0.12, "horizonMonths": 114,
                "targetAmount": 300000, "inflationAnnual": 0.045, "numSimulations": 10000, "seed": 42}
    status, sim, elapsed = api.call("POST", f"{v1}/simulation/run", sim_body)
    ok = r.check(status == 201 and 0 <= sim["successProbability"] <= 1, "POST /simulation/run (10.000 cenarios)")
    if ok:
        r.check(len(sim.get("trajectory", [])) == 115, "bandas P10-P90 mes a mes (fan chart)")
        r.check(sim.get("inputs", {}).get("monthlyContribution") == 2500, "premissas da rodada na resposta")
        print(f"          P(sucesso)={sim['successProbability']:.3f}  motor={sim['executionTimeMs']} ms  "
              f"HTTP={elapsed:.0f} ms")
        status, again, _ = api.call("GET", f"{v1}/simulation/{sim['id']}")
        r.check(status == 200 and again["id"] == sim["id"] and again.get("inputs"), "GET /simulation/{id} com id real")
    status, history, _ = api.call("GET", f"{v1}/simulation/by-goal/{goal_id}")
    r.check(status == 200 and history and history[0].get("inputs"), "historico por meta com premissas")

    status, cal, _ = api.call("POST", f"{v1}/simulation/run-calibrated",
                              {"goalId": str(goal_id), "initialCapital": 40000, "horizonMonths": 114,
                               "targetAmount": 300000, "seed": 42})
    ok = r.check(status == 201, "POST /simulation/run-calibrated com historico curto (recuo)", str(cal)[:300])
    if ok:
        calib = cal["calibration"]
        r.check(calib["incomeSource"] == "PROFILE" and calib["fallbackNotes"],
                "renda do perfil e motivos do recuo informados", str(calib))
        r.check(cal["simulation"]["inputs"]["calibrated"] is True, "rodada marcada como calibrada")

    print("== Otimizacao")
    status, opt, _ = api.call("POST", f"{v1}/optimize/contribution",
                              {"goalId": str(goal_id), "initialCapital": 40000, "expectedReturnAnnual": 0.10,
                               "volatilityAnnual": 0.12, "targetAmount": 300000, "horizonMonths": 114, "seed": 42})
    r.check(status == 200 and opt["feasible"] and opt["iterations"], "aporte ideal por busca binaria", str(opt)[:200])
    status, hor, _ = api.call("POST", f"{v1}/optimize/horizon",
                              {"initialCapital": 40000, "expectedReturnAnnual": 0.10, "volatilityAnnual": 0.12,
                               "targetAmount": 300000, "monthlyContribution": 2500, "seed": 42})
    r.check(status == 200 and hor["feasible"], "prazo ajustado")
    status, reb, _ = api.call("POST", f"{v1}/optimize/rebalance",
                              {"riskProfile": "MODERATE", "currentCapital": 40000, "targetAmount": 300000,
                               "monthsToGoal": 114})
    r.check(status == 200 and abs(sum(reb["weights"].values()) - 1) < 1e-6, "rebalanceamento (pesos somam 100%)")

    print("== Predicoes e contrato de erros")
    status, pred, _ = api.call("POST", f"{v1}/predictions/income", {"horizonMonths": 12})
    r.check(status == 422 and pred["error"] == "INSUFFICIENT_DATA", "historico curto -> 422 INSUFFICIENT_DATA")
    status, err, _ = api.call("POST", f"{v1}/goals", raw="{ nao e json")
    r.check(status == 400 and err["error"] == "VALIDATION", "JSON malformado -> 400 VALIDATION (nao 500)")
    status, err, _ = api.call("GET", f"{v1}/goals/999999999")
    r.check(status == 404 and err["error"] == "NOT_FOUND", "recurso inexistente -> 404 com ErrorResponse")

    if args.bench > 0:
        bench(api, sim_body, args.bench)

    print("== Limpeza")
    api.call("DELETE", f"{v1}/goals/{goal_id}")
    api.call("DELETE", f"{v1}/incomes")
    api.call("DELETE", f"{v1}/expenses")
    if isinstance(asset, dict) and "id" in asset:
        api.call("DELETE", f"{v1}/assets/{asset['id']}")
    print("  lancamentos e meta de teste removidos")
    return finish(r)


def bench(api: Api, body: dict, rounds: int) -> None:
    """Latencia de ponta a ponta de POST /simulation/run (rede local + JSON + gravacao no banco)."""
    print(f"== Latencia de /simulation/run ({rounds} rodadas, apos 3 de aquecimento)")
    for _ in range(3):
        api.call("POST", "/api/v1/simulation/run", body)
    http, motor = [], []
    for _ in range(rounds):
        status, sim, elapsed = api.call("POST", "/api/v1/simulation/run", body)
        if status == 201:
            http.append(elapsed)
            motor.append(sim["executionTimeMs"])
    if len(http) >= 2:
        print(f"  HTTP: media {statistics.mean(http):.0f} ms, desvio {statistics.stdev(http):.0f} ms, "
              f"max {max(http):.0f} ms")
        print(f"  motor (executionTimeMs): media {statistics.mean(motor):.1f} ms")


def finish(r: Report) -> int:
    print(f"\n{len(r.passed)} verificacoes ok, {len(r.failed)} falha(s)")
    for label in r.failed:
        print(f"  - {label}")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
