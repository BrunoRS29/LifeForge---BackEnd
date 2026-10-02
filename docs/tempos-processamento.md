# Tempos de processamento do motor

> Artefato gerado por `EngineBenchmarkTest` (`gradlew benchmark`) em 2026-10-02. Média ± desvio-padrão de 30 execuções, após 5 de aquecimento. Cenário: baseline da análise de sensibilidade (semente 42).

| Operação | Configuração | Motor — `executionTimeMs` (ms) | Chamada completa (ms) |
|---|---|---|---|
| Simulação de Monte Carlo | 10.000 iterações · 240 meses | 5 ± 1 | 10 ± 2 |
| Simulação de Monte Carlo | 20.000 iterações · 240 meses | 9 ± 1 | 15 ± 2 |
| Simulação de Monte Carlo | 50.000 iterações · 240 meses | 23 ± 1 | 30 ± 1 |
| Otimização de aporte (busca binária) | 14 avaliações de 2.000 cenários + verificação de 10.000 | 79 ± 2 | 89 ± 2 |

Primeira execução numa JVM recém-iniciada (sem otimização JIT), 10.000 iterações: motor 62 ms; chamada completa 91 ms.

## Ambiente

- Processador: AMD Ryzen 9 5900X 12-Core Processor
- Núcleos lógicos disponíveis à JVM: 24
- JVM: Java HotSpot(TM) 64-Bit Server VM 17.0.5
- Sistema: Windows 11 10.0

A coluna *motor* mede o laço de simulação (campo `executionTimeMs`); a *chamada completa* inclui a agregação dos resultados (ordenação, percentis, histograma e bandas do fan chart). Na otimização, *motor* é o tempo total da busca binária com a verificação final.
