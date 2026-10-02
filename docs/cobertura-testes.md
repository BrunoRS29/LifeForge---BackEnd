# Cobertura de testes — Motor de simulação

> Critério 12.3 do TCC: *"Testes automatizados com cobertura superior a 70% no motor de simulação."*

A cobertura é medida com **JaCoCo** (configurado em `build.gradle.kts`). Para gerar:

```bash
./gradlew test jacocoTestReport
```

Relatórios em `build/reports/jacoco/test/` (HTML em `html/index.html`, além de `xml` e `csv`).

## Resultado (motor de simulação)

Pacotes sob `com.lifeforge.engine.*` — o núcleo técnico do projeto:

| Pacote | Cobertura de linhas | Cobertura de ramos |
|---|---|---|
| `com.lifeforge.engine.montecarlo` | 96,0% (144/150) | 76,7% |
| `com.lifeforge.engine.optimization` | 95,6% (372/389) | 68,3% |
| `com.lifeforge.engine.statistics` | 96,8% (120/124) | 79,2% |
| **Motor agregado** | **95,9% (636/663)** | **73,5%** |

Medição de 02/10/2026, após a paralelização do motor, o choque de despesa
inesperada e a variação de renda (que acrescentaram linhas ao pacote
`montecarlo`). A versão anterior desta tabela registrava 96,0% (555/578).

**95,9% de cobertura de linhas no motor**, bem acima do mínimo de 70% exigido — critério 12.3 atendido.

A suíte que sustenta esse número inclui: testes do Monte Carlo (determinismo com volatilidade zero, reprodutibilidade por seed, monotonicidade de percentis, performance, fan chart), do motor de otimização (busca binária de aporte, prazo, rebalanceamento) e das estatísticas descritivas, além do `EngineAnalysisTest` (sensibilidade + comparação determinístico × Monte Carlo).
