# Análise de sensibilidade — Motor de Monte Carlo

> Artefato gerado por `EngineAnalysisTest`. **Baseline**: capital R$ 50.000, aporte R$ 2.000/mês, retorno 8% a.a., volatilidade 15% a.a., inflação 4% a.a., horizonte 240 meses (20 anos), meta R$ 1.000.000, 20.000 simulações, seed fixa (42).

Metodologia *one-at-a-time*: cada tabela varia UM parâmetro e mantém os demais no baseline. Métrica principal: probabilidade de atingir a meta.

## Retorno esperado (a.a.)

| Retorno esperado (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 4% | 25.5% | R$ 746,202 | R$ 436,882 | R$ 1,350,862 |
| 6% | 45.0% | R$ 944,604 | R$ 538,337 | R$ 1,749,068 |
| 8% | 65.3% | R$ 1,203,651 | R$ 669,982 | R$ 2,270,223 |
| 10% | 81.9% | R$ 1,546,096 | R$ 841,437 | R$ 2,968,730 |
| 12% | 92.1% | R$ 1,994,132 | R$ 1,065,192 | R$ 3,907,130 |

## Volatilidade (a.a.)

| Volatilidade (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 5% | 97.1% | R$ 1,350,489 | R$ 1,101,149 | R$ 1,667,967 |
| 10% | 79.5% | R$ 1,292,087 | R$ 866,654 | R$ 1,973,978 |
| 15% | 65.3% | R$ 1,203,651 | R$ 669,982 | R$ 2,270,223 |
| 20% | 55.6% | R$ 1,092,890 | R$ 512,821 | R$ 2,537,877 |
| 30% | 42.3% | R$ 839,246 | R$ 298,649 | R$ 2,897,280 |

## Aporte mensal

| Aporte mensal | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| R$ 1000 | 23.4% | R$ 695,713 | R$ 378,286 | R$ 1,354,575 |
| R$ 1500 | 45.7% | R$ 949,401 | R$ 524,008 | R$ 1,810,106 |
| R$ 2000 | 65.3% | R$ 1,203,651 | R$ 669,982 | R$ 2,270,223 |
| R$ 2500 | 79.6% | R$ 1,457,887 | R$ 816,378 | R$ 2,736,168 |
| R$ 3000 | 88.4% | R$ 1,710,645 | R$ 962,233 | R$ 3,201,240 |

## Prob. desemprego (a.a.)

| Prob. desemprego (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 0% | 65.3% | R$ 1,203,651 | R$ 669,982 | R$ 2,270,223 |
| 5% | 64.1% | R$ 1,184,279 | R$ 661,825 | R$ 2,206,304 |
| 10% | 62.3% | R$ 1,162,830 | R$ 646,846 | R$ 2,165,975 |
| 20% | 59.2% | R$ 1,117,786 | R$ 621,364 | R$ 2,093,116 |

## Leitura

- **Retorno** e **aporte** elevam a probabilidade de sucesso (relação direta).
- **Volatilidade** e **desemprego** reduzem a probabilidade (relação inversa); a volatilidade ainda alarga a faixa P10–P90, ou seja, aumenta a incerteza.
- No baseline, o parâmetro de maior alavancagem é o **aporte mensal**.
