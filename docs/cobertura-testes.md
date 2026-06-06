# Cobertura de testes — Motor de simulação

> Critério 12.3 do TCC: *"Testes automatizados com cobertura superior a 70% no motor de simulação."*

A cobertura é medida com **JaCoCo** (configurado em `build.gradle.kts`). Para gerar:

```bash
./gradlew test jacocoTestReport
```

Relatórios em `build/reports/jacoco/test/` (HTML em `html/index.html`, além de `xml` e `csv`).

## Resultado (motor de simulação)

Pacotes sob `com.lifeforge.engine.*` — o núcleo técnico do projeto:

| Pacote | Cobertura de linhas |
|---|---|
| `com.lifeforge.engine.montecarlo` | 97,5% (118/121) |
| `com.lifeforge.engine.optimization` | 95,6% (367/384) |
| `com.lifeforge.engine.statistics` | 95,9% (70/73) |
| **Motor agregado** | **96,0% (555/578)** — branches 72,7% |

**96,0% de cobertura de linhas no motor**, bem acima do mínimo de 70% exigido — critério 12.3 atendido.

A suíte que sustenta esse número inclui: testes do Monte Carlo (determinismo com volatilidade zero, reprodutibilidade por seed, monotonicidade de percentis, performance, fan chart), do motor de otimização (busca binária de aporte, prazo, rebalanceamento) e das estatísticas descritivas, além do `EngineAnalysisTest` (sensibilidade + comparação determinístico × Monte Carlo).
